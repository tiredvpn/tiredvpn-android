# TiredVPN ProGuard Rules

# Keep VPN service
-keep class com.tiredvpn.android.vpn.** { *; }

# Keep native library interface
-keep class com.tiredvpn.android.native.** { *; }

# WorkManager + Room (used by UpdateWorker / VpnWatchdogWorker)
# androidx.startup InitializationProvider loads WorkDatabase by canonicalName,
# Room looks up generated *_Impl via Class.forName — both break under R8 without these rules.
-keep class androidx.work.** { *; }
-keep class androidx.work.impl.** { *; }
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-dontwarn androidx.work.**

-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Database class * { *; }
-keep class androidx.room.** { *; }
-dontwarn androidx.room.**

# androidx.startup
-keep class androidx.startup.** { *; }
-keep class * implements androidx.startup.Initializer { *; }
