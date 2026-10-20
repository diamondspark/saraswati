/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// SARASWATI: Task 13 — tap-to-audio against the speed budget (docs/perf-baseline.md). The video ids come from the
// instrumentation argument sarVideoIds at run time and are never committed, so the owner picks them per run:
//   -P sarVideoIds=<10 comma-separated ids>   (Gradle property, see docs/perf-baseline.md)
private const val TARGET_PACKAGE_NAME = "dev.diamondspark.saraswati.benchmark"
private const val VIDEO_IDS_ARGUMENT = "sarVideoIds"
private const val VIDEO_COUNT = 10

// Long enough for the first audio of a song to start after the tap, short enough to keep a 10-song pass quick.
private const val TAP_SETTLE_MS = 8_000L

private const val NETWORK_SECTION = "SarTapToAudioNetwork"
private const val OFFLINE_SECTION = "SarTapToAudioOffline"

/**
 * Pass 1 (network) and pass 2 (offline) over the same ten songs. Each iteration opens one song with a VIEW intent on
 * its music.youtube.com link and waits for it to start, so the Task 12 section is the tap-to-audio time. Pass 2 needs
 * the ten songs already downloaded on the device; without that its sections never appear and it measures nothing.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class TapToAudioBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun tapToAudioNetwork() = tapToAudio(NETWORK_SECTION)

    // Known limit: the player cache (files/exoplayer) fills during pass 1 and is not cleared before this pass, so a
    // tap of a song pass 1 already played is classed offline by the cache, not by a download. Clearing it needs a
    // debuggable build (run-as), and pm clear would also drop the downloads this pass needs.
    @Test
    fun tapToAudioOffline() = tapToAudio(OFFLINE_SECTION)

    // TraceSectionMetric is marked experimental in the Macrobenchmark API; it is the metric this benchmark exists for.
    @OptIn(ExperimentalMetricApi::class)
    private fun tapToAudio(section: String) {
        val ids = videoIds()
        var iteration = 0
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE_NAME,
            metrics = listOf(TraceSectionMetric(section, TraceSectionMetric.Mode.First)),
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.WARM,
            iterations = ids.size,
            setupBlock = {
                pressHome()
            },
        ) {
            val id = ids[iteration++ % ids.size]
            device.executeShellCommand(
                "am start -W -a android.intent.action.VIEW -d https://music.youtube.com/watch?v=$id -p $TARGET_PACKAGE_NAME",
            )
            Thread.sleep(TAP_SETTLE_MS)
        }
    }

    private fun videoIds(): List<String> {
        val raw =
            InstrumentationRegistry.getArguments().getString(VIDEO_IDS_ARGUMENT)
                ?: error("Pass -P $VIDEO_IDS_ARGUMENT=<$VIDEO_COUNT comma-separated video ids>; they are never committed")
        val ids = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        require(ids.size == VIDEO_COUNT) { "$VIDEO_IDS_ARGUMENT needs exactly $VIDEO_COUNT ids, got ${ids.size}" }
        return ids
    }
}
