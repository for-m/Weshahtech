plugins {
    kotlin("jvm") version "2.0.0"
    id("com.google.devtools.ksp") version "2.0.0-1.0.21"
}

// Plain-JVM build of the REAL sources of :core, :router-api and :router-openwrt (everything except the
// Android Keystore class). It compiles and tests the Kotlin without an Android SDK, including the live
// contract tests against a running weshah-agent (set WESHAH_AGENT_PORT, see weshah-agent/test/target).
val repo = rootDir.resolve("../..")
sourceSets {
    main {
        kotlin.srcDir(repo.resolve("core/src/main/kotlin"))
        kotlin.srcDir(repo.resolve("router-api/src/main/kotlin"))
        kotlin.srcDir(repo.resolve("router-openwrt/src/main/kotlin"))
        kotlin.exclude("**/RouterCredentialManager.kt")
    }
    test {
        kotlin.srcDir(repo.resolve("core/src/test/kotlin"))
        kotlin.srcDir(repo.resolve("router-openwrt/src/test/kotlin"))
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.moshi:moshi:1.15.1")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
    ksp("com.squareup.moshi:moshi-kotlin-codegen:1.15.1")
    implementation("javax.inject:javax.inject:1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    System.getenv("WESHAH_AGENT_PORT")?.let { environment("WESHAH_AGENT_PORT", it) }
}
