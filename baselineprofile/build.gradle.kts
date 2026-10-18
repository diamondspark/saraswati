/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 11 — Macrobenchmark + Baseline Profile module. com.android.test
// (not com.android.library): these are instrumented tests that run against
// an already-installed :app APK on a real device, not library code the app
// links against. Mirrors :app's single "variant" flavor dimension with only
// the "gms" flavor declared (the shipped flavor; the design spec's
// benchmarks are "Pixel 8 Pro" numbers, not per-distribution-channel ones),
// so this module never has to resolve a matching benchmark variant for
// foss/izzy. No separate Kotlin plugin is applied here, matching every
// other Android module in this repo (app/build.gradle.kts,
// innertube/build.gradle.kts): AGP's own `kotlin {}` DSL extension covers
// compilation.
plugins {
    id("com.android.test")
    alias(libs.plugins.baseline.profile)
}

android {
    namespace = "com.metrolist.music.baselineprofile"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    flavorDimensions += "variant"
    productFlavors {
        create("gms") {
            dimension = "variant"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
    }
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}

// This module's own baselineProfile {} options (useConnectedDevices,
// managedDevices) are left at their defaults: useConnectedDevices = true
// is exactly what's needed to run against the real, connected Pixel. The
// output-location/merge options (mergeIntoMain, baselineProfileOutputDir,
// automaticGenerationDuringBuild) belong to the *consumer* extension, in
// app/build.gradle.kts, not here.
