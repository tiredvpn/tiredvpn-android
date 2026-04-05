package com.tiredvpn.android.native

/**
 * Common interface for both NativeProcess and NativeProcessJNI implementations.
 * Allows TiredVpnService to work with either implementation without knowing the details.
 */
interface TiredVpnProcess {
    val isRunning: Boolean

    fun start()
    fun stop()
    suspend fun stopAndWait(): Boolean
}
