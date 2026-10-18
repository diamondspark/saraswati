import unittest

from smoke_helpers import parse_playing_state

# Captured-sample text matching the stable AOSP `dumpsys media_session`
# shape: one block per session, each starting with an indented
# `package=<name>` line and, later in the same block,
# `state=PlaybackState {state=<int>, ...}` — the shape
# `android.media.session.PlaybackState.toString()` has printed since
# Lollipop, where `PlaybackState.STATE_PLAYING == 3` and
# `STATE_PAUSED == 2`. A real dump can list more than one session (one per
# app currently holding a MediaSession); blocks are delimited by the next
# `package=` line or end of text.

PLAYING_SAMPLE = """\
Sessions Stack - in priority order (top to bottom):
  dev.diamondspark.saraswati.debug/com.metrolist.music.MainActivity (userId=0)
    package=dev.diamondspark.saraswati.debug
    tag=null
    version=2
    launchIntent=Intent { act=android.intent.action.MAIN pkg=dev.diamondspark.saraswati.debug }
    mediaButtonReceiver=null
    active=true
    flags=ACTIVE
    rating type=0
    controllers: 1
    state=PlaybackState {state=3, position=12345, buffered position=12345, speed=1.0, updated=123456789, actions=558, custom actions=[], active item id=0, error=null}
    audioAttrs=AudioAttributes: usage=USAGE_MEDIA content=CONTENT_TYPE_MUSIC flags=0x0
"""

# A different app (not Saraswati) is PLAYING; Saraswati's own package does
# not appear in this dump at all (e.g. it hasn't been launched yet).
OTHER_PACKAGE_PLAYING_SAMPLE = """\
Sessions Stack - in priority order (top to bottom):
  com.google.android.apps.youtube.music/.MainActivity (userId=0)
    package=com.google.android.apps.youtube.music
    tag=null
    version=2
    launchIntent=Intent { act=android.intent.action.MAIN pkg=com.google.android.apps.youtube.music }
    mediaButtonReceiver=null
    active=true
    flags=ACTIVE
    rating type=0
    controllers: 1
    state=PlaybackState {state=3, position=500, buffered position=500, speed=1.0, updated=123456789, actions=558, custom actions=[], active item id=0, error=null}
    audioAttrs=AudioAttributes: usage=USAGE_MEDIA content=CONTENT_TYPE_MUSIC flags=0x0
"""

PAUSED_SAMPLE = """\
Sessions Stack - in priority order (top to bottom):
  dev.diamondspark.saraswati/com.metrolist.music.MainActivity (userId=0)
    package=dev.diamondspark.saraswati
    tag=null
    version=2
    launchIntent=Intent { act=android.intent.action.MAIN pkg=dev.diamondspark.saraswati }
    mediaButtonReceiver=null
    active=true
    flags=ACTIVE
    rating type=0
    controllers: 1
    state=PlaybackState {state=2, position=12345, buffered position=12345, speed=0.0, updated=123456789, actions=558, custom actions=[], active item id=0, error=null}
    audioAttrs=AudioAttributes: usage=USAGE_MEDIA content=CONTENT_TYPE_MUSIC flags=0x0
"""

# Two sessions in one dump: a third-party app is PLAYING and Saraswati's own
# session (listed second) is only PAUSED. Exercises the block-boundary
# logic (stopping at the *next* `package=` line) rather than accidentally
# matching the first block's PlaybackState for the second block's package.
MULTI_SESSION_SAMPLE = OTHER_PACKAGE_PLAYING_SAMPLE + PAUSED_SAMPLE.replace(
    "Sessions Stack - in priority order (top to bottom):\n", ""
)


class ParsePlayingStateTest(unittest.TestCase):
    def test_playing_state_true(self) -> None:
        self.assertTrue(
            parse_playing_state(PLAYING_SAMPLE, "dev.diamondspark.saraswati.debug")
        )

    def test_playing_state_other_package(self) -> None:
        self.assertFalse(
            parse_playing_state(
                OTHER_PACKAGE_PLAYING_SAMPLE, "dev.diamondspark.saraswati.debug"
            )
        )

    def test_playing_state_paused(self) -> None:
        self.assertFalse(
            parse_playing_state(PAUSED_SAMPLE, "dev.diamondspark.saraswati")
        )

    def test_playing_state_multi_session_matches_own_block_only(self) -> None:
        self.assertFalse(
            parse_playing_state(MULTI_SESSION_SAMPLE, "dev.diamondspark.saraswati")
        )
        self.assertTrue(
            parse_playing_state(
                MULTI_SESSION_SAMPLE, "com.google.android.apps.youtube.music"
            )
        )

    def test_playing_state_package_absent_from_dump(self) -> None:
        self.assertFalse(parse_playing_state("", "dev.diamondspark.saraswati"))


if __name__ == "__main__":
    unittest.main()
