/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// SARASWATI: Task 11 — must match :app's baseApplicationId
// (app/build.gradle.kts). Hardcoded rather than read from BuildConfig: this
// module targets :app's installed APK via instrumentation, it doesn't
// compile against :app's code.
private const val TARGET_PACKAGE_NAME = "dev.diamondspark.saraswati"

// Matches scripts/perf-baseline.sh's own run count (Task 13), so the two
// tools' medians/p90s are comparable at a glance.
private const val MEASUREMENT_ITERATIONS = 10

/**
 * Cold-start timing against the design spec's budget (docs/perf-baseline.md:
 * "Cold start to cached Home" — >=20% faster than Metrolist 13.6.3, <=1.0s).
 *
 * Two compilation modes, per the Task 11 brief:
 *  - [CompilationMode.None]: a freshly sideloaded install whose baseline
 *    profile hasn't been compiled yet (profileinstaller only compiles it
 *    later, on an idle+charging background job).
 *  - [CompilationMode.Partial]: the steady state once that profile has been
 *    compiled from the committed app/src/main/baseline-prof.txt.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun startupCompilationNone() = startup(CompilationMode.None())

    @Test
    fun startupCompilationBaselineProfile() = startup(CompilationMode.Partial())

    private fun startup(compilationMode: CompilationMode) =
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE_NAME,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = compilationMode,
            startupMode = StartupMode.COLD,
            iterations = MEASUREMENT_ITERATIONS,
            setupBlock = {
                pressHome()
            },
        ) {
            startActivityAndWait()
        }
}
