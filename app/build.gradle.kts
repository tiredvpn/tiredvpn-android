import com.android.build.api.artifact.SingleArtifact
// Imported rather than written out: inside a Kotlin DSL script `java`
// resolves to the project's java extension, not to the package.
import java.security.MessageDigest
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
    // Without a key the NVD API is throttled hard: a cold download took
    // 27 min to 1h45m in CI, and several runs at once hung for hours. CI
    // passes both from the environment; locally the plugin defaults stay.
    System.getenv("NVD_API_KEY")?.takeIf { it.isNotBlank() }?.let { nvd.apiKey.set(it) }
    System.getenv("DEPENDENCY_CHECK_DATA")?.takeIf { it.isNotBlank() }?.let { data.directory.set(it) }
}

// --- JNI core: build libtiredvpn.so only from a core checkout someone named ---
//
// The .so carries the whole VPN, so which core went into an APK has to be a
// decision, not an accident. This task used to fall back to ../tiredvpn and
// then to /tmp/tiredvpn-core, a clone build-jni.sh made once and never
// updated; a .so copied in by hand without a stamp was silently replaced from
// there. Debug APKs shipped a core nobody had chosen, twice, and the people
// testing them found out from behaviour, not from the build.
//
// Now the core comes from -PtiredvpnCoreDir=/path/to/tiredvpn (also accepted
// from ~/.gradle/gradle.properties), or from a jniLibs directory whose
// .core-revision stamp, written by scripts/build-jni.sh, still matches every
// .so byte for byte. Anything else stops the packaging with a message saying
// what to do. Unit tests and lint do not package native code and are not
// affected.

val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs")

/** Written by scripts/build-jni.sh next to the libraries it built. */
val jniRevisionStamp = jniLibsDir.file(".core-revision").asFile

val jniAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

/**
 * Identify the core checkout in [dir], or null when it cannot be identified.
 * Same format as scripts/build-jni.sh writes into the stamp: a dirty tree is
 * recorded as such, so uncommitted edits are visible after the fact.
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

fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** key=value lines; a stamp from before the checksums has none and is not trusted. */
fun readCoreStamp(file: File): Map<String, String>? {
    if (!file.isFile) return null
    return file.readLines()
        .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 } }
        .associate { (k, v) -> k.trim() to v.trim() }
}

/**
 * Why the libraries in jniLibs cannot be trusted, or null when the stamp
 * matches every one of them.
 */
fun jniCoreProblem(): String? {
    val stamp = readCoreStamp(jniRevisionStamp)
        ?: return "no ${jniRevisionStamp.name} stamp in ${jniLibsDir.asFile}"
    if (stamp["revision"].isNullOrEmpty()) return "the stamp names no core revision (written by an older build-jni.sh?)"
    for (abi in jniAbis) {
        val so = jniLibsDir.dir(abi).file("libtiredvpn.so").asFile
        if (!so.isFile) return "$abi/libtiredvpn.so is missing"
        val recorded = stamp[abi] ?: return "the stamp has no checksum for $abi"
        if (sha256Of(so) != recorded) return "$abi/libtiredvpn.so is not the file the stamp describes (replaced by hand?)"
    }
    return null
}

val explicitCoreDir: String? = providers.gradleProperty("tiredvpnCoreDir").orNull

// Checked up front: a misspelt path must not degrade into "use what is there".
if (explicitCoreDir != null && coreRevision(File(explicitCoreDir)) == null) {
    throw GradleException("-PtiredvpnCoreDir=$explicitCoreDir is not a tiredvpn core checkout (no git HEAD, no VERSION file)")
}

val buildJni by tasks.registering(Exec::class) {
    description = "Build libtiredvpn.so from the core checkout named by -PtiredvpnCoreDir"
    group = "build"

    // Without a named checkout there is nothing this task may build from;
    // requireJniCore decides whether what is already there can be packaged.
    onlyIf {
        val dir = explicitCoreDir ?: return@onlyIf false
        val current = coreRevision(File(dir)) ?: return@onlyIf false // rejected at configuration
        val problem = jniCoreProblem()
        val stamped = readCoreStamp(jniRevisionStamp)?.get("revision")
        when {
            problem != null -> {
                logger.lifecycle("buildJni: rebuilding from $current: $problem")
                true
            }
            stamped != current -> {
                logger.lifecycle("buildJni: core moved since the .so was built ($stamped -> $current), rebuilding")
                true
            }
            else -> {
                logger.lifecycle("buildJni: libtiredvpn.so is current ($current)")
                false
            }
        }
    }

    workingDir = rootProject.layout.projectDirectory.asFile
    commandLine("bash", "scripts/build-jni.sh", "--core-dir", explicitCoreDir ?: "",
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

        logger.lifecycle("buildJni: coreDir=$explicitCoreDir, ndkHome=$ndkHome")
        environment("ANDROID_NDK_HOME", ndkHome)
    }
}

/**
 * Put the core revision into the APK as assets/core-revision.txt, so the
 * question "which core is in this build" has an answer after the fact.
 *
 * Never fails: unit tests merge assets too and do not need a core. A build
 * without a verified core gets "unverified: <reason>" here, and
 * requireJniCore stops it before anything native is packaged.
 */
abstract class CoreRevisionAsset : DefaultTask() {
    @get:OutputDirectory
    abstract val assetDir: DirectoryProperty

    @get:Internal
    abstract val content: Property<String>

    @TaskAction
    fun write() {
        val out = assetDir.get().asFile
        out.mkdirs()
        out.resolve("core-revision.txt").writeText(content.get())
    }
}

val coreRevisionAsset = tasks.register<CoreRevisionAsset>("coreRevisionAsset") {
    dependsOn(buildJni)
    outputs.upToDateWhen { false }
    assetDir.set(layout.buildDirectory.dir("generated/coreRevision/assets"))
    content.set(provider {
        val problem = jniCoreProblem()
        if (problem != null) {
            "unverified: $problem\n"
        } else {
            val stamp = readCoreStamp(jniRevisionStamp).orEmpty()
            "revision=${stamp["revision"]}\nversion=${stamp["version"] ?: "unknown"}\n"
        }
    })
}

val requireJniCore by tasks.registering {
    description = "Refuse to package native code from a core nobody named"
    group = "verification"
    dependsOn(buildJni)
    outputs.upToDateWhen { false }
    doLast {
        val problem = jniCoreProblem()
        if (problem != null) {
            throw GradleException(
                "\n" + "=".repeat(78) + "\n" +
                    "No verified Go core to package: $problem.\n" +
                    "\n" +
                    "Name the core checkout to build it from:\n" +
                    "  ./gradlew assembleDebug -PtiredvpnCoreDir=/path/to/tiredvpn\n" +
                    "or build the libraries yourself, which also writes the stamp:\n" +
                    "  ./scripts/build-jni.sh --core-dir /path/to/tiredvpn\n" +
                    "Copying a libtiredvpn.so into app/src/main/jniLibs by hand is not enough.\n" +
                    "=".repeat(78)
            )
        }
        val stamp = readCoreStamp(jniRevisionStamp).orEmpty()
        logger.lifecycle("requireJniCore: packaging core ${stamp["revision"]} (${stamp["version"] ?: "unknown version"})")
    }
}

// Native libraries are merged by merge<Variant>NativeLibs; that is the step a
// core has to be verified for, and the only one.
tasks.matching { it.name.matches(Regex("merge\\w*NativeLibs")) }.configureEach {
    dependsOn(requireJniCore)
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
// "build" is in the list on its own: it is an aggregate whose literal name
// mentions neither assemble nor Release, and it pulls assembleRelease in as a
// dependency all the same. Without it the one command a developer is most
// likely to type by hand is the one command that skips the check.
val buildingReleaseArtifact = gradle.startParameter.taskNames.any { requested ->
    val task = requested.substringAfterLast(':')
    task == "build" || (
        task.contains("Release") &&
            listOf("assemble", "bundle", "install", "package", "publish").any { task.startsWith(it) }
        )
}

android {
    namespace = "com.tiredvpn.android"
    compileSdk = 37  // required by androidx.core 1.19.0 AAR metadata

    defaultConfig {
        applicationId = "com.tiredvpn.android"
        minSdk = 24
        targetSdk = 37  // Android 16 QPR
        versionCode = 24
        versionName = "1.11.0"

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
        variant.sources.assets?.addGeneratedSourceDirectory(coreRevisionAsset, CoreRevisionAsset::assetDir)
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

    // ML Kit Barcode Scanning - DISABLED for 16KB page support on recent Android devices
    // implementation("com.google.mlkit:barcode-scanning:17.2.0")

    // CameraX - DISABLED for 16KB page support on recent Android devices
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
