import unittest

from perf_stats import (
    median,
    p90,
    parse_am_start_total_time,
    parse_gfxinfo_janky_percent,
)

# Captured-sample text used verbatim by the brief (task-13-brief.md Step 1):
#   `TotalTime: 545` -> 545
#   a gfxinfo block with `Janky frames: 12 (4.80%)` -> 4.8
#   median([3, 1, 2]) == 2
#   p90 of 1..10 == 9
AM_START_SAMPLE = """\
Starting: Intent { act=android.intent.action.MAIN \
cat=[android.intent.category.LAUNCHER] \
cmp=dev.diamondspark.saraswati/com.metrolist.music.MainActivity }
Status: ok
LaunchState: COLD
Activity: dev.diamondspark.saraswati/com.metrolist.music.MainActivity
TotalTime: 545
WaitTime: 571
Complete
"""

GFXINFO_SAMPLE = """\
Applications Graphics Acceleration Info:
Uptime: 1234567 Realtime: 1234567

** Graphics info for pid 12345 [dev.diamondspark.saraswati] **

Stats since: 752982706758785ns
Total frames rendered: 318
Janky frames: 12 (4.80%)
50th percentile: 5ms
90th percentile: 9ms
95th percentile: 10ms
99th percentile: 25ms
Number Missed Vsync: 0
Number High input latency: 0
Number Slow UI thread: 3
Number Slow bitmap uploads: 0
Number Slow issue draw commands: 2
Number Frame deadline missed: 12
"""


class ParseAmStartTotalTimeTest(unittest.TestCase):
    def test_total_time_545(self) -> None:
        self.assertEqual(parse_am_start_total_time(AM_START_SAMPLE), 545)

    def test_missing_total_time_raises(self) -> None:
        with self.assertRaises(ValueError):
            parse_am_start_total_time("Status: ok\nComplete\n")

    def test_falls_back_to_wait_time_when_total_time_is_missing(self) -> None:
        # Android 17 on the Pixel prints no TotalTime line, only WaitTime.
        out = (
            "Status: ok\n"
            "LaunchState: COLD\n"
            "Activity: dev.diamondspark.saraswati/com.metrolist.music.MainActivity\n"
            "WaitTime: 612\n"
            "Complete\n"
        )
        self.assertEqual(parse_am_start_total_time(out), 612)


class ParseGfxinfoJankyPercentTest(unittest.TestCase):
    def test_janky_frames_4_80_percent(self) -> None:
        self.assertEqual(parse_gfxinfo_janky_percent(GFXINFO_SAMPLE), 4.8)

    def test_missing_janky_line_raises(self) -> None:
        with self.assertRaises(ValueError):
            parse_gfxinfo_janky_percent("Total frames rendered: 318\n")


class MedianTest(unittest.TestCase):
    def test_median_of_3_1_2_is_2(self) -> None:
        self.assertEqual(median([3, 1, 2]), 2)

    def test_median_of_even_length_averages_middle_two(self) -> None:
        self.assertEqual(median([1, 2, 3, 4]), 2.5)

    def test_median_of_empty_raises(self) -> None:
        with self.assertRaises(ValueError):
            median([])


class P90Test(unittest.TestCase):
    def test_p90_of_1_to_10_is_9(self) -> None:
        self.assertEqual(p90(list(range(1, 11))), 9)

    def test_p90_of_empty_raises(self) -> None:
        with self.assertRaises(ValueError):
            p90([])


if __name__ == "__main__":
    unittest.main()
