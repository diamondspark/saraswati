# Strip checklist (Task 5)

Every feature Phase 1 drops, the flag that gates it, where its entry points were hidden, where its manifest components were already removed (Task 4), and a manual click-through for Task 10 to confirm nothing dead is left reachable.

All flags live in `app/src/main/kotlin/com/metrolist/music/saraswati/SaraswatiFeatures.kt` and are regression-tested by `app/src/test/kotlin/com/metrolist/music/saraswati/SaraswatiFeaturesTest.kt`. Re-enabling one later means: flip the `const val`, add the feature's hosts to the Task 12 egress allowlist, and re-review what it needs — not just flipping the flag.

## Dropped features

| Feature | Flag | Why dropped |
| --- | --- | --- |
| Discord Rich Presence | `DISCORD` | Unvetted OAuth + a long-lived gateway connection to a third party, reporting every song played. |
| Last.fm scrobbling | `LASTFM` | Same shape of risk as Discord: a third-party service learns every song played. |
| AI lyrics translation | `AI_LYRICS` | Sends lyrics text to an unvetted third-party translation API. |
| Music recognition | `RECOGNITION` | Needs `RECORD_AUDIO`/microphone access, which Task 4's manifest overlay removes; the feature cannot work without it and its UI must not pretend otherwise. |
| Alarms | `ALARMS` | Needs `SCHEDULE_EXACT_ALARM` and `RECEIVE_BOOT_COMPLETED`, both removed from the manifest; same reasoning as recognition. |
| Home-screen widgets | `WIDGETS` | Task 4's manifest overlay already removes all four widget receivers (`tools:node="remove"`); audit finding NEW-1 — they were exported with no permission, so any app could skip tracks or start playlists. This flag exists for completeness and is read by Task 8's `MusicService.onStartCommand` guard; no Kotlin entry point needed changing in this task. |
| Proxy settings | `PROXY` | Audit A15.2. A user-entered HTTP/SOCKS proxy (with optional username/password) is an easy way to misconfigure or leak traffic, and this fork does not need it. |
| InnerTubeX remote cipher code | `REMOTE_CIPHER_CODE` | Audit A12-b, High. See below — this is not a settings row, it is a silent network+code-execution path. |

## Lyrics providers

`lyrics/LyricsProviderRegistry.kt`'s `providerMap` is filtered to `SaraswatiFeatures.ALLOWED_LYRICS_PROVIDER_KEYS`: **BetterLyrics, LrcLib, YouTubeSubtitle, YouTube**. Dropped:

- **KuGou**, **Paxsenix**, **LyricsPlus** (itself a front for six further community lyrics mirrors)
- **Zemer**, which also calls `jewishmusic.fm:8443`

Each extra lyrics host this app queries learns every song the family plays (the search query is usually the track title/artist). Four providers already cover the family's music well; the rest are dropped rather than reviewed host-by-host.

`getDefaultProviderOrder()` is filtered the same way, so a blank/legacy saved order (or one that only names dropped providers, e.g. restored from an old backup) can never resurface KuGou/Paxsenix/LyricsPlus/Zemer — covered by `LyricsPolicyTest`.

To re-add one later: add its key back to `ALLOWED_LYRICS_PROVIDER_KEYS`, add its host(s) to the Task 12 egress allowlist, and re-run `LyricsPolicyTest`.

## InnerTubeX remote cipher code (audit A12-b, High)

InnerTubeX can fetch a player-config JSON from a third-party repo (`MetrolistGroup/faraday` via `cdn.jsdelivr.net`, fallback `raw.githubusercontent.com`; the specific URL this fork's copy of `AndroidPlayerConfigRepository` used points at `raw.githubusercontent.com/ZemerTeam/zemer-cipher`) and run the JavaScript it contains inside the app's QuickJS runtime, to work around YouTube cipher changes. That is a complete, unsigned, remotely-served code-execution path that bypasses every other gate in this project — the design's assumption that these configs were regex-checked data did not hold for this base.

`utils/InnerTubeXPlayer.kt`'s `AndroidPlayerConfigRepository.enabled` now reads `SaraswatiFeatures.REMOTE_CIPHER_CODE` (`false`) instead of a hardcoded `true`. With it off, playback falls back to InnerTubeX's local cipher solver running against YouTube's own player script — the same approach `yt-dlp` uses — and YouTube breakages get fixed by bumping the InnerTubeX dependency, not by downloading code at runtime. `InnerTubeXPlayerCipherConfigTest` instantiates the repository directly (via Robolectric, since it needs a `Context` for `SharedPreferences`) and fails the build if `enabled` ever reads `true` again. Task 12 blocks both hosts at the network layer too, as a second line of defense.

## Listen Together (audit A9.1/NEW-5)

Listen Together is **not** a dropped feature — it stays visible in the UI (it's woven through `HomeScreen`'s top bar) because ripping it out cleanly is more invasive than this phase's scope. It must not be able to *connect* anywhere until Phase 4 adds a join confirmation, though:

- `MainActivity.handleDeepLinkIntent` now checks `SaraswatiDeepLinkPolicy.isListenTogetherLink(uri.host, uri.pathSegments.firstOrNull())` and returns immediately, before any join logic runs, for a link, shared text, or explicit intent whose host or first path segment is `listen` (case-insensitive).
- Task 4's manifest overlay already removes the `https://metrolist.cc/listen...` browsable `<intent-filter>`, so a tapped web link can't even reach `MainActivity` with that data in the first place. The code-level guard above is defense in depth for the paths that remain: `Intent.EXTRA_TEXT` (share-to-app), an explicit `Intent` targeting `MainActivity`, and `onNewIntent`.
- The settings row that configures a Listen Together username/room still exists (`settings/integrations/listen_together`) and the in-app "start/join" button in the top bar still exists; both remain reachable (per the visible-but-can't-connect decision) but a join can now only happen through `ListenTogetherManager` paths this task did not touch directly — the deep-link join path specifically is dead. Full confirmation + network-level blocking is Phase 4/Task 12's job.

## Entry points hidden (Step 3)

| Entry point | File | What changed |
| --- | --- | --- |
| "Integrations" settings row (hosts Discord + Last.fm only) | `ui/screens/settings/AccountSettings.kt` | Row only added to the list when `DISCORD \|\| LASTFM` |
| "AI lyrics translation" settings row | `ui/screens/settings/SettingsScreen.kt` | Row only added when `AI_LYRICS` |
| Alarms section on the Player settings screen | `ui/screens/settings/PlayerSettings.kt` (`AlarmSettingsSection()` call) | Call wrapped in `if (ALARMS)` |
| Recognition FAB on the Home screen | `ui/screens/HomeScreen.kt` (`onRecognitionClick`) | Passes `null` instead of a navigate lambda when `!RECOGNITION` (the FAB component already hides itself when this is `null`) |
| Recognition FAB on the online search results screen | `ui/screens/search/OnlineSearchResult.kt` | `HideOnScrollFAB(...)` call (its only purpose here is the mic/recognition button) wrapped in `if (RECOGNITION)` |
| Recognition FAB on the search screen | `ui/screens/search/SearchScreen.kt` | Same as above |
| Search bar long-press → recognition (phone layout) | `MainActivity.kt` (`onSearchLongClick`) | Body wrapped in `if (RECOGNITION)`; long-press becomes a no-op instead |
| Search rail long-press → recognition (tablet/rail layout) | `MainActivity.kt` (`onRailSearchLongClick`) | Same |
| `ACTION_RECOGNITION` intent handler | `MainActivity.kt` (`handleRecognitionIntent`) | Added `if (!RECOGNITION) return` right after the existing action check, so an explicit component start (not just the removed intent-filter) still can't reach the recognition screen |
| Proxy settings section | `ui/screens/settings/ContentSettings.kt` | Whole `Material3SettingsGroup` (toggle + "configure proxy" rows) wrapped in `if (PROXY)` |
| Proxy initialization from stored settings | `App.kt` (`initializeSettings`) | `if (settings[ProxyEnabledKey] == true)` → `if (PROXY && settings[ProxyEnabledKey] == true)`, so a proxy config restored from an old backup is never honored even if the row that set it is gone (Task 7 also scrubs it) |
| Listen Together deep-link join | `MainActivity.kt` (`handleDeepLinkIntent`) | Early `return` via `SaraswatiDeepLinkPolicy.isListenTogetherLink(...)` before any join logic |

## Manifest entries already removed (Task 4, for cross-reference)

`app/src/gms/AndroidManifest.xml` (gms-flavor overlay) already removes, independently of the flags above:

- Permissions: `RECORD_AUDIO`, `FOREGROUND_SERVICE_MICROPHONE`, `SCHEDULE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`; the `android.hardware.microphone` feature.
- The `ACTION_RECOGNITION` intent filter on `MainActivity`.
- The `https://metrolist.cc/listen...` browsable intent filter on `MainActivity`.
- `discord.DiscordOAuthActivity`, `recognition.RecognitionLaunchActivity`, `recognition.RecognitionForegroundService`, `quicksettings.MusicRecognizerTileService`.
- All four widget receivers: `widget.MusicRecognizerWidgetReceiver`, `widget.MusicWidgetReceiver`, `widget.TurntableWidgetReceiver`, `widget.PlaylistWidgetReceiver`.
- `playback.alarm.MusicAlarmReceiver`, `playback.alarm.MusicAlarmRescheduleReceiver`.
- The unused `org.apache.http.legacy` library.

The Kotlin-level guards in this task (Step 3) are needed on top of that manifest removal wherever an *explicit* `Intent`, a `Composable` call, or UI state could still reach the dropped code without going through a removed `<intent-filter>` or component.

## Manual click-through (Task 10)

With a debug build installed, work through this list; every item should behave as described and nothing should crash:

1. **Settings → root screen.** No "Integrations" row. No "AI lyrics translation" row (under Player & Content). "Proxy" section absent from Settings → Content.
2. **Settings → Player & Audio.** Scroll to where the Alarms section used to sit (between the sleep-timer group and the Queue group) — it is gone; no stray heading or empty section.
3. **Settings → Content.** No "Proxy" group (was between the "App language" group and the "Lyrics" group).
4. **Settings → Content → Lyrics → "Lyrics provider selection".** Only four providers listed: BetterLyrics, LrcLib, YouTube Subtitle, YouTube. KuGou/Paxsenix/LyricsPlus/Zemer do not appear even if an old backup's saved order named them.
5. **Home screen.** No secondary (mic) FAB next to the main FAB.
6. **Search screen** (tap the search bar, before typing and while results are showing). No floating mic button in the bottom-right corner.
7. **Search bar long-press**, both in the bottom nav (phone) and the nav rail (tablet, if testable). Nothing happens — no navigation, no crash.
8. **Listen Together.** The top-bar entry point is still visible (expected — it's not dropped, just not yet connectable). Tapping it should not silently fail in a confusing way; if it attempts to join anything, that's worth flagging to Task 10/Phase 4, since the deep-link join path is blocked but the in-app button path was not rewired in this task.
9. **Recognition via explicit intent** (a Metrolist-style check, package/activity name are placeholders — do not commit a real device serial):

   ```bash
   adb shell am start -a com.metrolist.music.action.RECOGNITION \
     -n dev.diamondspark.saraswati.debug/com.metrolist.music.MainActivity
   ```

   Expected: the app opens normally (to whatever screen it was last on, or the home screen on a cold start) — no recognition screen, no crash, no stack trace in `adb logcat`.
10. **General smoke test.** Playback, search, and navigation all still work; nothing above left a blank screen or a crash where a hidden row used to be.
