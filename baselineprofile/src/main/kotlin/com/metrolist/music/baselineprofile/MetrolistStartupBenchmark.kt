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

// SARASWATI: Task 13 — the same cold-start measurement as StartupBenchmark, run against Metrolist 13.6.3 (the competitor
// the design spec's "at least 20% faster" budget is measured against). Needs that build installed on the same device.
private const val COMPETITOR_PACKAGE = "com.metrolist.music"
private const val MEASUREMENT_ITERATIONS = 10

@LargeTest
@RunWith(AndroidJUnit4::class)
class MetrolistStartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun startupCompilationNone() = startup(CompilationMode.None())

    @Test
    fun startupCompilationBaselineProfile() = startup(CompilationMode.Partial())

    private fun startup(compilationMode: CompilationMode) =
        benchmarkRule.measureRepeated(
            packageName = COMPETITOR_PACKAGE,
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
