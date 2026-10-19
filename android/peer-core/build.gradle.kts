plugins { `java-library` }

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

dependencies {
    // Android platform supplies org.json at runtime; never package a competing JSON implementation.
    compileOnly("org.json:json:20240303")
}

// Pure Java business and signaling protocol. No Android SDK, networking, or Relay credentials.
