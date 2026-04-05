package com.tiredvpn.android.update

/**
 * Data class representing update configuration from t.json
 */
data class UpdateConfig(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val releaseNotes: String,
    val minAndroidSdk: Int = 24,
    val forceUpdate: Boolean = false
)
