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
        versionCode = 51
        versionName = "0.17.1"
        buildConfigField("boolean", "P2P_PREVIEW", "false")
        buildConfigField("boolean", "STORE_CHANNEL", "false")
    }
    buildFeatures { buildConfig = true; compose = true; aidl = true; resValues = true }
    if (providers.gradleProperty("p2pAndroidSandbox").orNull == "true") testBuildType = "p2pPreview"
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
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}


dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
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
