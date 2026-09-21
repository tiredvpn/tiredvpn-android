package com.tiredvpn.android.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tiredvpn.android.util.FileLogger
import com.tiredvpn.android.R
import com.tiredvpn.android.TiredVpnApp
import com.tiredvpn.android.native.NativeArgs
import com.tiredvpn.android.native.NativeProcess
import com.tiredvpn.android.native.NativeProcessJNI
import com.tiredvpn.android.native.TiredVpnNative
import com.tiredvpn.android.native.TiredVpnProcess
import com.tiredvpn.android.receiver.BootReceiver
import com.tiredvpn.android.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.EmptyCoroutineContext
import org.json.JSONObject
import android.system.Os
import java.io.File
import java.net.InetAddress

class TiredVpnService : VpnService() {

    companion object {
        private const val TAG = "TiredVpnService"
        private const val NOTIFICATION_ID = 1
        private const val CONTROL_SOCKET_TIMEOUT = 30_000 // 30 sec for the socket file to appear

        // Connect budget derived from the core's Android profile; see ConnectBudget
        // for where each factor comes from and why 30s was short.
        private const val CONTROL_SOCKET_READ_TIMEOUT =
            ConnectBudget.CONTROL_SOCKET_READ_TIMEOUT_MS.toInt()
        private const val CONNECTION_TIMEOUT = ConnectBudget.CONNECT_TIMEOUT_MS

        /**
         * How long to wait for the core's answer to `set_fd`.
         *
         * Same budget as any other control read, and deliberately so: `set_fd`
         * is the command that makes the core run a full Connect, which is what
         * [ConnectBudget.CONTROL_SOCKET_READ_TIMEOUT_MS] is sized against. The
         * 15s this replaces never fenced anything — it lived in a
         * `withTimeoutOrNull` around a blocking read, which cancels a coroutine
         * and leaves the socket reading.
         */
        private const val SET_FD_READ_TIMEOUT = CONTROL_SOCKET_READ_TIMEOUT

        /**
         * Names for the three waits a connect is made of, carried by
         * [ConnectDeadlineExceeded] and shown to the user.
         *
         * They are separate because the faults are: the core never opened its
         * socket, the core opened it and never answered `connect`, the core
         * answered `connect` and then never finished dialling. One sentence for
         * all three ("Connection timed out") cost real diagnosis time.
         */
        private const val STEP_CORE_STARTUP = "core startup"
        private const val STEP_CORE_CONNECT = "core handshake"
        private const val STEP_HANDSHAKE = "server handshake"

        /** Pause between the two `set_fd` attempts. Counted against the budget. */
        private const val HANDSHAKE_RETRY_DELAY_MS = 1_000L

        /**
         * Read deadline for one protect-socket client. The core connects,
         * writes four bytes and waits for one back, so anything slower than
         * this is a peer that has stopped talking.
         */
        private const val PROTECT_CLIENT_READ_TIMEOUT_MS = 5_000L

        /** Ceiling on simultaneously live protect handlers. */
        private const val MAX_PROTECT_CLIENTS = 32

        /** requestCode for the VPN notification's content intent. */
        private const val NOTIFICATION_REQUEST_CODE = 0

        /**
         * How long a renegotiated IPv6 pair waits for the state to settle on
         * Connected before its interface rebuild is abandoned. Ten seconds:
         * the core's own reconnect is what put us in Connecting, and it either
         * finishes or reports the connection dead well inside that.
         */
        private const val IPV6_REBUILD_WAIT_TICK_MS = 500L
        private const val IPV6_REBUILD_WAIT_TICKS = 20

        // IPv4 resolver used as backup, and as replacement for a configured IPv6
        // resolver, which is unreachable behind the IPv6 blackhole (see Ipv6Guard)
        private const val FALLBACK_DNS = "8.8.8.8"

        private val _state = MutableStateFlow<VpnState>(VpnState.Disconnected)
        val state: StateFlow<VpnState> = _state.asStateFlow()

        // Fine-grained connection phase shown by the UI instead of a static
        // "Connecting…". Empty string means "no phase" (UI falls back to the
        // generic connecting label). Kept separate from VpnState so the sealed
        // state machine and its when() exhaustiveness stay untouched.
        private val _connectingPhase = MutableStateFlow("")
        val connectingPhase: StateFlow<String> = _connectingPhase.asStateFlow()

        const val ACTION_CONNECT = "com.tiredvpn.android.CONNECT"
        const val ACTION_DISCONNECT = "com.tiredvpn.android.DISCONNECT"
        const val ACTION_FORCE_RESET = "com.tiredvpn.android.FORCE_RESET"

        // CONNECT WATCHDOG: safety net for the residual case where a blocking native
        // (cgo) call into the Go core never returns, wedging the Dispatchers.IO worker
        // thread it's running on forever. Cancelling a Job is cooperative and does NOT
        // reclaim that thread, so if enough attempts wedge threads this way, the whole
        // shared IO pool eventually has zero free workers and a brand-new connect()
        // coroutine can be queued but never actually dispatched — its body never runs
        // and "COROUTINE BODY ENTERED" never prints. A healthy body enters immediately
        // (the slow work happens AFTER entry), so this threshold has ample margin.
        private const val CONNECT_BODY_WATCHDOG_MS = 14_000L

        // Deliberately its OWN dedicated thread, never scope / Dispatchers.IO — that
        // pool is exactly what's suspected of being fully wedged, so a watchdog
        // scheduled on it would be just as stuck as the thing it's meant to catch.
        private val connectWatchdogExecutor: java.util.concurrent.ScheduledExecutorService =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "TiredVpnConnectWatchdog").apply { isDaemon = true }
            }

        // Google apps (Meet, Gmail, Maps, ...) offload signaling, push and STUN/auth
        // to Google Play Services. In include/allowlist mode the selected app is
        // tunneled but GPS is not, so WebRTC call setup (Meet) never completes.
        // When a Google app is allowed, pull its network companions into the tunnel too.
        private val GOOGLE_COMPANION_PACKAGES = listOf(
            "com.google.android.gms",       // Google Play Services
            "com.google.android.gsf",       // Google Services Framework
            "com.android.vending"           // Play Store (license/auth checks)
        )
    }

    /**
     * [ControlTransport] over the Android LocalSocket the core listens on.
     *
     * Everything that makes the socket dangerous — the send-attachment being
     * socket-wide state rather than an argument, the single shared input
     * stream — is behind this adapter, so [ControlChannel] can be exercised
     * without one.
     */
    private class LocalSocketTransport(private val socket: LocalSocket) : ControlTransport {
        override val input: java.io.InputStream get() = socket.inputStream
        override val output: java.io.OutputStream get() = socket.outputStream
        override fun attachFds(fds: Array<java.io.FileDescriptor>?) {
            socket.setFileDescriptorsForSend(fds)
        }
        override fun setReadTimeoutMs(ms: Int) { socket.soTimeout = ms }
        override fun close() { socket.close() }
    }

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        FileLogger.e(TAG, "UNCAUGHT coroutine exception", throwable)
    }

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    private fun ensureScopeActive() {
        if (!scope.isActive) {
            FileLogger.w(TAG, "Coroutine scope is dead, recreating")
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)
        }
    }

    /**
     * Cancel and recreate the coroutine scope to release any blocked Dispatchers.IO threads.
     * Must be called after all coroutine jobs are cancelled and resources are cleaned up.
     */
    private fun resetScope() {
        FileLogger.d(TAG, "Resetting coroutine scope (releasing blocked IO threads)")
        scope.cancel()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)
    }

    /**
     * Arm the connect watchdog: if COROUTINE BODY ENTERED doesn't print within
     * CONNECT_BODY_WATCHDOG_MS, we conclude a previous blocking native call has
     * wedged an IO thread forever (see companion object comment) and kill the
     * process — the only way left to reclaim a native thread the JVM can't stop.
     */
    private fun armConnectWatchdog(reason: String) {
        disarmConnectWatchdog()
        FileLogger.w(TAG, "armConnectWatchdog[$reason]: armed, ${CONNECT_BODY_WATCHDOG_MS}ms to reach BODY ENTERED or process is killed")
        connectWatchdogFuture = connectWatchdogExecutor.schedule({
            FileLogger.e(TAG, "CONNECT WATCHDOG FIRED[$reason]: coroutine body never entered within " +
                "${CONNECT_BODY_WATCHDOG_MS}ms (scopeActive=${scope.isActive}) - a native call is presumed " +
                "wedged forever on an IO thread that the JVM cannot reclaim; killing process so the system " +
                "or user can relaunch cleanly")
            Log.e(TAG, "CONNECT WATCHDOG FIRED: killing process to release a wedged native thread")
            // Best-effort: give FileLogger's writer thread (flushes every ~100ms) one
            // cycle to persist the line above before the process dies.
            try { Thread.sleep(200) } catch (_: InterruptedException) {}
            android.os.Process.killProcess(android.os.Process.myPid())
        }, CONNECT_BODY_WATCHDOG_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /** Disarm the connect watchdog: body entered, or connect/disconnect concluded. */
    private fun disarmConnectWatchdog() {
        connectWatchdogFuture?.cancel(false)
        connectWatchdogFuture = null
    }

    private var vpnInterface: ParcelFileDescriptor? = null

    /**
     * Every TUN descriptor this service established, so cleanup can close our
     * orphans without touching descriptors owned by someone else. See
     * TunHandleRegistry for why closing by number aborted the process.
     */
    private val tunHandles = TunHandleRegistry<ParcelFileDescriptor> { it.close() }

    private var tiredvpnProcess: TiredVpnProcess? = null  // Can be NativeProcess or NativeProcessJNI

    /**
     * The one way in and out of the control socket. See [ControlChannel] for
     * the three bugs that made five ad-hoc writers and three readers over one
     * stream untenable.
     */
    @Volatile
    private var controlChannel: ControlChannel? = null

    private var statusMonitorJob: Job? = null
    private var protectServerJob: Job? = null  // Socket protection server

    /**
     * The listening socket of the protect server, held so it can be closed.
     * Cancelling [protectServerJob] does not interrupt the blocking accept it
     * is parked in; closing this does.
     */
    @Volatile
    private var protectServerSocket: LocalSocket? = null

    /** Live protect-client descriptors, so a stop can wake their readers. */
    private val protectClientFds =
        java.util.Collections.synchronizedList(mutableListOf<java.io.FileDescriptor>())

    /**
     * Which protect server owns `protect.sock` right now.
     *
     * The path is one fixed name shared by every generation, and a superseded
     * server's teardown runs asynchronously — it is woken by the next server's
     * [stopProtectServer], which happens *before* that server binds. So by the
     * time the old coroutine reaches its `finally`, the name it is holding a
     * string for can already belong to somebody else. Unlinking it there left
     * the core's InitAndroidProtector with ENOENT, which means every socket the
     * core opens afterwards goes unprotected and routes into our own tunnel.
     *
     * Checking that the file exists would not help: it does exist, it is simply
     * not ours. Ownership is what has to be checked, so each server carries a
     * generation and only the current one may unlink.
     */
    private val protectGeneration = ConnectGeneration()

    /**
     * Who is allowed to stop the one core in this process, and the handover
     * between them. See [CoreOwnership] — the short version is that
     * `NativeProcessJNI.stop()` reaches a Go package-level variable, so a
     * snapshot of the Kotlin object stops whatever core is running, not the
     * one the snapshot was taken of.
     */
    private val coreOwnership = CoreOwnership()

    /**
     * How long a connect waits for the previous owner to finish stopping.
     *
     * Its ceiling is Go's: `stopClient()` waits on `clientWg` through a select
     * with `time.After(5 * time.Second)` and returns either way. Seven seconds
     * is that plus the protect server's own teardown and slack. On expiry the
     * connect proceeds and says so — see [CoreOwnership.awaitHandover].
     */
    private val coreHandoverTimeoutMs = 7_000L
    private var connectionJob: Job? = null  // Current connection attempt - can be cancelled
    private var connectAttemptsSinceBodyEntered = 0  // Track if coroutine body ever starts
    @Volatile private var connectWatchdogFuture: java.util.concurrent.ScheduledFuture<*>? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Connection info from Go binary response
    private var connectedStrategy: String = ""
    private var connectedLatencyMs: Long = 0
    private var connectedAttempts: Int = 1
    private var currentVpnIp: String = ""  // Current assigned VPN IP

    /**
     * The tunnel config the core returned on the handshake.
     *
     * Kept so a network change can rebuild the interface from what was
     * negotiated instead of from constants. Without it the user's resolver
     * survived exactly until the first Wi-Fi/LTE switch.
     */
    @Volatile private var activeTunnelConfig: TunnelConfig? = null

    // Written by the control-socket event listener and read by the
    // network-change coroutine, so neither may cache them.
    @Volatile private var currentVpnIp6: String = ""  // Negotiated dual-stack v6 ("" = v4-only session)
    @Volatile private var currentVpnServerIp6: String = ""  // Server's tunnel v6 (informational)

    // Network monitoring - ignore events right after connection
    @Volatile private var connectionTime: Long = 0

    /**
     * The single gate for "the network changed". Both the ConnectivityManager
     * callback (a system thread) and the 2-second poll (a coroutine) report the
     * same events; see NetworkChangeGate for the two-descriptor race that the
     * old read-compare-assign on a plain Long allowed.
     */
    private val networkChangeGate = NetworkChangeGate()

    /** Serializes the work behind the gate, not just the decision to do it. */
    private val networkChangeMutex = Mutex()

    // Reconnection tracking
    private var reconnectAttempts: Int = 0
    private val reconnectCooldownMs = 60_000L // Reset counter after 1 minute of stable connection
    private val maxBackoffMs = 10_000L // Maximum backoff delay (reduced from 30s)
    private var isNetworkLost: Boolean = false // Track if we lost network (for fast reconnect)
    private var networkRecoveryJob: Job? = null // Periodic network check when network is lost
    private var lastNetworkAvailableTime: Long = 0 // Track when we last had network

    // ITERATION 2: Reconnect state management to prevent race conditions.
    // Owner-token lock, not a bare Mutex: see ReconnectLock for the two
    // parallel reconnects a token-less unlock() used to produce.
    private val reconnectLock = ReconnectLock()

    /**
     * Which connection attempt is current. Cleanup written by an older attempt
     * checks this before touching shared state — see ConnectGeneration.
     */
    private val connectGeneration = ConnectGeneration()

    private var pendingReconnectJob: Job? = null // Track pending reconnect job to cancel it
    @Volatile private var isReconnecting = false // Atomic flag to prevent parallel reconnects
    private var lastNetworkSignalSentTime: Long = 0 // Debounce for network_available signals to Go

    // When the core last said anything down the control channel, on the clock
    // that stops while the device is suspended.
    //
    // uptimeMillis and not currentTimeMillis, and that is the whole point: the
    // core's goroutines are frozen alongside the rest of the process during
    // suspend, so wall-clock time spent asleep is time the core was not given
    // to speak in. Measured against the wall clock, every wake from a sleep
    // longer than the timeout below read as a dead tunnel and queued a
    // reconnect on top of whatever else the wake had already triggered.
    //
    // Updated on EVERY line the core sends, not only on keepalive events. Note
    // what that does and does not measure: the keepalive frames in
    // internal/tun/vpn.go travel over the network socket to the server, not up
    // this channel, so in practice the thing being timed is our own `status`
    // poll getting an answer — i.e. the control goroutine is alive. A wedged
    // relay behind a healthy control goroutine is not visible from here and
    // needs a field in the core's status response to become so.
    @Volatile private var lastKeepaliveTime: Long = 0

    /**
     * No line from the core for this long means the control channel is dead.
     *
     * Arithmetic: the `status` poll runs every 30s and the core answers it, so
     * a healthy channel speaks at least that often; 75s is two missed polls
     * plus slack.
     */
    private val keepaliveTimeoutMs = 75_000L

    /**
     * The core was started and has not reported that it exited.
     *
     * One property because the same question was asked six different ways,
     * three of them inverted by accident: `isRunning == true != true` (which
     * is !isRunning), `isRunning == true == true`, and `isRunning == true ?:
     * false` where the elvis operand is unreachable. Note what it does NOT
     * mean: the core runs in this process, so a wedged goroutine, a looping
     * handshake or a dead relay leave it true. For "is the tunnel alive" see
     * [checkTunnelHealth].
     */
    private val coreStarted: Boolean get() = tiredvpnProcess?.isRunning == true

    // Internal process watchdog - faster than WorkManager (which has 15min minimum)
    private var processWatchdogJob: Job? = null
    private val processWatchdogIntervalMs = 30_000L // Check every 30 seconds

    // ITERATION 3: Network availability watchdog and health checks
    private var networkWatchdogJob: Job? = null // Watchdog for long disconnects
    private val networkWatchdogTimeoutMs = 45_000L // Force restart if offline >45s
    private var healthCheckJob: Job? = null // Periodic health check of Go process
    private val healthCheckIntervalMs = 10_000L // Check every 10 seconds

    // PIXEL FIX: Aggressive active network monitoring (backup for unreliable NetworkCallback)
    private var activeNetworkMonitorJob: Job? = null
    private val activeNetworkMonitorIntervalMs = 2_000L // Check every 2 seconds

    // BUG FIX 1: Track last link properties to avoid unnecessary reconnects
    private var lastLinkProperties: String = "" // Serialized IP addresses

    override fun onCreate() {
        super.onCreate()
        FileLogger.d(TAG, "Service created")

        // Reconcile stale process-global state: a fresh service instance holds no
        // resources, so any non-Disconnected value here is leftover from a dead
        // instance (the static _state survives instance death). Clearing it — and
        // releasing a possibly-stuck reconnect mutex — prevents the UI button from
        // getting wedged. A legitimate connect arrives next as its own onStartCommand.
        if (_state.value !is VpnState.Disconnected) {
            FileLogger.w(TAG, "onCreate: stale state ${_state.value}, reconciling to Disconnected")
            _state.value = VpnState.Disconnected
        }
        reconnectLock.forceRelease()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        FileLogger.d(TAG, "onStartCommand: action=${intent?.action}, startId=$startId, scopeActive=${scope.isActive}")

        // Android redelivers a null intent to a START_STICKY service after it
        // restarts the process. That path is not hypothetical here: the connect
        // watchdog kills the process itself when a native call wedges an IO
        // thread. Falling through the when() below left the service alive with
        // no foreground notification, no tunnel and no stopSelf.
        if (intent == null) return handleStickyRestart()

        when (intent.action) {
            ACTION_CONNECT -> {
                val config = ServerRepository.getActiveServer(this)
                FileLogger.d(TAG, "onStartCommand: config=${if (config != null) "present, valid=${config.isValid}" else "null"}")
                if (config != null && config.isValid) {
                    // Dedup: drop a duplicate start intent while a connection attempt is
                    // already running, so we don't kill an almost-finished connectionJob.
                    if (_state.value is VpnState.Connecting && connectionJob?.isActive == true) {
                        FileLogger.w(TAG, "onStartCommand: already connecting with live job, ignoring duplicate CONNECT")
                        return START_STICKY
                    }
                    // Authoritative clean slate on every tap: stops the core, drops
                    // its callback bridge, closes every TUN descriptor in the ledger,
                    // frees a stuck reconnect mutex and blocked IO threads, so the
                    // core ALWAYS starts cleanly without needing a force-stop. Internal
                    // auto-reconnects call connect() directly (bypassing onStartCommand),
                    // so they are not affected by this.
                    forceResetCore("connect")
                    startForeground(NOTIFICATION_ID, createNotification("Connecting..."))
                    connect(config)
                } else {
                    FileLogger.e(TAG, "Invalid config")
                    _state.value = VpnState.Error("Invalid configuration")
                    stopSelf()
                }
            }
            ACTION_DISCONNECT -> {
                disconnect(StopIntent.USER)
            }
            ACTION_FORCE_RESET -> {
                FileLogger.w(TAG, "onStartCommand: ACTION_FORCE_RESET")
                forceResetCore("user force reset")
                _state.value = VpnState.Disconnected
                applyStopIntent(StopIntent.USER)
                try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
                stopSelf()
            }
        }
        return START_STICKY
    }

    /**
     * Handle the null intent of a sticky restart.
     *
     * The decision is [StickyRestart]'s; this only carries it out. The
     * foreground notification goes up before anything slow, including before
     * the stop path, because the system has just started us and a service that
     * never calls startForeground is a crash waiting on a timer.
     */
    private fun handleStickyRestart(): Int {
        startForeground(NOTIFICATION_ID, createNotification("Reconnecting..."))

        val shouldBeConnected = VpnWatchdogWorker.shouldVpnBeConnected(this)
        val config = ServerRepository.getActiveServer(this)
        val decision = StickyRestart.decide(shouldBeConnected, config != null && config.isValid)
        FileLogger.i(TAG, "onStartCommand: sticky restart (null intent), shouldBeConnected=$shouldBeConnected, " +
            "config=${if (config != null) "valid=${config.isValid}" else "null"} -> $decision")

        return when (decision) {
            StickyRestart.Decision.RECONNECT -> {
                forceResetCore("sticky restart")
                connect(config!!)
                START_STICKY
            }
            StickyRestart.Decision.STOP -> {
                _state.value = VpnState.Disconnected
                try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
                stopSelf()
                START_NOT_STICKY
            }
        }
    }

    /**
     * Write the persistent flags a teardown is entitled to write, and nothing
     * else. See [DisconnectPolicy] for why the intent has to travel this far.
     */
    private fun applyStopIntent(intent: StopIntent) {
        FileLogger.d(TAG, "applyStopIntent: $intent")
        if (DisconnectPolicy.clearsBootFlag(intent)) BootReceiver.markVpnDisconnected(this)
        if (DisconnectPolicy.cancelsWatchdog(intent)) VpnWatchdogWorker.cancel(this)
        if (DisconnectPolicy.marksUserDisconnect(intent)) VpnWatchdogWorker.markUserDisconnect(this)
    }

    /**
     * Start a connection attempt.
     *
     * [supersedes] is for callers that decided to connect some time ago and
     * have been waiting since — the auto-reconnect sequence spends seconds in
     * connectivity checks and backoff, and it is not entitled to cancel a
     * connect the user started meanwhile. Passing the generation the caller
     * believes it owns makes the claim atomic: either this becomes the newest
     * attempt, or nothing happens at all.
     *
     * Callers that are the user's own action (ACTION_CONNECT, a sticky
     * restart) pass null and always win.
     *
     * @return false when [supersedes] has been superseded and nothing started.
     */
    private fun connect(config: VpnConfig, supersedes: Int? = null): Boolean {
        // Claimed before anything else, including the watchdog: declining has
        // to leave no trace, and an armed watchdog with no attempt behind it
        // kills the process.
        val generation = if (supersedes == null) {
            // A new attempt starts here, so every cleanup an older one scheduled
            // is now stale. Bumping before anything is created means a
            // NonCancellable teardown already in flight cannot reach what we
            // are about to make.
            connectGeneration.begin()
        } else {
            connectGeneration.beginIfCurrent(supersedes) ?: run {
                FileLogger.w(TAG, "connect: generation $supersedes superseded while waiting, a newer attempt owns the connection")
                return false
            }
        }

        // WATCHDOG: arm before anything else below, so even the (currently harmless)
        // cancel/init calls that follow are inside the guarded window.
        armConnectWatchdog("connect")

        // Cancel any existing connection attempt
        connectionJob?.cancel()

        // Ensure coroutine scope is alive
        ensureScopeActive()

        // Keep the CPU awake for the connect and for the tunnel it produces.
        // Released on the two paths that end a tunnel: cleanupFailedConnection
        // and disconnect.
        acquireWakeLock()

        // Auto-reset scope if coroutine body hasn't started after 2 attempts
        connectAttemptsSinceBodyEntered++
        if (connectAttemptsSinceBodyEntered > 2) {
            FileLogger.w(TAG, "connect: coroutine body hasn't started in ${connectAttemptsSinceBodyEntered} attempts, resetting scope")
            Log.w(TAG, "connect: coroutine body hasn't started in ${connectAttemptsSinceBodyEntered} attempts, resetting scope")
            resetScope()
            connectAttemptsSinceBodyEntered = 1
        }

        FileLogger.d(TAG, "connect: launching coroutine (scopeActive=${scope.isActive}, scopeJob=${scope.coroutineContext[kotlinx.coroutines.Job]})")

        // DIAG: nothing above this line can block the calling (onStartCommand) thread -
        // connectionJob?.cancel() does not join/wait, there is no mutex/lock acquired
        // here. So this is the exact point where a new coroutine is handed to scope's
        // dispatcher (Dispatchers.IO's shared thread pool) for execution. If neither
        // this dispatch test nor COROUTINE BODY ENTERED below ever print, the gate is
        // NOT a scope/mutex/join block in this function - it means Dispatchers.IO has
        // no free worker thread (all wedged in blocking native calls) to run the new
        // coroutine on.
        FileLogger.w(TAG, "connect: dispatching onto scope now (Dispatchers.IO) - if BODY ENTERED doesn't follow, IO pool is exhausted, not a scope/lock/join block")

        // Diagnostic: test if scope can dispatch at all
        scope.launch {
            Log.d(TAG, ">>> SCOPE DISPATCH TEST OK on thread=${Thread.currentThread().name}")
            FileLogger.d(TAG, ">>> SCOPE DISPATCH TEST OK on thread=${Thread.currentThread().name}")
        }

        connectionJob = scope.launch {
            connectAttemptsSinceBodyEntered = 0  // Reset counter — body is executing
            Log.d(TAG, ">>> COROUTINE BODY ENTERED on thread=${Thread.currentThread().name}")
            FileLogger.d(TAG, ">>> COROUTINE BODY ENTERED on thread=${Thread.currentThread().name}")
            // WATCHDOG: body reached, disarm immediately - whatever happens next
            // (success, timeout, error) is real coroutine execution, not a wedge.
            disarmConnectWatchdog()
            try {
                FileLogger.i(TAG, "=== CONNECT START === mode=${config.connectionMode}, strategy=${config.strategy}")
                _state.value = VpnState.Connecting

                // One point of reference for the whole attempt, started at the
                // same instant as the coroutine fence and carrying the same
                // number. The fence alone could not keep it: every blocking
                // socket read below used to name its own timeout and run to it
                // after the fence had already fired, which is how a 60s budget
                // produced a 110s "Connecting…".
                val deadline = ConnectDeadline(System.currentTimeMillis(), CONNECTION_TIMEOUT)
                withTimeout(CONNECTION_TIMEOUT) {
                    when (config.connectionMode) {
                        "proxy" -> connectProxyMode(config, generation)
                        else -> connectTunMode(config, generation, deadline)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                FileLogger.e(TAG, "Connection timed out after ${CONNECTION_TIMEOUT/1000} seconds")
                clearPhase()
                _state.value = VpnState.Error("Connection timed out")
                cleanupFailedConnection(generation)
                // Schedule auto-reconnect after timeout
                scheduleAutoReconnect(config)
            } catch (e: ConnectDeadlineExceeded) {
                // The budget ran out inside a step that knows which step it was.
                // Reported instead of the coroutine fence firing later, and with
                // the step named: "waiting for the core to start" and "waiting
                // for it to answer set_fd" are different faults.
                FileLogger.e(TAG, "Connect budget spent at ${e.step} after ${e.elapsedMs}ms of ${e.totalMs}ms")
                clearPhase()
                _state.value = VpnState.Error(getString(R.string.connect_timed_out_at, e.step))
                cleanupFailedConnection(generation)
                scheduleAutoReconnect(config)
            } catch (e: CancellationException) {
                FileLogger.i(TAG, "Connection cancelled")
                // Don't set error state - this is intentional cancellation
                return@launch
            } catch (e: Exception) {
                FileLogger.e(TAG, "Connection failed", e)
                clearPhase()
                _state.value = VpnState.Error(e.message ?: "Unknown error")
                cleanupFailedConnection(generation)
                // Schedule auto-reconnect after failure
                scheduleAutoReconnect(config)
            }
        }
        FileLogger.d(TAG, "connect: job launched, isActive=${connectionJob?.isActive}, isCancelled=${connectionJob?.isCancelled}, isCompleted=${connectionJob?.isCompleted}")
        return true
    }

    /**
     * Schedule an automatic reconnection attempt after a failed connection.
     * Uses exponential backoff to avoid hammering the server.
     *
     * ITERATION 2: Now uses mutex to prevent parallel reconnect attempts
     * and cancels pending jobs before scheduling new one.
     *
     * ITERATION 2.1 (P0 FIX): Uses tryLock() and NonCancellable context to fix
     * JobCancellationException race conditions.
     */
    private fun scheduleAutoReconnect(config: VpnConfig) {
        FileLogger.d(TAG, "scheduleAutoReconnect: Called (state=${_state.value})")

        // Don't auto-reconnect if user disconnected
        if (_state.value is VpnState.Disconnected) {
            FileLogger.d(TAG, "scheduleAutoReconnect: State is Disconnected, not reconnecting")
            return
        }

        // CRITICAL: Try to acquire the reconnect lock - skip if already held
        val token = reconnectLock.tryAcquire()
        if (token == null) {
            FileLogger.w(TAG, "scheduleAutoReconnect: Reconnect already in progress (lock held), skipping")
            return
        }

        // NOTE: lock is released INSIDE the coroutine, not here
        FileLogger.d(TAG, "scheduleAutoReconnect: Lock acquired, proceeding")

        // Cancel any pending reconnect job BEFORE scheduling new one
        pendingReconnectJob?.cancel()

        reconnectAttempts++

        // Maximum number of reconnect attempts before giving up
        val maxReconnectAttempts = 30
        if (reconnectAttempts > maxReconnectAttempts) {
            FileLogger.e(TAG, "scheduleAutoReconnect: Too many attempts ($reconnectAttempts), giving up")
            _state.value = VpnState.Error("Connection failed after $reconnectAttempts attempts")
            reconnectLock.release(token)
            // TECHNICAL: we gave up, the user did not. Clearing the persistent
            // flags here is what used to make the watchdog and BootReceiver
            // stand down for good after a bad hour of connectivity.
            disconnect(StopIntent.TECHNICAL)
            return
        }

        // Exponential backoff: 2s, 4s, 6s, 8s, 10s (capped)
        val backoffMs = minOf(2000L * reconnectAttempts, maxBackoffMs)

        FileLogger.i(TAG, "scheduleAutoReconnect: Scheduling reconnect in ${backoffMs}ms (attempt $reconnectAttempts)")

        // Don't overwrite Connected state — new instance may have already connected
        if (_state.value !is VpnState.Connected) {
            _state.value = VpnState.Connecting
        }

        ensureScopeActive()
        // The generation this retry belongs to, read before the wait. If
        // anything opens a newer one while we sleep, the connect below declines
        // instead of cancelling it — the backoff delay is cancellable, but only
        // up to the moment it returns, and connect() follows it inside a
        // NonCancellable block.
        val scheduledFrom = connectGeneration.current
        pendingReconnectJob = scope.launch {
            try {
                // Wrap delay() to suppress cosmetic JobCancellationException
                try {
                    delay(backoffMs)
                } catch (e: CancellationException) {
                    FileLogger.d(TAG, "scheduleAutoReconnect: Reconnect delay cancelled")
                    return@launch
                }

                if (_state.value is VpnState.Disconnected || _state.value is VpnState.Connected) {
                    FileLogger.d(TAG, "scheduleAutoReconnect: State is ${_state.value}, aborting scheduled reconnect")
                    return@launch
                }

                FileLogger.i(TAG, "scheduleAutoReconnect: Starting reconnect (attempt $reconnectAttempts)")

                withContext(NonCancellable) {
                    // Final guard: the user may have hit disconnect() during the backoff
                    // delay. NonCancellable means delay's cancellation can't abort us, so
                    // re-check before reconnecting to avoid reviving the VPN against the
                    // user's will.
                    if (_state.value is VpnState.Disconnected) {
                        FileLogger.i(TAG, "scheduleAutoReconnect: Disconnected during backoff, aborting reconnect")
                        isReconnecting = false
                        return@withContext
                    }
                    isReconnecting = true
                    try {
                        if (!connect(config, supersedes = scheduledFrom)) {
                            FileLogger.w(TAG, "scheduleAutoReconnect: a newer attempt claimed the connection, standing down")
                        }
                    } catch (e: Exception) {
                        FileLogger.e(TAG, "scheduleAutoReconnect: connect() failed", e)
                        throw e
                    } finally {
                        isReconnecting = false
                    }
                }
            } catch (e: CancellationException) {
                FileLogger.w(TAG, "scheduleAutoReconnect: Reconnect job cancelled", e)
                throw e
            } catch (e: Exception) {
                FileLogger.e(TAG, "scheduleAutoReconnect: Reconnect job failed", e)
            } finally {
                // release(token), never a bare unlock: if this job was
                // cancelled and a newer reconnect has taken the lock since,
                // this call must fail rather than free the newer holder.
                val released = reconnectLock.release(token)
                FileLogger.d(TAG, "scheduleAutoReconnect: Lock released=$released (coroutine done)")
            }
        }
    }

    /**
     * Clean up resources after a failed connection attempt.
     * Does NOT change state or stop service - caller should handle that.
     *
     * [generation] is the attempt this cleanup belongs to. Checking it once on
     * entry was not enough, and for the same reason as in
     * [executeReconnectSequence]: this runs from the catch blocks of a connect
     * coroutine that has already been cancelled, so a fresh connect() can be
     * building its core, its channel and its interface while these lines run.
     * One check at the top is a window, not a guard — every step below is
     * gated, and the two long-lived objects are taken by identity so that a
     * field holding a newer one is left alone.
     */
    private fun cleanupFailedConnection(generation: Int) {
        if (!stillOurs(generation, "cleanupFailedConnection")) return
        FileLogger.d(TAG, "Cleaning up failed connection...")

        val ownedChannel = controlChannel

        // Stop the core and the protect server — process-wide, both of them,
        // so a snapshot of the Kotlin object is not enough: NativeProcessJNI
        // .stop() ends up in stopClient() on a Go package-level variable, and
        // stopProtectServer() closes whatever socket the field holds. Taking
        // ownership is what makes this safe; a newer connect is inside
        // awaitCoreHandover until the block below returns.
        // Everything process-wide goes inside one ownership block: the core,
        // the JNI callback bridge, the protect server and the control socket
        // file. A snapshot of the Kotlin objects is not enough for any of them
        // — NativeProcessJNI.stop() ends in stopClient() on a Go package-level
        // variable, cleanup() drops a JNI global ref, stopProtectServer()
        // closes whatever the field holds, and control.sock is one fixed name.
        // Taking ownership is what makes this safe: a newer connect sits in
        // awaitCoreHandover until this block returns.
        withOwnedCore(generation, "cleanupFailedConnection") {
            val ownedProcess = tiredvpnProcess
            ownedProcess?.stop()
            if (tiredvpnProcess === ownedProcess) tiredvpnProcess = null

            // Unwire the callback bridge. Read cleanupNative before relying on
            // this for anything else: on the Go side it is DeleteGlobalRef on
            // the callback object plus two method-id assignments
            // (cmd/tiredvpn/jni_android.go, jni_cleanup). It does not cancel
            // the client context, does not wait for any goroutine and does not
            // close the dup'd TUN descriptor — four comments here used to say
            // it did. What it does buy is worth having: a strategy attempt that
            // outlives stopClient() can no longer report its own death to us.
            try { TiredVpnNative.cleanup() } catch (e: Throwable) {
                FileLogger.w(TAG, "cleanupFailedConnection: native cleanup failed: ${e.message}")
            }

            // The protect server belongs to the same handover: the core dials
            // it, and a half-torn-down pair leaves the core's sockets
            // unprotected. The socket FILE is not unlinked here — that belongs
            // to the generation that owns the name, see [protectGeneration].
            stopProtectServer()

            File("${filesDir.absolutePath}/control.sock").delete()
        }

        // Close the channel we opened, by identity
        if (ownedChannel != null) {
            if (controlChannel === ownedChannel) controlChannel = null
            try { ownedChannel.close() } catch (e: Exception) {
                FileLogger.w(TAG, "cleanupFailedConnection: closing control channel: ${e.message}")
            }
        }

        // Close VPN interface (through the ledger, so it is closed exactly once)
        if (!stillOurs(generation, "TUN ledger")) return
        vpnInterface = null
        tunHandles.releaseAll()

        // The endpoint pool lists every server the user dials; it is rebuilt on
        // the next connect, so nothing needs it once the core has stopped —
        // but a newer connect writes it before starting its core, and deleting
        // it then leaves that core with no failover list at all.
        if (!stillOurs(generation, "endpoint pool")) return
        ServerPoolConfig.delete(filesDir)

        // Release the WakeLock. Shared with whatever is connected now, so a
        // stale cleanup letting the CPU sleep would end a tunnel it has nothing
        // to do with — which is exactly the fault this lock was widened to fix.
        if (!stillOurs(generation, "wake lock")) return
        releaseWakeLock()
    }

    /**
     * Is [generation] still the current connection attempt?
     *
     * The question every destructive step has to ask, and the reason it is a
     * function rather than a check at the top of each teardown: cleanup here
     * runs from cancelled coroutines and from NonCancellable blocks, so between
     * any two lines a fresh connect() can have created the very thing the next
     * line destroys. One check at the entry answers for the entry only.
     */
    private fun stillOurs(generation: Int, step: String): Boolean {
        if (connectGeneration.isCurrent(generation)) return true
        FileLogger.w(TAG, "generation $generation superseded before $step, leaving the newer attempt alone")
        return false
    }

    /**
     * Stop the process-wide core and protect server, but only if [generation]
     * still owns them — and take them out of the slot before touching them.
     *
     * The difference from [stillOurs] is the whole point of [CoreOwnership]:
     * that one answers a question and leaves the answer to go stale, this one
     * takes possession. A newer connect arriving after the take finds an empty
     * slot and waits in [awaitCoreHandover] rather than racing us.
     *
     * @return true when [teardown] ran.
     */
    private inline fun withOwnedCore(generation: Int, what: String, teardown: () -> Unit): Boolean {
        if (!coreOwnership.takeForTeardown(generation)) {
            FileLogger.w(TAG, "$what: generation $generation does not own the core (owner=${coreOwnership.currentOwner}), leaving it alone")
            return false
        }
        try {
            teardown()
        } finally {
            coreOwnership.finishTeardown(generation)
        }
        return true
    }

    /**
     * The same, for a teardown that answers to no generation: a user-initiated
     * disconnect and the authoritative reset stop whatever is running.
     *
     * "Authoritative" does not mean "regardless of who else is in there".
     * [CoreOwnership.takeForReset] returns null while another teardown holds
     * the core, and then this does nothing global — that teardown is already
     * running the same `stop()` and `cleanup()`, and a second pass over one Go
     * core is the fault the ownership model exists to prevent. Everything else
     * on the disconnect path still runs; only the process-wide block is skipped.
     */
    private inline fun withCoreReset(teardown: () -> Unit) {
        val taken = coreOwnership.takeForReset()
        if (taken == null) {
            FileLogger.w(TAG, "withCoreReset: another teardown owns the core, leaving the core to it")
            return
        }
        try {
            teardown()
        } finally {
            coreOwnership.finishTeardown(taken)
        }
    }

    /**
     * Wait for a teardown in flight to hand the core over, then take ownership.
     *
     * Called on the connect path before anything process-wide is created.
     *
     * On expiry this does **not** start anyway. It used to, and that put the
     * ownership model back where it started: the wedged teardown is not
     * cancelled by the timeout, it is merely no longer waited for — so it comes
     * back at its leisure and runs `cleanup()`, `stopProtectServer()` and the
     * `control.sock` unlink over resources the new attempt has since created.
     * A timeout that hands the same core to two owners is worse than no
     * timeout.
     *
     * What it does instead is what [armConnectWatchdog] already does for the
     * same class of fault, and for the same reason: `stopClient()` has a
     * five-second ceiling of its own (a select with `time.After` in
     * jni_android.go), so a teardown still running after seven is a native
     * thread the JVM cannot reclaim. The process is not in a state anything
     * should be built on top of. So: say so in the log, tell the UI, and end
     * the process so the system or the user starts one that is clean — the
     * watchdog, the boot receiver and a tap on Connect all bring it back, and
     * the persistent flags are untouched because this is not a user stop.
     */
    private fun awaitCoreHandover(generation: Int) {
        if (!coreOwnership.awaitHandover(coreHandoverTimeoutMs)) {
            FileLogger.e(
                TAG,
                "=== CORE HANDOVER TIMED OUT === the previous teardown has been inside stopClient() for " +
                    "${coreHandoverTimeoutMs}ms, past its own 5s ceiling: a native thread is wedged and the " +
                    "JVM cannot reclaim it. Refusing to start a second core on top of it; killing the process " +
                    "so the system or the user can relaunch cleanly."
            )
            Log.e(TAG, "CORE HANDOVER TIMED OUT: killing process to release a wedged native thread")

            clearPhase()
            _state.value = VpnState.Error(getString(R.string.error_core_wedged))
            try { updateNotification(getString(R.string.error_core_wedged)) } catch (_: Exception) {}

            // Best-effort: give FileLogger's writer thread (flushes every
            // ~100ms) one cycle to persist the lines above, and the state flow
            // one to reach a bound UI, before the process dies.
            try { Thread.sleep(200) } catch (_: InterruptedException) {}
            android.os.Process.killProcess(android.os.Process.myPid())
            // Unreachable in practice; keeps the contract explicit for anyone
            // reading this as "and then it carries on".
            throw IllegalStateException("core handover timed out; process is going down")
        }
        coreOwnership.claim(generation)
        FileLogger.d(TAG, "Core ownership claimed by generation $generation")
    }

    /**
     * Authoritative, synchronous, idempotent reset of the native core and all
     * service state. Consolidates the cleanup helpers already used by disconnect()
     * so a fresh connect can ALWAYS start from a clean slate — even when the
     * previous attempt left a stuck reconnect mutex, leaked Go goroutines holding
     * the TUN fd, or blocked Dispatchers.IO threads. This is what makes "force-stop
     * the app from Android settings" unnecessary.
     *
     * MUST be called from onStartCommand (main service thread), NOT from inside a
     * scope coroutine — the final resetScope() would cancel the calling coroutine.
     */
    private fun forceResetCore(reason: String) {
        FileLogger.w(TAG, "=== forceResetCore: $reason ===")

        // 1. Cancel connection / reconnect coroutines
        disarmConnectWatchdog()  // explicit reset, not a wedge - don't kill for this
        connectionJob?.cancel()
        connectionJob = null
        pendingReconnectJob?.cancel()
        pendingReconnectJob = null

        // 2. Stop all background jobs
        statusMonitorJob?.cancel()
        statusMonitorJob = null
        stopProcessWatchdog()
        stopHealthCheck()
        stopNetworkMonitoring()
        stopActiveNetworkMonitor()
        stopNetworkWatchdog()
        stopNetworkRecoveryJob()
        stopProtectServer()

        // 3. Close control socket
        closeControlChannel()

        // 4/5. Stop the core and drop the JNI callback bridge, as one owner.
        //
        // takeForReset and not takeForTeardown: this is the authoritative reset
        // and it answers to no generation — it stops whatever is running. It
        // still goes through the same slot, so a connect that arrives during it
        // waits in awaitCoreHandover instead of starting a second core.
        withCoreReset {
            try { tiredvpnProcess?.stop() } catch (e: Exception) { FileLogger.w(TAG, "forceResetCore: stop process", e) }
            tiredvpnProcess = null
            try { TiredVpnNative.cleanup() } catch (e: Exception) { FileLogger.w(TAG, "forceResetCore: native cleanup", e) }
        }

        // 6. Kill processes left over from a build that ran the core as a
        // separate binary. In JNI mode there is no such process, so this is
        // purely an upgrade path - which is why it now runs here only, and not
        // five times per connect as it used to.
        NativeProcess.killAllTiredVpnProcesses()

        // Forget the negotiated tunnel config: the next handshake writes a new one.
        activeTunnelConfig = null

        // 7. Drop the current interface; step 8 closes it along with our orphans.
        vpnInterface = null

        // 8. Close the TUN descriptors we still own. One pass is enough now:
        // the ledger already holds every descriptor this service established,
        // so there is nothing to wait for. The old delayed sweep existed only
        // to catch descriptors appearing later in /proc/self/fd, which is the
        // scan that could reach the core's dup and abort the process.
        forceCloseTunFds()

        // 9. Delete socket files and the generated endpoint pool
        File("${filesDir.absolutePath}/control.sock").delete()
        File("${filesDir.absolutePath}/protect.sock").delete()
        ServerPoolConfig.delete(filesDir)

        // 10. Release the reconnect lock by the token we recorded when it was
        // taken. A bare unlock() also succeeded — tryLock() had taken it
        // without an owner — and that is precisely the bug: the cancelled
        // coroutine then released whatever the NEXT reconnect had acquired,
        // and two reconnects ran at once against one Go core.
        if (reconnectLock.forceRelease()) {
            FileLogger.d(TAG, "forceResetCore: released a held reconnect lock")
        }

        // 11. Reset all sticky flags
        isReconnecting = false
        reconnectAttempts = 0
        isNetworkLost = false
        connectAttemptsSinceBodyEntered = 0
        clearPhase()

        // 12. Reset coroutine scope last (releases stuck Dispatchers.IO threads)
        resetScope()

        FileLogger.d(TAG, "forceResetCore: done")
    }

    private suspend fun connectTunMode(config: VpnConfig, generation: Int, deadline: ConnectDeadline) {
        val controlPath = "${filesDir.absolutePath}/control.sock"
        val protectPath = "${filesDir.absolutePath}/protect.sock"
        FileLogger.d(TAG, "Control socket path: $controlPath")
        FileLogger.d(TAG, "Protect socket path: $protectPath")

        // Clean up old sockets
        File(controlPath).delete()
        File(protectPath).delete()
        FileLogger.d(TAG, "Old sockets deleted")

        // 0. CRITICAL: Resolve DNS BEFORE starting VPN
        // After VPN is established, DNS queries go through the tunnel (which doesn't work yet)
        setPhase(getString(R.string.phase_resolving))
        FileLogger.i(TAG, "STEP 0: Pre-resolving server hostname...")
        val resolvedEndpoint = resolveServerEndpoint(config.serverEndpoint)
        if (resolvedEndpoint == null) {
            throw RuntimeException("Failed to resolve server hostname: ${config.serverEndpoint}")
        }
        FileLogger.i(TAG, "STEP 0: Resolved ${config.serverEndpoint} -> $resolvedEndpoint")

        // 0b. Resolve the rest of the failover pool on the same raw network.
        // A pool member is dialled only after the active one stopped working,
        // by which time DNS through the tunnel is exactly what is broken - so
        // the names have to be turned into addresses here or not at all.
        val pool = ArrayList<VpnConfig>()
        val resolvedPool = HashMap<String, String>()
        pool.add(config)
        resolvedPool[config.id] = resolvedEndpoint
        for (member in ServerPoolConfig.selectPool(ServerRepository.getServers(this), config)) {
            if (member.id == config.id) continue
            // InetAddress.getAllByName has no timeout of its own, so the guard
            // is between resolutions rather than around one: a pool of five
            // behind a dead resolver must not spend the whole attempt here.
            if (deadline.isExpired(System.currentTimeMillis())) {
                FileLogger.w(TAG, "Pool member ${member.name} left out: connect budget spent while resolving")
                break
            }
            val resolved = resolveServerEndpoint(member.serverEndpoint)
            if (resolved == null) {
                // Left out rather than passed through as a name: an
                // unresolvable candidate is a connect cycle the core would
                // spend before reaching one that works.
                FileLogger.w(TAG, "Pool member ${member.name} left out: cannot resolve ${member.serverEndpoint}")
                continue
            }
            pool.add(member)
            resolvedPool[member.id] = resolved
        }
        val poolConfigPath = preparePoolConfig(config, pool, resolvedPool)

        // 1a. Take ownership of the process-wide core and protect server
        // before creating either. A teardown from the attempt we superseded
        // may still be inside its stop; starting on top of it is how a fresh
        // core got stopped by an old one's cleanup.
        awaitCoreHandover(generation)

        // Start protect socket server BEFORE starting native process
        // Native process will use this to call VpnService.protect() on its sockets
        FileLogger.i(TAG, "STEP 1a: Starting protect socket server...")
        startProtectServer(protectPath)
        FileLogger.i(TAG, "STEP 1a: Protect server started")

        // 1b. Start tiredvpn with control socket (using resolved IP)
        setPhase(getString(R.string.phase_starting_core))
        FileLogger.i(TAG, "STEP 1b: Starting tiredvpn process...")
        startTiredVpnProcess(config, resolvedEndpoint, controlPath, protectPath, poolConfigPath)
        FileLogger.i(TAG, "STEP 1b: Process started")

        // 2. Wait for socket and connect
        setPhase(getString(R.string.phase_control_socket))
        FileLogger.i(TAG, "STEP 2: Connecting to control socket...")
        val tunConfig = connectToControlSocket(controlPath, deadline)
            ?: throw RuntimeException("Failed to get tunnel config from server")

        FileLogger.i(TAG, "STEP 2: Got tunnel config: IP=${tunConfig.ip}, DNS=${tunConfig.dns}, MTU=${tunConfig.mtu}")

        // Remember what the core negotiated. sendNetworkChangedCommand rebuilds
        // the interface from this; before, it rebuilt from constants and lost
        // the user's DNS on the first network switch.
        activeTunnelConfig = tunConfig

        // 3. Create VPN interface with server-provided config
        setPhase(getString(R.string.phase_creating_tunnel))
        FileLogger.i(TAG, "STEP 3: Creating VPN interface...")
        val vpnFd = establishVpn(config, tunConfig)
            ?: throw RuntimeException("Failed to establish VPN interface")
        vpnInterface = vpnFd
        FileLogger.i(TAG, "STEP 3: VPN interface created, fd=${vpnFd.fd}")

        // Diagnostic: check if our VPN is now the active network
        val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val activeNet = cm.activeNetwork
        val activeCaps = activeNet?.let { cm.getNetworkCapabilities(it) }
        val isVpnTransport = activeCaps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
        FileLogger.i(TAG, "STEP 3: Post-establish check: activeNetwork=$activeNet, isVpnTransport=$isVpnTransport")

        // 4. Send fd to tiredvpn via control socket with retry
        // BUG FIX 2: Retry if handshake fails (EOF error from server)
        var tunFd = vpnFd.fd
        var finalIp: String? = null
        val maxHandshakeRetries = 2
        var handshakeAttempt = 0

        while (finalIp == null && handshakeAttempt < maxHandshakeRetries) {
            handshakeAttempt++
            setPhase(getString(R.string.phase_handshake))
            FileLogger.i(TAG, "STEP 4: Sending TUN fd=$tunFd to tiredvpn (attempt $handshakeAttempt/$maxHandshakeRetries)...")
            finalIp = sendTunFd(tunFd, deadline)

            if (finalIp == null && handshakeAttempt < maxHandshakeRetries) {
                // A retry is worth starting only while the core could still
                // reach a verdict inside what is left. Otherwise it is the same
                // failure, announced later — which is the whole defect this
                // deadline exists to remove.
                val left = deadline.remainingMs(System.currentTimeMillis())
                if (left < ConnectBudget.HANDSHAKE_RETRY_MIN_BUDGET_MS + HANDSHAKE_RETRY_DELAY_MS) {
                    FileLogger.w(TAG, "STEP 4: Handshake failed and only ${left}ms of budget left, not retrying")
                    break
                }
                FileLogger.w(TAG, "STEP 4: Handshake failed, retrying after ${HANDSHAKE_RETRY_DELAY_MS}ms (${left}ms of budget left)...")
                delay(HANDSHAKE_RETRY_DELAY_MS)
            }
        }

        if (finalIp == null) {
            FileLogger.e(TAG, "STEP 4: FAILED - sendTunFd failed after $maxHandshakeRetries attempts")
            throw RuntimeException("Failed to activate tunnel after $maxHandshakeRetries attempts")
        }
        FileLogger.i(TAG, "STEP 4: SUCCESS - finalIp=$finalIp (after $handshakeAttempt attempts)")

        // 5. If server assigned different IP, recreate VPN interface
        // FIX: Don't recreate if original IP was "auto" - just use assigned IP
        if (finalIp != tunConfig.ip && finalIp.isNotEmpty() && tunConfig.ip != "auto") {
            FileLogger.w(TAG, "Server assigned different IP: $finalIp (requested: ${tunConfig.ip}), recreating interface")

            val newTunConfig = tunConfig.copy(ip = finalIp)
            val newVpnFd = establishVpn(config, newTunConfig)
            if (newVpnFd == null) {
                FileLogger.e(TAG, "Failed to recreate VPN interface with new IP")
            } else {
                // Close the interface we are superseding. Leaving it open was
                // how /dev/tun descriptors accumulated across reconnects (tun0,
                // tun1, tun2 alive at once with frozen counters), which is what
                // the by-number sweep was later invented to mop up.
                val superseded = vpnInterface
                vpnInterface = newVpnFd
                if (superseded != null && superseded !== newVpnFd) {
                    tunHandles.forget(superseded)
                    try { superseded.close() } catch (e: Exception) {
                        FileLogger.w(TAG, "Failed to close superseded VPN interface: ${e.message}")
                    }
                }
                tunFd = newVpnFd.fd

                val newFinalIp = sendTunFd(tunFd, deadline)
                if (newFinalIp != null) {
                    finalIp = newFinalIp
                    activeTunnelConfig = newTunConfig.copy(ip = finalIp)
                    FileLogger.i(TAG, "VPN interface recreated with IP: $finalIp")
                }
            }
        }

        FileLogger.i(TAG, "STEP 5: Connection complete, updating state...")
        clearPhase()
        _state.value = VpnState.Connected(
            strategy = connectedStrategy.ifEmpty { config.strategy },
            latencyMs = connectedLatencyMs,
            attempts = connectedAttempts
        )
        updateNotification("Connected • $finalIp")
        currentVpnIp = finalIp
        // Remember the negotiated dual-stack addresses so the network-change
        // path can recreate the interface with the same v6 configuration.
        currentVpnIp6 = tunConfig.ip6 ?: ""
        currentVpnServerIp6 = tunConfig.serverIp6 ?: ""
        FileLogger.i(TAG, "=== VPN CONNECTED === strategy=$connectedStrategy, latency=${connectedLatencyMs}ms, ip=$finalIp")

        // Record connection time and reset reconnect counter. The wake lock
        // stays held: see acquireWakeLock for why letting the CPU suspend here
        // ends the session rather than saving battery.
        connectionTime = System.currentTimeMillis()
        lastKeepaliveTime = SystemClock.uptimeMillis() // Initialize for health check
        reconnectAttempts = 0

        // Mark VPN as connected for boot recovery (Direct Boot support)
        BootReceiver.markVpnConnected(this)

        // Schedule VPN watchdog to auto-restart if service gets killed
        VpnWatchdogWorker.schedule(this)

        // 6. Start status monitoring
        FileLogger.i(TAG, "STEP 6: Starting status monitoring...")
        startStatusMonitoring()

        // 7. Start network change monitoring
        FileLogger.i(TAG, "STEP 7: Starting network monitoring...")
        startNetworkMonitoring()

        // 8. Start internal process watchdog (detects PhantomProcess kills quickly)
        FileLogger.i(TAG, "STEP 8: Starting process watchdog...")
        startProcessWatchdog()

        // 9. ITERATION 3: Start health check (pings Go process every 10s)
        FileLogger.i(TAG, "STEP 9: Starting health check...")
        startHealthCheck()

        // 10. PIXEL FIX: Start active network monitor (backup for unreliable NetworkCallback)
        FileLogger.i(TAG, "STEP 10: Starting active network monitor (Pixel fix)...")
        startActiveNetworkMonitor()

        FileLogger.i(TAG, "=== ALL STEPS COMPLETE ===")
    }

    private suspend fun connectProxyMode(config: VpnConfig, generation: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw RuntimeException("HTTP Proxy mode requires Android 10+")
        }

        FileLogger.i(TAG, "=== PROXY MODE CONNECT START ===")

        // 1. Take ownership of the one core in this process, then start it.
        awaitCoreHandover(generation)

        // Start tiredvpn in proxy mode (without -tun, without control socket)
        setPhase(getString(R.string.phase_starting_core))
        FileLogger.i(TAG, "STEP 1: Starting tiredvpn in proxy mode...")
        startTiredVpnProxyProcess(config)

        // 2. Wait for proxy port to be ready
        setPhase(getString(R.string.phase_control_socket))
        FileLogger.i(TAG, "STEP 2: Waiting for proxy port ${config.proxyPort} to be ready...")
        val proxyReady = waitForProxyPort(config.proxyPort)
        if (!proxyReady) {
            throw RuntimeException("Failed to start HTTP proxy on port ${config.proxyPort}")
        }
        FileLogger.i(TAG, "STEP 2: Proxy is listening on port ${config.proxyPort}")

        // 3. Create VPN with HTTP proxy (Android 10+)
        setPhase(getString(R.string.phase_creating_tunnel))
        FileLogger.i(TAG, "STEP 3: Creating VPN with HTTP proxy...")
        val vpnFd = establishProxyVpn(config)
        if (vpnFd == null) {
            throw RuntimeException("Failed to establish VPN interface for proxy")
        }
        vpnInterface = vpnFd
        tunHandles.track(vpnFd)

        // 4. Update state
        val proxyAddress = "127.0.0.1:${config.proxyPort}"
        FileLogger.i(TAG, "STEP 4: Proxy mode connected at $proxyAddress")

        clearPhase()
        _state.value = VpnState.Connected(
            strategy = connectedStrategy.ifEmpty { config.strategy },
            latencyMs = connectedLatencyMs,
            attempts = connectedAttempts,
            mode = "proxy",
            proxyAddress = proxyAddress
        )
        updateNotification("Proxy • $proxyAddress")

        // Record connection time. The wake lock stays held for the lifetime of
        // the tunnel — see acquireWakeLock.
        connectionTime = System.currentTimeMillis()
        lastKeepaliveTime = SystemClock.uptimeMillis()
        reconnectAttempts = 0

        // Mark VPN as connected for boot recovery (Direct Boot support)
        BootReceiver.markVpnConnected(this)

        // Schedule VPN watchdog to auto-restart if service gets killed
        VpnWatchdogWorker.schedule(this)

        // 5. Start process monitoring (no control socket in proxy mode)
        FileLogger.i(TAG, "STEP 5: Starting process monitoring...")
        startProxyMonitoring()
        startNetworkMonitoring()

        // 6. Start internal process watchdog (detects PhantomProcess kills quickly)
        FileLogger.i(TAG, "STEP 6: Starting process watchdog...")
        startProcessWatchdog()

        // 7. ITERATION 3: Start health check
        FileLogger.i(TAG, "STEP 7: Starting health check...")
        startHealthCheck()

        // 8. PIXEL FIX: Start active network monitor
        FileLogger.i(TAG, "STEP 8: Starting active network monitor (Pixel fix)...")
        startActiveNetworkMonitor()

        FileLogger.i(TAG, "=== PROXY MODE CONNECTED ===")
    }

    /**
     * Write the endpoint pool the core dials through `-config` and return its
     * path, or null when there is nothing to fail over to.
     *
     * A one-element pool deliberately keeps the old `-server` path: the file
     * would say exactly what the flag already says, and the flag is the
     * better-travelled route. A failure to write is not fatal for the same
     * reason - the caller falls back to the single active server.
     */
    private fun preparePoolConfig(
        active: VpnConfig,
        pool: List<VpnConfig>,
        resolvedById: Map<String, String>
    ): String? {
        try {
            val entries = if (pool.size < 2) emptyList() else ServerPoolConfig.entries(pool, resolvedById)
            if (entries.size < 2) {
                ServerPoolConfig.delete(filesDir)
                FileLogger.i(TAG, "Endpoint pool: single server, using -server")
                return null
            }
            val file = ServerPoolConfig.write(filesDir, ServerPoolConfig.render(entries, active))
            FileLogger.i(TAG, "Endpoint pool: ${entries.size} servers [${entries.joinToString(", ") { it.name }}]")
            return file.absolutePath
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to write endpoint pool config, falling back to a single server", e)
            ServerPoolConfig.delete(filesDir)
            return null
        }
    }

    private fun startTiredVpnProxyProcess(config: VpnConfig) {
        val binaryPath = "${applicationInfo.nativeLibraryDir}/libtiredvpn.so"
        val binaryFile = File(binaryPath)

        if (!binaryFile.exists()) {
            throw RuntimeException("tiredvpn binary not found at $binaryPath")
        }

        // Proxy mode has no tunnel, so DNS keeps working and the pool entries
        // go in as configured - no pre-resolution pass here.
        val pool = ServerPoolConfig.selectPool(ServerRepository.getServers(this), config)
        val poolConfigPath = preparePoolConfig(config, pool, emptyMap())

        // Proxy mode: no -tun, no -control-socket, just -listen.
        //
        // -secret is the active server's key and stays here even with a pool
        // file, where every entry names its own: the core reads it as the
        // default for entries that name none, so it is inert with a pool and is
        // the only key on the -server path a one-server pool falls back to.
        val args = mutableListOf(
            binaryFile.absolutePath,
            "client",
            "-secret", config.secret,
            "-listen", "127.0.0.1:${config.proxyPort}"
        )

        // Pool file or single server, never both - see ServerPoolConfig.endpointArgs.
        args.addAll(
            ServerPoolConfig.endpointArgs(
                poolConfigPath = poolConfigPath,
                serverEndpoint = config.serverEndpoint,
                serverAddressV6 = config.serverAddressV6,
                preferIpv6 = config.preferIpv6,
                fallbackV4 = config.fallbackV4
            )
        )

        // Strategy
        if (config.strategy != "auto") {
            args.addAll(listOf("-strategy", config.strategy))
        }

        // QUIC
        if (config.enableQuic) {
            args.add("-quic")
            args.addAll(listOf("-quic-port", config.quicPort.toString()))
        }

        // Cover host for traffic morphing
        if (config.coverHost.isNotBlank()) {
            args.addAll(listOf("-cover", config.coverHost))
        }

        // RTT masking
        if (config.rttMasking) {
            args.add("-rtt-masking")
            args.addAll(listOf("-rtt-profile", config.rttProfile))
        }

        // Fallback
        if (config.fallbackEnabled) {
            args.add("-fallback")
        }

        // Traffic shaper
        if (config.shaperPreset.isNotBlank()) {
            args.addAll(listOf("-shaper", config.shaperPreset))
            if (config.shaperSeed != 0L) {
                args.addAll(listOf("-shaper-seed", config.shaperSeed.toString()))
            }
        }

        // ECH (Encrypted Client Hello)
        if (config.echEnabled) {
            args.add("-ech")
            if (config.echConfig.isNotBlank()) {
                args.addAll(listOf("-ech-config", config.echConfig))
            }
            args.addAll(listOf("-ech-public-name", config.echPublicName))
        }

        // QUIC SNI fragmentation
        if (config.quicSniFrag) {
            args.add("-quic-sni-frag")
        }

        // Debug logging controlled by settings toggle
        if (config.debugLogging) args.add("-debug")

        FileLogger.d(TAG, "Starting tiredvpn (proxy mode): ${NativeArgs.redact(args)}")

        // Use JNI mode on all Android versions to avoid SELinux restrictions
        // Android 10+ blocks execution of standalone binaries from app storage
        // JNI mode on every Android version: Android 10+ blocks execution of
        // standalone binaries from app storage, so there is no second path.
        // The `if (true) { ... } else { NativeProcess(...) }` this replaces
        // carried an unreachable copy of the exit state machine that had
        // already started drifting from the live one.
        tiredvpnProcess = NativeProcessJNI(
            args = args.drop(1), // Skip binary path for JNI mode
            onOutput = { line ->
                FileLogger.d(TAG, "[tiredvpn-jni] $line")
                parseConnectionInfo(line)
            },
            onError = { line ->
                FileLogger.e(TAG, "[tiredvpn-jni] $line")
                parseConnectionInfo(line)
            },
            onExit = { code -> handleCoreExit(code, "proxy") }
        ).also { it.start() }
    }

    /**
     * Wait for proxy port to be listening by trying to connect to it.
     */
    private suspend fun waitForProxyPort(port: Int): Boolean {
        FileLogger.d(TAG, "waitForProxyPort: waiting for port $port to be ready")

        val deadline = System.currentTimeMillis() + CONTROL_SOCKET_TIMEOUT
        var waitMs = 0L

        while (System.currentTimeMillis() < deadline) {
            // "did the core start and is it still there" - the right question
            // here: the proxy is not listening yet, so there is no traffic to
            // measure the tunnel by.
            if (!coreStarted) {
                FileLogger.e(TAG, "waitForProxyPort: tiredvpn core exited")
                return false
            }

            // Try to connect to the proxy port
            try {
                val socket = java.net.Socket()
                socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 1000)
                socket.close()
                FileLogger.d(TAG, "waitForProxyPort: port $port is ready after ${waitMs}ms")
                return true
            } catch (e: Exception) {
                // Port not ready yet, keep waiting
                if (waitMs % 1000 == 0L) {
                    FileLogger.d(TAG, "waitForProxyPort: still waiting... ${waitMs}ms")
                }
            }

            delay(200)
            waitMs += 200
        }

        FileLogger.e(TAG, "waitForProxyPort: timeout waiting for port $port")
        return false
    }

    /**
     * Monitor tiredvpn process in proxy mode (no control socket).
     */
    private fun startProxyMonitoring() {
        statusMonitorJob = scope.launch {
            // Just monitor if process is still running
            while (isActive && _state.value is VpnState.Connected) {
                delay(5000) // Check every 5 seconds

                // Proxy mode has no control channel, so this is the only
                // liveness signal available - see checkTunnelHealth, which opts
                // out here for the same reason.
                if (!coreStarted) {
                    FileLogger.e(TAG, "Proxy core exited, reconnecting...")
                    handleControlSocketBroken()
                    break
                }
            }
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun establishProxyVpn(config: VpnConfig): ParcelFileDescriptor? {
        return try {
            val proxyInfo = ProxyInfo.buildDirectProxy("127.0.0.1", config.proxyPort)

            val proxyMtu = 1500
            val builder = Builder()
                .setSession("TiredVPN Proxy")
                .setMtu(proxyMtu)
                .addAddress("10.255.255.1", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(FALLBACK_DNS)
                .setHttpProxy(proxyInfo)

            // Apps bypassing the HTTP proxy with a direct v6 socket would otherwise
            // go out over the native IPv6 route in the clear (issue #55)
            if (!Ipv6Guard.blackholeIpv6(builder, proxyMtu)) {
                FileLogger.w(TAG, "Proxy MTU $proxyMtu < ${Ipv6Guard.MIN_IPV6_MTU}: cannot claim ::/0, IPv6 may leak outside the tunnel")
            }

            // Apply split tunneling for this profile
            val usesAllowedApps = applySplitTunneling(builder, config.id)

            // Exclude our own app if not using allowedApps mode
            if (!usesAllowedApps) {
                builder.addDisallowedApplication(packageName)
            }

            // Apply kill switch
            applyKillSwitch(builder)

            builder.establish()
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to establish proxy VPN interface", e)
            null
        }
    }

    /**
     * Start the socket protection server.
     * Go side sends fd as 4-byte little-endian int, we call VpnService.protect(fd).
     * Returns 0 on success, 1 on failure.
     * Uses FILESYSTEM namespace (not abstract) so Go can connect to it.
     */
    private fun startProtectServer(socketPath: String) {
        // Close and cancel any existing protect server, in that order
        stopProtectServer()

        // From here on the socket name is ours. Claimed before the unlink, so
        // a predecessor woken by the stop above can no longer pass the
        // ownership check in its own teardown.
        val generation = protectGeneration.begin()

        // Remove old socket file
        File(socketPath).delete()

        protectServerJob = scope.launch {
            var serverSocket: LocalSocket? = null
            try {
                // Create socket and bind to FILESYSTEM namespace
                serverSocket = LocalSocket(LocalSocket.SOCKET_STREAM)
                serverSocket.bind(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))

                // No chmod here. The socket lives in filesDir (0700) and the
                // only thing that connects to it is the core, which runs in
                // this very process under this very uid — world read/write bits
                // bought nothing and widened the surface for free.

                // Published so stopProtectServer can close the listening
                // descriptor. Cancelling the Job cannot interrupt the blocking
                // Os.accept below, so the finally never ran, the thread stayed
                // wedged on Dispatchers.IO and the descriptor stayed open —
                // one of each per reconnect.
                protectServerSocket = serverSocket

                // Get the file descriptor and start listening
                val fd = serverSocket.fileDescriptor
                android.system.Os.listen(fd, 5)

                FileLogger.d(TAG, "Protect server listening on $socketPath (FILESYSTEM namespace)")

                while (isActive) {
                    val clientFd = try {
                        // Accept connection using Os API
                        android.system.Os.accept(fd, null)
                    } catch (e: android.system.ErrnoException) {
                        if (e.errno == android.system.OsConstants.EINTR) continue
                        // EINVAL (shutdown) and EBADF (close) are the normal
                        // exits: stopProtectServer took the listening socket
                        // out from under us on purpose, in that order.
                        if (isActive) FileLogger.d(TAG, "Protect accept stopped: ${e.message}")
                        break
                    }

                    // Bound the number of live handlers. Each one blocks in
                    // Os.read, and a client that connects and says nothing
                    // holds its thread for the read timeout; unbounded, a
                    // stuck peer could take the IO pool with it.
                    if (protectClientFds.size >= MAX_PROTECT_CLIENTS) {
                        FileLogger.w(TAG, "Protect: too many live handlers (${protectClientFds.size}), dropping connection")
                        try { android.system.Os.close(clientFd) } catch (_: Exception) {}
                        continue
                    }
                    protectClientFds.add(clientFd)

                    launch {
                        // Without a deadline a silent client blocks its thread
                        // forever: the read below has none of its own.
                        // SO_RCVTIMEO through Os is API 29; below that the only
                        // lever is closing the descriptor, which makes the
                        // blocked read return.
                        // Exactly one of the two paths below may close this
                        // descriptor. Closing a number twice is what the TUN
                        // ledger exists to prevent elsewhere: between the two
                        // closes the number can be handed to something else.
                        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
                        fun closeOnce(why: String) {
                            if (closed.compareAndSet(false, true)) {
                                try { android.system.Os.close(clientFd) } catch (_: Exception) {}
                            } else {
                                FileLogger.d(TAG, "Protect: fd already closed ($why)")
                            }
                        }

                        var closer: Job? = null
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            try {
                                android.system.Os.setsockoptTimeval(
                                    clientFd,
                                    android.system.OsConstants.SOL_SOCKET,
                                    android.system.OsConstants.SO_RCVTIMEO,
                                    android.system.StructTimeval.fromMillis(PROTECT_CLIENT_READ_TIMEOUT_MS)
                                )
                            } catch (e: Exception) {
                                FileLogger.w(TAG, "Protect: cannot set read timeout: ${e.message}")
                            }
                        } else {
                            closer = launch {
                                delay(PROTECT_CLIENT_READ_TIMEOUT_MS)
                                FileLogger.w(TAG, "Protect: client silent for ${PROTECT_CLIENT_READ_TIMEOUT_MS}ms, closing fd (pre-API-29 path)")
                                closeOnce("timeout")
                            }
                        }
                        try {
                            handleProtectClientFd(clientFd)
                        } catch (e: Exception) {
                            FileLogger.w(TAG, "Protect client error", e)
                        } finally {
                            closer?.cancel()
                            protectClientFds.remove(clientFd)
                            closeOnce("handler done")
                        }
                    }
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "Protect server failed", e)
            } finally {
                try { serverSocket?.close() } catch (_: Exception) {}
                // Unlink only what is still ours. See [protectGeneration]: this
                // block usually runs *after* a successor has claimed the name,
                // because what wakes it is that successor's stopProtectServer.
                if (protectGeneration.isCurrent(generation)) {
                    File(socketPath).delete()
                } else {
                    FileLogger.d(TAG, "Protect: generation $generation superseded, leaving $socketPath to its owner")
                }
            }
        }
    }

    /**
     * Handle a single protect client connection using raw file descriptor.
     * Protocol: Read 4-byte little-endian fd, call protect(), send 1-byte response (0=ok, 1=fail).
     * Note: Go's InitAndroidProtector() does a test connection (connect+close) without sending data.
     */
    private fun handleProtectClientFd(clientFd: java.io.FileDescriptor) {
        // Read fd as 4-byte little-endian int
        val fdBytes = ByteArray(4)
        val byteBuf = java.nio.ByteBuffer.wrap(fdBytes)
        var totalRead = 0
        while (totalRead < 4) {
            val n = android.system.Os.read(clientFd, byteBuf)
            if (n <= 0) {
                // 0-byte read means client closed connection (Go's test connection)
                if (totalRead == 0) {
                    FileLogger.d(TAG, "Protect: test connection (no data)")
                } else {
                    FileLogger.w(TAG, "Protect: incomplete read, got $totalRead/4 bytes")
                }
                return  // Don't send error for test connections
            }
            totalRead += n
        }

        // Little-endian to int
        val fd = (fdBytes[0].toInt() and 0xFF) or
                 ((fdBytes[1].toInt() and 0xFF) shl 8) or
                 ((fdBytes[2].toInt() and 0xFF) shl 16) or
                 ((fdBytes[3].toInt() and 0xFF) shl 24)

        // Call VpnService.protect()
        val success = protect(fd)
        if (success) {
            FileLogger.d(TAG, "Protected fd=$fd")
            android.system.Os.write(clientFd, java.nio.ByteBuffer.wrap(byteArrayOf(0)))
        } else {
            FileLogger.w(TAG, "Failed to protect fd=$fd")
            android.system.Os.write(clientFd, java.nio.ByteBuffer.wrap(byteArrayOf(1)))
        }
    }

    /**
     * Stop the protect server.
     *
     * Order matters and is the whole fix: the blocking `Os.accept` has to be
     * woken before anything else can proceed. Cancelling the Job first, as the
     * old code did, cancels nothing a syscall can see — the coroutine stayed
     * parked in accept, its `finally` never ran, and both the IO thread and the
     * descriptor leaked once per reconnect.
     *
     * `shutdown` and then `close`, in that order, because on Linux `close()`
     * alone does not wake a thread already blocked in `accept()` on that
     * descriptor: the fd number goes away, the sleeping thread does not.
     * `shutdown(SHUT_RDWR)` on a listening socket is what Linux answers with
     * EINVAL out of accept. Closing afterwards releases the descriptor.
     *
     * No join: every caller runs on the service's main thread, and waiting for
     * an IO coroutine there trades a descriptor leak for an ANR. Waking the
     * loop is what matters; the coroutine then unwinds on its own.
     */
    private fun stopProtectServer() {
        val socket = protectServerSocket
        protectServerSocket = null
        try {
            socket?.fileDescriptor?.let { Os.shutdown(it, android.system.OsConstants.SHUT_RDWR) }
        } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}

        // Client handlers are parked in Os.read; closing their descriptors is
        // the only thing that wakes them.
        val clients = protectClientFds.toList()
        protectClientFds.clear()
        for (clientFd in clients) {
            try { android.system.Os.close(clientFd) } catch (_: Exception) {}
        }
        if (clients.isNotEmpty()) {
            FileLogger.d(TAG, "Protect: closed ${clients.size} live client fd(s)")
        }

        protectServerJob?.cancel()
        protectServerJob = null
    }

    /**
     * Resolve server hostname to IP address BEFORE VPN is established.
     * This is critical because after VPN is up, DNS queries go through the tunnel.
     * Returns "ip:port" or null on failure.
     */
    private suspend fun resolveServerEndpoint(endpoint: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                // Parse host:port
                val parts = endpoint.split(":")
                if (parts.size != 2) {
                    FileLogger.e(TAG, "Invalid endpoint format: $endpoint")
                    return@withContext null
                }
                val host = parts[0]
                val port = parts[1]

                // Check if already an IP address
                if (host.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"))) {
                    FileLogger.d(TAG, "Endpoint is already an IP: $endpoint")
                    return@withContext endpoint
                }

                // Resolve hostname to IP
                FileLogger.d(TAG, "Resolving hostname: $host")
                val addresses = InetAddress.getAllByName(host)
                if (addresses.isEmpty()) {
                    FileLogger.e(TAG, "No addresses found for: $host")
                    return@withContext null
                }

                // Prefer IPv4
                val ipv4 = addresses.find { it is java.net.Inet4Address }
                val ip = (ipv4 ?: addresses[0]).hostAddress

                FileLogger.i(TAG, "Resolved $host -> $ip")
                "$ip:$port"
            } catch (e: Exception) {
                FileLogger.e(TAG, "DNS resolution failed for $endpoint", e)
                null
            }
        }
    }

    private fun startTiredVpnProcess(
        config: VpnConfig,
        serverEndpoint: String,
        controlPath: String,
        protectPath: String,
        poolConfigPath: String?
    ) {
        // The core runs via JNI (NativeProcessJNI) from the embedded libtiredvpn.so,
        // which ships for every ABI in nativeLibraryDir. No standalone binary is
        // extracted or executed here: args[0] below is a cosmetic placeholder that
        // the JNI path drops (args.drop(1)). Extracting an assets binary used to run
        // on every connect and threw on non-arm64 devices (only arm64 was bundled),
        // blocking armeabi-v7a and x86_64 from connecting at all.

        // -secret is the active server's key. With a pool file every entry names
        // its own and the core only consults this one as the default for entries
        // that do not - see ServerPoolConfig.render. It is kept because the
        // -server path a one-server pool degenerates to has no other key.
        val args = mutableListOf(
            "tiredvpn",
            "client",
            "-secret", config.secret,
            "-control-socket", controlPath,
            "-protect-path", protectPath,  // Socket protection for VpnService.protect()
            "-tun",
            "-tun-ip", "auto"
        )

        // Endpoint pool, or the single (pre-resolved) server it degenerates to.
        // The mutual exclusion of -config and -server lives in one tested place -
        // see ServerPoolConfig.endpointArgs for why it cannot live here.
        args.addAll(
            ServerPoolConfig.endpointArgs(
                poolConfigPath = poolConfigPath,
                serverEndpoint = serverEndpoint,
                serverAddressV6 = config.serverAddressV6,
                preferIpv6 = config.preferIpv6,
                fallbackV4 = config.fallbackV4
            )
        )

        // Strategy
        if (config.strategy != "auto") {
            args.addAll(listOf("-strategy", config.strategy))
        }

        // QUIC
        if (config.enableQuic) {
            args.add("-quic")
            args.addAll(listOf("-quic-port", config.quicPort.toString()))
        }

        // Cover host for traffic morphing
        if (config.coverHost.isNotBlank()) {
            args.addAll(listOf("-cover", config.coverHost))
        }

        // RTT masking
        if (config.rttMasking) {
            args.add("-rtt-masking")
            args.addAll(listOf("-rtt-profile", config.rttProfile))
        }

        // Fallback
        if (config.fallbackEnabled) {
            args.add("-fallback")
        }

        // Traffic shaper
        if (config.shaperPreset.isNotBlank()) {
            args.addAll(listOf("-shaper", config.shaperPreset))
            if (config.shaperSeed != 0L) {
                args.addAll(listOf("-shaper-seed", config.shaperSeed.toString()))
            }
        }

        // ECH (Encrypted Client Hello)
        if (config.echEnabled) {
            args.add("-ech")
            if (config.echConfig.isNotBlank()) {
                args.addAll(listOf("-ech-config", config.echConfig))
            }
            args.addAll(listOf("-ech-public-name", config.echPublicName))
        }

        // QUIC SNI fragmentation
        if (config.quicSniFrag) {
            args.add("-quic-sni-frag")
        }

        // TUN MTU override (TUN path only)
        if (config.mtu > 0) {
            args.addAll(listOf("-tun-mtu", config.mtu.toString()))
        }

        // IPv6 inside the tunnel (dual-stack handshake v0x04). Old cores warn
        // about the unknown flag and continue v4-only; the control response
        // then simply carries no ip6 and we fall back to the leak blackhole.
        if (config.tunnelIpv6 == "dual") {
            args.addAll(listOf("-tun-ipv6", "dual"))
        }

        // Android mode - disables os/exec, ICMP checks (causes SIGSYS)
        args.add("-android")

        // Debug logging controlled by settings toggle (significant per-packet overhead)
        if (config.debugLogging) args.add("-debug")

        FileLogger.d(TAG, "Starting tiredvpn: ${NativeArgs.redact(args)}")

        // Use JNI mode on all Android versions to avoid SELinux restrictions
        // Android 10+ blocks execution of standalone binaries from app storage
        // JNI mode on every Android version - see startTiredVpnProxyProcess
        // for why there is no second branch here any more.
        tiredvpnProcess = NativeProcessJNI(
            args = args.drop(1), // Skip binary path for JNI mode
            onOutput = { line ->
                FileLogger.d(TAG, "[tiredvpn-jni] $line")
                parseConnectionInfo(line)
            },
            onError = { line ->
                FileLogger.e(TAG, "[tiredvpn-jni] $line")
                parseConnectionInfo(line)
            },
            onExit = { code -> handleCoreExit(code, "tun") }
        ).also { it.start() }
    }

    private suspend fun connectToControlSocket(
        socketPath: String,
        budget: ConnectDeadline,
    ): TunnelConfig? {
        FileLogger.d(TAG, "connectToControlSocket: waiting for socket at $socketPath")

        // Wait for socket to appear. The step's own ceiling still applies - a
        // core that has not opened its socket in 30s is not going to - but the
        // attempt's remaining budget is the smaller of the two whenever the
        // steps before this one were slow.
        val socketFile = File(socketPath)
        val waitBudget = budget.budgetMs(System.currentTimeMillis(), CONTROL_SOCKET_TIMEOUT.toLong())
        val deadline = System.currentTimeMillis() + waitBudget
        var waitMs = 0L

        while (!socketFile.exists() && System.currentTimeMillis() < deadline) {
            // Check if coroutine was cancelled (e.g., process died or timeout)
            currentCoroutineContext().ensureActive()

            // The socket does not exist yet, so "started" is all there is.
            if (!coreStarted) {
                FileLogger.e(TAG, "connectToControlSocket: tiredvpn core exited after ${waitMs}ms")
                return null
            }
            delay(100)
            waitMs += 100
            if (waitMs % 1000 == 0L) {
                FileLogger.d(TAG, "connectToControlSocket: still waiting... ${waitMs}ms")
            }
        }

        // Final check after loop
        currentCoroutineContext().ensureActive()

        if (!socketFile.exists()) {
            FileLogger.e(TAG, "connectToControlSocket: socket NOT created within ${waitBudget}ms")
            budget.requireMs(System.currentTimeMillis(), STEP_CORE_STARTUP)
            return null
        }
        FileLogger.d(TAG, "connectToControlSocket: socket appeared after ${waitMs}ms")

        // Small delay to ensure socket is listening
        delay(200)

        return try {
            FileLogger.d(TAG, "connectToControlSocket: connecting to LocalSocket...")
            val socket = LocalSocket().apply {
                connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
            }
            val channel = ControlChannel(LocalSocketTransport(socket))
            // Read timeout, so a wedged core cannot block us forever. Sized to
            // outlast one full Connect in the core and to stay below
            // CONNECTION_TIMEOUT, which is the outer fence.
            channel.setBaseReadTimeoutMs(CONTROL_SOCKET_READ_TIMEOUT)
            controlChannel = channel
            FileLogger.d(TAG, "connectToControlSocket: LocalSocket connected!")

            // Send connect command
            val connectCmd = JSONObject().apply {
                put("command", "connect")
            }.toString()

            FileLogger.d(TAG, "connectToControlSocket: sending 'connect' command...")
            channel.send(connectCmd)
            FileLogger.d(TAG, "connectToControlSocket: 'connect' sent, waiting for response...")

            // Read the response, stepping over any asynchronous event that got
            // in front of it. The core registers this connection as its event
            // sink before it starts reading commands, so a keepalive can arrive
            // between our write and its answer; treating that line as the
            // response failed the connect on a healthy core.
            //
            // The read deadline is what is left of the attempt, capped by the
            // socket's standing ceiling, and it is recomputed per line: an
            // event that arrives first must not buy the next read a fresh full
            // timeout. The number handed to readLine is the socket option, so
            // it is the real one.
            var response: String? = null
            while (response == null) {
                val readMs = minOf(
                    budget.requireSocketTimeoutMs(System.currentTimeMillis(), STEP_CORE_CONNECT).toLong(),
                    CONTROL_SOCKET_READ_TIMEOUT.toLong(),
                ).toInt()
                val line = channel.readLine(readMs)
                    ?: throw Exception("No response from control socket within ${readMs}ms")
                if (ControlSocketProtocol.isEvent(line)) {
                    FileLogger.d(TAG, "connectToControlSocket: skipping event line: $line")
                    continue
                }
                response = line
            }
            FileLogger.d(TAG, "connectToControlSocket: got response: $response")
            val json = JSONObject(response)

            val status = json.optString("status", "")
            FileLogger.d(TAG, "connectToControlSocket: status=$status")

            if (status == "waiting_fd") {
                val config = TunnelConfig.fromJson(json)
                FileLogger.d(TAG, "connectToControlSocket: SUCCESS - config=$config")
                config
            } else {
                val error = json.optString("error", "Unexpected status: $status")
                FileLogger.e(TAG, "connectToControlSocket: Server rejected: $error")
                null
            }
        } catch (e: ConnectDeadlineExceeded) {
            // Not a failure of this step: the attempt as a whole is over, and
            // the caller reports which step spent it. Folding it into `null`
            // here would turn a stated verdict back into "failed, somehow".
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "connectToControlSocket: EXCEPTION", e)
            null
        }
    }

    /**
     * Send TUN fd to tiredvpn and get final connection info.
     * Returns the final IP assigned by server, or null on failure.
     *
     * Protocol:
     * 1. Send JSON command with fd attached via SCM_RIGHTS in ONE write
     * 2. Receive response: {"status":"connected",...}
     */
    private suspend fun sendTunFd(fd: Int, budget: ConnectDeadline): String? {
        FileLogger.d(TAG, "sendTunFd: START fd=$fd")
        try {
            val channel = controlChannel ?: run {
                FileLogger.e(TAG, "sendTunFd: no control channel")
                return null
            }

            FileLogger.d(TAG, "sendTunFd: preparing FileDescriptor...")

            // Create FileDescriptor from raw fd
            val fileDescriptor = java.io.FileDescriptor()
            try {
                val field = java.io.FileDescriptor::class.java.getDeclaredField("fd")
                field.isAccessible = true
                field.setInt(fileDescriptor, fd)
            } catch (e: Exception) {
                // Some Android versions use "descriptor" instead of "fd"
                val field = java.io.FileDescriptor::class.java.getDeclaredField("descriptor")
                field.isAccessible = true
                field.setInt(fileDescriptor, fd)
            }

            // Attach + write + detach as one transaction against the other four
            // writers, so nobody else's command can leave with our descriptor.
            // What is left of the attempt, never more than the socket's
            // standing ceiling. Claimed before the write, so a budget already
            // spent is reported instead of arming a read nobody is waiting for.
            val readMs = minOf(
                budget.requireSocketTimeoutMs(System.currentTimeMillis(), STEP_HANDSHAKE).toLong(),
                SET_FD_READ_TIMEOUT.toLong(),
            ).toInt()

            FileLogger.d(TAG, "sendTunFd: sending set_fd with SCM_RIGHTS...")
            channel.send("""{"command":"set_fd"}""", fileDescriptor)
            FileLogger.d(TAG, "sendTunFd: set_fd sent, waiting for response (${readMs}ms)...")

            // Read confirmation. The old code wrapped a blocking readLine() in
            // withTimeoutOrNull(15000); cancelling a coroutine does not touch a
            // socket, so the read ran to the socket's own timeout and the 15s
            // was decoration. The number below IS the socket option, and it is
            // now also bounded by the attempt: set_fd is what makes the core run
            // a full Connect, and two of those at the socket's own ceiling do
            // not fit inside one connect budget. They used not to have to.
            val response = withContext(Dispatchers.IO) {
                channel.readLine(readMs)
            }

            if (response == null) {
                FileLogger.e(TAG, "sendTunFd: TIMEOUT - no response from tiredvpn after ${readMs}ms")
                FileLogger.e(TAG, "sendTunFd: tiredvpn core started=$coreStarted")
                return null
            }

            FileLogger.d(TAG, "sendTunFd: got response: $response")
            val json = JSONObject(response)

            val status = json.optString("status", "")
            val connected = json.optBoolean("connected", false)
            val finalIp = json.optString("ip", "")

            // Parse connection metadata from Go binary
            val strategy = json.optString("strategy", "")
            val latencyMs = json.optLong("latency_ms", 0)
            val attempts = json.optInt("attempts", 1)

            FileLogger.d(TAG, "sendTunFd: parsed response - status=$status, connected=$connected, ip=$finalIp, strategy=$strategy, latency=${latencyMs}ms, attempts=$attempts")

            if (status == "connected" && connected) {
                // Store connection metadata for UI
                if (strategy.isNotEmpty()) {
                    connectedStrategy = strategy
                }
                if (latencyMs > 0) {
                    connectedLatencyMs = latencyMs
                }
                if (attempts > 0) {
                    connectedAttempts = attempts
                }
                FileLogger.i(TAG, "sendTunFd: SUCCESS - tunnel is active, final IP: $finalIp, strategy=$connectedStrategy, latency=${connectedLatencyMs}ms")
                return finalIp
            } else {
                // BUG FIX 2: Check if error is handshake EOF - this means server closed connection
                val error = json.optString("error", "")
                FileLogger.e(TAG, "sendTunFd: FAILED - status=$status, error=$error")

                if (error.contains("handshake") && error.contains("EOF")) {
                    FileLogger.w(TAG, "sendTunFd: Handshake EOF detected - server closed connection during TLS handshake")
                }

                return null
            }
        } catch (e: ConnectDeadlineExceeded) {
            // The attempt is over, not this step. See connectToControlSocket.
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "sendTunFd: EXCEPTION", e)
            return null
        }
    }

    /**
     * The core announced it stopped.
     *
     * One handler for both launch paths. They used to carry a copy each, plus
     * an unreachable copy apiece behind `if (true) … else`, and the four had
     * already drifted: 500ms versus 2000ms before a retry, and only one of
     * them declined to pile a retry on top of an in-flight reconnect. The
     * stricter reading of each is kept here.
     *
     * @param label which launch path reported the exit, for the log only
     */
    private fun handleCoreExit(code: Int, label: String) {
        FileLogger.w(TAG, "tiredvpn-jni[$label] exited with code $code")
        val currentState = _state.value
        FileLogger.d(TAG, "onExit[$label]: currentState=$currentState")

        if (code == NativeProcessJNI.EXIT_NO_NATIVE_LIBRARY) {
            // Retrying cannot conjure a core that is not in the APK for this
            // ABI. Without this the Connecting branch below would reconnect
            // forever against a library that will never load.
            FileLogger.e(TAG, "onExit[$label]: native core unavailable on this device, not reconnecting")
            clearPhase()
            _state.value = VpnState.Error("Native core unavailable for this device")
            return
        }

        when (currentState) {
            is VpnState.Disconnected -> {
                // User disconnected - don't reconnect
                FileLogger.d(TAG, "onExit[$label]: User disconnected, not reconnecting")
            }
            is VpnState.Connecting -> {
                // Died mid-connect, most likely a PhantomProcess kill.
                FileLogger.e(TAG, "onExit[$label]: core died during Connecting")
                val config = ServerRepository.getActiveServer(this@TiredVpnService)
                if (config != null && config.isValid) {
                    FileLogger.i(TAG, "onExit[$label]: scheduling fast reconnect")
                    scope.launch {
                        // Short delay so a core being killed repeatedly does
                        // not turn into a tight loop.
                        delay(1000)
                        if (_state.value !is VpnState.Disconnected) {
                            scheduleAutoReconnect(config)
                        }
                    }
                }
            }
            is VpnState.Connected -> {
                scope.launch {
                    FileLogger.e(TAG, "onExit[$label]: core died while connected, attempting reconnect...")
                    handleControlSocketBroken()
                }
            }
            is VpnState.Error -> {
                val config = ServerRepository.getActiveServer(this@TiredVpnService)
                if (config != null && config.isValid) {
                    FileLogger.d(TAG, "onExit[$label]: error state - ensuring a reconnect is scheduled")
                    scope.launch {
                        delay(2000)
                        if (_state.value !is VpnState.Disconnected && _state.value !is VpnState.Connecting) {
                            scheduleAutoReconnect(config)
                        }
                    }
                }
            }
        }
    }

    /**
     * Adopt a dual-stack pair the core renegotiated behind our back.
     *
     * A VpnService interface has no way to change an address in place, so the
     * only way to follow the core is to establish a new one and hand the
     * descriptor over — which is exactly what [sendNetworkChangedCommand]
     * already does. Until this existed the interface kept the old v6 address
     * after such a reconnect: a v6 default route pointing into the tunnel at an
     * exit that no longer routes it, i.e. v6 traffic into a black hole, until
     * something forced a full reconnect.
     *
     * The rebuild makes the core reconnect once more and answer with a pair
     * again, so the guard against a loop is that [Ipv6Renegotiation] asks for a
     * rebuild only when the pair actually differs from the one in force.
     *
     * @param reportedIp6 null when the core said nothing about it, which is not
     *        the same as reporting none - see [Ipv6Renegotiation].
     */
    private fun applyRenegotiatedIpv6(
        reportedIp6: String?,
        reportedServerIp6: String?,
        removed: Boolean,
        source: String,
    ) {
        val outcome = Ipv6Renegotiation.apply(
            currentIp6 = currentVpnIp6,
            currentServerIp6 = currentVpnServerIp6,
            reportedIp6 = reportedIp6,
            reportedServerIp6 = reportedServerIp6,
            removed = removed,
        )
        if (!outcome.interfaceMustBeRebuilt) return

        FileLogger.i(TAG, "IPv6 renegotiated via $source: client [$currentVpnIp6] -> [${outcome.ip6}], " +
            "server [$currentVpnServerIp6] -> [${outcome.serverIp6}]; rebuilding the interface")

        currentVpnIp6 = outcome.ip6
        currentVpnServerIp6 = outcome.serverIp6
        // The remembered handshake config has to move too, or the rebuild reads
        // the old pair straight back out of it through forNetworkChange.
        activeTunnelConfig = activeTunnelConfig?.copy(
            ip6 = outcome.ip6.takeIf { it.isNotEmpty() },
            serverIp6 = outcome.serverIp6.takeIf { it.isNotEmpty() },
        )

        // forceReconnect, because this is not a debounceable network event: the
        // addresses are already wrong and waiting three seconds only extends
        // the black hole.
        //
        // Bounded wait rather than a bare call: the ipv6_changed event is
        // emitted from inside the core's own reconnect, which has already put
        // us in Connecting via its "reconnecting" event, and the rebuild path
        // runs only while Connected. Dropping the change there would leave the
        // interface on the dead address - the thing this function exists to
        // prevent. If Connected never arrives the pair is still stored above,
        // so the next interface build picks it up.
        ensureScopeActive()
        scope.launch {
            repeat(IPV6_REBUILD_WAIT_TICKS) {
                when (_state.value) {
                    is VpnState.Connected -> {
                        sendNetworkChangedCommand(forceReconnect = true)
                        return@launch
                    }
                    is VpnState.Disconnected -> return@launch
                    else -> delay(IPV6_REBUILD_WAIT_TICK_MS)
                }
            }
            FileLogger.w(TAG, "IPv6 rebuild skipped: never reached Connected within " +
                "${IPV6_REBUILD_WAIT_TICKS * IPV6_REBUILD_WAIT_TICK_MS}ms; addresses stored for the next build")
        }
    }

    /** Close and forget the control channel. Idempotent. */
    private fun closeControlChannel() {
        val channel = controlChannel
        controlChannel = null
        try { channel?.close() } catch (e: Exception) {
            FileLogger.w(TAG, "Error closing control channel: ${e.message}")
        }
    }

    private fun startStatusMonitoring() {
        // Cancel first, like every other start* here. Today the callers happen
        // to have cancelled it already, but that is an agreement between call
        // sites rather than a property of this function; a second event
        // listener over a fresh channel would double-handle connection_dead
        // and ipv6_changed.
        statusMonitorJob?.cancel()

        // Start event listener that handles both periodic status checks and Go events
        statusMonitorJob = scope.launch {
            // The channel's reader, not a third BufferedReader over the same
            // stream: the two earlier ones buffered ahead and this loop used to
            // start life having already lost whatever they had pulled in.
            val channel = controlChannel ?: return@launch

            // Launch a separate coroutine to listen for events from Go
            val eventListener = launch {
                // Don't exit loop just because state changed - we need to catch connection_dead
                while (isActive) {
                    val line: String?
                    try {
                        line = withContext(Dispatchers.IO) { channel.readLine() }
                    } catch (e: java.net.SocketTimeoutException) {
                        // Socket timeout is expected due to setSoTimeout(15_000)
                        // Just continue the loop - the connection might still be fine
                        FileLogger.d(TAG, "Event listener: socket read timeout, continuing...")
                        continue
                    } catch (e: Exception) {
                        if (isActive) {
                            FileLogger.e(TAG, "Event listener error", e)
                            handleControlSocketBroken()
                        }
                        break
                    }

                    if (line == null) {
                        FileLogger.w(TAG, "Event listener: socket closed")
                        handleControlSocketBroken()
                        break
                    }

                    // Any line at all is proof the control channel is alive.
                    // Counting only "keepalive" events would misread a busy
                    // tunnel as dead: the server suppresses keepalive frames
                    // while traffic is flowing, so under load the only regular
                    // traffic here is the answer to our own `status` poll.
                    lastKeepaliveTime = SystemClock.uptimeMillis()

                    // Parse response/event
                    try {
                        val json = JSONObject(line)

                            // Check if this is an EventMessage (has "event" field)
                            val eventType = json.optString("event", "")
                            if (eventType.isNotEmpty()) {
                                val timestamp = json.optLong("timestamp", 0)
                                val data = json.optString("data", "")
                                FileLogger.i(TAG, "=== EVENT: $eventType (data=$data, ts=$timestamp) ===")

                                when (eventType) {
                                    "keepalive" -> {
                                        // Update keepalive time for health check
                                        lastKeepaliveTime = SystemClock.uptimeMillis()
                                        FileLogger.d(TAG, "Keepalive received from server")
                                    }
                                    "connection_dead" -> {
                                        FileLogger.e(TAG, "Connection dead: $data")
                                        // Trigger reconnect - the Go side already stopped relay
                                        handleControlSocketBroken()
                                    }
                                    "reconnecting" -> {
                                        FileLogger.i(TAG, "Go is reconnecting: $data")
                                        _state.value = VpnState.Connecting
                                        setPhase(getString(R.string.phase_reconnecting))
                                    }
                                    "connected" -> {
                                        FileLogger.i(TAG, "Reconnect successful")
                                        clearPhase()
                                        lastKeepaliveTime = SystemClock.uptimeMillis()
                                        // Parse reconnect metadata from event data (JSON)
                                        if (data.startsWith("{")) {
                                            try {
                                                val meta = org.json.JSONObject(data)
                                                val strategy = meta.optString("strategy", "")
                                                val latencyMs = meta.optLong("latency_ms", 0)
                                                val attempts = meta.optInt("attempts", 0)
                                                if (strategy.isNotEmpty()) connectedStrategy = strategy
                                                if (latencyMs > 0) connectedLatencyMs = latencyMs
                                                if (attempts > 0) connectedAttempts = attempts
                                            } catch (e: Exception) {
                                                FileLogger.w(TAG, "Failed to parse reconnect metadata: $e")
                                            }
                                        }
                                        _state.value = VpnState.Connected(
                                            strategy = connectedStrategy,
                                            latencyMs = connectedLatencyMs,
                                            attempts = connectedAttempts
                                        )
                                        updateNotification("Connected • $currentVpnIp")
                                    }
                                    "ipv6_changed" -> {
                                        // Emitted from doAutoReconnect when the
                                        // reconnect came back with a different
                                        // dual-stack pair. data is
                                        // {"ip6":"…","server_ip6":"…"} with ""
                                        // meaning none; the event only fires on
                                        // a change, so "" here IS removal.
                                        FileLogger.i(TAG, "Core renegotiated IPv6: $data")
                                        try {
                                            val meta = org.json.JSONObject(data)
                                            applyRenegotiatedIpv6(
                                                reportedIp6 = meta.optString("ip6", ""),
                                                reportedServerIp6 = meta.optString("server_ip6", ""),
                                                removed = false,
                                                source = "ipv6_changed",
                                            )
                                        } catch (e: Exception) {
                                            FileLogger.w(TAG, "Failed to parse ipv6_changed payload: $e")
                                        }
                                    }
                                    else -> {
                                        FileLogger.w(TAG, "Unknown event: $eventType")
                                    }
                                }
                                continue
                            }

                            // Otherwise it's a ControlResponse (has "status" field)
                            val status = json.optString("status", "")
                            when (status) {
                                "ok" -> {
                                    // Status response - rely on keepalive events for health check
                                    // Don't check 'connected' field here - it causes false positives
                                    // during network_available and other command responses
                                    FileLogger.d(TAG, "Command acknowledged: $line")
                                }
                                "connected" -> {
                                    // Response to network_changed or reconnect
                                    FileLogger.d(TAG, "Reconnect confirmed: $line")
                                    lastKeepaliveTime = SystemClock.uptimeMillis()

                                    // The dual-stack pair the reconnect settled
                                    // on. ip6/server_ip6 are omitempty, so an
                                    // absent field says nothing (every v4-only
                                    // session sends one) - removal is stated by
                                    // ipv6_removed. This is the channel that
                                    // actually carries a v6 change on Android:
                                    // the ipv6_changed event above rides
                                    // doAutoReconnect, which the control-socket
                                    // path disables.
                                    // Update latency/strategy from reconnect response
                                    val strategy = json.optString("strategy", "")
                                    val latencyMs = json.optLong("latency_ms", 0)
                                    val attempts = json.optInt("attempts", 0)
                                    if (strategy.isNotEmpty()) connectedStrategy = strategy
                                    if (latencyMs > 0) connectedLatencyMs = latencyMs
                                    if (attempts > 0) connectedAttempts = attempts
                                    _state.value = VpnState.Connected(
                                        strategy = connectedStrategy,
                                        latencyMs = connectedLatencyMs,
                                        attempts = connectedAttempts
                                    )

                                    // After the state is Connected, not before:
                                    // the rebuild path refuses to run in any
                                    // other state, and this branch is reached
                                    // while the preceding "reconnecting" event
                                    // still has us in Connecting.
                                    //
                                    // ip6/server_ip6 are omitempty, so an absent
                                    // field says nothing (every v4-only session
                                    // sends one); removal is stated by
                                    // ipv6_removed. This is the channel that
                                    // actually carries a v6 change on Android -
                                    // the ipv6_changed event rides
                                    // doAutoReconnect, which the control-socket
                                    // path disables.
                                    applyRenegotiatedIpv6(
                                        reportedIp6 = if (json.has("ip6")) json.optString("ip6", "") else null,
                                        reportedServerIp6 = if (json.has("server_ip6")) json.optString("server_ip6", "") else null,
                                        removed = json.optBoolean("ipv6_removed", false),
                                        source = "network_changed response",
                                    )
                                }
                                "error" -> {
                                    val error = json.optString("error", "unknown")
                                    FileLogger.e(TAG, "Error from Go: $error")
                                    // Trigger reconnect on certain errors
                                    if (error.contains("reconnect", ignoreCase = true) ||
                                        error.contains("failed", ignoreCase = true) ||
                                        error.contains("timeout", ignoreCase = true) ||
                                        error.contains("connection", ignoreCase = true)) {
                                        FileLogger.w(TAG, "Error requires reconnect, triggering handleControlSocketBroken()")
                                        handleControlSocketBroken()
                                    }
                                }
                                else -> {
                                    FileLogger.d(TAG, "Received: $line")
                                }
                            }
                        } catch (e: Exception) {
                            FileLogger.w(TAG, "Failed to parse response: $line", e)
                        }
                    }
                }

            // Periodic status checks (health check now relies on events from Go)

            // Keep sending status checks while event listener is active
            // Don't check VpnState here - we need to keep listening even during reconnect
            while (isActive && eventListener.isActive) {
                delay(30_000) // Check every 30 seconds (Go handles keepalive tracking)

                // Only send status check if connected
                val currentState = _state.value
                if (currentState !is VpnState.Connected && currentState !is VpnState.Connecting) {
                    FileLogger.d(TAG, "Status monitor: state is $currentState, stopping")
                    break
                }

                // Send status check to control socket
                try {
                    if (currentState is VpnState.Connected) {
                        val statusCmd = JSONObject().apply {
                            put("command", "status")
                        }.toString()

                        channel.send(statusCmd)
                        FileLogger.d(TAG, "Sent status check")
                    }
                } catch (e: Exception) {
                    FileLogger.e(TAG, "Status check send error", e)
                    // Don't break - let event listener handle socket errors
                }
            }

            // Don't cancel event listener here - it will stop on its own when socket closes
            // or when handleControlSocketBroken() cancels the whole statusMonitorJob
            FileLogger.d(TAG, "Status monitor loop ended, waiting for event listener...")
            eventListener.join()
        }
    }

    /**
     * Internal process watchdog - checks if Go process is alive every 30 seconds.
     * This is faster than WorkManager's 15 minute minimum and helps detect
     * PhantomProcess kills quickly.
     */
    private fun startProcessWatchdog() {
        processWatchdogJob?.cancel()

        processWatchdogJob = scope.launch {
            FileLogger.i(TAG, "=== PROCESS WATCHDOG STARTED ===")

            while (isActive) {
                delay(processWatchdogIntervalMs)

                val currentState = _state.value

                // Only check if we should be connected
                if (currentState is VpnState.Disconnected) {
                    FileLogger.d(TAG, "Process watchdog: Disconnected state, stopping")
                    break
                }

                // Skip if already reconnecting
                if (currentState is VpnState.Connecting) {
                    FileLogger.d(TAG, "Process watchdog: Already connecting, skip check")
                    continue
                }

                // Deliberately the coarse question. This watchdog exists to
                // catch a core killed out from under us (PhantomProcess); the
                // finer "is the tunnel carrying traffic" runs three times as
                // often in startHealthCheck and would only duplicate it here.
                val isProcessAlive = coreStarted

                if (!isProcessAlive && currentState is VpnState.Connected) {
                    FileLogger.e(TAG, "=== PROCESS WATCHDOG: core exited but state is Connected! ===")
                    FileLogger.i(TAG, "Process watchdog triggering reconnect...")
                    handleControlSocketBroken()
                } else if (!isProcessAlive && currentState is VpnState.Error) {
                    // Process died and we're in error state - ensure reconnect happens
                    val config = ServerRepository.getActiveServer(this@TiredVpnService)
                    if (config != null && config.isValid) {
                        FileLogger.i(TAG, "Process watchdog: Process dead in Error state, triggering reconnect")
                        scheduleAutoReconnect(config)
                    }
                } else {
                    FileLogger.d(TAG, "Process watchdog: OK (processAlive=$isProcessAlive, state=$currentState)")
                }
            }

            FileLogger.i(TAG, "=== PROCESS WATCHDOG STOPPED ===")
        }
    }

    private fun stopProcessWatchdog() {
        processWatchdogJob?.cancel()
        processWatchdogJob = null
    }

    private fun sendDisconnectCommand() {
        try {
            val channel = controlChannel ?: return

            val disconnectCmd = JSONObject().apply {
                put("command", "disconnect")
            }.toString()

            channel.send(disconnectCmd)

            FileLogger.d(TAG, "Sent disconnect command to tiredvpn")
        } catch (e: Exception) {
            FileLogger.w(TAG, "Failed to send disconnect command", e)
        }
    }

    private fun startNetworkMonitoring() {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        lastNetworkAvailableTime = System.currentTimeMillis()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            /**
             * Every validated non-VPN network that is up, and which of them
             * carries traffic. Replaces a single field holding whatever fired
             * last — see [NetworkInUse] for the two decisions that got wrong.
             */
            private val networksUp = NetworkInUse<Network>()
            private var hadNetworkLoss = false

            /** This network's transport, read from its capabilities. */
            private fun kindOf(network: Network): NetworkInUse.Kind {
                val caps = try {
                    connectivityManager.getNetworkCapabilities(network)
                } catch (e: Exception) {
                    null
                } ?: return NetworkInUse.Kind.OTHER
                return NetworkInUse.kindOf(
                    ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                    wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                    cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                )
            }

            private fun isValidated(network: Network): Boolean = try {
                connectivityManager.getNetworkCapabilities(network)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false
            } catch (e: Exception) {
                false
            }

            override fun onAvailable(network: Network) {
                val previousInUse = networksUp.inUse
                val previousKind = networksUp.inUseKind
                val change = networksUp.available(network, kindOf(network), isValidated(network))
                val inUse = networksUp.inUse

                FileLogger.i(TAG, "=== NETWORK AVAILABLE: $network (in use: $inUse, was: $previousInUse, up: ${networksUp.size}, hadLoss: $hadNetworkLoss) ===")
                lastNetworkAvailableTime = System.currentTimeMillis()

                // ITERATION 3: Stop network watchdog - we have network now
                stopNetworkWatchdog()

                // Stop network recovery job - we have network now
                stopNetworkRecoveryJob()

                // BUG FIX 1: Check if network ACTUALLY changed (by IP address, not just Network ID)
                // Android sometimes changes Network ID even though it's the same WiFi/cellular
                //
                // Only the network in use is fingerprinted. Recording the
                // addresses of a background link here is how the idle LTE
                // interface coming up looked like the Wi-Fi's address changing,
                // and cost a reconnect on a connection that was fine.
                var networkActuallyChanged = false
                if (network == inUse) try {
                    val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                    val linkProps = connectivityManager.getLinkProperties(network)
                    linkProps?.let { props ->
                        // Same fingerprint as onLinkPropertiesChanged below: both write
                        // lastLinkProperties, so they must agree on the format or each
                        // would read the other's value as a change.
                        val addresses = NetworkFingerprint.of(
                            props.linkAddresses.map { it.address to it.prefixLength }
                        )

                        // Check if IP addresses actually changed
                        if (lastLinkProperties.isNotEmpty() && addresses != lastLinkProperties) {
                            FileLogger.i(TAG, "Network ACTUALLY changed: IP from [$lastLinkProperties] to [$addresses]")
                            networkActuallyChanged = true
                        } else if (lastLinkProperties.isEmpty()) {
                            // First time seeing this network
                            FileLogger.d(TAG, "First network initialization: [$addresses]")
                        } else {
                            // Same network, just different Network ID
                            FileLogger.d(TAG, "Network ID changed (${previousInUse} -> $network) but IP same: [$addresses]")
                        }

                        lastLinkProperties = addresses
                    }
                } catch (e: Exception) {
                    FileLogger.w(TAG, "Failed to get link properties", e)
                }

                // A better network appearing moves traffic onto it, and that is
                // a change whether or not the addresses we had recorded differ:
                // they belonged to the network we just left. Without this the
                // switch was noticed only by the two-second poll.
                val switchedWhileUp = change == NetworkInUse.Change.SWITCHED && previousInUse != null

                // Trigger reconnect if:
                // 1. We had a network loss and now network is back (only if not already handled by networkRecoveryJob)
                // 2. Network ACTUALLY switched (IP address changed, not just Network ID)
                // 3. Traffic moved to a different network that was already up
                // Use isNetworkLost as guard: networkRecoveryJob sets it to false before triggering reconnect,
                // so if it's already false here, the recovery was already handled — skip duplicate trigger.
                val needsReconnect =
                    (hadNetworkLoss && isNetworkLost) || networkActuallyChanged || switchedWhileUp

                // Grace period: after fresh connection NetworkCallback fires onAvailable
                // for all existing networks immediately — suppress spurious reconnect signals
                if (needsReconnect && !hadNetworkLoss && System.currentTimeMillis() - connectionTime < 5_000L) {
                    FileLogger.d(TAG, "onAvailable: Within 5s grace period post-connect, suppressing spurious reconnect")
                    return
                }

                if (switchedWhileUp) {
                    FileLogger.i(
                        TAG,
                        "Traffic moved from $previousInUse (${previousKind?.wireName}) to " +
                            "$inUse (${networksUp.inUseKind?.wireName})"
                    )
                }

                // ITERATION 3: CRITICAL - Send explicit network_available signal to Go
                // This is the main fix for "waiting for network..." stuck issue
                // ITERATION 4 FIX: Only send signal if we actually had network loss!
                if (needsReconnect) {
                    sendNetworkAvailableSignal()
                }

                if (needsReconnect) {
                    FileLogger.i(TAG, "Network recovered/switched, triggering FAST reconnect")
                    hadNetworkLoss = false
                    isNetworkLost = false

                    // "started" is the right question: we are choosing between
                    // a graceful network_changed and a full rebuild, and a core
                    // that has exited can be handed neither.
                    val isProcessAlive = coreStarted
                    FileLogger.d(TAG, "onAvailable: Go process alive = $isProcessAlive")

                    if (!isProcessAlive && _state.value is VpnState.Connected) {
                        FileLogger.w(TAG, "onAvailable: Go process is DEAD, forcing full reconnect with cleanup")
                        handleControlSocketBroken()
                    } else {
                        // Process is alive - try graceful reconnect
                        triggerReconnectAfterNetworkRecovery()
                    }
                }
            }

            override fun onLost(network: Network) {
                // The callback is registered on a NetworkRequest that matches
                // every validated non-VPN network, so this fires for the
                // background LTE link going away while Wi-Fi is perfectly
                // alive. Reacting to that started the whole recovery machinery
                // for nothing.
                //
                // The question the policy answers has not changed; what it is
                // asked about has. It used to be handed the network of the last
                // onAvailable, which after LTE registered behind live Wi-Fi was
                // LTE - so Wi-Fi's own loss read as "not the one in use".
                val inUseBefore = networksUp.inUse
                val kindBefore = networksUp.inUseKind
                val change = networksUp.lost(network)

                if (!NetworkLossPolicy.isRelevant(network, inUseBefore, checkNetworkAvailability())) {
                    FileLogger.d(TAG, "Network lost: $network, but it is not the one in use ($inUseBefore) and connectivity remains - ignoring")
                    return
                }

                if (change == NetworkInUse.Change.SWITCHED) {
                    // The link that carried traffic went away and another one
                    // is already up: a handover, not an outage. Treated as one
                    // here rather than two seconds later, when the poll notices
                    // - and deliberately without the loss machinery, which
                    // exists for having no network at all.
                    val reason = NetworkTransition.reason(
                        kindBefore?.wireName.orEmpty(),
                        networksUp.inUseKind?.wireName.orEmpty(),
                    )
                    FileLogger.i(
                        TAG,
                        "=== NETWORK IN USE CHANGED: $network (${kindBefore?.wireName}) went away, " +
                            "traffic moves to ${networksUp.inUse} (${networksUp.inUseKind?.wireName})" +
                            (if (reason.isEmpty()) "" else ", reason=$reason") + " ==="
                    )
                    if (_state.value is VpnState.Connected) {
                        sendNetworkChangedCommand(forceReconnect = false, isCritical = true, reason = reason)
                    }
                    return
                }

                FileLogger.i(TAG, "=== NETWORK LOST: $network - starting watchdog and recovery job ===")
                // Mark that we lost network - this allows fast reconnect without TCP checks
                hadNetworkLoss = true
                isNetworkLost = true

                // ITERATION 3: Start watchdog timer for long disconnects
                // If offline >45s, force full VPN restart
                startNetworkWatchdog()

                // Start periodic network recovery check
                // This is crucial for reconnecting after long network outages (1+ minute)
                startNetworkRecoveryJob()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // ITERATION 3: Send network_available signal if capabilities improved
                // This helps when WiFi reconnects but onAvailable doesn't fire
                val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val hasValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

                FileLogger.d(TAG, "Network capabilities changed: $network (internet=$hasInternet, validated=$hasValidated)")

                // A network becoming validated can move traffic onto it, and
                // losing validation can move it off. Both arrive here and
                // nowhere else, so the ranking has to be told. Acting on the
                // move is left to onAvailable and to the poll - the gate would
                // discard the second report anyway, and a capabilities update
                // is the weakest of the three signals.
                if (networksUp.available(
                        network,
                        NetworkInUse.kindOf(
                            ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                            wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                            cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                        ),
                        validated = hasValidated,
                    ) == NetworkInUse.Change.SWITCHED
                ) {
                    FileLogger.i(TAG, "Capabilities moved traffic onto ${networksUp.inUse} (${networksUp.inUseKind?.wireName})")
                }

                if (hasInternet && hasValidated && hadNetworkLoss) {
                    FileLogger.i(TAG, "Capabilities improved after loss - sending network_available signal")
                    sendNetworkAvailableSignal()
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
                // BUG FIX 1: Only trigger reconnect if IP addresses actually changed
                // On some devices (Pixel 6) this fires constantly even when nothing changed
                //
                // Both families count now. The old filter kept IPv4 only, so a network
                // that moved to a different IPv6 prefix while holding its v4 address
                // looked unchanged and never triggered a reconnect — the common case on
                // a home router handing out a short IPv6 lifetime. IPv6 is compared by
                // prefix so RFC 4941 temporary-address rotation inside one prefix does
                // not turn into a reconnect storm; see NetworkFingerprint.
                val addresses = NetworkFingerprint.of(
                    linkProperties.linkAddresses.map { it.address to it.prefixLength }
                )

                FileLogger.i(TAG, "Network link properties changed: $network, addresses: [$addresses]")

                // Only trigger reconnect if this is the network traffic uses
                // AND addresses actually changed
                if (network == networksUp.inUse) {
                    if (addresses != lastLinkProperties) {
                        FileLogger.i(TAG, "IP addresses changed from [$lastLinkProperties] to [$addresses]")
                        lastLinkProperties = addresses
                        sendNetworkChangedCommand()
                    } else {
                        FileLogger.d(TAG, "Link properties changed but IP addresses are same, ignoring")
                    }
                }
            }
        }

        // Use default network callback to properly track network loss
        // This tracks the system's preferred network (WiFi when available, then cellular)
        // When VPN is active, the default network changes - but we want underlying network
        try {
            // First, try to track non-VPN networks with INTERNET capability
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build()

            connectivityManager.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            FileLogger.w(TAG, "Failed to register network callback with NOT_VPN, using default", e)
            // Fallback to default callback
            connectivityManager.registerDefaultNetworkCallback(networkCallback!!)
        }
        FileLogger.d(TAG, "Network monitoring started")
    }

    private fun stopNetworkMonitoring() {
        stopNetworkRecoveryJob()
        stopNetworkWatchdog()
        networkCallback?.let { callback ->
            try {
                val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                connectivityManager.unregisterNetworkCallback(callback)
                FileLogger.d(TAG, "Network monitoring stopped")
            } catch (e: Exception) {
                FileLogger.w(TAG, "Failed to unregister network callback", e)
            }
        }
        networkCallback = null
    }

    /**
     * ITERATION 3: Send explicit network_available command to Go process.
     * This is CRITICAL because Go cannot detect network restoration on its own.
     * Without this, Go waits forever in "waiting for network..." state.
     */
    private fun sendNetworkAvailableSignal() {
        if (_state.value is VpnState.Disconnected) {
            FileLogger.d(TAG, "sendNetworkAvailableSignal: VPN disconnected, skipping")
            return
        }

        // Debounce: ignore signals within 2 seconds of each other to prevent cascade
        val now = System.currentTimeMillis()
        if (now - lastNetworkSignalSentTime < 2000) {
            FileLogger.d(TAG, "sendNetworkAvailableSignal: debounced (${now - lastNetworkSignalSentTime}ms since last)")
            return
        }
        lastNetworkSignalSentTime = now

        scope.launch {
            try {
                val channel = controlChannel
                if (channel != null) {
                    val cmd = JSONObject().apply {
                        put("command", "network_available")
                        put("timestamp", System.currentTimeMillis())
                    }.toString()

                    channel.send(cmd)

                    FileLogger.i(TAG, "=== SENT network_available SIGNAL TO GO ===")
                } else {
                    FileLogger.d(TAG, "sendNetworkAvailableSignal: control channel not available")
                }
            } catch (e: Exception) {
                FileLogger.w(TAG, "Failed to send network_available signal: ${e.message}")
            }
        }
    }

    /**
     * ITERATION 3: Network watchdog timer - force restart if offline >45s.
     * This is CRITICAL for fixing long disconnect (60s+) scenario.
     */
    private fun startNetworkWatchdog() {
        // Cancel any existing watchdog
        stopNetworkWatchdog()

        val disconnectTime = System.currentTimeMillis()

        networkWatchdogJob = scope.launch {
            FileLogger.i(TAG, "=== NETWORK WATCHDOG STARTED (timeout=${networkWatchdogTimeoutMs}ms) ===")

            delay(networkWatchdogTimeoutMs)

            // Check if still offline
            if (isNetworkLost) {
                val offlineTime = System.currentTimeMillis() - disconnectTime
                FileLogger.e(TAG, "=== NETWORK WATCHDOG TRIGGERED: ${offlineTime}ms offline, forcing VPN restart ===")

                // Force full VPN restart
                handleControlSocketBroken()
            } else {
                FileLogger.i(TAG, "Network watchdog: Network recovered before timeout")
            }
        }
    }

    private fun stopNetworkWatchdog() {
        networkWatchdogJob?.cancel()
        networkWatchdogJob = null
    }

    /**
     * ITERATION 3: Health check - ping Go process every 10s.
     * Detects dead/zombie processes and triggers restart.
     */
    private fun startHealthCheck() {
        // Cancel any existing health check
        stopHealthCheck()

        healthCheckJob = scope.launch {
            FileLogger.i(TAG, "=== HEALTH CHECK STARTED (interval=${healthCheckIntervalMs}ms) ===")

            while (isActive) {
                delay(healthCheckIntervalMs)

                val currentState = _state.value

                // Only check if we should be connected
                if (currentState is VpnState.Disconnected) {
                    FileLogger.d(TAG, "Health check: Disconnected state, stopping")
                    break
                }

                // Skip if already reconnecting
                if (currentState is VpnState.Connecting || isReconnecting) {
                    FileLogger.d(TAG, "Health check: Already connecting/reconnecting, skip")
                    continue
                }

                // Two different questions, and only the second one can see a
                // core that is running but no longer carrying traffic.
                val isProcessAlive = coreStarted
                val tunnelAlive = checkTunnelHealth()

                if (currentState is VpnState.Connected && !isProcessAlive) {
                    FileLogger.e(TAG, "=== HEALTH CHECK FAILED: Go core reported exit ===")
                    handleControlSocketBroken()
                    break
                } else if (currentState is VpnState.Connected && !tunnelAlive) {
                    FileLogger.e(TAG, "=== HEALTH CHECK FAILED: core alive but tunnel silent ===")
                    handleControlSocketBroken()
                    break
                } else {
                    FileLogger.d(TAG, "Health check: OK (started=$isProcessAlive, tunnel=$tunnelAlive, state=$currentState)")
                }
            }

            FileLogger.i(TAG, "=== HEALTH CHECK STOPPED ===")
        }
    }

    private fun stopHealthCheck() {
        healthCheckJob?.cancel()
        healthCheckJob = null
    }

    /**
     * PIXEL FIX: Active network monitor - polls activeNetwork every 2 seconds.
     * This is a CRITICAL fix for Pixel devices where NetworkCallback.onAvailable()
     * is unreliable during WiFi <-> Mobile transitions.
     *
     * Why this is needed:
     * - On Pixel devices, NetworkCallback sometimes doesn't fire on network recovery
     * - Airplane mode on/off can miss onAvailable() callback entirely
     * - Fast network switching (WiFi->Mobile->WiFi) can drop events
     *
     * This aggressive polling ensures we NEVER miss network changes.
     */
    private fun startActiveNetworkMonitor() {
        // Cancel any existing monitor
        stopActiveNetworkMonitor()

        activeNetworkMonitorJob = scope.launch {
            FileLogger.i(TAG, "=== ACTIVE NETWORK MONITOR STARTED (Pixel fix, interval=${activeNetworkMonitorIntervalMs}ms) ===")

            var lastActiveNetwork: Network? = null
            var lastNetworkType: String = "unknown"

            while (isActive) {
                try {
                    val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                    val currentNetwork = cm.activeNetwork
                    val capabilities = currentNetwork?.let { cm.getNetworkCapabilities(it) }

                    // Determine network type
                    val currentNetworkType = when {
                        capabilities == null -> "none"
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                        else -> "other"
                    }

                    // Detect network changes
                    val networkChanged = currentNetwork != lastActiveNetwork
                    val typeChanged = currentNetworkType != lastNetworkType

                    if (networkChanged || typeChanged) {
                        FileLogger.i(TAG, "=== ACTIVE MONITOR: Network changed! ===")
                        FileLogger.i(TAG, "  Old: $lastActiveNetwork ($lastNetworkType)")
                        FileLogger.i(TAG, "  New: $currentNetwork ($currentNetworkType)")

                        // Network appeared (was null, now not null)
                        if (lastActiveNetwork == null && currentNetwork != null) {
                            FileLogger.i(TAG, "ACTIVE MONITOR: Network APPEARED - sending network_available signal")
                            sendNetworkAvailableSignal()

                            // If we were in network loss state, trigger reconnect
                            if (isNetworkLost) {
                                FileLogger.i(TAG, "ACTIVE MONITOR: Triggering recovery from network loss")
                                isNetworkLost = false
                                triggerReconnectAfterNetworkRecovery()
                            }
                        }
                        // Network changed (different network object or type)
                        else if (currentNetwork != null && (networkChanged || typeChanged)) {
                            // The pair was already computed here and thrown
                            // away; the core has always accepted a name for it.
                            val reason = NetworkTransition.reason(lastNetworkType, currentNetworkType)
                            FileLogger.i(TAG, "ACTIVE MONITOR: Network SWITCHED - sending network_changed" +
                                if (reason.isEmpty()) "" else " (reason=$reason)")
                            // Give NetworkCallback 500ms to handle it first
                            delay(500)
                            // Only trigger if still connected (NetworkCallback might have handled it)
                            if (_state.value is VpnState.Connected) {
                                sendNetworkChangedCommand(forceReconnect = false, isCritical = true, reason = reason)
                            }
                        }
                        // Network disappeared (was not null, now null)
                        else if (currentNetwork == null) {
                            FileLogger.w(TAG, "ACTIVE MONITOR: Network DISAPPEARED")
                            isNetworkLost = true
                        }

                        lastActiveNetwork = currentNetwork
                        lastNetworkType = currentNetworkType
                    }

                } catch (e: Exception) {
                    FileLogger.w(TAG, "Active network monitor error: ${e.message}")
                }

                delay(activeNetworkMonitorIntervalMs)
            }

            FileLogger.i(TAG, "=== ACTIVE NETWORK MONITOR STOPPED ===")
        }
    }

    private fun stopActiveNetworkMonitor() {
        activeNetworkMonitorJob?.cancel()
        activeNetworkMonitorJob = null
    }

    /**
     * Start a background job that periodically checks for network availability.
     * This is crucial for reconnecting after long network outages (1+ minute)
     * when NetworkCallback.onAvailable() might not fire reliably.
     */
    private fun startNetworkRecoveryJob() {
        // Cancel any existing job
        stopNetworkRecoveryJob()

        networkRecoveryJob = scope.launch {
            FileLogger.i(TAG, "=== NETWORK RECOVERY JOB STARTED ===")
            var checkCount = 0

            while (isActive && isNetworkLost) {
                // IMPROVED: More aggressive checking for faster recovery
                // Check every 2 seconds for the first 30 seconds
                // Then every 5 seconds for the next minute
                // Then every 10 seconds afterwards
                val checkInterval = when {
                    checkCount < 15 -> 2_000L  // First 30 seconds: 2s interval
                    checkCount < 27 -> 5_000L  // Next 60 seconds: 5s interval
                    else -> 10_000L            // After 90 seconds: 10s interval
                }
                delay(checkInterval)
                checkCount++

                if (!isNetworkLost) {
                    FileLogger.i(TAG, "Network recovery job: isNetworkLost=false, exiting")
                    break
                }

                // Skip check if we're already reconnecting
                val currentState = _state.value
                if (currentState is VpnState.Connecting) {
                    FileLogger.d(TAG, "Network recovery job: already connecting, waiting...")
                    continue
                }

                FileLogger.d(TAG, "Network recovery job: checking network (attempt $checkCount)")

                // Check if we have network connectivity using ConnectivityManager
                val hasNetwork = checkNetworkAvailability()
                if (hasNetwork) {
                    FileLogger.i(TAG, "=== NETWORK RECOVERY JOB: Network restored! Triggering reconnect ===")
                    isNetworkLost = false
                    lastNetworkAvailableTime = System.currentTimeMillis()

                    // ITERATION 3: Send explicit network_available signal
                    sendNetworkAvailableSignal()

                    triggerReconnectAfterNetworkRecovery()
                    break
                } else {
                    // Update notification to show we're waiting for network.
                    // Connecting belongs here since the reconnect stages stopped
                    // masquerading as Error; without it the notification froze on
                    // whatever the last phase happened to be.
                    if (currentState is VpnState.Connected ||
                        currentState is VpnState.Connecting ||
                        currentState is VpnState.Error) {
                        val waitTime = (System.currentTimeMillis() - lastNetworkAvailableTime) / 1000
                        updateNotification("Waiting for network... (${waitTime}s)")
                        FileLogger.d(TAG, "Network recovery job: Still no network after ${waitTime}s")
                    }
                }
            }
            FileLogger.i(TAG, "=== NETWORK RECOVERY JOB ENDED ===")
        }
    }

    private fun stopNetworkRecoveryJob() {
        networkRecoveryJob?.cancel()
        networkRecoveryJob = null
    }

    /**
     * Check if network is available using ConnectivityManager.
     * This is a backup check when NetworkCallback might not fire.
     */
    private fun checkNetworkAvailability(): Boolean {
        return try {
            val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false

            // Check for internet capability (not just network connection)
            val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val hasValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

            FileLogger.d(TAG, "Network check: hasInternet=$hasInternet, hasValidated=$hasValidated")
            hasInternet && hasValidated
        } catch (e: Exception) {
            FileLogger.w(TAG, "Network check failed: ${e.message}")
            false
        }
    }

    /**
     * Trigger reconnection after network is recovered.
     * This handles both the case when connection is still "alive" (but broken)
     * and when it's already in error state.
     */
    private fun triggerReconnectAfterNetworkRecovery() {
        // Guard: skip if reconnect already in progress
        if (isReconnecting) {
            FileLogger.d(TAG, "triggerReconnectAfterNetworkRecovery: reconnect in progress, skipping")
            return
        }

        val currentState = _state.value
        FileLogger.i(TAG, "triggerReconnectAfterNetworkRecovery: currentState=$currentState")

        when (currentState) {
            is VpnState.Connected -> {
                // CRITICAL: Before trying network_changed, verify VPN interface is still valid
                // Network change might have invalidated the old tun0 interface
                val vpnFd = vpnInterface
                if (vpnFd == null || !isVpnInterfaceValid(vpnFd)) {
                    FileLogger.w(TAG, "VPN interface is invalid after network recovery, forcing full reconnect")
                    handleControlSocketBroken()
                    return
                }

                // Connection is "alive" and interface is valid - try to refresh it
                FileLogger.i(TAG, "Connected state - sending network_changed command")
                sendNetworkChangedCommand(forceReconnect = true)
            }
            is VpnState.Error -> {
                // Already in error state - trigger full reconnect
                FileLogger.i(TAG, "Error state - triggering full reconnect")
                handleControlSocketBroken()
            }
            is VpnState.Connecting -> {
                // Already reconnecting - do nothing
                FileLogger.d(TAG, "Already connecting - no action needed")
            }
            is VpnState.Disconnected -> {
                // User disconnected - don't auto-reconnect
                FileLogger.d(TAG, "Disconnected state - not auto-reconnecting")
            }
        }
    }

    /**
     * Check if VPN interface file descriptor is still valid.
     * An invalid FD means the interface was destroyed (e.g., after network change).
     *
     * PIXEL FIX: Enhanced validation that checks OS-level FD validity, not just Java wrapper.
     */
    private fun isVpnInterfaceValid(vpnFd: ParcelFileDescriptor): Boolean {
        return try {
            // Try to get fd value - will throw if closed/invalid
            val fd = vpnFd.fd
            if (fd < 0) {
                FileLogger.w(TAG, "VPN interface FD is negative: $fd")
                return false
            }

            // Check if FileDescriptor is valid
            if (!vpnFd.fileDescriptor.valid()) {
                FileLogger.w(TAG, "VPN interface FileDescriptor is invalid")
                return false
            }

            // CRITICAL: Check if FD is still valid at OS level (API 30+)
            // This catches cases where FD is valid in Java but closed/invalid in kernel
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    android.system.Os.fcntlInt(vpnFd.fileDescriptor, android.system.OsConstants.F_GETFL, 0)
                } catch (e: android.system.ErrnoException) {
                    FileLogger.w(TAG, "VPN interface FD validation failed at OS level: ${e.message}")
                    return false
                }
            }

            true
        } catch (e: Exception) {
            FileLogger.w(TAG, "VPN interface FD validation failed: ${e.message}")
            false
        }
    }

    /**
     * @param reason the core's name for the transport change, from
     *        [NetworkTransition]. Empty when the caller cannot tell — the
     *        NetworkCallback's link-property path and the post-recovery path
     *        both know that something changed but not from what to what — and
     *        then the field is omitted rather than sent blank.
     */
    private fun sendNetworkChangedCommand(
        forceReconnect: Boolean = false,
        isCritical: Boolean = false,
        reason: String = "",
    ) {
        // Guard: skip if reconnect already in progress to prevent race conditions in Go
        if (isReconnecting) {
            FileLogger.d(TAG, "sendNetworkChangedCommand: reconnect in progress, skipping")
            return
        }

        if (_state.value !is VpnState.Connected) {
            FileLogger.d(TAG, "sendNetworkChangedCommand: state is not Connected (${_state.value}), skipping")
            return
        }

        val now = System.currentTimeMillis()
        val bypassDebounce = forceReconnect || isCritical
        if (bypassDebounce) {
            if (forceReconnect) {
                FileLogger.i(TAG, "Force reconnect requested, bypassing debounce")
            }
            if (isCritical) {
                FileLogger.i(TAG, "Critical network change (from active monitor), bypassing debounce")
            }
        }

        // One atomic claim covers both quiet periods and the race between the
        // ConnectivityManager callback and the active-network poll. Reading,
        // comparing and assigning a plain Long in three steps let both through
        // for one event, and each then built its own ParcelFileDescriptor and
        // handed the core a competing TUN fd.
        if (!networkChangeGate.tryEnter(now, connectionTime, bypassDebounce)) {
            FileLogger.d(TAG, "Ignoring network change - debounced or claimed by another reporter")
            return
        }

        scope.launch {
          // Serializes the work itself, not just the decision to start it: a
          // forced event may legitimately arrive while the previous one is
          // still swapping interfaces.
          networkChangeMutex.withLock {
            try {
                FileLogger.i(TAG, "=== NETWORK CHANGED - Recreating TUN interface ===")

                // A network_changed command needs someone to read it.
                if (!coreStarted) {
                    FileLogger.w(TAG, "Go core reported exit, triggering full reconnect")
                    handleControlSocketBroken()
                    return@withLock
                }

                // Check if control socket is still valid
                if (controlChannel == null) {
                    FileLogger.w(TAG, "Control channel is null, triggering full reconnect")
                    handleControlSocketBroken()
                    return@withLock
                }

                // Get current IP - use saved IP or fallback
                val currentState = _state.value
                if (currentState !is VpnState.Connected) {
                    FileLogger.w(TAG, "State changed to $currentState during network change, triggering full reconnect")
                    handleControlSocketBroken()
                    return@withLock
                }
                val currentIp = if (currentVpnIp.isNotEmpty()) currentVpnIp else TunnelConfig.DEFAULT_TUN_IP
                FileLogger.i(TAG, "Using current VPN IP: $currentIp")

                // Create new VPN interface (old one may be invalid after network change)
                val config = ServerRepository.getActiveServer(this@TiredVpnService) ?: return@withLock

                // Rebuild from what the core actually negotiated, not from
                // constants. The old code hardcoded dns = 8.8.8.8, mtu = 1280
                // and serverIp = 10.9.0.1 here, so a user-configured resolver
                // worked until the first Wi-Fi/LTE switch and then silently
                // became Google's. The v6 addresses were already carried across
                // this way — the mechanism existed, it just was not applied to
                // the rest of the fields.
                val tunConfig = (activeTunnelConfig ?: TunnelConfig.androidDefault())
                    .forNetworkChange(
                        ip = currentIp,
                        ip6 = currentVpnIp6.takeIf { it.isNotEmpty() },
                        serverIp6 = currentVpnServerIp6.takeIf { it.isNotEmpty() }
                    )

                // CRITICAL FIX: Create new interface FIRST, then swap, then close old
                // This prevents packet loss and Go process crashes during network change
                FileLogger.i(TAG, "Creating NEW VPN interface BEFORE closing old one (atomic swap)")

                // Try to create new VPN interface with retry
                var newVpnFd: ParcelFileDescriptor? = null
                var retries = 0
                val maxRetries = 3

                while (newVpnFd == null && retries < maxRetries) {
                    if (retries > 0) {
                        FileLogger.d(TAG, "Retrying VPN interface creation (attempt ${retries + 1}/$maxRetries)")
                        delay(500) // Wait 500ms before retry
                    }

                    newVpnFd = establishVpn(config, tunConfig)
                    retries++

                    if (newVpnFd == null && retries < maxRetries) {
                        FileLogger.w(TAG, "Failed to create VPN interface, will retry...")
                    }
                }

                if (newVpnFd == null) {
                    FileLogger.e(TAG, "Failed to create new VPN interface after $maxRetries attempts")
                    handleControlSocketBroken()
                    return@withLock
                }

                FileLogger.i(TAG, "New VPN interface created successfully, fd=${newVpnFd.fd}")

                // Atomically swap interfaces
                val oldVpnFd = vpnInterface
                vpnInterface = newVpnFd
                FileLogger.i(TAG, "VPN interface swapped atomically")

                // Send network_changed command with new fd
                sendNetworkChangedWithFd(newVpnFd.fd, reason)

                // NOW close old interface AFTER new one is active
                oldVpnFd?.let { oldVpn ->
                    FileLogger.d(TAG, "Closing old VPN interface fd=${oldVpn.fd}")
                    // Drop it from the ledger first: once closed, its number can
                    // be reused by anyone, and a later sweep must not reach it.
                    tunHandles.forget(oldVpn)
                    try { oldVpn.close() } catch (e: Exception) {
                        FileLogger.w(TAG, "Error closing old VPN interface: ${e.message}")
                    }
                }

            } catch (e: Exception) {
                FileLogger.w(TAG, "Failed to handle network change: ${e.message}", e)
                // Any error during network change recovery - trigger full reconnect
                FileLogger.e(TAG, "Network change failed, triggering full reconnect...")
                handleControlSocketBroken()
            }
          }
        }
    }

    private suspend fun sendNetworkChangedWithFd(fd: Int, reason: String = "") {
        try {
            val channel = controlChannel ?: throw IllegalStateException("no control channel")

            // Create FileDescriptor from raw fd
            val fileDescriptor = java.io.FileDescriptor()
            try {
                val field = java.io.FileDescriptor::class.java.getDeclaredField("fd")
                field.isAccessible = true
                field.setInt(fileDescriptor, fd)
            } catch (e: Exception) {
                val field = java.io.FileDescriptor::class.java.getDeclaredField("descriptor")
                field.isAccessible = true
                field.setInt(fileDescriptor, fd)
            }

            // Attach + write + detach in one critical section. Split apart,
            // the 30-second `status` poll or a `network_available` could take
            // the descriptor with them and leave this command without one.
            // Built with JSONObject rather than a literal now that it has an
            // optional field; the core's decoder ignores what it does not know,
            // so an omitted reason is exactly the old wire form.
            val cmd = JSONObject().apply {
                put("command", "network_changed")
                if (reason.isNotEmpty()) put("reason", reason)
            }.toString()
            channel.send(cmd, fileDescriptor)

            FileLogger.i(TAG, "Sent network_changed command with new TUN fd=$fd, reason=${reason.ifEmpty { "(none)" }}")
            // Response will be handled by eventListener in startStatusMonitoring

        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to send network_changed with fd", e)
            throw e
        }
    }

    /**
     * ITERATION 2: Improved handleControlSocketBroken with mutex to prevent parallel reconnects.
     * Now uses the reconnect lock to ensure only ONE reconnect happens at a time.
     *
     * CRITICAL FIX: Mutex is now released INSIDE the coroutine (after executeReconnectSequence()
     * finishes), not immediately after launching the coroutine. The old code released the mutex
     * in the outer finally{} block, which ran BEFORE the async coroutine completed — allowing
     * a second handleControlSocketBroken() call to slip through and cause concurrent reconnects
     * that crashed the Go runtime (SIGABRT).
     */
    private fun handleControlSocketBroken() {
        FileLogger.d(TAG, "handleControlSocketBroken: Called (state=${_state.value})")

        val state = _state.value
        if (state is VpnState.Disconnected) {
            FileLogger.d(TAG, "handleControlSocketBroken: State is Disconnected - not reconnecting (user action)")
            return
        }

        // Fast path: skip if already reconnecting
        if (isReconnecting) {
            FileLogger.w(TAG, "handleControlSocketBroken: Already reconnecting (isReconnecting=true), skipping")
            return
        }

        // CRITICAL: Try to acquire the reconnect lock - skip if already held
        val token = reconnectLock.tryAcquire()
        if (token == null) {
            FileLogger.w(TAG, "handleControlSocketBroken: Reconnect already in progress (lock held), skipping")
            return
        }

        // NOTE: lock is now released INSIDE the coroutine, not here
        FileLogger.d(TAG, "handleControlSocketBroken: Lock acquired, proceeding")

        val now = System.currentTimeMillis()

        // Reset reconnect counter if connection was stable for a while
        if (now - connectionTime > reconnectCooldownMs) {
            FileLogger.d(TAG, "handleControlSocketBroken: Resetting reconnect attempts (cooldown expired)")
            reconnectAttempts = 0
        }

        reconnectAttempts++

        FileLogger.e(TAG, "handleControlSocketBroken: Control socket broken - reconnecting (attempt $reconnectAttempts)")

        enterReconnectingState(getString(R.string.phase_reconnecting))

        // Cancel any pending reconnect job
        pendingReconnectJob?.cancel()

        // Launch reconnect sequence — mutex is released when coroutine completes
        ensureScopeActive()
        val generation = connectGeneration.begin()
        pendingReconnectJob = scope.launch {
            // Set when the sequence could not start a connect. Retrying inside
            // executeReconnectSequence was pointless: scheduleAutoReconnect
            // needs this very lock, hit tryLock() and returned, so the failure
            // path silently gave up. The retry now happens after the finally
            // below has released it.
            var needsRetry = false
            try {
                withContext(NonCancellable) {
                    isReconnecting = true
                    try {
                        needsRetry = !executeReconnectSequence(generation)
                        FileLogger.d(TAG, "handleControlSocketBroken: executeReconnectSequence() completed, needsRetry=$needsRetry")
                    } catch (e: Exception) {
                        FileLogger.e(TAG, "handleControlSocketBroken: executeReconnectSequence() failed", e)
                        needsRetry = true
                    } finally {
                        isReconnecting = false
                        FileLogger.d(TAG, "handleControlSocketBroken: Cleared isReconnecting flag")
                    }
                }
            } catch (e: CancellationException) {
                FileLogger.w(TAG, "handleControlSocketBroken: Reconnect coroutine cancelled", e)
                throw e
            } catch (e: Exception) {
                FileLogger.e(TAG, "handleControlSocketBroken: Reconnect coroutine failed", e)
            } finally {
                val released = reconnectLock.release(token)
                FileLogger.d(TAG, "handleControlSocketBroken: Lock released=$released (coroutine done)")
            }

            if (needsRetry && connectGeneration.isCurrent(generation)) {
                val config = ServerRepository.getActiveServer(this@TiredVpnService)
                if (config != null && config.isValid && _state.value !is VpnState.Disconnected) {
                    FileLogger.i(TAG, "handleControlSocketBroken: sequence did not connect, scheduling retry")
                    scheduleAutoReconnect(config)
                }
            }
        }
    }

    /**
     * ITERATION 2: Extracted reconnect logic into separate function.
     * This is always called with the reconnect lock held.
     *
     * ITERATION 2.1 (P0 FIX): Uses NonCancellable context for critical cleanup sections
     * to prevent JobCancellationException during resource cleanup.
     *
     * [generation] is the connection generation this sequence belongs to. The
     * cleanup below runs inside NonCancellable, so cancelling this job does
     * not stop it: forceResetCore could cancel us, a fresh connect() could
     * create a process and a TUN interface, and then these lines would null
     * the shared fields and close the descriptor the new connection is using.
     * Every destructive step is therefore gated on the generation still being
     * current.
     *
     * @return true when a connect was actually started. False means the caller
     *         must schedule the retry — which it can only do after releasing
     *         the reconnect lock, since scheduleAutoReconnect needs it.
     */
    private suspend fun executeReconnectSequence(generation: Int): Boolean {
        try {
            FileLogger.d(TAG, "executeReconnectSequence: Reconnect sequence STARTED (gen=$generation)")

            // CRITICAL SECTION: Cleanup operations must not be cancelled
            withContext(NonCancellable) {
                FileLogger.d(TAG, "executeReconnectSequence: Entered NonCancellable context for cleanup")

                // Every step below destroys something shared, and a fresh
                // connect() can start at any point in between — forceResetCore
                // cancels the job this runs in, but the body is NonCancellable
                // and keeps going. Checking once on entry only protected the
                // steps that happen to come first; the one check that used to
                // exist further down covered the TUN descriptor and nothing
                // else, while the core, the control channel and control.sock
                // were torn out from under the new attempt.
                if (!stillOurs(generation, "step 0")) return@withContext

                // The objects this reconnect is entitled to destroy, taken now.
                // Step 3 blocks for up to five seconds, so re-reading the
                // fields afterwards can hand us a core and a channel that a
                // newer connect has just installed.
                val ownedProcess = tiredvpnProcess
                val ownedChannel = controlChannel

                // Step 0: Cancel active connectionJob to prevent race with resource cleanup
                FileLogger.d(TAG, "executeReconnectSequence: Step 0 - Cancel active connectionJob")
                connectionJob?.cancel()
                connectionJob = null

                if (!stillOurs(generation, "step 1")) return@withContext
                FileLogger.d(TAG, "executeReconnectSequence: Step 1 - Stop monitoring")
                // 1. Stop monitoring
                stopNetworkMonitoring()
                stopActiveNetworkMonitor()
                statusMonitorJob?.cancel()
                statusMonitorJob = null
                stopProcessWatchdog()
                stopHealthCheck()

                if (!stillOurs(generation, "step 2")) return@withContext
                FileLogger.d(TAG, "executeReconnectSequence: Step 2 - Close control socket")
                // 2. Close the channel we came in with, not whatever the field
                // holds. FIRST, before stopping the process.
                if (ownedChannel != null) {
                    if (controlChannel === ownedChannel) controlChannel = null
                    try { ownedChannel.close() } catch (e: Exception) {
                        FileLogger.w(TAG, "executeReconnectSequence: closing control channel: ${e.message}")
                    }
                }

                if (!stillOurs(generation, "step 3")) return@withContext
                FileLogger.d(TAG, "executeReconnectSequence: Step 3 - Stop process and WAIT")
                // 3. WAIT for the core to actually stop.
                //
                // The bound is Go's, not ours, and there used to be a
                // withTimeout(3000) here pretending otherwise. stopAndWait() is
                // a blocking JNI call with no suspension point inside it, and
                // cancelling a coroutine does not touch a thread parked in a
                // syscall — the same thing this diff fixes for the control
                // socket read. The timeout could only fire after the call had
                // already returned, so it bounded nothing and logged
                // "force-killing" for an event that had not happened.
                //
                // What does bound it: stopClient() in jni_android.go waits on
                // clientWg through a select with time.After(5 * time.Second)
                // and returns either way. Five seconds, held here, once.
                // Ownership, not a re-check: stopAndWait() reaches the one core
                // in the process through a Go package-level variable, so the
                // object we snapshotted is not what decides which core stops.
                // Taking it here also parks a newer connect in
                // awaitCoreHandover instead of letting it start underneath us.
                val stoppedTheCore = withOwnedCore(generation, "executeReconnectSequence") {
                    val ownedProcess = tiredvpnProcess
                    ownedProcess?.stopAndWait()
                    if (tiredvpnProcess === ownedProcess) tiredvpnProcess = null

                    // 3b. Drop the JNI callback bridge, inside the same
                    // ownership: it is global state, and dropping it after a
                    // newer core has registered cuts that core's callback.
                    FileLogger.d(TAG, "executeReconnectSequence: Step 3b - Native cleanup")
                    try { TiredVpnNative.cleanup() } catch (e: Throwable) {
                        FileLogger.w(TAG, "executeReconnectSequence: native cleanup failed: ${e.message}")
                    }

                    // 4. Delete the control socket file. One fixed name, and
                    // after the wait above a new core may have bound it;
                    // unlinking it then leaves connectToControlSocket waiting
                    // 30s for a file that will never reappear.
                    FileLogger.d(TAG, "executeReconnectSequence: Step 4 - Delete control socket file")
                    val socketFile = File("${filesDir.absolutePath}/control.sock")
                    if (socketFile.exists()) {
                        FileLogger.d(TAG, "executeReconnectSequence: Control socket file deleted: ${socketFile.delete()}")
                    }
                }
                if (!stoppedTheCore) return@withContext

                // Steps 3b and 4 moved inside the ownership block above: the
                // JNI bridge and control.sock are process-wide, so they belong
                // to whoever owns the core, not to whoever passed a check a
                // moment ago.

                // 5. Close VPN interface (through the ledger: closed once, ours only).
                if (!stillOurs(generation, "step 5")) return@withContext
                FileLogger.d(TAG, "executeReconnectSequence: Step 5 - Close VPN interface")
                vpnInterface = null
                tunHandles.releaseAll()

                FileLogger.d(TAG, "executeReconnectSequence: Critical cleanup completed, exiting NonCancellable context")
            }

            // The same guard for the rest of the sequence: a superseded attempt
            // must not reconnect on top of the one that replaced it.
            if (!connectGeneration.isCurrent(generation)) {
                FileLogger.w(TAG, "executeReconnectSequence: generation $generation superseded, a newer attempt owns the connection")
                return false
            }

            // Guard: if disconnect() was called while we were in NonCancellable cleanup
            // (e.g. onRevoke() from another VPN taking over), abort reconnect.
            // Without this check the NonCancellable block ignores cancellation and overrides
            // _state back to Connecting, leaving the UI button stuck.
            if (_state.value is VpnState.Disconnected) {
                FileLogger.w(TAG, "executeReconnectSequence: disconnect() called during cleanup, aborting reconnect")
                return false
            }

            // 6. Get config first to check connectivity
            val config = ServerRepository.getActiveServer(this@TiredVpnService)
            if (config == null || !config.isValid) {
                FileLogger.e(TAG, "Cannot reconnect - invalid config")
                _state.value = VpnState.Error("Connection lost - invalid config")
                // TECHNICAL: the profile went missing under us. The user never
                // asked for the VPN to stay off, so nothing persistent is
                // cleared — restoring a profile and letting the watchdog pick
                // it up must keep working.
                disconnect(StopIntent.TECHNICAL)
                return false
            }

            // 7. Wait for TCP connectivity before reconnecting
            //
            // Everything from here to the connect is waiting, and this whole
            // function runs inside the caller's withContext(NonCancellable) —
            // so cancelling our job does not stop us, the delays below run to
            // completion, and several seconds pass in which the user can pick a
            // different server and tap connect. The generation is therefore
            // re-checked after every wait, and the connect itself claims it
            // atomically rather than trusting the last check.
            val skipConnectivityCheck = isNetworkLost
            isNetworkLost = false  // Reset flag

            if (!skipConnectivityCheck) {
                FileLogger.d(TAG, "Reconnect: Step 6 - Checking TCP connectivity to ${config.serverAddress}:${config.serverPort}...")
                enterReconnectingState("Checking network...")

                var connectivityAttempts = 0
                val maxConnectivityAttempts = 3  // Limit attempts
                while (!checkTcpConnectivity(config.serverAddress, config.serverPort) && connectivityAttempts < maxConnectivityAttempts) {
                    connectivityAttempts++
                    // Shorter backoff: 1s, 2s, 3s (max 3s instead of 30s)
                    val waitMs = minOf(1000L * connectivityAttempts, 3000L)
                    FileLogger.d(TAG, "Reconnect: No connectivity, waiting ${waitMs}ms (attempt $connectivityAttempts/$maxConnectivityAttempts)...")
                    enterReconnectingState("Waiting for network...")
                    delay(waitMs)
                    if (!connectGeneration.isCurrent(generation)) {
                        FileLogger.w(TAG, "executeReconnectSequence: generation $generation superseded while waiting for network")
                        return false
                    }
                }
                FileLogger.i(TAG, "Reconnect: TCP connectivity check done after $connectivityAttempts attempts")
            } else {
                FileLogger.i(TAG, "Reconnect: Skipping TCP check - network just recovered")
            }

            // 8. Minimal delay before reconnecting (only on retry attempts)
            val backoffMs = if (reconnectAttempts <= 1) 500L else minOf(1000L * reconnectAttempts, maxBackoffMs)
            FileLogger.d(TAG, "Reconnect: Step 7 - Waiting ${backoffMs}ms before reconnecting...")
            delay(backoffMs)
            if (!connectGeneration.isCurrent(generation)) {
                FileLogger.w(TAG, "executeReconnectSequence: generation $generation superseded during backoff")
                return false
            }

            // 9. Reconnect.
            //
            // `supersedes = generation`: the check above is a courtesy that
            // keeps the log readable, not the guarantee. The guarantee is that
            // connect() claims the next generation with a compare-and-set on
            // this one, so a connect the user started in the last microsecond
            // cannot be cancelled and replaced by this stale config.
            FileLogger.d(TAG, "Reconnect: Step 8 - Starting reconnection")
            enterReconnectingState(getString(R.string.phase_reconnecting))
            FileLogger.i(TAG, "Attempting automatic reconnection...")
            if (!connect(config, supersedes = generation)) {
                FileLogger.w(TAG, "executeReconnectSequence: a newer attempt claimed the connection, standing down")
                return true // not our failure, and not ours to retry
            }
            FileLogger.d(TAG, "DEBUG: Reconnect sequence COMPLETED successfully")
            return true
        } catch (e: Exception) {
            FileLogger.e(TAG, "DEBUG: Reconnect sequence FAILED with exception: ${e.message}", e)
            // Do NOT schedule the retry here. This runs with the reconnect lock
            // held, scheduleAutoReconnect needs the same lock, and its tryLock
            // simply returned — so every failure of this sequence silently
            // dropped the retry it thought it had arranged. The caller schedules
            // it after the lock is released.
            return false
        }
    }

    /**
     * Establish a TUN interface and record it as ours. Tracking happens here,
     * at the single point where a descriptor comes into existence, so no call
     * site can create one that cleanup does not know about.
     */
    private fun establishVpn(config: VpnConfig, tunConfig: TunnelConfig): ParcelFileDescriptor? =
        establishVpnInterface(config, tunConfig)?.also { tunHandles.track(it) }

    private fun establishVpnInterface(config: VpnConfig, tunConfig: TunnelConfig): ParcelFileDescriptor? {
        return try {
            // FIX: Use default IP if "auto" or "0.0.0.0" to avoid VPN interface creation failure
            // One address for every fallback. These used to disagree —
            // "auto" became 10.8.0.2 and a blank became 10.9.0.2 — and the
            // network-change path then rebuilt the interface around a third
            // value again. 10.8.0.2 is what the core itself defaults to and
            // what the exits hand out; see TunnelConfig.DEFAULT_TUN_IP.
            val effectiveIp = when {
                tunConfig.ip == "auto" -> TunnelConfig.DEFAULT_TUN_IP
                tunConfig.ip == "0.0.0.0" || tunConfig.ip.isNullOrBlank() -> TunnelConfig.DEFAULT_TUN_IP
                else -> tunConfig.ip
            }

            // MTU override: use config.mtu if set (>0), otherwise core-provided MTU
            val effectiveMtu = if (config.mtu > 0) config.mtu else tunConfig.mtu

            // DNS + IPv6 addressing plan: real dual-stack v6 when the core
            // negotiated it, otherwise today's v4-only setup with the leak
            // blackhole. Also decides whether a v6 DNS literal is usable.
            val plan = DualStackPlan.create(tunConfig, config.customDns, FALLBACK_DNS, effectiveMtu)
            if (plan.dnsSwappedToFallback) {
                FileLogger.w(TAG, "Ignoring IPv6 DNS ${plan.requestedDns} - unreachable behind the IPv6 blackhole, using $FALLBACK_DNS")
            }

            val builder = Builder()
                .setSession("TiredVPN")
                .setMtu(effectiveMtu)
                .addAddress(effectiveIp, 24)
                .addRoute("0.0.0.0", 0)  // Route all IPv4
                .addDnsServer(plan.dns)

            if (plan.dualStack) {
                // Dual-stack negotiated (handshake v0x04): real v6 replaces the
                // leak blackhole - v6 traffic now exits through the tunnel.
                builder.addAddress(plan.ip6!!, DualStackPlan.IPV6_PREFIX_LENGTH)
                builder.addRoute("::", 0)  // Route all IPv6 into the tunnel
                FileLogger.i(TAG, "Dual-stack active: v6 address ${plan.ip6}/${DualStackPlan.IPV6_PREFIX_LENGTH}, server ${plan.serverIp6}")
            } else {
                // Pull IPv6 into the tunnel so it dies here instead of leaking out over
                // the device's native v6 default route (issue #55)
                if (!Ipv6Guard.blackholeIpv6(builder, effectiveMtu)) {
                    FileLogger.w(TAG, "MTU $effectiveMtu < ${Ipv6Guard.MIN_IPV6_MTU}: cannot claim ::/0, IPv6 may leak outside the tunnel")
                }
            }

            // Add backup DNS if different
            if (plan.dns != FALLBACK_DNS) {
                builder.addDnsServer(FALLBACK_DNS)
            }

            // Apply split tunneling (handles app inclusion/exclusion) for this profile
            val usesAllowedApps = applySplitTunneling(builder, config.id)

            // Exclude our own app to prevent loops (only if not using addAllowedApplication)
            if (!usesAllowedApps) {
                builder.addDisallowedApplication(packageName)
            }

            // Apply kill switch
            applyKillSwitch(builder)

            builder.establish()
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to establish VPN interface", e)
            null
        }
    }

    /**
     * Apply split tunneling settings.
     * @return true if addAllowedApplication was used (can't mix with addDisallowedApplication)
     */
    private fun applySplitTunneling(builder: Builder, profileId: String?): Boolean {
        val prefs = getSharedPreferences(SplitTunnelSettings.PREFS_NAME, MODE_PRIVATE)
        val mode = SplitTunnelSettings.getMode(prefs, profileId)
        val selectedApps = SplitTunnelSettings.getApps(prefs, profileId)

        FileLogger.i(TAG, "Split tunneling: profile=$profileId, mode=$mode, apps=${selectedApps.size} (${selectedApps.joinToString()})")

        if (selectedApps.isEmpty()) {
            FileLogger.d(TAG, "No apps selected for split tunneling")
            return false
        }

        when (mode) {
            "exclude" -> {
                // Exclude selected apps from VPN (they bypass VPN)
                for (pkg in selectedApps) {
                    try {
                        builder.addDisallowedApplication(pkg)
                        FileLogger.d(TAG, "Excluded from VPN: $pkg")
                    } catch (e: Exception) {
                        FileLogger.w(TAG, "Failed to exclude $pkg: ${e.message}")
                    }
                }
                return false
            }
            "include" -> {
                // Only selected apps use VPN
                // Note: when using addAllowedApplication, we can't use addDisallowedApplication
                val appsToAllow = LinkedHashSet(selectedApps)

                // If any Google app is tunneled, also tunnel Google Play Services and
                // friends, otherwise their offloaded signaling/STUN stays off-tunnel
                // and WebRTC calls (Google Meet) hang on ICE. Companions that aren't
                // installed are skipped by the per-package try/catch below.
                if (selectedApps.any { it.startsWith("com.google.android") }) {
                    appsToAllow += GOOGLE_COMPANION_PACKAGES
                    FileLogger.i(TAG, "Google app in allowlist, adding companions: $GOOGLE_COMPANION_PACKAGES")
                }

                for (pkg in appsToAllow) {
                    try {
                        builder.addAllowedApplication(pkg)
                        FileLogger.d(TAG, "VPN only for: $pkg")
                    } catch (e: Exception) {
                        FileLogger.w(TAG, "Failed to add allowed app $pkg: ${e.message}")
                    }
                }
                return true  // Signal that we used addAllowedApplication
            }
        }
        return false
    }

    private fun applyKillSwitch(builder: Builder) {
        val prefs = getSharedPreferences("tiredvpn_settings", MODE_PRIVATE)
        val killSwitchEnabled = prefs.getBoolean("kill_switch", false)

        if (killSwitchEnabled) {
            // Block connections without VPN by setting blocking mode
            // This is handled by the system when VPN disconnects if we set it up properly
            builder.setBlocking(true)
            FileLogger.d(TAG, "Kill switch enabled - blocking mode on")
        }
    }


    /**
     * Tear the tunnel down.
     *
     * [intent] is not decoration. Everything that can bring the VPN back —
     * VpnWatchdogWorker.doWork, BootReceiver, AirplaneModeReceiver — is gated
     * on flags this function used to clear unconditionally, and four of its
     * five callers are paths the user never touched: reconnect budget
     * exhausted, config invalid mid-reconnect, onDestroy, onRevoke. After any
     * of them the tunnel stayed down until the user tapped connect again.
     */
    private fun disconnect(intent: StopIntent) {
        FileLogger.d(TAG, "Disconnecting VPN (intent=$intent)")

        // CRITICAL: Set state to Disconnected FIRST to prevent handleControlSocketBroken race condition
        // If we close control socket before setting state, event listener will trigger reconnect
        clearPhase()
        _state.value = VpnState.Disconnected

        applyStopIntent(intent)

        // Cancel any ongoing connection attempt
        disarmConnectWatchdog()  // user disconnected explicitly - don't kill for this
        connectionJob?.cancel()
        connectionJob = null

        // ITERATION 2: Cancel any pending reconnect jobs to prevent race conditions
        pendingReconnectJob?.cancel()
        pendingReconnectJob = null

        // Reset network lost flag to prevent auto-reconnect
        isNetworkLost = false

        // Stop network monitoring (includes recovery job)
        stopNetworkMonitoring()
        stopActiveNetworkMonitor()

        // Stop status monitoring
        statusMonitorJob?.cancel()
        statusMonitorJob = null

        // Stop process watchdog
        stopProcessWatchdog()

        // ITERATION 3: Stop health check
        stopHealthCheck()

        // Stop protect server
        stopProtectServer()

        // Send disconnect command before closing socket
        sendDisconnectCommand()

        // Close control socket
        closeControlChannel()

        // Stop the core and drop its callback bridge, as one owner. A
        // user-initiated disconnect answers to no generation, so it takes
        // whatever is in the slot — and a connect racing it waits in
        // awaitCoreHandover rather than starting a core this block then stops.
        //
        // What actually stops the core is stop() (stopClient cancels the client
        // context and waits up to five seconds on clientWg); cleanup() only
        // makes sure whatever survived that wait has no way left to call back
        // into us. Descriptors are closed below, through the ledger.
        withCoreReset {
            try {
                FileLogger.d(TAG, "Stopping tiredvpn process...")
                tiredvpnProcess?.stop()
                FileLogger.d(TAG, "Process stopped")
            } catch (e: Exception) {
                FileLogger.w(TAG, "Error stopping process", e)
            }
            tiredvpnProcess = null

            try {
                TiredVpnNative.cleanup()
                FileLogger.d(TAG, "Native callback bridge dropped")
            } catch (e: Exception) {
                FileLogger.w(TAG, "Error cleaning up native library", e)
            }
        }

        // Close the VPN interface and every other descriptor we established.
        // Going through the ledger means each one is closed exactly once and
        // nothing outside it is touched; the previous delayed /proc/self/fd
        // sweep ran unguarded here and could close the core's dup, which is
        // what turned a disconnect into a fdsan SIGABRT.
        vpnInterface = null
        val closedOnDisconnect = tunHandles.releaseAll()
        FileLogger.d(TAG, "VPN interface closed ($closedOnDisconnect TUN fd(s))")

        // Release WakeLock
        releaseWakeLock()

        // Reset coroutine scope to release any blocked Dispatchers.IO threads.
        // Without this, a stuck connection leaves dead threads in the IO pool,
        // and subsequent scope.launch calls queue coroutines that never execute.
        resetScope()

        // State already set to Disconnected at the beginning of disconnect()
        // CRITICAL: Call stopForeground first, THEN stopSelf to ensure proper cleanup
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            FileLogger.w(TAG, "Error stopping foreground", e)
        }

        // stopSelf() should clear VPN from system
        try {
            stopSelf()
        } catch (e: Exception) {
            FileLogger.w(TAG, "Error stopping service", e)
        }
    }

    /**
     * Hold the CPU awake for as long as there is a tunnel to keep alive.
     *
     * Releasing it on a successful connect — which is what this did for one
     * revision — looks like an obvious battery win and is not one, because the
     * session does not survive the sleep that follows. The keepalive frames
     * that hold a session open are generated by the client side
     * (`runKeepaliveSender` in internal/tun/vpn.go, a 10s ticker), so while
     * this process is suspended nobody sends them; the server's read deadline
     * is 30s and its dead-connection monitor 45s. Any sleep longer than half a
     * minute therefore comes back to a torn-down session, and the way back is a
     * full reconnect with a fresh strategy scan — up to 46s of no network on
     * every screen unlock.
     *
     * So: taken on entry to connect, released on the two paths that end a
     * tunnel — [cleanupFailedConnection] and [disconnect]. No timeout, because
     * there is no honest number: the lock is wanted exactly as long as the
     * tunnel is.
     *
     * This is not free and is not the last word on it. Deep sleep with the
     * tunnel up needs the session to survive a wall-clock gap, which is a core
     * change (re-arm the deadline on a detected clock jump, hot-swap instead of
     * a full reconnect), not a Kotlin one. Doze already ignores partial wake
     * locks, so the long-idle case behaves as if this were absent either way.
     */
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "TiredVPN::VpnConnectLock"
            )
        }
        wakeLock?.let {
            if (!it.isHeld) {
                it.acquire()
                FileLogger.d(TAG, "WakeLock acquired for the lifetime of the tunnel")
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                FileLogger.d(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }

    /**
     * The ongoing VPN notification.
     *
     * [status] carries the tunnel address and, in proxy mode, the listening
     * port. The channel is IMPORTANCE_DEFAULT, so on a locked screen that text
     * was readable by anyone holding the phone. VISIBILITY_PRIVATE plus a
     * public version keeps the notification visible while the lock screen
     * shows only the app name.
     */
    private fun createNotification(status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            NOTIFICATION_REQUEST_CODE,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val publicVersion = NotificationCompat.Builder(this, TiredVpnApp.VPN_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(getString(R.string.vpn_notification_text))
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        return NotificationCompat.Builder(this, TiredVpnApp.VPN_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()
    }

    private fun updateNotification(status: String) {
        val notification = createNotification(status)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /** Publish a connection phase to the UI and the ongoing notification. */
    private fun setPhase(text: String) {
        _connectingPhase.value = text
        updateNotification(text)
        FileLogger.d(TAG, "Phase: $text")
    }

    /** Clear the connection phase (called when leaving the Connecting state). */
    private fun clearPhase() {
        _connectingPhase.value = ""
    }

    /**
     * Enter the live-reconnect state and say which stage we are at.
     *
     * The stages used to be published as `VpnState.Error("Reconnecting...")`,
     * `Error("Checking network...")` and `Error("Waiting for network...")`.
     * Every reader took them at face value: MainActivity painted "Отключено"
     * in red, and both VpnWatchdogWorker.doWork and AirplaneModeReceiver saw a
     * state that was neither Connected nor Connecting and kicked the service
     * again — on top of the reconnect that was already running.
     *
     * Error stays what it says on the tin: terminal.
     */
    private fun enterReconnectingState(phase: String) {
        _state.value = VpnState.Connecting
        setPhase(phase)
    }

    /**
     * Close the TUN descriptors we established and no longer use.
     *
     * This used to walk /proc/self/fd and close by number every entry whose
     * readlink target was /dev/tun. That reached descriptors we do not own —
     * the interface a concurrent connect() had just established, and the dup
     * the Go core holds after receiving the fd over SCM_RIGHTS. Freeing a
     * number behind its owner's back gets it handed to the next open() in the
     * process, and fdsan aborts on the ownership mismatch; the process died
     * with SIGABRT on a network change and nothing brought the VPN back. The
     * `vpnInterface == null` guard around the delayed sweep narrowed the race
     * but could not close it: the check and the close were not atomic, and it
     * never protected the core's dup at all.
     *
     * The ledger cannot name a descriptor by number, so a foreign fd is now
     * unreachable by construction rather than by timing.
     */
    private fun forceCloseTunFds() {
        val closed = tunHandles.releaseAllExcept(vpnInterface)
        if (closed > 0) FileLogger.i(TAG, "Closed $closed orphan TUN fd(s) we owned")
    }

    override fun onDestroy() {
        FileLogger.i(TAG, "=== SERVICE onDestroy() ===")
        // TECHNICAL: the service dying says nothing about what the user wants.
        // A user-initiated disconnect has already come through ACTION_DISCONNECT
        // and cleared the flags itself; a system kill must leave them alone so
        // the watchdog can bring the tunnel back.
        // disconnect() resets the scope internally (resetScope()), so no separate scope.cancel() needed
        disconnect(StopIntent.TECHNICAL)

        // Final scope cancellation for safety (in case disconnect() was already called)
        scope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        // The system withdrew our VPN consent, or another VPN app took the
        // slot. Restarting would be pointless and rude: every auto-start path
        // ends at VpnService.prepare(), which now returns a consent intent, so
        // they would spin without ever connecting, and if another VPN is
        // active we would be fighting it for the tunnel.
        //
        // It is still not "the user turned our VPN off", so the periodic
        // watchdog stays scheduled; only vpn_should_be_connected is cleared,
        // and the next successful connect re-arms it through
        // VpnWatchdogWorker.schedule().
        FileLogger.w(TAG, "VPN permission revoked")
        disconnect(StopIntent.REVOKED)
        super.onRevoke()
    }

    data class TunnelConfig(
        val ip: String,
        val serverIp: String,
        val dns: String,
        val mtu: Int,
        val routes: String,
        // Dual-stack IPv6 inside the tunnel (handshake v0x04). Null when the
        // core did not negotiate it: old cores, policy off, or a v4-only exit.
        val ip6: String? = null,
        val serverIp6: String? = null
    ) {
        /**
         * The same tunnel, re-pointed at the addresses in force right now.
         *
         * Everything the core negotiated — resolver, MTU, server address,
         * routes — is carried over; only what genuinely changes across an
         * interface swap is replaced.
         */
        fun forNetworkChange(ip: String, ip6: String?, serverIp6: String?): TunnelConfig =
            copy(
                ip = ip,
                // The core re-negotiates the same session v6 on reconnect
                // (handshake v0x04), so these stay valid across the swap.
                ip6 = ip6 ?: this.ip6,
                serverIp6 = serverIp6 ?: this.serverIp6,
            )

        companion object {
            /**
             * Client address the core itself defaults to
             * (cmd/tiredvpn/main.go: `-tun-ip`, "10.8.0.2"), and the subnet the
             * exits actually hand out. Everything here used to be split between
             * 10.8.0.x and 10.9.0.x; 10.9.0.x is the placeholder the core uses
             * before the handshake (internal/client/client.go), never a real
             * tunnel address, so 10.8.0.x is the one to agree on.
             */
            const val DEFAULT_TUN_IP = "10.8.0.2"
            const val DEFAULT_SERVER_IP = "10.8.0.1"

            /**
             * Last-resort config for a network change that arrives before any
             * handshake was recorded. Only reachable if the core answered
             * `waiting_fd` and then the response was lost, so it is a floor,
             * not a policy.
             */
            fun androidDefault(): TunnelConfig = TunnelConfig(
                ip = DEFAULT_TUN_IP,
                serverIp = DEFAULT_SERVER_IP,
                dns = FALLBACK_DNS,
                // Keep in sync with the core TUN MTU default (issue #27).
                mtu = 1280,
                routes = "0.0.0.0/0",
            )

            /**
             * Parse the control-socket "waiting_fd" response. Unknown/absent
             * fields fall back to defaults, so old cores keep working.
             */
            fun fromJson(json: JSONObject): TunnelConfig {
                return TunnelConfig(
                    ip = json.getString("ip"),
                    serverIp = json.optString("server_ip", DEFAULT_SERVER_IP),
                    dns = json.optString("dns", "8.8.8.8"),
                    // 1280 (IPv6 minimum) matches the core's TUN MTU default and
                    // leaves headroom for tunnel encapsulation. 1400 let segments
                    // exceed the real path MTU once framed, causing fragmentation
                    // / PMTUD blackholes and poor download throughput (issue #27).
                    mtu = json.optInt("mtu", 1280),
                    routes = json.optString("routes", "0.0.0.0/0"),
                    ip6 = json.optString("ip6", "").takeIf { it.isNotBlank() },
                    serverIp6 = json.optString("server_ip6", "").takeIf { it.isNotBlank() }
                )
            }
        }
    }

    /**
     * Parse connection info from log lines.
     * Format: "Connected via Traffic Morph (Yandex Video) (latency=131.708456ms, attempts=9, rtt_masking=false)"
     */
    private fun parseConnectionInfo(line: String) {
        // Match "Connected via STRATEGY_NAME (latency=XXXms, attempts=N"
        val regex = Regex("""Connected via (.+?) \(latency=([0-9.]+)ms, attempts=(\d+)""")
        val match = regex.find(line)
        if (match != null) {
            connectedStrategy = match.groupValues[1]
            connectedLatencyMs = match.groupValues[2].toDoubleOrNull()?.toLong() ?: 0
            connectedAttempts = match.groupValues[3].toIntOrNull() ?: 1
            FileLogger.i(TAG, "Parsed connection info: strategy=$connectedStrategy, latency=${connectedLatencyMs}ms, attempts=$connectedAttempts")
        }
        // Note: Keepalive tracking moved to Go side - events sent via control socket
    }

    /**
     * Check TCP connectivity to server before attempting reconnect.
     * This avoids wasting time on full reconnect attempts when network is down.
     * IMPORTANT: Socket must be protected to bypass VPN and use physical network.
     */
    private suspend fun checkTcpConnectivity(host: String, port: Int): Boolean {
        return withContext(Dispatchers.IO) {
            // use{} and not a close() on the success path only: a refused
            // connect threw straight past that close, and this runs in a loop
            // during every reconnect.
            try {
                java.net.Socket().use { socket ->
                    // CRITICAL: Protect socket so it bypasses the (dead) VPN tunnel
                    // and goes through the physical network
                    if (!protect(socket)) {
                        FileLogger.w(TAG, "TCP connectivity check: failed to protect socket")
                    }
                    socket.soTimeout = 3000  // Reduced from 5s to 3s
                    val address = java.net.InetSocketAddress(host, port)
                    socket.connect(address, 3000) // 3 second timeout (reduced from 5s)
                }
                FileLogger.d(TAG, "TCP connectivity check passed: $host:$port")
                true
            } catch (e: Exception) {
                FileLogger.d(TAG, "TCP connectivity check failed: $host:$port - ${e.message}")
                false
            }
        }
    }

    /**
     * Has the core gone silent on the control channel?
     *
     * Narrower than the name suggests, and deliberately stated that way: what
     * this observes is the core's control goroutine answering, which
     * [coreStarted] cannot see (that flag is set when we start the client and
     * cleared only when the core announces it left, so a wedged goroutine
     * leaves it true forever). It is *not* a measure of the relay: the core's
     * `status` response is assembled from bookkeeping fields and never asks the
     * relay anything, so a dead relay behind a live control goroutine still
     * answers. Making this a real tunnel-health check means adding an idle
     * counter to that response in internal/tun/control.go.
     *
     * Measured on [SystemClock.uptimeMillis] — the clock the core was also
     * frozen on. See [lastKeepaliveTime].
     *
     * Deliberately true in proxy mode: there is no control channel there, so
     * `lastKeepaliveTime` would freeze at connect time and this would declare a
     * healthy proxy dead 75 seconds later.
     */
    fun checkTunnelHealth(): Boolean {
        if (_state.value !is VpnState.Connected) return true
        if (controlChannel == null) return true // proxy mode: no event channel to measure
        if (lastKeepaliveTime == 0L) return true // Not initialized yet

        val timeSinceKeepalive = SystemClock.uptimeMillis() - lastKeepaliveTime
        if (timeSinceKeepalive > keepaliveTimeoutMs) {
            FileLogger.e(TAG, "Tunnel appears dead - core silent for ${timeSinceKeepalive}ms (limit ${keepaliveTimeoutMs}ms)")
            return false
        }
        return true
    }
}
