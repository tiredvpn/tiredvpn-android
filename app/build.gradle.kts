plugins {
    id("com.android.application")
}

// --- JNI auto-build: compile libtiredvpn.so from Go core when missing ---

val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs")

val buildJni by tasks.registering(Exec::class) {
    description = "Build libtiredvpn.so from Go core for all Android architectures"
    group = "build"

    // Only run when .so is missing
    onlyIf { !jniLibsDir.dir("arm64-v8a").file("libtiredvpn.so").asFile.exists() }

    // Resolve Go core directory:
    //   1. -PtiredvpnCoreDir=...
    //   2. ../tiredvpn (sibling dir)
    //   3. /tmp/tiredvpn-core (fallback)
    val coreDir = providers.gradleProperty("tiredvpnCoreDir").orNull
        ?: rootProject.layout.projectDirectory.dir("../tiredvpn").asFile
            .takeIf { it.resolve("go.mod").exists() }?.absolutePath
        ?: "/tmp/tiredvpn-core"

    workingDir = rootProject.layout.projectDirectory.asFile
    commandLine("bash", "scripts/build-jni.sh", "--core-dir", coreDir,
        "--output-dir", "app/src/main/jniLibs")

    doFirst {
        // Resolve NDK: env var → ANDROID_HOME/ndk dir → error
        val ndkHome = providers.environmentVariable("ANDROID_NDK_HOME").orNull
            ?: run {
                val androidHome = System.getenv("ANDROID_HOME")
                if (androidHome != null) {
                    val ndkDir = File(androidHome, "ndk")
                    ndkDir.listFiles()?.filter { f -> f.isDirectory }
                        ?.maxByOrNull { f -> f.name }?.absolutePath
                } else null
            }
            ?: error("Set ANDROID_NDK_HOME or install NDK via SDK Manager")

        logger.lifecycle("buildJni: coreDir=$coreDir, ndkHome=$ndkHome")
        environment("ANDROID_NDK_HOME", ndkHome)
    }
}

tasks.named("preBuild") {
    dependsOn(buildJni)
}

android {
    namespace = "com.tiredvpn.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tiredvpn.android"
        minSdk = 24
        targetSdk = 36  // Android 16
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        buildConfigField("String", "UPDATE_URL", "\"${findProperty("updateUrl") ?: ""}\"")
        buildConfigField("String", "UPDATE_SERVER_PIN", "\"${findProperty("updateServerPin") ?: ""}\"")
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: "keystore.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyAlias = System.getenv("KEY_ALIAS") ?: ""
            keyPassword = System.getenv("KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    lint {
        disable += "RemoveWorkManagerInitializer"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.directories.add("src/main/jniLibs")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // ML Kit Barcode Scanning - DISABLED for 16KB page support on Pixel 9
    // implementation("com.google.mlkit:barcode-scanning:17.2.0")

    // CameraX - DISABLED for 16KB page support on Pixel 9
    // implementation("androidx.camera:camera-camera2:1.3.1")
    // implementation("androidx.camera:camera-lifecycle:1.3.1")
    // implementation("androidx.camera:camera-view:1.3.1")

    // WorkManager for VPN watchdog
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // OkHttp for update checking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Encrypted SharedPreferences for secure credential storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
