/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 5 — the Saraswati Updates installer app. Deliberately
// tiny: no Compose, no Hilt, no Room, nothing beyond plain Android views and
// OkHttp for the download. This is the ONE app on a family phone ever
// allowed to hold REQUEST_INSTALL_PACKAGES (see
// security/updater-allowlist.json and the Global Constraints in the phase 2
// plan), so its own dependency surface is kept as small as the task allows.
import java.security.KeyStore
import java.security.MessageDigest

plugins {
    id("com.android.application")
}

// Mirrors :app's signing-config plumbing (same env var names) because both
// apps are signed with the SAME release key (alias "saraswati") per the
// Global Constraints — the release workflow (Task 6) sets these once and
// both APKs pick them up.
val debugKeystorePathOverride = System.getenv("METROLIST_DEBUG_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
val debugKeystorePassword = System.getenv("METROLIST_DEBUG_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() } ?: "android"
val debugKeyAlias = System.getenv("METROLIST_DEBUG_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "androiddebugkey"
val debugKeyPassword = System.getenv("METROLIST_DEBUG_KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: "android"
val persistentDebugKeystoreFile = rootProject.file("app/persistent-debug.keystore")
val workflowDebugKeystoreFile = debugKeystorePathOverride?.let(::file)

// SARASWATI: Task 5 — security/release-cert.sha256 is Task 6's output (the
// real signing ceremony hasn't run yet). Read it if present, otherwise an
// empty string: a release build with no pinned cert trusts nothing (fails
// closed, never silently accepts an unsigned/unexpected signer) rather than
// failing the Gradle configuration outright, so this module can be built
// and tested well before Task 6 lands.
val releaseCertShaFile = rootProject.file("security/release-cert.sha256")
val releaseCertSha256 = if (releaseCertShaFile.isFile) {
    releaseCertShaFile.readText().trim()
} else {
    ""
}

// SARASWATI: Task 5 — debug builds additionally pin the SHA-256 of whatever
// certificate signs THIS machine's ~/.android/debug.keystore, so a locally
// built debug APK can still install itself without security/release-cert.sha256
// existing yet. Modern `keytool` defaults to a PKCS12 keystore, but a
// machine that generated its debug.keystore with an older JDK may have a
// legacy JKS one, so both types are tried.
fun debugKeystoreCertSha256(): String? {
    val keystoreFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
    if (!keystoreFile.isFile) return null
    for (storeType in listOf("PKCS12", "JKS")) {
        try {
            val keyStore = KeyStore.getInstance(storeType)
            keystoreFile.inputStream().use { keyStore.load(it, "android".toCharArray()) }
            val alias = keyStore.aliases().asSequence().firstOrNull() ?: continue
            val certificate = keyStore.getCertificate(alias) ?: continue
            val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            return digest.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            // Wrong store type (or any other read failure) for this attempt — try the next one.
        }
    }
    return null
}

val saraswatiVersionCode = providers.exec {
    commandLine("python3", "-I", rootProject.file("scripts/version.py").absolutePath)
}.standardOutput.asText.get().trim().toInt()

android {
    namespace = "dev.diamondspark.saraswati.updater"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.diamondspark.saraswati.updater"
        minSdk = 26
        targetSdk = 36
        versionCode = saraswatiVersionCode
        versionName = "1.$saraswatiVersionCode"
    }

    signingConfigs {
        create("persistentDebug") {
            storeFile = persistentDebugKeystoreFile
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("workflowDebug") {
            storeFile = workflowDebugKeystoreFile ?: persistentDebugKeystoreFile
            storePassword = debugKeystorePassword
            keyAlias = debugKeyAlias
            keyPassword = debugKeyPassword
        }
        create("release") {
            storeFile = rootProject.file("app/keystore/release.keystore")
            storePassword = System.getenv("STORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
        getByName("debug") {
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storePassword = "android"
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // SARASWATI: release never trusts the local debug cert — only
            // the committed, publicly-known release-signer digest.
            buildConfigField("String", "PINNED_CERT_SHA256", "\"$releaseCertSha256\"")
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            resValue("string", "app_name", "Saraswati Updates Debug")
            signingConfig = if (workflowDebugKeystoreFile != null) {
                signingConfigs.getByName("workflowDebug")
            } else if (persistentDebugKeystoreFile.isFile) {
                signingConfigs.getByName("persistentDebug")
            } else {
                signingConfigs.getByName("debug")
            }
            val debugCertSha256 = debugKeystoreCertSha256()
            val pinnedDebug = listOfNotNull(
                releaseCertSha256.takeIf { it.isNotEmpty() },
                debugCertSha256,
            ).joinToString(",")
            buildConfigField("String", "PINNED_CERT_SHA256", "\"$pinnedDebug\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
    }

    buildFeatures {
        buildConfig = true
        resValues = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":update-core"))
    implementation(libs.okhttp)

    testImplementation(libs.junit)
}
