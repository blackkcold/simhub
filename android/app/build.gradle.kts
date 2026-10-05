plugins { id("com.android.application") }

android {
    namespace = "com.blackkcold.simhub"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.blackkcold.simhub"
        minSdk = 29
        targetSdk = 37
        versionCode = 5
        versionName = "0.1.5"
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
