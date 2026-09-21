package com.tiredvpn.android.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

object PingManager {
    private const val TAG = "PingManager"

    suspend fun ping(host: String): Long = withContext(Dispatchers.IO) {
        // Try ICMP first (works on some devices/networks)
        val icmpResult = pingIcmp(host)
        if (icmpResult != -1L) return@withContext icmpResult

        // Fallback to TCP connect (reliable but measures connect time, not pure ICMP)
        // We assume port 443 or 80 are open on most servers, or we could use the VPN port.
        // Since we don't always know the port here easily without passing config, 
        // let's try a standard port or just failover.
        // Actually, for a VPN server, the best check is often the VPN port itself.
        // But here we only have 'host'.
        
        return@withContext -1L
    }

    suspend fun ping(host: String, port: Int): Long = withContext(Dispatchers.IO) {
         // Try ICMP first as requested
        val icmpResult = pingIcmp(host)
        if (icmpResult != -1L) return@withContext icmpResult

        // Fallback to TCP connect to the specific port
        return@withContext pingTcp(host, port)
    }

    private fun pingIcmp(host: String): Long {
        try {
            val process = Runtime.getRuntime().exec("/system/bin/ping -c 1 -W 1 $host")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val latency = parseIcmpLatencyMs(reader.lineSequence())
            if (latency != null) return latency
            process.waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "ICMP ping failed: ${e.message}")
        }
        return -1L
    }

    /**
     * The verdict `/system/bin/ping`'s output carries, or null when no line
     * mentions a time at all.
     *
     * Split out from [pingIcmp] so the parsing can be driven from a test
     * without a `ping` binary: this is what turns a server's entry in the list
     * from "—" into a number, and it is the part that gets the format wrong.
     * The caller still owns the process; this owns only the text.
     *
     * Null and -1 are different answers, and the split keeps them that way:
     * the first line mentioning `time=` decides the outcome whether or not it
     * parses, so an unparseable one is -1 (a verdict) while no such line at
     * all is null (no verdict, the caller reaps the process and gives up).
     * That is exactly how the inline loop behaved.
     *
     * The other two rules it inherits, unchanged: the value is read up to a
     * SPACE followed by `ms`, so busybox-style `time=1.23ms` does not parse;
     * and the result is truncated to whole milliseconds, so anything under
     * 1 ms reports 0.
     */
    internal fun parseIcmpLatencyMs(lines: Sequence<String>): Long? {
        for (line in lines) {
            if (line.contains("time=")) {
                val time = line.substringAfter("time=").substringBefore(" ms").trim()
                return time.toDoubleOrNull()?.toLong() ?: -1L
            }
        }
        return null
    }

    private fun pingTcp(host: String, port: Int): Long {
        return try {
            val start = System.currentTimeMillis()
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), 2000) // 2s timeout
            socket.close()
            System.currentTimeMillis() - start
        } catch (e: Exception) {
            -1L
        }
    }
}
