import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.androidx.baseline.profile)
}

// ============================================================================
// Release signing (Sprint D §6.1): keystore.properties at the repository root
// (local development, git-ignored) OR the standard CI environment variables
//   KEYSTORE_PATH / KEYSTORE_PASSWORD / KEYSTORE_ALIAS / KEYSTORE_KEY_PASSWORD
// When neither source is present the release build type keeps NO signing
// config, so `assembleRelease` still produces an unsigned APK and the R8
// regression gate runs everywhere without baked-in secrets.
// ============================================================================
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingProperty(key: String, env: String): String? =
    keystoreProperties.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: System.getenv(env)?.takeIf { it.isNotBlank() }

val releaseKeystorePath: String? = signingProperty("storeFile", "KEYSTORE_PATH")

// ============================================================================
// VersionCode automation (§6.2): derived from git commit count.
// Every commit on the main branch produces a unique, monotonically increasing
// integer — no manual bumps, no collisions, CI-compatible. The count is
// computed at configuration time via `git rev-list --count HEAD`.
// ============================================================================
val gitCommitCount: Int by lazy {
    val process = ProcessBuilder("git", "rev-list", "--count", "HEAD")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    val count = process.inputStream.bufferedReader().readText().trim().toIntOrNull()
    process.waitFor()
    count ?: 1
}

android {
    namespace = "com.anomalyco.opencode"
    compileSdk = 36

    defaultConfig {
        // Compliance: the PUBLISHED package id must live in a namespace this
        // project owns (Play identity/impersonation policy). The internal
        // `namespace`/source packages remain com.anomalyco.opencode for now;
        // a full source rename is tracked separately.
        applicationId = "com.hjinlabs.opencodeclient"
        minSdk = 26
        targetSdk = 36
        versionCode = gitCommitCount
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = rootProject.file(releaseKeystorePath)
                storePassword = signingProperty("storePassword", "KEYSTORE_PASSWORD")
                keyAlias = signingProperty("keyAlias", "KEYSTORE_ALIAS")
                keyPassword = signingProperty("keyPassword", "KEYSTORE_KEY_PASSWORD")
            }
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
            // Null when no keystore is configured -> unsigned release APK (CI R8 gate).
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true // BuildConfig.VERSION_NAME feeds the settings diagnostics card.
    }

    sourceSets {
        // Single source of truth for test fixtures shared by the JVM unit
        // tests and the instrumentation tests (SSE real-socket server).
        getByName("test") { java.srcDirs("src/sharedTest/java") }
        getByName("androidTest") { java.srcDirs("src/sharedTest/java") }
    }

    testOptions {
        unitTests {
            // android.util.Log is a silent no-op in JVM tests (Sprint L.1/L.2
            // debug logger and SSE breadcrumbs run under BuildConfig.DEBUG).
            isReturnDefaultValues = true
        }
    }

    lint {
        // Sprint D §5 quality gate: any lint ERROR (and any NEW issue) fails the
        // build locally and in CI; XML/HTML/text reports are uploaded as artifacts.
        abortOnError = true
        warningsAsErrors = false
        xmlReport = true
        htmlReport = true
        textReport = true
        checkReleaseBuilds = true
        // Documented product decisions, not regressions:
        disable += setOf(
            // Dependency freshness: the version catalog is the single upgrade
            // surface; AGP/Kotlin bumps are dedicated maintenance tasks.
            "GradleDependency",
            "NewerVersionAvailable",
            "AndroidGradlePluginVersion",
            // Cleartext HTTP is a deliberate, documented requirement of the
            // self-hosted LAN server model (network_security_config.xml,
            // NEW_ROADMAP §6.4). Bearer-token auth guards the API itself.
            "InsecureBaseConfiguration",
        )
    }
}

dependencies {
    // AndroidX core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    // Lifecycle
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Compose (versions come from the BOM)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Ktor client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.logging)

    // Kotlinx
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Secure storage
    implementation(libs.androidx.security.crypto)

    // Preferences persistence (P2-6): DataStore-backed plain UI preferences.
    implementation(libs.androidx.datastore.preferences)

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.ktor.client.mock)

    // Instrumentation tests (Sprint 1c.3): the device tier runs the shared
    // SSE engine spec over Android's real network stack. Minimal surface —
    // no Hilt/Espresso; the fixture is a plain loopback ServerSocket.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)

    // Baseline profile (P2-3): macrobenchmark generates the profile,
    // profileinstaller applies it at install time.
    implementation(libs.androidx.profileinstaller)
    androidTestImplementation(libs.androidx.benchmark.macro.junit4)
}
