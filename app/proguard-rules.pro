# TiredVPN ProGuard Rules

# Keep VPN service
-keep class com.tiredvpn.android.vpn.** { *; }

# Keep native library interface
-keep class com.tiredvpn.android.native.** { *; }
