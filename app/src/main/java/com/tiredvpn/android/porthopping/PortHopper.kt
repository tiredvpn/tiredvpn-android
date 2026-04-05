package com.tiredvpn.android.porthopping

import com.tiredvpn.android.util.FileLogger
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.random.Random

/**
 * Port hopping implementation for DPI evasion.
 *
 * CRITICAL: This implementation MUST generate the same port sequence as the Go version
 * when using the same seed. The algorithm is:
 * 1. SHA-256 hash of the seed
 * 2. First 8 bytes interpreted as BigEndian Int64
 * 3. Use this as seed for kotlin.random.Random
 * 4. Port = portRangeStart + random.nextInt(rangeSize)
 *
 * Compatible with: github.com/tiredvpn/tiredvpn/internal/porthopping
 */
class PortHopper(
    private val config: PortHopperConfig
) {
    companion object {
        private const val TAG = "PortHopper"
    }

    private var currentPort: Int
    private var lastHopTime: Long = System.currentTimeMillis()
    private var hopCount: Long = 0L

    // Fibonacci state for StrategyFibonacci
    private var fibPrev: Int = 0
    private var fibCurr: Int = 1

    // Deterministic RNG for synchronized port generation
    private var random: Random

    // Jittered interval for current hop cycle
    private var jitteredIntervalMs: Long

    // Callback when port changes
    private var onHopCallback: ((oldPort: Int, newPort: Int) -> Unit)? = null

    init {
        // Initialize RNG based on seed
        random = if (config.seed != null && config.seed.isNotEmpty()) {
            // Deterministic RNG from seed for client-server sync
            // MUST match Go implementation exactly!
            val hash = MessageDigest.getInstance("SHA-256").digest(config.seed)
            val seedLong = bytesToLongBigEndian(hash.sliceArray(0 until 8))
            Random(seedLong)
        } else {
            // Use secure random seed
            val secureRandom = SecureRandom()
            val seedBytes = ByteArray(8)
            secureRandom.nextBytes(seedBytes)
            Random(bytesToLongBigEndian(seedBytes))
        }

        // Calculate initial port
        currentPort = calculateNextPort()
        jitteredIntervalMs = randomizeInterval()

        FileLogger.d(TAG, "PortHopper created (strategy=${config.strategy}, range=${config.portRangeStart}-${config.portRangeEnd}, interval=${config.hopIntervalMs}ms, initial_port=$currentPort)")
    }

    /**
     * Convert 8 bytes to Long using BigEndian order.
     * MUST match Go's binary.BigEndian.Uint64()
     */
    private fun bytesToLongBigEndian(bytes: ByteArray): Long {
        require(bytes.size >= 8) { "Need at least 8 bytes" }
        var result = 0L
        for (i in 0 until 8) {
            result = (result shl 8) or (bytes[i].toLong() and 0xFF)
        }
        return result
    }

    /**
     * Get the current port being used.
     */
    fun currentPort(): Int {
        synchronized(this) {
            return currentPort
        }
    }

    /**
     * Check if it's time to switch to a new port.
     */
    fun shouldHop(): Boolean {
        if (!config.enabled) return false

        synchronized(this) {
            val elapsed = System.currentTimeMillis() - lastHopTime
            return elapsed >= jitteredIntervalMs
        }
    }

    /**
     * Perform a port hop and return the new port.
     * Returns the current port if hopping is disabled.
     */
    fun nextPort(): Int {
        if (!config.enabled) {
            synchronized(this) {
                return currentPort
            }
        }

        synchronized(this) {
            val oldPort = currentPort
            val newPort = calculateNextPort()

            currentPort = newPort
            lastHopTime = System.currentTimeMillis()
            hopCount++
            jitteredIntervalMs = randomizeInterval()

            FileLogger.d(TAG, "Port hop #$hopCount: $oldPort -> $newPort (next hop in ${jitteredIntervalMs}ms)")

            // Trigger callback if set
            onHopCallback?.invoke(oldPort, newPort)

            return newPort
        }
    }

    /**
     * Get time remaining until next hop in milliseconds.
     */
    fun timeUntilNextHopMs(): Long {
        if (!config.enabled) return 0

        synchronized(this) {
            val elapsed = System.currentTimeMillis() - lastHopTime
            return if (elapsed >= jitteredIntervalMs) 0 else jitteredIntervalMs - elapsed
        }
    }

    /**
     * Set a callback function to be called when port changes.
     */
    fun onHop(callback: (oldPort: Int, newPort: Int) -> Unit) {
        synchronized(this) {
            onHopCallback = callback
        }
    }

    /**
     * Get current hopper statistics.
     */
    fun stats(): PortHopperStats {
        synchronized(this) {
            val timeUntilHop = jitteredIntervalMs - (System.currentTimeMillis() - lastHopTime)
            return PortHopperStats(
                enabled = config.enabled,
                currentPort = currentPort,
                hopCount = hopCount,
                lastHopTime = lastHopTime,
                timeUntilNextHopMs = if (timeUntilHop < 0) 0 else timeUntilHop,
                strategy = config.strategy,
                portRangeStart = config.portRangeStart,
                portRangeEnd = config.portRangeEnd
            )
        }
    }

    /**
     * Reset the hopper to initial state.
     * With a deterministic seed, this will restart the same port sequence.
     */
    fun reset() {
        synchronized(this) {
            hopCount = 0
            lastHopTime = System.currentTimeMillis()
            fibPrev = 0
            fibCurr = 1

            // Reset RNG to initial state if using deterministic seed
            if (config.seed != null && config.seed.isNotEmpty()) {
                val hash = MessageDigest.getInstance("SHA-256").digest(config.seed)
                val seedLong = bytesToLongBigEndian(hash.sliceArray(0 until 8))
                random = Random(seedLong)
            }

            // Recalculate initial port (same as constructor would)
            currentPort = calculateNextPort()
            jitteredIntervalMs = randomizeInterval()

            FileLogger.d(TAG, "PortHopper reset (port=$currentPort)")
        }
    }

    /**
     * Calculate the next port based on strategy.
     * MUST match Go implementation for deterministic mode!
     */
    private fun calculateNextPort(): Int {
        val rangeSize = config.portRangeEnd - config.portRangeStart + 1

        return when (config.strategy) {
            HopStrategy.RANDOM -> {
                val offset = random.nextInt(rangeSize)
                config.portRangeStart + offset
            }

            HopStrategy.SEQUENTIAL -> {
                val next = currentPort + 1
                if (next > config.portRangeEnd) {
                    config.portRangeStart
                } else {
                    next
                }
            }

            HopStrategy.FIBONACCI -> {
                // Calculate next Fibonacci number
                val next = fibPrev + fibCurr
                fibPrev = fibCurr
                fibCurr = next

                // Reset Fibonacci if it gets too large
                if (fibCurr > rangeSize * 10) {
                    fibPrev = 0
                    fibCurr = 1
                }

                // Map to port range
                val offset = fibCurr % rangeSize
                config.portRangeStart + offset
            }
        }
    }

    /**
     * Returns the hop interval with +/-30% jitter.
     * MUST match Go implementation!
     */
    private fun randomizeInterval(): Long {
        var base = config.hopIntervalMs

        // If base is 0, generate random interval between 30-120s
        if (base == 0L) {
            base = (30_000L + random.nextInt(91) * 1000L)
        }

        // Apply jitter: -30% to +30%
        val jitterFactor = 0.7 + random.nextDouble() * 0.6 // 0.7 to 1.3
        return (base * jitterFactor).toLong()
    }

    /**
     * Generate a list of ports that the hopper will use.
     * Useful for debugging or pre-allocation.
     */
    fun portList(maxPorts: Int = 100): List<Int> {
        val ports = mutableListOf<Int>()
        val seen = mutableSetOf<Int>()

        // Create a temporary hopper with the same config for deterministic sequence
        val tempHopper = PortHopper(config)

        for (i in 0 until maxPorts) {
            val port = tempHopper.nextPort()
            if (port !in seen) {
                ports.add(port)
                seen.add(port)
            }
        }

        return ports
    }
}

/**
 * Port hopping strategy.
 */
enum class HopStrategy {
    RANDOM,
    SEQUENTIAL,
    FIBONACCI;

    companion object {
        fun fromString(s: String): HopStrategy = when (s.lowercase()) {
            "random" -> RANDOM
            "sequential" -> SEQUENTIAL
            "fibonacci" -> FIBONACCI
            else -> RANDOM
        }
    }

    override fun toString(): String = when (this) {
        RANDOM -> "random"
        SEQUENTIAL -> "sequential"
        FIBONACCI -> "fibonacci"
    }
}

/**
 * Configuration for port hopping.
 */
data class PortHopperConfig(
    /** Enable/disable port hopping */
    val enabled: Boolean = true,

    /** Start of port range (default: 47000 - high ports less analyzed by DPI) */
    val portRangeStart: Int = 47000,

    /** End of port range (default: 65535) */
    val portRangeEnd: Int = 65535,

    /** Base interval between port changes in milliseconds (default: 60s) */
    val hopIntervalMs: Long = 60_000L,

    /** Strategy for selecting next port */
    val strategy: HopStrategy = HopStrategy.RANDOM,

    /** Seed for deterministic port generation (client-server sync) */
    val seed: ByteArray? = null
) {
    fun validate(): Result<Unit> {
        if (portRangeStart < 1 || portRangeStart > 65535) {
            return Result.failure(IllegalArgumentException("Invalid port range start: $portRangeStart"))
        }
        if (portRangeEnd < 1 || portRangeEnd > 65535) {
            return Result.failure(IllegalArgumentException("Invalid port range end: $portRangeEnd"))
        }
        if (portRangeStart >= portRangeEnd) {
            return Result.failure(IllegalArgumentException("Port range start must be less than end"))
        }
        if (hopIntervalMs < 0) {
            return Result.failure(IllegalArgumentException("Hop interval must be non-negative"))
        }
        return Result.success(Unit)
    }

    /** Number of ports in the range */
    val portRange: Int
        get() = portRangeEnd - portRangeStart + 1

    companion object {
        /** Default config optimized for DPI evasion */
        fun default(): PortHopperConfig = PortHopperConfig()
    }
}

/**
 * Port hopper statistics.
 */
data class PortHopperStats(
    val enabled: Boolean,
    val currentPort: Int,
    val hopCount: Long,
    val lastHopTime: Long,
    val timeUntilNextHopMs: Long,
    val strategy: HopStrategy,
    val portRangeStart: Int,
    val portRangeEnd: Int
)
