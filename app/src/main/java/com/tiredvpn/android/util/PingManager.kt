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
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.contains("time=")) {
                    val time = line!!.substringAfter("time=").substringBefore(" ms").trim()
                    return time.toDoubleOrNull()?.toLong() ?: -1L
                }
            }
            process.waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "ICMP ping failed: ${e.message}")
        }
        return -1L
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
