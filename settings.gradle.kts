@file:Suppress("UnstableApiUsage")

val useMavenLocalInnerTubeX = providers.gradleProperty("useMavenLocalInnerTubeX").isPresent

// SARASWATI: supply-chain hardening (Task 9) — dropped the unconditional
// mavenLocal() below (upstream had it in pluginManagement unconditionally;
// anything a local plugin repo contributes here is an untrusted injection
// path on a shared/public fork). The dependencyResolutionManagement
// mavenLocal() path further down stays, but only when useMavenLocalInnerTubeX
// is explicitly passed.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        exclusiveContent {
            forRepository {
                if (useMavenLocalInnerTubeX) mavenLocal() else maven("https://jitpack.io")
            }
            filter {
                if (useMavenLocalInnerTubeX) {
                    includeModule("com.github.MetrolistGroup", "innertubex")
                    includeModule("com.github.MetrolistGroup", "innertubex-android")
                    includeModule("com.github.MetrolistGroup", "innertubex-desktop")
                } else {
                    includeGroup("com.github.MetrolistGroup.innertubex")
                }
            }
        }
        google()
        mavenCentral()
        // SARASWATI: supply-chain hardening (Task 9) — upstream's generic
        // JitPack entry was unfiltered (any group could resolve from it) and
        // the aliyun mirror below it was removed outright: it mirrors
        // mavenCentral/jitpack for faster downloads in mainland China and we
        // need neither the extra trust surface nor the speedup. Content
        // filter restricted to the groups a verification-metadata run showed
        // genuinely resolve from JitPack. InnerTubeX has its own
        // exclusiveContent filter above; everything else must come from
        // google()/mavenCentral().
        maven {
            setUrl("https://jitpack.io")
            content {
                includeGroup("com.github.yalantis")
                includeGroup("com.github.promeG")
                // tinypinyin's real engine module publishes under the
                // repo-qualified group, not the bare "com.github.promeG"
                // aggregator POM the app depends on directly (same JitPack
                // convention as InnerTubeX's own group above).
                includeGroup("com.github.promeG.tinypinyin")
            }
        }
    }
}

// F-Droid doesn't support foojay-resolver plugin
rootProject.name = "Metrolist"
include(":app")
include(":innertube")
// SARASWATI: Task 4 — new pure-Kotlin/JVM module (manifest schema, update
// decision, APK verification, download allowlisting) for the separate
// Saraswati Updates installer app landing in a later task.
include(":update-core")
// SARASWATI: Task 5 — the Saraswati Updates installer app, the only app on
// the family phones ever allowed to hold REQUEST_INSTALL_PACKAGES.
include(":updater")
// SARASWATI: Task 11 — Macrobenchmark + Baseline Profile module (com.android.test,
// targets :app's gms flavor). Never shipped; generates/measures
// app/src/main/baseline-prof.txt against the real Pixel.
include(":baselineprofile")
