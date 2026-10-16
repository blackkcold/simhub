import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.util.Base64
import java.util.Locale
import java.util.UUID

plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" }

val releaseStoreFile = System.getenv("ANDROID_KEYSTORE_FILE")
val releaseStorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")
val hasReleaseSigning = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }

android {
    namespace = "com.blackkcold.simhub"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.blackkcold.simhub"
        minSdk = 29
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 53
        versionName = "0.17.3"
        buildConfigField("boolean", "P2P_PREVIEW", "false")
        buildConfigField("boolean", "STORE_CHANNEL", "false")
        buildConfigField("String", "RELEASE_CHANNEL", "\"stable\"")
    }
    buildFeatures { buildConfig = true; compose = true; aidl = true; resValues = true }
    if (providers.gradleProperty("p2pAndroidSandbox").orNull == "true") testBuildType = "p2pPreview"
    // Generated, non-source configuration asset. Absent unless a configuration mode
    // is requested, so a plain build never carries a center description.
    sourceSets["main"].assets.srcDir(File(layout.buildDirectory.get().asFile, "generated/centerConfig"))
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        create("p2pPreview") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".p2ptest"
            versionNameSuffix = "-p2p-preview.2"
            buildConfigField("boolean", "P2P_PREVIEW", "true")
            resValue("string", "app_name", "SIM Hub P2P Test")
            matchingFallbacks += listOf("debug")
        }
        create("storePreview") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".storetest"
            versionNameSuffix = "-store-preview.1"
            buildConfigField("boolean", "STORE_CHANNEL", "true")
            resValue("string", "app_name", "SIM Hub Store Test")
            matchingFallbacks += listOf("debug")
        }
        release {
            isMinifyEnabled = true
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("devRelease") {
            initWith(getByName("release"))
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev." + (providers.gradleProperty("simhubDevSequence").orNull ?: "1")
            buildConfigField("String", "RELEASE_CHANNEL", "\"dev\"")
            resValue("string", "app_name", "SIM Hub Dev")
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}


androidComponents {
    onVariants(selector().withBuildType("devRelease")) { variant ->
        val sequence = providers.gradleProperty("simhubDevSequence").orNull?.toIntOrNull() ?: 1
        require(sequence in 1..99999) { "Invalid Dev release sequence" }
        variant.outputs.forEach { output -> output.versionCode.set(53 * 100000 + sequence) }
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    // Data-only native WebRTC engine. Pinned exact version; no dynamic ranges.
    // Verified before adoption: Maven Central aar, BSD-3-Clause, webrtc-sdk/android.
    implementation("io.github.webrtc-sdk:android:150.7871.01")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.webkit:webkit:1.15.0")
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    // Align transitive Fragment API for ActivityResult; keep release Lint enabled.
    implementation("androidx.fragment:fragment:1.9.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.runtime:runtime-saveable")
    implementation("androidx.window:window:1.5.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

// ---------------------------------------------------------------------------
// Trusted center configuration asset.
//
// Modes:
//   -PcenterConfigFake=true         public CI: same generator/loader path, pins
//                                   derived from the checked-in test certificate.
//   -PcenterConfigPackaging=true    controlled packaging: real values arrive only
//                                   through Environment variables. Any missing or
//                                   invalid value fails the build with a fixed code
//                                   and never echoes the offending input.
//   (neither)                       no configuration asset at all.
//
// Sensitive values never enter -P arguments, source files or tracked resources.

fun centerConfigPin(pem: File): String {
    val certificate = CertificateFactory.getInstance("X.509").generateCertificate(pem.inputStream())
    val digest = MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
    return "sha256/" + Base64.getEncoder().encodeToString(digest)
}

fun centerConfigOrigin(raw: String?): String {
    if (raw == null) throw GradleException("CENTER_CONFIG_ORIGIN_MISSING")
    val uri = try { URI(raw.trim()) } catch (_: Exception) { throw GradleException("CENTER_CONFIG_ORIGIN_INVALID") }
    if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null ||
        uri.rawQuery != null || uri.rawFragment != null ||
        (uri.rawPath != null && uri.rawPath != "" && uri.rawPath != "/"))
        throw GradleException("CENTER_CONFIG_ORIGIN_INVALID")
    return URI("https", null, uri.host.lowercase(Locale.ROOT), uri.port, null, null, null).toASCIIString()
}

fun centerConfigPinText(raw: String?): String {
    if (raw == null || raw.isBlank()) throw GradleException("CENTER_CONFIG_PIN_MISSING")
    val value = raw.trim().removePrefix("sha256/")
    val decoded = try { Base64.getDecoder().decode(value) } catch (_: Exception) { throw GradleException("CENTER_CONFIG_PIN_INVALID") }
    if (decoded.size != 32) throw GradleException("CENTER_CONFIG_PIN_INVALID")
    return "sha256/" + Base64.getEncoder().encodeToString(decoded)
}

fun centerConfigJson(configId: String, nodeOrigin: String, nodePins: List<String>,
                    controllerOrigin: String, controllerPins: List<String>): String {
    fun pins(values: List<String>) = values.joinToString(",", "[", "]") { "\"" + it + "\"" }
    return buildString {
        append("{\n")
        append("  \"schemaVersion\": 1,\n")
        append("  \"configId\": \"").append(configId).append("\",\n")
        append("  \"node\": {\"origin\": \"").append(nodeOrigin).append("\", \"pins\": ").append(pins(nodePins)).append("},\n")
        append("  \"controller\": {\"origin\": \"").append(controllerOrigin).append("\", \"pins\": ").append(pins(controllerPins)).append("}\n")
        append("}\n")
    }
}

val generateCenterConfig = tasks.register("generateCenterConfig") {
    val outDir = layout.buildDirectory.dir("generated/centerConfig")
    val fake = providers.gradleProperty("centerConfigFake").orNull == "true"
    val packaging = providers.gradleProperty("centerConfigPackaging").orNull == "true"
    val nodeOrigin = providers.environmentVariable("P2P_NODE_ORIGIN").orNull
    val nodePinCurrent = providers.environmentVariable("P2P_NODE_PIN_CURRENT").orNull
    val nodePinBackup = providers.environmentVariable("P2P_NODE_PIN_BACKUP").orNull
    val controllerOrigin = providers.environmentVariable("P2P_CONTROLLER_ORIGIN").orNull
    val controllerPinCurrent = providers.environmentVariable("P2P_CONTROLLER_PIN_CURRENT").orNull
    val controllerPinBackup = providers.environmentVariable("P2P_CONTROLLER_PIN_BACKUP").orNull
    outputs.dir(outDir)
    inputs.property("fake", fake)
    inputs.property("packaging", packaging)
    // Never put trusted origins or SPKI pins into Gradle input fingerprints, task
    // histories or shared build-cache metadata. A configuration may change in any
    // of the six environment values between invocations; validating on every run
    // makes missing, duplicate and rotated pins fail closed, including after a
    // successful build of another configuration.
    outputs.upToDateWhen { false }
    doLast {
        val directory = outDir.get().asFile
        val file = File(directory, "center-config.json")
        val text = when {
            packaging -> {
                val nodeOriginText = centerConfigOrigin(nodeOrigin)
                val controllerOriginText = centerConfigOrigin(controllerOrigin)
                val nodePins = listOf(centerConfigPinText(nodePinCurrent), centerConfigPinText(nodePinBackup))
                val controllerPins = listOf(centerConfigPinText(controllerPinCurrent), centerConfigPinText(controllerPinBackup))
                if (nodePins[0] == nodePins[1] || controllerPins[0] == controllerPins[1])
                    throw GradleException("CENTER_CONFIG_PIN_DUPLICATE")
                if (nodeOriginText == controllerOriginText && nodePins != controllerPins)
                    throw GradleException("CENTER_CONFIG_SHARED_ORIGIN_PINS")
                centerConfigJson(UUID.randomUUID().toString().replace("-", ""),
                    nodeOriginText, nodePins, controllerOriginText, controllerPins)
            }
            fake -> {
                val testDir = file("src/test/resources/center-test")
                val pins = listOf("center-test-cert.pem", "center-test-backup-cert.pem")
                    .map { centerConfigPin(File(testDir, it)) }
                if (pins[0] == pins[1]) throw GradleException("CENTER_CONFIG_PIN_DUPLICATE")
                centerConfigJson("fakepublicci0000000000000000000000",
                    "https://node.relay.invalid", pins, "https://admin.relay.invalid", pins)
            }
            else -> null
        }
        if (text == null) {
            if (file.exists()) file.delete()
            return@doLast
        }
        directory.mkdirs()
        file.writeText(text)
        // Only a non-sensitive count leaves the task output.
        logger.lifecycle("CENTER_CONFIG_WRITTEN pins=" + (if (packaging) 4 else 2))
    }
}

tasks.matching { it.name.startsWith("preBuild") }.configureEach { dependsOn(generateCenterConfig) }
