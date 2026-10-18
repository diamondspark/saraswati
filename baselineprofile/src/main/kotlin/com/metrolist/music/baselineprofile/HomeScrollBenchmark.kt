/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TARGET_PACKAGE_NAME = "dev.diamondspark.saraswati"
private const val MEASUREMENT_ITERATIONS = 10
private const val SWIPE_COUNT = 10
private const val SWIPE_STEPS = 30

/**
 * Jank on the Home feed against the design spec's budget
 * (docs/perf-baseline.md: "Scroll jank on Home and Library" — <1% janky
 * frames).
 *
 * No composable in this app sets `Modifier.testTag` (none exist anywhere in
 * app/src/main/kotlin), and adding one is outside this task's file scope
 * (app/build.gradle.kts is "plugin and dependency lines only" — Task 11
 * brief). So, like scripts/perf-baseline.sh's own jank measurement (Task
 * 13), this drives a coordinate-based swipe gesture instead of a
 * resource-id lookup; `scripts/perf-baseline.sh`'s `scripted_swipes` uses
 * the same up/down-from-80%/20%-of-screen-height pattern.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class HomeScrollBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun scrollCompilationNone() = scrollHome(CompilationMode.None())

    @Test
    fun scrollCompilationBaselineProfile() = scrollHome(CompilationMode.Partial())

    private fun scrollHome(compilationMode: CompilationMode) =
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = compilationMode,
            startupMode = StartupMode.WARM,
            iterations = MEASUREMENT_ITERATIONS,
            setupBlock = {
                pressHome()
                startActivityAndWait()
            },
        ) {
            val x = device.displayWidth / 2
            val topY = (device.displayHeight * 0.8).toInt()
            val bottomY = (device.displayHeight * 0.2).toInt()
            repeat(SWIPE_COUNT) { i ->
                if (i % 2 == 0) {
                    device.swipe(x, topY, x, bottomY, SWIPE_STEPS)
                } else {
                    device.swipe(x, bottomY, x, topY, SWIPE_STEPS)
                }
                device.waitForIdle()
            }
        }
}
