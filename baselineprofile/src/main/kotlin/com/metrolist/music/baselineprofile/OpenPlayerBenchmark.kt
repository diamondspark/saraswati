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
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TARGET_PACKAGE_NAME = "dev.diamondspark.saraswati.benchmark"
private const val MEASUREMENT_ITERATIONS = 10
private const val UI_TIMEOUT_MS = 10_000L

/**
 * The "open search, open a playlist, play, open and close the player"
 * journey named in the Task 11 brief, as one FrameTimingMetric benchmark
 * (no `androidx.tracing` sections exist in the app to target with a
 * `TraceSectionMetric`, and adding them is outside this task's file scope).
 *
 * The bottom-navigation taps (Home/Search) use the item's label text — the
 * only stable lookup available, since the app's Compose tree has no
 * testTag/resource-id for UiAutomator to target (see
 * HomeScrollBenchmark's doc comment). Compose's Material3 `NavigationBarItem`
 * merges its icon+label into one clickable, named accessibility node, so
 * `By.text(label)` resolves to the clickable item itself.
 *
 * Picking a Home-feed item to play, and expanding/collapsing the player,
 * instead use coordinate taps: the feed is the signed-in account's real,
 * dynamic YouTube Music data, so there is no stable id or text to target,
 * and (same as above) adding one to the app is out of scope here. This is
 * this benchmark's known fragile step — see the Task 11 report.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class OpenPlayerBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun openSearchAndPlayer() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE_NAME,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = MEASUREMENT_ITERATIONS,
        setupBlock = {
            pressHome()
            startActivityAndWait()
        },
    ) {
        // 1. Open search, then back to Home.
        device.wait(Until.hasObject(By.text("Search")), UI_TIMEOUT_MS)
        device.findObject(By.text("Search"))?.click()
        device.waitForIdle()
        device.pressBack()
        device.wait(Until.hasObject(By.text("Home")), UI_TIMEOUT_MS)

        // 2. Tap a Home feed item to start playback (opens the mini player).
        device.click(device.displayWidth / 2, (device.displayHeight * 0.35).toInt())
        device.waitForIdle()
        Thread.sleep(1_000)

        // 3. Expand the mini player to the full player, then collapse it.
        device.click(device.displayWidth / 2, (device.displayHeight * 0.92).toInt())
        device.waitForIdle()
        Thread.sleep(500)
        device.pressBack()
        device.waitForIdle()
    }
}
