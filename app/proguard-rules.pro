# TiredVPN ProGuard Rules

# Keep VPN service
-keep class com.tiredvpn.android.vpn.** { *; }

# Keep native library interface
-keep class com.tiredvpn.android.native.** { *; }

# --- JNI contract with the Go core ---
#
# These are not covered by the package keeps above on purpose: the package keeps
# say "do not touch this package", which is a refactoring decision that someone
# will narrow one day. The rules below say "the Go core resolves these by name at
# runtime", which stays true no matter how the packages are reorganised, and they
# fail loudly at R8 time if a name is changed on the Kotlin side without the Go
# side following.
#
# The entry points: cgo exports the symbol Java_com_tiredvpn_android_native_
# TiredVpnNative_<method>, so both the class name and every native method name
# are part of the ABI. proguard-android-optimize.txt already carries a
# -keepclasseswithmembernames rule for native methods; this restates it for this
# class so the dependency is visible here.
-keepclasseswithmembernames class com.tiredvpn.android.native.TiredVpnNative {
    native <methods>;
}

# The callbacks: jni_android.go does GetObjectClass(callback_obj) followed by
# GetMethodID(cls, "onStateChange", "(Ljava/lang/String;Ljava/lang/String;)V")
# and GetMethodID(cls, "onLogMessage", "(Ljava/lang/String;)V"). The lookup runs
# against the concrete class of whatever object was passed to initNative, which
# today is TiredVpnServiceJNI (vpn package), NativeProcessJNI, or the anonymous
# callbackProxy inside TiredVpnNative. Renaming those two methods in any of them
# turns every state change and every native log line into a silent no-op, so keep
# them by signature across all implementations.
-keep interface com.tiredvpn.android.native.TiredVpnNative$NativeCallback { *; }
-keepclassmembers class * implements com.tiredvpn.android.native.TiredVpnNative$NativeCallback {
    void onStateChange(java.lang.String, java.lang.String);
    void onLogMessage(java.lang.String);
}

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
