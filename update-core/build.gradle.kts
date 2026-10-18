/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 4 — pure Kotlin/JVM module, deliberately with no Android
// dependency, so it can be unit-tested with plain JUnit4 (no Robolectric/
// emulator needed) and reused as-is by the separate Saraswati Updates
// installer app (Task 5) without dragging this app's Android surface along.
//
// The Kotlin Gradle plugin is already on the root build's buildscript
// classpath (see build.gradle.kts), the same way "com.android.application"/
// "com.android.library" are applied elsewhere in this repo without a
// version — so this is applied by id, with no version, for the same reason:
// specifying one here conflicts with the unversioned classpath copy.
plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()

    // Tests read the golden manifest fixture from security/fixtures/ at the
    // repo root (shared with the Python security gates), not from this
    // module's own test resources. The JVM test task's working directory is
    // this module's directory, not the repo root, so the root is handed
    // over explicitly rather than guessed from a relative "../../" path.
    systemProperty("saraswati.repoRoot", rootProject.projectDir.absolutePath)
}
