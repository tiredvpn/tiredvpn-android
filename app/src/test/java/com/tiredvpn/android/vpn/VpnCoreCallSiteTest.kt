package com.tiredvpn.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards properties of `TiredVpnService` and friends that a JVM unit test
 * cannot reach.
 *
 * Nothing here is a substitute for a real test; where the logic could be
 * lifted out it was, and it has its own file — ServiceLifecycleTest,
 * ControlChannelTest, ReconnectGuardsTest, NetworkEventGateTest,
 * ServerStoreIntegrityTest. What is left lives inside a VpnService, a
 * BroadcastReceiver and a WorkManager Worker, and several of the defects were
 * precisely of the kind that a test on the extracted function cannot see: the
 * function was right, the call site was not. Same trade as
 * ArgLoggingCallSiteTest: a rule that can be checked beats a rule written in a
 * comment above the line that broke.
 */
class VpnCoreCallSiteTest {

    private fun sourceRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "src/main/java").isDirectory) dir = dir.parentFile
        return File(requireNotNull(dir) { "cannot locate src/main/java from ${File("").absolutePath}" }, "src/main/java")
    }

    private fun rawSource(name: String): String =
        sourceRoot().walkTopDown().single { it.isFile && it.name == name }.readText()

    /**
     * The file with comments removed.
     *
     * Everything below asks about code, and several of these rules are stated
     * in a comment right next to the line they govern — "the `if (true)` this
     * replaces", "10.9.0.x is a placeholder". Scanning the raw text made those
     * comments fail their own assertions, which is the polite version of a
     * test that passes because it found the word rather than the behaviour.
     */
    private fun source(name: String): String = stripComments(rawSource(name))

    private fun sourceOrNull(name: String): String? =
        sourceRoot().walkTopDown().firstOrNull { it.isFile && it.name == name }?.readText()

    private fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                text.startsWith("\"\"\"", i) -> {
                    val end = text.indexOf("\"\"\"", i + 3)
                    val stop = if (end < 0) text.length else end + 3
                    out.append(text, i, stop)
                    i = stop
                }
                c == '"' || c == '\'' -> {
                    out.append(c)
                    i++
                    while (i < text.length) {
                        val d = text[i]
                        out.append(d)
                        i++
                        if (d == '\\' && i < text.length) {
                            out.append(text[i]); i++
                        } else if (d == c) break
                    }
                }
                text.startsWith("//", i) -> {
                    val nl = text.indexOf('\n', i)
                    i = if (nl < 0) text.length else nl
                }
                text.startsWith("/*", i) -> {
                    val end = text.indexOf("*/", i + 2)
                    i = if (end < 0) text.length else end + 2
                    out.append(' ')
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /**
     * Comments gone and string contents blanked.
     *
     * For rules about call sites: a log line that mentions `disconnect()` in
     * its text is prose too, just prose the compiler keeps.
     */
    private fun callSites(name: String): String {
        val text = source(name)
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("\"\"\"", i) -> {
                    val end = text.indexOf("\"\"\"", i + 3)
                    i = if (end < 0) text.length else end + 3
                    out.append("\"\"\"\"\"\"")
                }
                text[i] == '"' -> {
                    i++
                    while (i < text.length) {
                        val d = text[i]
                        i++
                        if (d == '\\' && i < text.length) i++ else if (d == '"') break
                    }
                    out.append("\"\"")
                }
                else -> { out.append(text[i]); i++ }
            }
        }
        return out.toString()
    }

    /** Everything from [header] up to the next member declaration. */
    private fun bodyAfter(code: String, header: String): String {
        val start = code.indexOf(header)
        assertTrue("header not found in source: $header", start >= 0)
        val rest = code.substring(start + header.length)
        val end = Regex("""\n {4}(private |internal |public )?(@Volatile )?(suspend )?(fun|val|var|class|object|companion|override|data) """)
            .find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    private fun occurrences(text: String, needle: String): Int {
        var i = 0
        var n = 0
        while (true) {
            val at = text.indexOf(needle, i)
            if (at < 0) return n
            n++
            i = at + needle.length
        }
    }

    // --- positive control (rule 2) ------------------------------------------

    @Test
    fun `the scanner actually reads the sources it asserts about`() {
        val service = rawSource("TiredVpnService.kt")
        assertTrue("TiredVpnService.kt looks empty", service.length > 50_000)
        assertTrue(
            "the comment stripper must remove prose but keep code",
            stripComments("val a = 1 // disconnect()\n/* if (true) */ val b = 2")
                .let { !it.contains("disconnect()") && !it.contains("if (true)") && it.contains("val b = 2") }
        )
        assertTrue(
            "the stripper must not eat a // inside a string literal",
            stripComments("""val r = "0.0.0.0/0"""").contains("0.0.0.0/0")
        )
        assertTrue("scanner cannot see a known landmark", service.contains("class TiredVpnService : VpnService()"))
        assertTrue(source("ServerRepository.kt").contains("object ServerRepository"))
        assertTrue(source("VpnWatchdogWorker.kt").contains("class VpnWatchdogWorker"))
    }

    // --- 1.1 persistent flags belong to the user's intent --------------------

    @Test
    fun `the persistent stop flags are written from exactly one place`() {
        val service = source("TiredVpnService.kt")

        assertEquals(
            "BootReceiver.markVpnDisconnected must be reached only through applyStopIntent",
            1, occurrences(service, "BootReceiver.markVpnDisconnected(")
        )
        assertEquals(
            "VpnWatchdogWorker.cancel must be reached only through applyStopIntent",
            1, occurrences(service, "VpnWatchdogWorker.cancel(")
        )

        val gated = service.lines().filter {
            it.contains("BootReceiver.markVpnDisconnected(") ||
                it.contains("VpnWatchdogWorker.cancel(") ||
                it.contains("VpnWatchdogWorker.markUserDisconnect(")
        }
        assertEquals("expected three gated writes", 3, gated.size)
        for (line in gated) {
            assertTrue("unguarded persistent write: ${line.trim()}", line.contains("DisconnectPolicy."))
        }
    }

    @Test
    fun `every teardown names the intent it is acting on`() {
        val service = callSites("TiredVpnService.kt")
        val calls = Regex("""(?<![A-Za-z])disconnect\(([^)]*)\)""").findAll(service)
            .map { it.groupValues[1].trim() }
            .filter { !it.startsWith("intent: StopIntent") }
            .toList()

        assertTrue("no disconnect() call sites found - the scan is broken", calls.isNotEmpty())
        for (arg in calls) {
            assertTrue("disconnect() called without an intent: disconnect($arg)", arg.startsWith("StopIntent."))
        }
        // onRevoke is the one that must not be lumped in with the user, and
        // the one that must not be lumped in with a technical failure either.
        assertTrue(
            "onRevoke must tear down with StopIntent.REVOKED",
            Regex("""onRevoke\(\)[\s\S]{0,1600}?disconnect\(StopIntent\.REVOKED\)""").containsMatchIn(service)
        )
    }

    // --- 1.2 the null intent of a sticky restart -----------------------------

    @Test
    fun `a null start intent is handled and raises the notification first`() {
        val service = source("TiredVpnService.kt")
        assertTrue(
            "onStartCommand must handle intent == null",
            service.contains("if (intent == null) return handleStickyRestart()")
        )

        val body = bodyAfter(service, "private fun handleStickyRestart(): Int {")
        assertTrue("sticky restart must consult the persistent flags", body.contains("VpnWatchdogWorker.shouldVpnBeConnected("))
        assertTrue("sticky restart must go through StickyRestart.decide", body.contains("StickyRestart.decide("))
        assertTrue("sticky restart must be able to stop the service", body.contains("stopSelf()"))
        assertTrue(
            "startForeground must come before any slow work or stopSelf",
            body.indexOf("startForeground(") in 0 until body.indexOf("stopSelf()")
        )
    }

    // --- 1.3 a reconnect is not an error ------------------------------------

    @Test
    fun `reconnect stages are not published as errors`() {
        val service = source("TiredVpnService.kt")
        for (text in listOf("Reconnecting...", "Checking network...", "Waiting for network...")) {
            assertFalse(
                "\"$text\" must not be a VpnState.Error - MainActivity paints it as disconnected and " +
                    "both the watchdog and AirplaneModeReceiver kick the service on it",
                service.contains("""VpnState.Error("$text""")
            )
        }
        assertTrue(
            "the reconnect stages must go through enterReconnectingState",
            occurrences(service, "enterReconnectingState(") >= 4
        )
    }

    // --- 2.1 liveness -------------------------------------------------------

    @Test
    fun `the core liveness question is asked once, in one shape`() {
        val service = source("TiredVpnService.kt")
        val raw = Regex("""tiredvpnProcess\?\.isRunning""").findAll(service).count()
        assertEquals(
            "isRunning must be read only by the coreStarted property; the six ad-hoc readings " +
                "included three that were inverted (`== true != true`) and one with an unreachable elvis",
            1, raw
        )
        assertTrue(service.contains("private val coreStarted: Boolean get() = tiredvpnProcess?.isRunning == true"))
        assertFalse("`== true != true` is !isRunning written the long way", service.contains("== true != true"))
        assertFalse(service.contains("== true == true"))
        assertFalse(service.contains("isRunning == true ?:"))
    }

    @Test
    fun `an error from the core is terminal on the Kotlin side too`() {
        val jni = source("NativeProcessJNI.kt")
        assertTrue("both terminal states must go through markExited", jni.contains("private fun markExited(code: Int)"))
        val whenBody = jni.substringAfter("when (state) {")
        assertTrue(""""error" must clear isStarted""", Regex(""""error" ->[\s\S]{0,900}?markExited\(""").containsMatchIn(whenBody))
        assertTrue(""""disconnected" must clear isStarted""", Regex(""""disconnected" ->[\s\S]{0,200}?markExited\(""").containsMatchIn(whenBody))
    }

    @Test
    fun `the health check asks about the tunnel, not only about the process`() {
        val service = source("TiredVpnService.kt")
        val body = bodyAfter(service, "private fun startHealthCheck() {")
        assertTrue(
            "checkTunnelHealth was written and never called; the health check is its caller",
            body.contains("checkTunnelHealth()")
        )
        assertTrue("a silent tunnel must trigger the reconnect path", body.contains("handleControlSocketBroken()"))
        // Proxy mode has no control channel, so the keepalive clock never
        // advances there and must not be read as death.
        assertTrue(
            "checkTunnelHealth must opt out when there is no control channel",
            bodyAfter(service, "fun checkTunnelHealth(): Boolean {").contains("controlChannel == null")
        )
    }

    // --- 3 control socket ---------------------------------------------------

    @Test
    fun `the control socket is reached only through ControlChannel`() {
        val service = source("TiredVpnService.kt")
        assertFalse(
            "no raw LocalSocket field may survive - five writers and three readers over one is what this fixes",
            service.contains("controlSocket")
        )
        assertEquals(
            "setFileDescriptorsForSend belongs to the transport adapter alone",
            1, occurrences(service, "setFileDescriptorsForSend(")
        )
        assertTrue(
            "the adapter is the only place that touches the send attachment",
            service.substringAfter("private class LocalSocketTransport").substringBefore("private val exceptionHandler")
                .contains("setFileDescriptorsForSend(fds)")
        )
    }

    @Test
    fun `the attachment is cleared inside the same critical section`() {
        val channel = source("ControlChannel.kt")
        val send = bodyAfter(channel, "fun send(json: String, fd: FileDescriptor? = null) {")
        assertTrue("send must be synchronized", send.contains("synchronized(writeLock)"))
        assertTrue("the attachment must be cleared in a finally", Regex("""finally\s*\{[\s\S]{0,300}?attachFds\(null\)""").containsMatchIn(send))
    }

    @Test
    fun `only one reader is ever wrapped around the control stream`() {
        val service = source("TiredVpnService.kt")
        assertFalse(
            "a second BufferedReader loses whatever the first one buffered ahead",
            service.contains("inputStream.bufferedReader()")
        )
        assertEquals(1, occurrences(source("ControlChannel.kt"), "transport.input.bufferedReader()"))
    }

    @Test
    fun `no declared read timeout is enforced by cancelling a coroutine`() {
        val service = source("TiredVpnService.kt")
        assertFalse(
            "withTimeoutOrNull around a blocking readLine cancels a coroutine and leaves the socket reading",
            service.contains("withTimeoutOrNull(15000)")
        )
        assertTrue(
            "the set_fd read must name the budget it actually uses",
            service.contains("channel.readLine(SET_FD_READ_TIMEOUT)")
        )
        assertTrue(
            "the timed read must set the socket option, not just a coroutine deadline",
            source("ControlChannel.kt")
                .substringAfter("fun readLine(timeoutMs: Int): String?")
                .contains("transport.setReadTimeoutMs(timeoutMs)")
        )
    }

    // --- 4 cancellation that cancels -----------------------------------------

    @Test
    fun `the protect server closes its listening socket before cancelling the job`() {
        val service = source("TiredVpnService.kt")
        val body = bodyAfter(service, "private fun stopProtectServer() {")
        val closeAt = body.indexOf("socket?.close()")
        val cancelAt = body.indexOf("protectServerJob?.cancel()")
        assertTrue("the listening socket must be closed", closeAt >= 0)
        assertTrue("the job must still be cancelled", cancelAt >= 0)
        assertTrue(
            "closing has to come first: cancelling a Job does not interrupt a blocking Os.accept",
            closeAt < cancelAt
        )
        assertTrue("live client descriptors must be closed too", body.contains("protectClientFds"))
    }

    @Test
    fun `protect clients get a read deadline and a ceiling`() {
        val service = source("TiredVpnService.kt")
        assertTrue("a silent client must not hold its thread forever", service.contains("SO_RCVTIMEO"))
        assertTrue("the number of live handlers must be bounded", service.contains("MAX_PROTECT_CLIENTS"))
    }

    @Test
    fun `the reconnect lock is never released without its token`() {
        val service = source("TiredVpnService.kt")
        assertFalse("the bare Mutex allowed an ownerless unlock()", service.contains("reconnectMutex"))
        assertEquals(
            "every release must name a token",
            0, Regex("""reconnectLock\.release\(\s*\)""").findAll(service).count()
        )
        assertTrue(service.contains("reconnectLock.forceRelease()"))
        assertTrue(service.contains("reconnectLock.tryAcquire()"))
    }

    @Test
    fun `cleanup is gated on the connection generation`() {
        val service = source("TiredVpnService.kt")
        assertTrue("connect() must open a generation", service.contains("val generation = connectGeneration.begin()"))
        assertTrue(
            "cleanupFailedConnection must refuse to run for a superseded attempt",
            bodyAfter(service, "private fun cleanupFailedConnection(generation: Int) {")
                .contains("connectGeneration.isCurrent(generation)")
        )
        val sequence = bodyAfter(service, "private suspend fun executeReconnectSequence(generation: Int): Boolean {")
        assertTrue(
            "the NonCancellable teardown must re-check before closing the TUN descriptor",
            occurrences(sequence, "connectGeneration.isCurrent(generation)") >= 2
        )
    }

    @Test
    fun `the reconnect sequence does not schedule a retry while holding the lock`() {
        val service = source("TiredVpnService.kt")
        val sequence = bodyAfter(service, "private suspend fun executeReconnectSequence(generation: Int): Boolean {")
        assertFalse(
            "scheduleAutoReconnect needs the same lock, so calling it here always lost the retry",
            sequence.contains("scheduleAutoReconnect(")
        )
        assertTrue(
            "the caller retries after releasing the lock",
            Regex("""reconnectLock\.release\(token\)[\s\S]{0,800}?scheduleAutoReconnect\(config\)""").containsMatchIn(service)
        )
    }

    @Test
    fun `network change events pass through one gate`() {
        val service = source("TiredVpnService.kt")
        assertFalse("the non-atomic debounce field is gone", service.contains("lastNetworkChangeTime"))
        assertTrue(service.contains("networkChangeGate.tryEnter("))
        assertTrue("the work itself is serialized too", service.contains("networkChangeMutex.withLock"))
    }

    // --- 5 the core surviving cleanup ---------------------------------------

    @Test
    fun `every path that ends a connection attempt also cleans up the core`() {
        val service = source("TiredVpnService.kt")
        for (fn in listOf(
            "private fun cleanupFailedConnection(generation: Int) {",
            "private fun forceResetCore(reason: String) {",
        )) {
            val body = bodyAfter(service, fn)
            assertTrue("$fn must call TiredVpnNative.cleanup()", body.contains("TiredVpnNative.cleanup()"))
        }
        assertTrue(
            "the reconnect teardown must clean up the core as well",
            bodyAfter(service, "private suspend fun executeReconnectSequence(generation: Int): Boolean {")
                .contains("TiredVpnNative.cleanup()")
        )
    }

    @Test
    fun `the JNI boundary degrades instead of killing the process`() {
        val jni = source("NativeProcessJNI.kt")
        for (entry in listOf("override fun start() {", "override fun stop() {")) {
            assertTrue(
                "$entry must catch LinkageError - UnsatisfiedLinkError is an Error and " +
                    "catch (e: Exception) lets it take the process down",
                bodyAfter(jni, entry).contains("catch (e: LinkageError)")
            )
        }
        assertTrue("the availability flag must be read before any external call", jni.contains("TiredVpnNative.isAvailable"))
        assertTrue(jni.contains("EXIT_NO_NATIVE_LIBRARY"))

        val service = source("TiredVpnService.kt")
        assertTrue(
            "a missing core must not be retried forever",
            service.contains("NativeProcessJNI.EXIT_NO_NATIVE_LIBRARY")
        )
    }

    @Test
    fun `a superseded core is drained before a new one registers its callback`() {
        assertTrue(
            "startClient cancels the old client without waiting for it, so its terminal " +
                "callback would land on the new instance",
            source("NativeProcessJNI.kt").contains("TiredVpnNative.reset()")
        )
        assertTrue(source("TiredVpnNative.kt").contains("fun reset()"))
    }

    // --- 7 the rest ---------------------------------------------------------

    @Test
    fun `a lost background network does not start the recovery machinery`() {
        val service = source("TiredVpnService.kt")
        assertTrue(
            "onLost fires for every validated non-VPN network, including the one we are not using",
            service.contains("NetworkLossPolicy.isRelevant(")
        )
    }

    @Test
    fun `a network change rebuilds the tunnel from what was negotiated`() {
        val service = source("TiredVpnService.kt")
        assertTrue("the handshake result must be remembered", service.contains("activeTunnelConfig = tunConfig"))
        assertTrue(
            "the rebuild must start from the remembered config, not from the fallback",
            service.contains("(activeTunnelConfig ?: TunnelConfig.androidDefault())")
        )
        assertTrue(service.contains(".forNetworkChange("))
        val changed = service.substringAfter("private fun sendNetworkChangedCommand(")
            .substringBefore("private suspend fun sendNetworkChangedWithFd")

        assertFalse("a user-configured resolver must survive a Wi-Fi/LTE switch", changed.contains("\"8.8.8.8\""))
        assertFalse(changed.contains("mtu = 1280"))
        assertFalse(changed.contains("\"10.9.0.1\""))
    }

    @Test
    fun `one tunnel address is used for every fallback`() {
        val service = source("TiredVpnService.kt")
        assertFalse(
            "10.9.0.x is the core's pre-handshake placeholder, never a real tunnel address",
            service.contains("10.9.0.")
        )
        assertTrue(service.contains("""const val DEFAULT_TUN_IP = "10.8.0.2""""))
    }

    @Test
    fun `the wakelock is bounded`() {
        val service = source("TiredVpnService.kt")
        assertFalse(
            "an untimed PARTIAL_WAKE_LOCK held for the life of the tunnel keeps the device out of deep sleep",
            Regex("""\bit\.acquire\(\)""").containsMatchIn(service)
        )
        assertTrue(service.contains("it.acquire(CONNECT_WAKELOCK_TIMEOUT_MS)"))
        assertTrue(
            "and released once the connect window closes",
            occurrences(service, "releaseWakeLock()") >= 4
        )
    }

    @Test
    fun `the notification hides addresses on the lock screen`() {
        val service = source("TiredVpnService.kt")
        assertTrue(service.contains("NotificationCompat.VISIBILITY_PRIVATE"))
        assertTrue(service.contains(".setPublicVersion(publicVersion)"))
        // The update agent owns its own requestCode; this one stays 0.
        assertTrue(service.contains("private const val NOTIFICATION_REQUEST_CODE = 0"))
    }

    @Test
    fun `the protect socket is not world readable`() {
        val service = source("TiredVpnService.kt")
        assertFalse(service.contains("setReadable(true, false)"))
        assertFalse(service.contains("setWritable(true, false)"))
    }

    @Test
    fun `a killed process's command line is redacted`() {
        val native = source("NativeProcess.kt")
        val body = native.substringAfter("fun killAllTiredVpnProcesses() {").substringBefore("\n    }")
        assertTrue("argv holds -secret and goes into a log the user shares", body.contains("NativeArgs.redact("))
        assertFalse("the raw command line must not reach the log", body.contains("cmd=\$cmdline"))
        assertFalse(
            "matching on \"tiredvpn\" alone also matches our own package name",
            body.contains("""cmdline.contains("tiredvpn")""")
        )

        val service = source("TiredVpnService.kt")
        assertEquals(
            "a /proc walk that kills nothing in JNI mode belongs on one path, not five",
            1, occurrences(service, "killAllTiredVpnProcesses(")
        )
    }

    @Test
    fun `the tcp probe closes its socket on every path`() {
        val body = bodyAfter(
            source("TiredVpnService.kt"),
            "private suspend fun checkTcpConnectivity(host: String, port: Int): Boolean {"
        )
        assertTrue("a refused connect threw straight past the close()", body.contains("java.net.Socket().use"))
    }

    @Test
    fun `the airplane mode receiver keeps the process alive across its delay`() {
        val receiver = source("AirplaneModeReceiver.kt")
        assertTrue("without goAsync nothing holds the process during delay(3000)", receiver.contains("goAsync()"))
        assertTrue("and it must always be finished", Regex("""finally\s*\{[\s\S]{0,200}?onDone\(\)""").containsMatchIn(receiver))
        assertTrue(
            "the Android 12+ refusal must be told apart from a real failure",
            receiver.contains("ForegroundServiceStartNotAllowedException")
        )
    }

    @Test
    fun `direct boot does not start a service it cannot configure`() {
        val receiver = source("BootReceiver.kt")
        assertFalse(
            "the profile lives in credential-protected storage behind a Keystore key that does not " +
                "exist before unlock, so starting here always looked like a bad configuration",
            receiver.contains("isDirectBoot = true")
        )
        assertTrue(
            "LOCKED_BOOT_COMPLETED must still be handled, just not by starting",
            receiver.contains("ACTION_LOCKED_BOOT_COMPLETED")
        )
    }

    @Test
    fun `the watchdog does not report success over a refused start`() {
        val worker = source("VpnWatchdogWorker.kt")
        assertTrue("a blocked restart must be visible in the result", worker.contains("Result.retry()"))
        assertTrue(worker.contains("private fun restartVpn(): Boolean"))
        assertTrue(worker.contains("ForegroundServiceStartNotAllowedException"))
    }

    // --- 7.12 / 7.13 dead code ----------------------------------------------

    @Test
    fun `the unregistered duplicate service is gone`() {
        assertEquals(
            "TiredVpnServiceJNI was not in the manifest and had already drifted from the live service",
            null, sourceOrNull("TiredVpnServiceJNI.kt")
        )
    }

    @Test
    fun `no unreachable branch hides behind a constant condition`() {
        val service = source("TiredVpnService.kt")
        assertFalse(
            "`if (true) { ... } else { ... }` carried a second, drifting copy of the exit state machine",
            Regex("""if\s*\(true\)""").containsMatchIn(service)
        )
        assertFalse("lastReconnectTime was written and never read", service.contains("lastReconnectTime"))

        val native = source("TiredVpnNative.kt")
        assertFalse("getTunFd had no caller", native.contains("getTunFd"))
        assertFalse("sendCommand had no caller, and the Go side answers it with a TODO", native.contains("sendCommand"))
        assertTrue("isLibraryLoaded is read now, through isAvailable", native.contains("val isAvailable: Boolean"))
    }

    @Test
    fun `the service no longer claims port hops that did not happen`() {
        val service = source("TiredVpnService.kt")
        assertFalse(
            "the core has no port_hop command; the old handler wrote it, ignored the answer, " +
                "and then reported Connected(currentPort = N)",
            service.contains("port_hop")
        )
        assertFalse("nothing may be wired to the unreachable hop callback", service.contains("onReconnectNeeded"))
        // The class itself stays: whoever implements the core side next needs it.
        assertTrue(sourceOrNull("PortHopper.kt") != null)
    }

    // --- 8 the gap against core 1.10 ----------------------------------------

    @Test
    fun `both channels that report a renegotiated IPv6 pair are consumed`() {
        val service = source("TiredVpnService.kt")

        // The event. Dormant on Android today - it is emitted from
        // doAutoReconnect and the control-socket path sets autoReconnect=false -
        // but it is one of the five events the core can send and was the only
        // one nobody read.
        assertTrue(
            "ipv6_changed must have a branch in the event when()",
            Regex(""""ipv6_changed" ->""").containsMatchIn(service)
        )

        // The channel that actually fires on Android: the response to
        // network_changed carries ip6 / server_ip6 / ipv6_removed.
        assertTrue("the reconnect response must be read for ip6", service.contains("""json.has("ip6")"""))
        assertTrue("and for the explicit teardown flag", service.contains("""json.optBoolean("ipv6_removed", false)"""))

        assertEquals(
            "both channels must go through one decision (two calls plus the declaration)",
            3, occurrences(service, "applyRenegotiatedIpv6(")
        )
        assertEquals(1, occurrences(service, "private fun applyRenegotiatedIpv6("))
        assertTrue(
            "the decision itself belongs to Ipv6Renegotiation, where it is tested",
            bodyAfter(service, "private fun applyRenegotiatedIpv6(").contains("Ipv6Renegotiation.apply(")
        )
    }

    @Test
    fun `adopting a new IPv6 pair also moves the remembered handshake config`() {
        val body = bodyAfter(source("TiredVpnService.kt"), "private fun applyRenegotiatedIpv6(")
        assertTrue("currentVpnIp6 must follow", body.contains("currentVpnIp6 = outcome.ip6"))
        assertTrue(
            "activeTunnelConfig must follow too, or forNetworkChange reads the old pair straight back",
            body.contains("activeTunnelConfig = activeTunnelConfig?.copy(")
        )
        assertTrue("and the interface has to be rebuilt", body.contains("sendNetworkChangedCommand(forceReconnect = true)"))
    }

    @Test
    fun `the reconnect response is read only once the state says Connected`() {
        val service = source("TiredVpnService.kt")
        val connectedAssignment = service.indexOf("_state.value = VpnState.Connected(\n                                        strategy = connectedStrategy")
        val applyCall = service.indexOf("source = \"network_changed response\"")
        assertTrue("both landmarks must be found", connectedAssignment >= 0 && applyCall >= 0)
        assertTrue(
            "the rebuild path refuses to run outside Connected, so reading the pair before " +
                "the state is set drops the change silently",
            connectedAssignment < applyCall
        )
    }

    @Test
    fun `a network change names the transport transition`() {
        val service = source("TiredVpnService.kt")
        assertTrue(
            "the monitor already had both transport names and threw the pair away",
            service.contains("NetworkTransition.reason(lastNetworkType, currentNetworkType)")
        )
        assertTrue(service.contains("sendNetworkChangedCommand(forceReconnect = false, isCritical = true, reason = reason)"))
        assertTrue(
            "the reason has to reach the wire",
            bodyAfter(service, "private suspend fun sendNetworkChangedWithFd(fd: Int, reason: String = \"\") {")
                .contains("""put("reason", reason)""")
        )
        assertTrue(
            "an unnamed transition must be omitted, not sent blank",
            bodyAfter(service, "private suspend fun sendNetworkChangedWithFd(fd: Int, reason: String = \"\") {")
                .contains("if (reason.isNotEmpty())")
        )
    }

    @Test
    fun `the endpoint selection policy comes from the profile and is clamped`() {
        val pool = source("ServerPoolConfig.kt")
        assertFalse("the hardcoded policy is gone", pool.contains("private const val POLICY ="))
        assertTrue(pool.contains("fun policyFor(active: VpnConfig): String"))
        assertTrue(
            "the generated file must use the clamped value",
            pool.contains("""sb.append("policy = ").append(quote(policyFor(active)))""")
        )
        assertTrue(source("VpnConfig.kt").contains("val serverSelectionPolicy: String"))
    }

    // --- 6 server storage ----------------------------------------------------

    @Test
    fun `server storage serializes its writes and can update one latency`() {
        val repo = source("ServerRepository.kt")
        assertTrue("read-modify-write on SharedPreferences needs a monitor", repo.contains("synchronized(lock)"))
        assertTrue(
            "the ping wave must stop rewriting the whole list for one number",
            repo.contains("fun updateLatency(context: Context, id: String, latencyMs: Long): Boolean")
        )
        assertTrue(
            "the decision must go through LatencyUpdate, which is where the " +
                "deleted-server rule is actually tested",
            repo.substringAfter("fun updateLatency(").substringBefore("fun deleteServer(")
                .contains("LatencyUpdate.apply(")
        )
    }

    @Test
    fun `a silent fallback to plaintext is no longer silent`() {
        val repo = source("ServerRepository.kt")
        assertTrue("the degradation must be observable by the UI", repo.contains("var isStorageDegraded: Boolean"))
        assertTrue(repo.contains("var storageDegradationReason: String?"))
        assertTrue(
            "androidx.security is alpha and fails with Errors, not only Exceptions",
            repo.substringAfter("private fun encryptedPrefsOrNull(").substringBefore("private fun plainPrefs(")
                .contains("catch (e: Throwable)")
        )
        assertTrue(
            "the built instance has to be cached - getActiveServer built it three times per call",
            repo.contains("cachedEncrypted")
        )
    }

    @Test
    fun `a damaged list is neither reported as empty nor destroyed`() {
        val repo = source("ServerRepository.kt")
        assertTrue(repo.contains("ServerStoreIntegrity.classify("))
        assertTrue(
            "the migration must verify the payload before clearing the only copy",
            repo.substringAfter("private fun migratePlaintextToEncryptedLocked(").substringBefore("private fun migrateLegacyConfigLocked(")
                .contains("ServerStoreIntegrity.isSafeToClearSource(")
        )
        assertTrue(
            "one bad entry must cost one entry, not the rest of the list",
            repo.contains("skipped++")
        )
        assertFalse("printStackTrace is not diagnostics", repo.contains("printStackTrace()"))
    }
}
