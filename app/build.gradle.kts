import com.android.build.api.variant.BuildConfigField
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

val baseApplicationId = "dev.diamondspark.saraswati" // SARASWATI: fork identity, was "com.metrolist.music"
val applicationIdOverride = System.getenv("METROLIST_APPLICATION_ID")?.takeIf { it.isNotBlank() }
val appNameOverride = System.getenv("METROLIST_APP_NAME")?.takeIf { it.isNotBlank() }
val buildCommit =
    System.getenv("METROLIST_BUILD_COMMIT")
        ?.trim()
        ?.takeIf { it.matches(Regex("[0-9a-fA-F]{7,40}")) }
        ?.take(7)
        ?.lowercase()
val debugKeystorePathOverride = System.getenv("METROLIST_DEBUG_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
val debugKeystorePassword = System.getenv("METROLIST_DEBUG_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() } ?: "android"
val debugKeyAlias = System.getenv("METROLIST_DEBUG_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "androiddebugkey"
val debugKeyPassword = System.getenv("METROLIST_DEBUG_KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: "android"
val persistentDebugKeystoreFile = file("persistent-debug.keystore")
val workflowDebugKeystoreFile = debugKeystorePathOverride?.let(::file)

plugins {
    id("com.android.application")
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.protobuf)
    // SARASWATI: Task 11 (:baselineprofile) — consumer side; see
    // baselineProfile(project(":baselineprofile")) below.
    alias(libs.plugins.baseline.profile)
}

android {
    namespace = "com.metrolist.music"
    compileSdk = 37

    defaultConfig {
        applicationId = applicationIdOverride ?: baseApplicationId
        minSdk = 26
        targetSdk = 36
        versionCode = 153
        versionName = "13.7.0"
        val baseVersionName = requireNotNull(versionName)
        buildConfigField("String", "BASE_VERSION_NAME", "\"$baseVersionName\"")
        buildCommit?.let { versionName = "$baseVersionName+$it" }
        resValue("string", "app_name", appNameOverride ?: "Saraswati") // SARASWATI: fork identity, was "Metrolist"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }

        // SARASWATI: Last.fm keys and the Discord app id are gone with those integrations (removed, owner decision).
        buildConfigField("String", "ARCHITECTURE", "\"universal\"")
    }

    flavorDimensions += listOf("variant")
    productFlavors {
        // FOSS - Updater, but no gcast
        create("foss") {
            dimension = "variant"
            isDefault = true
            buildConfigField("Boolean", "CAST_AVAILABLE", "false")
            buildConfigField("Boolean", "UPDATER_AVAILABLE", "true")
        }

        // GMS - Updater and gcast
        create("gms") {
            dimension = "variant"
            buildConfigField("Boolean", "CAST_AVAILABLE", "true")
            buildConfigField("Boolean", "UPDATER_AVAILABLE", "true")
        }

        // IzzyOnDroid - no gcast, no updater - the ONLY F-droid compliant build
        create("izzy") {
            dimension = "variant"
            buildConfigField("Boolean", "CAST_AVAILABLE", "false")
            buildConfigField("Boolean", "UPDATER_AVAILABLE", "false")
        }
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
        // SARASWATI: unused by Saraswati's real release pipeline — no build
        // type references this config. Real signing happens via
        // scripts/sign_release.sh inside saraswati-release.yml's
        // environment-gated `sign` job (see docs/release-runbook.md). Never
        // wire this into a build type that runs before that approval gate,
        // or signing secrets would need to reach an unprotected job. Kept
        // only as pre-existing upstream structure.
        create("release") {
            storeFile = file("keystore/release.keystore")
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
            isCrunchPngs = false
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            if (applicationIdOverride == null) {
                applicationIdSuffix = ".debug"
            }
            isDebuggable = true
            if (appNameOverride == null) {
                resValue("string", "app_name", "Saraswati Debug") // SARASWATI: fork identity, was "Metrolist Debug"
            }
            signingConfig =
                if (workflowDebugKeystoreFile != null) {
                    signingConfigs.getByName("workflowDebug")
                } else if (persistentDebugKeystoreFile.exists()) {
                    signingConfigs.getByName("persistentDebug")
                } else {
                    signingConfigs.getByName("debug")
                }
        }

        // SARASWATI: Task 11 (:baselineprofile) — the androidx.baselineprofile
        // plugin auto-creates these two build types (initWith "release") if
        // they don't already exist; pre-creating them here only to give
        // them a separate package and a signing config, same reasoning for
        // both. "release" itself has no signingConfig (assemble*Release is
        // always unsigned — real signing happens in saraswati-release.yml's
        // `sign` job via scripts/sign_release.sh), so without an override
        // these would be unsigned and uninstallable; they're debug-key
        // signed instead. applicationIdSuffix ".benchmark" makes them
        // install as their own package, dev.diamondspark.saraswati.benchmark,
        // so a benchmark/profile-generation run coexists with whatever is
        // really installed on the device (a debug-signed build today, a
        // release-signed one once Task 8 ships) instead of needing to match
        // its signer — and AGP's connected-test runner uninstalling the
        // target app after each run only ever wipes this throwaway package,
        // never the real install's YouTube sign-in. The baselineprofile
        // module's TARGET_PACKAGE_NAME constants must match this suffix.
        // Every other property (isDebuggable, isProfileable,
        // isMinifyEnabled for nonMinifiedRelease, etc.) is enforced by the
        // plugin regardless of what's set here (androidx.baselineprofile
        // 1.2.4+).
        create("benchmarkRelease") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            applicationIdSuffix = ".benchmark"
            signingConfig = signingConfigs.getByName("debug")
        }
        create("nonMinifiedRelease") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            applicationIdSuffix = ".benchmark"
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        lintConfig = file("lint.xml")
        warningsAsErrors = false
        abortOnError = false
        checkDependencies = false
        // Lint never gated anything here (abortOnError = false), so the
        // lintVital pass that assembleRelease implicitly triggers was pure
        // build time. Run lint on demand with ./gradlew :app:lintGmsRelease.
        checkReleaseBuilds = false
    }

    androidResources {
        generateLocaleConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
            keepDebugSymbols +=
                listOf(
                    "**/libandroidx.graphics.path.so",
                    "**/libdatastore_shared_counter.so",
                )
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/CONTRIBUTORS.md"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }
}

// SARASWATI: Task 11 (:baselineprofile) — consumer-side configuration.
// mergeIntoMain is left at its default (false for apps): with it left
// false, :app keeps a per-flavor task name, `generateGmsBaselineProfile`
// (Task 11 brief, Step 3) — mergeIntoMain = true collapses that into a
// single, flavor-less `generateBaselineProfile` task instead. The
// generated file (src/gmsRelease/generated/baselineProfiles/baseline-prof.txt)
// is then moved by hand to the single committed app/src/main/baseline-prof.txt
// the brief names, which every flavor already inherits (gms is the only
// shipped flavor — see AGENTS.md — so foss/izzy never generate or need
// their own).
baselineProfile {
    // Never run as a side effect of an ordinary :app build — only on
    // demand against the real Pixel (Task 11 brief, Step 3).
    automaticGenerationDuringBuild = false
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                create("java") { option("lite") }
                create("kotlin") { option("lite") }
            }
        }
    }
}

val cleanLegacyProtoSources = tasks.register<Delete>("cleanLegacyProtoSources") {
    delete(layout.projectDirectory.dir("src/main/java/com/metrolist/music/listentogether/proto"))
}

tasks.named("preBuild") {
    dependsOn(cleanLegacyProtoSources)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
        )
        suppressWarnings.set(false)
    }
}

// Android provides org.json as a platform API (/apex/com.android.art/javalib/core-libart.jar).
// The standalone org.json:json artefact bundles an older Apache Harmony copy of JSONArray that
// contains an internal `myArrayList` field absent from the platform class.  Without obfuscation
// R8 inlines against this internal field; at runtime the platform class is resolved instead,
// producing a NoSuchFieldError.  Excluding the artefact globally ensures only the platform
// class is ever referenced.
configurations.configureEach {
    exclude(group = "org.json", module = "json")
}

dependencies {
    implementation(libs.guava)
    implementation(libs.coroutines.guava)

    implementation(libs.activity)
    implementation(libs.hilt.navigation)
    implementation(libs.datastore)

    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.util)
    implementation(libs.compose.animation)
    implementation(libs.compose.reorderable)

    implementation(libs.viewmodel.compose)
    implementation(libs.lifecycle.process)

    implementation(libs.material3)
    implementation(libs.palette)
    implementation(libs.materialKolor)

    implementation(libs.appcompat)

    implementation(libs.coil)
    implementation(libs.coil.network.okhttp)
    implementation(libs.browser)

    implementation(libs.ucrop)

    implementation(libs.shimmer)

    implementation(libs.media3)
    implementation(libs.media3.session)
    implementation(libs.media3.okhttp)

    // Google Cast - only included in GMS flavor (not available in F-Droid/FOSS builds)
    "gmsImplementation"(libs.media3.cast)
    "gmsImplementation"(libs.mediarouter)
    "gmsImplementation"(libs.cast.framework)

    // SARASWATI: Sangat push - FCM only (no BOM, no Analytics, no google-services plugin), gms flavor only.
    "gmsImplementation"(libs.firebase.messaging)

    implementation(libs.room.runtime)
    implementation(libs.kuromoji.ipadic)
    implementation(libs.tinypinyin)
    ksp(libs.room.compiler)

    implementation(libs.hilt)
    ksp(libs.hilt.compiler)

    implementation(project(":innertube"))

    // SARASWATI: Task 7 — the "Sudhaar" ("Update ready") Home banner reuses
    // update-core's already-tested manifest parsing and update-decision
    // rules (Task 4) instead of re-implementing them against the app's own
    // network stack.
    implementation(project(":update-core"))

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.encoding)
    implementation(libs.ktor.serialization.json)

    // SARASWATI: Task 6 (HubClient) — parses the family hub Worker's JSON with
    // kotlinx.serialization directly. It already resolves transitively
    // (Ktor), declared explicitly so HubClient does not depend on Ktor
    // happening to expose it. OkHttp is deliberately NOT declared here:
    // the app already imports okhttp3 through Coil/Ktor/Media3, and a direct
    // implementation(libs.okhttp) changes what the generateLocaleConfig
    // classpath resolves to, pulling in an unverified
    // org.jetbrains.compose.annotation-internal 1.8.0 .module.
    implementation(libs.kotlinx.serialization.json)
    // SARASWATI: Task 7 — renders the owner's pairing link as a QR code
    // (QrCode.kt). Encoding only; no camera or scanning code is added.
    implementation(libs.zxing.core)

    // Protobuf for message serialization (lite version for Android)
    implementation(libs.protobuf.javalite)
    implementation(libs.protobuf.kotlin.lite)

    coreLibraryDesugaring(libs.desugaring)

    implementation(libs.timber)

    // SARASWATI: Task 11 (:baselineprofile) — profileinstaller reads and
    // locally compiles the committed app/src/main/baseline-prof.txt on
    // first launch (no Play Store cloud profile delivery for a sideloaded
    // app). baselineProfile(...) wires the generated-profile dependency
    // that drives the plugin's generate*BaselineProfile tasks.
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.ktor.client.mock)
    // SARASWATI: security-ask-explanation (branch audit) — a real Compose UI test (AskVoiceExplanationUiTest)
    // for the scrim that must consume its own taps: every other Saral UI check so far is source-level regex
    // (SaralIsolationTest) or ViewModel-only, which is exactly how a non-consuming scrim slipped through review.
    // Robolectric 4.17 renders Compose content without needing the instrumentation (androidTest) runtime, so
    // this stays a JVM unit test: createEmptyComposeRule() + Robolectric.setupActivity(ComponentActivity) (not
    // createComposeRule(), whose own Activity launch Robolectric cannot resolve here).
    testImplementation("androidx.compose.ui:ui-test-junit4:${libs.versions.compose.get()}")
    // SARASWATI: Task 6 — HubClientTest runs HubClient against a real local
    // HTTP server instead of a mocked OkHttp Call.
    testImplementation(libs.okhttp.mockwebserver)

    // SARASWATI: Task 12 instrumented proof that the network security
    // config's deny-by-default egress allowlist is enforced on-device
    // (EgressPolicyInstrumentedTest).
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

// SARASWATI: everything below overrides the upstream android {} block above
// by reassignment rather than editing its lines in place (see
// docs/superpowers/plans/2026-10-17-saraswati-phase1-fork-harden.md, Task 3):
//  - versionCode comes from the real git commit count (scripts/version.py)
//    instead of upstream's hand-bumped counter, so it only ever increases
//    and a shallow clone (tiny commit count) fails the build instead of
//    silently producing a versionCode Android would reject as a downgrade.
//  - the shipped ABI is arm64-v8a only: every family phone is 2022+.
//  - BuildConfig.ARCHITECTURE reflects that for the About screen / crash
//    reports, which already read it.
//  - the gms flavor reports no in-app updater (Task 2's updater pipeline
//    replaces it in Phase 2); Cast support (CAST_AVAILABLE) is untouched.
//  - the Last.fm and Discord integrations are removed outright: no keys, no
//    app id, no code.
val saraswatiVersionCode = providers.exec {
    commandLine("python3", "-I", rootProject.file("scripts/version.py").absolutePath)
}.standardOutput.asText.get().trim().toInt()

android {
    defaultConfig {
        versionCode = saraswatiVersionCode
        versionName = "${versionName}-s$versionCode"

        ndk {
            abiFilters.clear()
            abiFilters += "arm64-v8a"
        }

        buildConfigField("String", "ARCHITECTURE", "\"arm64\"")
    }

    // SARASWATI: Task 6 — base URL of the family hub Worker (HubClient).
    // The URL's host must also be allowlisted in network_security_config.xml
    // and security/egress-allowlist.json. foss/izzy never ship (gms only),
    // so they get an empty URL: HubClient then fails every call with
    // "not configured" instead of talking to a hub nobody vetted for them.
    //
    // SARASWATI: Task 7 — the same host is the pairing link's host
    // (https://<host>/pair#<token>): app/src/gms/AndroidManifest.xml reads it
    // as ${hubHost}, so the link filter and HubClient can never disagree.
    val gmsHubBaseUrl = "https://saraswati-sangat.pandey-mohitk.workers.dev"
    productFlavors {
        getByName("foss") {
            buildConfigField("String", "HUB_BASE_URL", "\"\"")
            buildConfigField("String", "FIREBASE_PROJECT_ID", "\"\"")
            buildConfigField("String", "FIREBASE_SENDER_ID", "\"\"")
            buildConfigField("String", "FIREBASE_API_KEY", "\"\"")
            buildConfigField("String", "FIREBASE_APP_ID", "\"\"")
        }
        getByName("gms") {
            buildConfigField("Boolean", "UPDATER_AVAILABLE", "false")
            buildConfigField("String", "HUB_BASE_URL", "\"$gmsHubBaseUrl\"")
            manifestPlaceholders["hubHost"] = gmsHubBaseUrl.substringAfter("://").substringBefore("/")
        }
        getByName("izzy") {
            buildConfigField("String", "HUB_BASE_URL", "\"\"")
            buildConfigField("String", "FIREBASE_PROJECT_ID", "\"\"")
            buildConfigField("String", "FIREBASE_SENDER_ID", "\"\"")
            buildConfigField("String", "FIREBASE_API_KEY", "\"\"")
            buildConfigField("String", "FIREBASE_APP_ID", "\"\"")
        }
    }
}

// SARASWATI: Sangat push (FCM, gms flavor only) — the Firebase identifiers come from the checked-in
// app/firebase.properties (not secrets; every APK carries them). The app id differs per applicationId,
// so the debug build type reads appId.debug and every other build type appId.release. A missing file
// or key is an empty string, never a build failure: push then stays unconfigured. foss and izzy get
// empty strings above and carry no Firebase code.
val firebaseProperties =
    Properties().apply {
        val file = file("firebase.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }

androidComponents {
    onVariants(selector().withFlavor("variant" to "gms")) { variant ->
        val appIdKey = if (variant.buildType == "debug") "appId.debug" else "appId.release"
        mapOf(
            "FIREBASE_PROJECT_ID" to "projectId",
            "FIREBASE_SENDER_ID" to "senderId",
            "FIREBASE_API_KEY" to "apiKey",
            "FIREBASE_APP_ID" to appIdKey,
        ).forEach { (field, key) ->
            val value = firebaseProperties.getProperty(key, "").trim().replace("\\", "\\\\").replace("\"", "\\\"")
            variant.buildConfigFields?.put(field, BuildConfigField("String", "\"$value\"", null))
        }
    }
}

// SARASWATI: fix wave B3 — hands the strict mode of FestivalCalendarTest to the test JVM:
// -Psaraswati.strictFestivalTable=true makes "the festival table is running out" a failure instead of a skip.
// Only the weekly festival-table workflow and a deliberate manual run pass it; saraswati-ci and the release
// verify job never do (docs/release-runbook.md, "Before every release").
tasks.withType<Test>().configureEach {
    providers.gradleProperty("saraswati.strictFestivalTable").orNull?.let { systemProperty("saraswati.strictFestivalTable", it) }
}
