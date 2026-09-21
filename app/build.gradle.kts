import com.android.build.api.artifact.SingleArtifact
// Imported rather than written as java.util.Date: inside a Kotlin DSL script
// `java` resolves to the project's java extension, not to the package.
import java.util.Date
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

plugins {
    id("com.android.application")
    id("org.owasp.dependencycheck")
}

dependencyCheck {
    failBuildOnCVSS = 9.0f
    formats = listOf("JSON", "HTML")
    analyzers.assemblyEnabled = false
}

// --- JNI auto-build: compile libtiredvpn.so from Go core when missing or stale ---

val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs")

/** Where the .so records which core checkout it was built from. */
val jniRevisionStamp = jniLibsDir.file(".core-revision").asFile

/**
 * Identify the core checkout in [dir], or null when it cannot be identified.
 *
 * Null is the ordinary case, not a failure: `scripts/build-jni.sh` clones the
 * core itself when the directory is missing, and CI runs that script directly
 * with its own --core-dir before Gradle is ever invoked. Both leave Gradle
 * without a checkout to interrogate.
 *
 * A dirty tree is recorded as such, so two builds from the same commit with
 * uncommitted edits in between compare equal and do not trigger an endless
 * rebuild — the mismatch that matters is the commit moving.
 */
fun coreRevision(dir: File): String? {
    if (!dir.isDirectory) return null

    fun git(vararg args: String): String? = try {
        val process = ProcessBuilder(listOf("git", *args))
            .directory(dir)
            .redirectErrorStream(false)
            .start()
        val out = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0 && out.isNotEmpty()) out else null
    } catch (e: Exception) {
        null
    }

    git("rev-parse", "HEAD")?.let { head ->
        val dirty = if (git("status", "--porcelain").isNullOrEmpty()) "" else "-dirty"
        return "git:$head$dirty"
    }

    // A tarball or an export is still worth pinning down, just more coarsely.
    return dir.resolve("VERSION").takeIf { it.isFile }?.readText()?.trim()
        ?.let { "version:$it" }
}

val buildJni by tasks.registering(Exec::class) {
    description = "Build libtiredvpn.so from Go core for all Android architectures"
    group = "build"

    // Resolve Go core directory:
    //   1. -PtiredvpnCoreDir=...
    //   2. ../tiredvpn (sibling dir)
    //   3. /tmp/tiredvpn-core (fallback)
    val coreDir = providers.gradleProperty("tiredvpnCoreDir").orNull
        ?: rootProject.layout.projectDirectory.dir("../tiredvpn").asFile
            .takeIf { it.resolve("go.mod").exists() }?.absolutePath
        ?: "/tmp/tiredvpn-core"

    // Rebuild when the .so is missing, and when the core has moved since the .so
    // was built. Existence alone used to be the whole test, so a developer with
    // a months-old .so shipped a months-old core out of a release build without
    // a single line in the log saying so.
    onlyIf {
        val so = jniLibsDir.dir("arm64-v8a").file("libtiredvpn.so").asFile
        if (!so.exists()) {
            logger.lifecycle("buildJni: libtiredvpn.so is missing, building it")
            return@onlyIf true
        }

        val current = coreRevision(File(coreDir))
        val stamped = jniRevisionStamp.takeIf { it.isFile }?.readText()?.trim()

        when {
            current == null -> {
                // Cannot compare. Say so where it will be read, with the date of
                // what is actually about to be packaged.
                logger.warn(
                    "\n" + "=".repeat(78) + "\n" +
                        "buildJni: REUSING AN UNVERIFIED libtiredvpn.so\n" +
                        "  built:    ${Date(so.lastModified())}\n" +
                        "  recorded: ${stamped ?: "<no .core-revision stamp>"}\n" +
                        "  no core checkout at $coreDir, so its revision cannot be compared.\n" +
                        "  Pass -PtiredvpnCoreDir=/path/to/tiredvpn to check it, or delete\n" +
                        "  app/src/main/jniLibs to force a rebuild.\n" +
                        "=".repeat(78)
                )
                false
            }

            stamped == null -> {
                logger.lifecycle(
                    "buildJni: libtiredvpn.so has no recorded core revision, rebuilding from $current"
                )
                true
            }

            stamped != current -> {
                logger.lifecycle(
                    "buildJni: core moved since the .so was built " +
                        "($stamped -> $current), rebuilding"
                )
                true
            }

            else -> {
                logger.lifecycle("buildJni: libtiredvpn.so is current ($current)")
                false
            }
        }
    }

    workingDir = rootProject.layout.projectDirectory.asFile
    commandLine("bash", "scripts/build-jni.sh", "--core-dir", coreDir,
        "--output-dir", "app/src/main/jniLibs")

    doLast {
        // Read the revision now, not at configuration time: when the core was
        // absent, build-jni.sh has only just cloned it.
        val built = coreRevision(File(coreDir))
        if (built == null) {
            jniRevisionStamp.delete()
            logger.warn("buildJni: built the .so but could not identify $coreDir, no stamp written")
        } else {
            jniRevisionStamp.parentFile.mkdirs()
            jniRevisionStamp.writeText(built + "\n")
            logger.lifecycle("buildJni: recorded core revision $built")
        }
    }

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

// --- Distribution switch ---
//
// Default is the sideload build: it carries REQUEST_INSTALL_PACKAGES and the
// self-update machinery. A build for Google Play is produced with
// `-PselfUpdate=false`, which removes both.
val selfUpdateEnabled = (findProperty("selfUpdate") as String?)?.toBoolean() ?: true

/**
 * Drops the self-update permissions from the merged manifest.
 *
 * A source-set overlay with tools:node="remove" would be the usual way to do
 * this, and a placeholder inside tools:node would be the tidy way — but the
 * manifest merger validates that attribute against a fixed vocabulary before
 * substituting placeholders, so it rejects "${selfUpdateNode}" outright. The
 * merged manifest is a transformable AGP artifact, so edit it there instead.
 */
abstract class StripSelfUpdatePermissions : DefaultTask() {

    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @get:OutputFile
    abstract val updatedManifest: RegularFileProperty

    @get:Input
    abstract val permissions: SetProperty<String>

    @TaskAction
    fun strip() {
        val androidNs = "http://schemas.android.com/apk/res/android"
        val doomed = permissions.get()

        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(mergedManifest.get().asFile)

        val nodes = document.getElementsByTagName("uses-permission")
        val removed = mutableListOf<org.w3c.dom.Element>()
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as org.w3c.dom.Element
            if (element.getAttributeNS(androidNs, "name") in doomed) {
                removed += element
            }
        }
        removed.forEach { it.parentNode.removeChild(it) }

        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.INDENT, "yes")
        }.transform(DOMSource(document), StreamResult(updatedManifest.get().asFile))

        logger.lifecycle(
            "selfUpdate=false: removed ${removed.size} of ${doomed.size} self-update permissions " +
                "from ${mergedManifest.get().asFile.name}"
        )
    }
}

// --- Release signing guard ---
//
// The signing config falls back to empty strings so that debug builds, unit
// tests and lint keep working without a keystore. That fallback must never
// reach a real release artifact, where it either produces an unsigned APK or
// fails deep inside the packaging task with an unreadable message. So the
// credentials are validated at configuration time, but only when the invoked
// tasks actually package a release.
val buildingReleaseArtifact = gradle.startParameter.taskNames.any { requested ->
    val task = requested.substringAfterLast(':')
    task.contains("Release") &&
        listOf("assemble", "bundle", "install", "package", "publish").any { task.startsWith(it) }
}

android {
    namespace = "com.tiredvpn.android"
    compileSdk = 37  // required by androidx.core 1.19.0 AAR metadata

    defaultConfig {
        applicationId = "com.tiredvpn.android"
        minSdk = 24
        targetSdk = 37  // Android 16 QPR
        versionCode = 22
        versionName = "1.9.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        buildConfigField("String", "UPDATE_URL", "\"${findProperty("updateUrl") ?: ""}\"")
        buildConfigField("String", "UPDATE_SERVER_PIN", "\"${findProperty("updateServerPin") ?: ""}\"")

        // Built-in APK self-update. Google Play forbids it for apps distributed
        // through the store, and the app is also distributed outside the store,
        // where it is the only update path there is. So it is a build-time switch
        // rather than a feature: -PselfUpdate=false drops REQUEST_INSTALL_PACKAGES
        // from the merged manifest and makes UpdateWorker refuse to schedule.
        buildConfigField("boolean", "SELF_UPDATE_ENABLED", selfUpdateEnabled.toString())
    }

    signingConfigs {
        create("release") {
            val storePath = System.getenv("KEYSTORE_PATH")
            val storePass = System.getenv("KEYSTORE_PASSWORD")
            val alias = System.getenv("KEY_ALIAS")
            val keyPass = System.getenv("KEY_PASSWORD")

            if (buildingReleaseArtifact) {
                val missing = listOf(
                    "KEYSTORE_PATH" to storePath,
                    "KEYSTORE_PASSWORD" to storePass,
                    "KEY_ALIAS" to alias,
                    "KEY_PASSWORD" to keyPass
                ).filter { (_, value) -> value.isNullOrBlank() }.map { (name, _) -> name }

                if (missing.isNotEmpty()) {
                    error(
                        "Release signing is not configured: ${missing.joinToString(", ")} " +
                            "${if (missing.size == 1) "is" else "are"} unset or empty. " +
                            "Export all four before packaging a release, or build a debug " +
                            "artifact instead (./gradlew :app:assembleDebug)."
                    )
                }
                if (!file(storePath!!).exists()) {
                    error("Release signing keystore not found at KEYSTORE_PATH=$storePath")
                }
            }

            storeFile = file(storePath ?: "keystore.jks")
            storePassword = storePass ?: ""
            keyAlias = alias ?: ""
            keyPassword = keyPass ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Lets plain JUnit tests exercise classes that call android.util.Log
            // without stubbing it. Robolectric cannot stand in here: 4.16.1 caps
            // at SDK 36 and this module targets 37.
            isReturnDefaultValues = true
        }
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

androidComponents {
    onVariants { variant ->
        if (!selfUpdateEnabled) {
            val strip = tasks.register<StripSelfUpdatePermissions>(
                "strip${variant.name.replaceFirstChar { it.uppercase() }}SelfUpdatePermissions"
            ) {
                // FOREGROUND_SERVICE_DATA_SYNC deliberately stays: it belongs to
                // WorkManager's shared foreground service, not to the updater.
                // See the comment next to it in AndroidManifest.xml.
                permissions.set(setOf("android.permission.REQUEST_INSTALL_PACKAGES"))
            }
            variant.artifacts.use(strip)
                .wiredWithFiles(
                    StripSelfUpdatePermissions::mergedManifest,
                    StripSelfUpdatePermissions::updatedManifest
                )
                .toTransform(SingleArtifact.MERGED_MANIFEST)
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // ML Kit Barcode Scanning - DISABLED for 16KB page support on Pixel 9
    // implementation("com.google.mlkit:barcode-scanning:17.2.0")

    // CameraX - DISABLED for 16KB page support on Pixel 9
    // implementation("androidx.camera:camera-camera2:1.3.1")
    // implementation("androidx.camera:camera-lifecycle:1.3.1")
    // implementation("androidx.camera:camera-view:1.3.1")

    // WorkManager for VPN watchdog
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Looping muted mascot video on the connecting screen
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-ui:1.10.1")

    // OkHttp for update checking
    implementation("com.squareup.okhttp3:okhttp:5.4.0")

    // Encrypted SharedPreferences for secure credential storage.
    // 1.1.0 went stable on 2025-07-30; nothing in this module needs an alpha.
    implementation("androidx.security:security-crypto:1.1.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    // Lets the update checker and downloader be driven against a real HTTP
    // server instead of being mocked away. okhttp-tls gives that server a
    // certificate, so the downloader's https-only rule can stay unconditional
    // instead of being relaxed for tests.
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.4.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:5.4.0")
    // org.json ships inside the SDK, so the mockable android.jar stubs it out and
    // every parse silently returns null. The real implementation on the test
    // classpath is what lets the update manifest be parsed under test at all.
    testImplementation("org.json:json:20260814")
}
