package com.tiredvpn.android.vpn

/**
 * Which pool server the reconnect's TCP pre-check knocks on.
 *
 * The pre-check asks one question - is there a network to reconnect over -
 * and it used to ask the active server only. With a pool whose first server
 * is dead, the answer was "no" on every full reconnect: three 3 s probes and
 * the waits between them, about 12 s, before the core even started, which
 * then went past the dead server on its own in 3 s.
 *
 * So a server that failed the probe is passed over for [windowMs] and the
 * next one in pool order is asked instead. The window matches the core's
 * carry (androidCarryTTL, 2 min): about as long as the core itself keeps
 * believing the server is dead. When every server failed inside the window,
 * the one that failed longest ago is asked, so a network that is really down
 * still costs what it did before: one probe per attempt.
 *
 * Keys are "host:port" as configured; no Android types, so the choice can be
 * tested on the JVM.
 */
internal class PrecheckMemory(private val windowMs: Long = WINDOW_MS) {

    private val failedAt = HashMap<String, Long>()

    /** The server to probe now, from [candidates] in pool order; null if there are none. */
    @Synchronized
    fun pick(candidates: List<String>, nowMs: Long): String? {
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { key ->
            val at = failedAt[key]
            at == null || nowMs - at > windowMs
        } ?: candidates.minByOrNull { failedAt[it] ?: Long.MIN_VALUE }
    }

    @Synchronized
    fun failed(key: String, nowMs: Long) {
        failedAt[key] = nowMs
    }

    @Synchronized
    fun answered(key: String) {
        failedAt.remove(key)
    }

    companion object {
        const val WINDOW_MS = 120_000L
    }
}
