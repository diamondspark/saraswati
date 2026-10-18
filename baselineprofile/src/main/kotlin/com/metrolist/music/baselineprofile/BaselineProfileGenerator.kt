/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TARGET_PACKAGE_NAME = "dev.diamondspark.saraswati"
private const val UI_TIMEOUT_MS = 10_000L

/**
 * Generates app/src/main/baseline-prof.txt (Task 11 brief, Step 3):
 * `sar_gradle :app:generateGmsBaselineProfile`.
 *
 * Exercises the same critical journey the three benchmark classes measure
 * separately — launch, scroll Home, open search, open a Home item, play,
 * open and close the player — so the committed profile covers every path
 * those benchmarks exercise, not just app startup.
 *
 * Never runs as a side effect of a normal build
 * (baselineprofile/build.gradle.kts: `automaticGenerationDuringBuild =
 * false`) — only on demand, against the real Pixel.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() = baselineProfileRule.collect(
        packageName = TARGET_PACKAGE_NAME,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()

        // Scroll Home.
        val x = device.displayWidth / 2
        val topY = (device.displayHeight * 0.8).toInt()
        val bottomY = (device.displayHeight * 0.2).toInt()
        repeat(6) { i ->
            if (i % 2 == 0) {
                device.swipe(x, topY, x, bottomY, 30)
            } else {
                device.swipe(x, bottomY, x, topY, 30)
            }
            device.waitForIdle()
        }

        // Open search, then back to Home.
        device.wait(Until.hasObject(By.text("Search")), UI_TIMEOUT_MS)
        device.findObject(By.text("Search"))?.click()
        device.waitForIdle()
        device.pressBack()
        device.wait(Until.hasObject(By.text("Home")), UI_TIMEOUT_MS)

        // Open a Home item, play, open and close the player. Coordinate
        // taps, not a resource lookup — see OpenPlayerBenchmark's doc
        // comment for why.
        device.click(device.displayWidth / 2, (device.displayHeight * 0.35).toInt())
        device.waitForIdle()
        Thread.sleep(1_000)
        device.click(device.displayWidth / 2, (device.displayHeight * 0.92).toInt())
        device.waitForIdle()
        Thread.sleep(500)
        device.pressBack()
        device.waitForIdle()
    }
}
