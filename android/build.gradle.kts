plugins { id("com.android.application") version "9.4.1" apply false }

// AGP 9.x bundles Kotlin 2.2.10 by default; Compose group mapping only
// ships for newer Kotlin toolchains. Match the module Compose compiler.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}
