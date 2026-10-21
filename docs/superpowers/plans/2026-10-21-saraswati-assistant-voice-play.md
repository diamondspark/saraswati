# Saraswati: Assistant Voice Play (phone-wide, not just Android Auto)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make "Hey Google, play [song/artist/playlist] on Saraswati" actually start playback from anywhere Assistant listens on the phone — lock screen, home screen, any app — not only inside Android Auto, which already works for song-title queries. This is one feature, not a new phase: most of the plumbing already exists; this plan closes the real gaps (artist/playlist/station matching, no-match behaviour, a security ruling) and proves the rest on-device instead of assuming it.

**Architecture:** No new subsystem. `MusicService` (a Media3 `MediaLibraryService`) already exposes one `MediaLibrarySessionCallback` that both Android Auto and any other `MediaController`/legacy `MediaBrowser` — including Assistant's own controller when it dispatches a voice "play" command to a named app — connect through via `onConnect`, gated by `ControllerPolicy.isAllowed` (own package, already-trusted controllers, Android Auto by verified signing digest, or any other system app). A successful connect lets that controller call the legacy `playFromSearch(query, extras)` API, which Media3's compatibility stub turns into a call to `onSetMediaItems` with a single placeholder `MediaItem` whose `requestMetadata.searchQuery` carries the spoken text — the exact code path `MusicService.SEARCH` in `onSetMediaItems` already handles for Android Auto's voice search via `VoiceSearchMatcher`. This plan extends that one shared branch (songs → also artists, playlists, stations) rather than building a second, phone-only path.

**Tech Stack:** Kotlin (existing app), Media3 `media3-session` 1.10.1 (existing), the existing `VoiceSearchMatcher` fuzzy-matching utility, the existing `ControllerPolicy`/`CallerInfoFactory` caller-identity gate.

**Spec:** `docs/superpowers/specs/2026-10-17-saraswati-design.md` (the "Music-service callers" rule, line 88; the Android Auto voice-search paragraph, line 164).

## Research findings (confirmed on reading the code; Task 1 re-confirms on-device)

- `onSetMediaItems`'s `MusicService.SEARCH` branch (`app/src/main/kotlin/com/metrolist/music/playback/MediaLibrarySessionCallback.kt`) is already the generic voice-search entry point for *any* controller that connects and calls legacy `playFromSearch` — not an Android-Auto-only code path. Whoever connects (car head unit, Assistant, a future integration) funnels through the same `onConnect` → `isCallerAllowed` → `onSetMediaItems` sequence.
- The caller-identity gate (`ControllerPolicy.isAllowed`, `app/src/main/kotlin/com/metrolist/music/saraswati/security/ControllerPolicy.kt`) is checked once, at `onConnect`, not again per command — this is by design (the connection itself is the trust boundary) and matches the design doc's rule. Assistant's own app (`com.google.android.googlequicksearchbox` on a Pixel) is a pre-installed/GMS-updated system app, which `CallerInfoFactory.isSystemApp` already recognizes (`FLAG_SYSTEM` or `FLAG_UPDATED_SYSTEM_APP`) — so `ControllerPolicy.isAllowed` should admit it today, with **no code change**, through its existing "any other system app" fallback. This must be proven live (Task 1), not assumed: if a different, non-system package turns out to be the actual connecting caller on this Pixel's current Android build, that is an owner decision (adding a verified signing digest), never a silent allowlist widening.
- `VoiceSearchMatcher.findBest` (`app/src/main/kotlin/com/metrolist/music/playback/VoiceSearchMatcher.kt`) deliberately returns confidence `0.5` for a query that is purely an artist name with no song-title tokens — below its own `STRONG_MATCH_THRESHOLD = 0.60` — so `findBest` returns `null` and nothing plays. "Hey Google, play Arijit Singh on Saraswati" fails today. This is the concrete "artist" gap.
- The local-playlist check in the same branch is an exact, case-insensitive name match only (`it.playlist.name.equals(searchQuery, ignoreCase = true)`) — no fuzzy matching, no online/YouTube playlist matching. "Station" has no voice-query path at all today; stations are reachable only by browsing the car's Stations tab (`CarStations`), never by name.
- `AndroidManifest.xml`'s three `MEDIA_PLAY_FROM_SEARCH` entries on `MainActivity` are scoped to YouTube link data hosts (`youtube.com`, `youtu.be`, `vnd.youtube`) — they exist for "open this YouTube link," not for generic free-text voice search, and are unrelated to the `onSetMediaItems` path above. Nothing here needs to change for this feature; flagged so no one "fixes" the wrong file.
- `res/xml/shortcuts.xml` declares only two App Shortcuts (search, library) — no App Actions `<capability>` for the built-in `actions.intent.PLAY_MEDIA`/`GET_MEDIA` intents. Google's bare, no-app-named "Hey Google, play X" resolution for a third-party app is normally validated through a Google Play Console "App Actions test" for a *published* app. Saraswati is sideloaded and will never go through that. **A bare "Hey Google, play X" defaulting to Saraswati with no app named is not achievable through app code here.** The one reliable, buildable phrase is "...on Saraswati," which (per the findings above) should already resolve to this app's `MediaLibrarySession` via the OS's by-name app targeting, independent of App Actions.
- There is no OS-level "set default music app" role for a sideloaded app (unlike default browser/launcher). Assistant's own "preferred music service" setting is for Google-linked streaming services (Spotify, YouTube Music, etc.), not arbitrary installed apps.
- Query text flows into `database.searchSongsExtended` (Room, parameterized — safe) and `YouTube.search` (network call carrying the raw transcript to YouTube). No injection-style sanitization gap found; the real exposure is sending a system-supplied voice transcript over the network and starting audio with no user confirmation, which is a product/security tradeoff, covered below.

## Global Constraints

- Work on branch `assistant-voice-play`, stacked on current `main`.
- Touches only `com.metrolist.music.playback` (upstream-owned — every edit needs a `// SARASWATI:` marker per `AGENTS.md`) and, for Task 4, `res/xml/shortcuts.xml` (`<!-- SARASWATI: -->`). `com.metrolist.music.saraswati.security` (`ControllerPolicy`, `CallerInfoFactory`) is **not** touched by this plan unless Task 1's live evidence shows the connecting caller is denied — and even then, only with the owner's explicit sign-off on the real verified signing digest to add, never a broadened rule.
- No new preference keys, no new UI screens — this is matching/behavior work on an existing path plus documentation, not a new surface.
- `security/apk_gate.py check` and the JVM test suite must pass before this lands, per `AGENTS.md`.
- The existing `HideExplicitKey`/`HideVideoSongsKey` filters, already applied on the song-matching path, must also cover every new matching path this plan adds (artist, fuzzy playlist) — a voice path that forgets the family's content filters is a silent regression.
- Never log the raw spoken transcript at a level that ships in the release build; the existing `android.util.Log.w("ControllerPolicy denied ...")` pattern (package name + reason only, kept by proguard) is the model to follow for any new diagnostic logging.

## Review Focus

1. **A query that resolves to nothing must never crash, hang, or quietly start the wrong song** — `onSetMediaItems` must keep returning the existing empty `defaultResult` on no-match; no new lower-confidence "good enough" guess gets invented without the owner seeing it first. Owner: Task 3.
2. **The new artist and fuzzy-playlist paths must apply `HideExplicitKey`/`HideVideoSongsKey` exactly like the existing song path already does** — this is an existing family-safety setting; a new code path that bypasses it is a regression nobody notices until it's heard. Owner: Task 2-3.
3. **No change to `ControllerPolicy`'s allowlist happens without Task 1's live, on-device evidence of which package actually connects for an Assistant-triggered play command, and without the owner's explicit sign-off** on the real signing digest — same rule the project already applies to Android Auto. Owner: Task 1.
4. **The best-effort App Actions declaration (Task 4) must not be oversold** in any doc as making bare "Hey Google, play X" work — Task 5's owner-facing text states only the tested, working phrase. Owner: Task 4-5.
5. **This feature is proven on the real phone with the real phrase, locked and unlocked, in and out of Saral mode** — not by code review alone. Task 1 and the Test Plan below are what this plan stands or falls on. Owner: Task 1, Test Plan.

---

### Task 1: Baseline verification on the Pixel (HUMAN CHECKPOINT)

**Files:** none changed (temporary, reverted debug logging only).

**Interfaces:** produces a findings note (appended to this plan file by whoever runs it) answering, for real, what today's code actually does — this gates every later task.

- [ ] **Step 1:** Add one temporary `android.util.Log.i` at the top of `onConnect` in `MediaLibrarySessionCallback.kt` printing `controller.packageName`, `controller.uid`, and the result of `callerOf(controller)` (trusted/system-app/signing-digest flags) — guarded so it's trivially removable, not a permanent addition.
- [ ] **Step 2:** On the Pixel, `adb logcat -s MediaLibrarySessionCallback:*` while saying "Hey Google, play `<a song already in the library>` on Saraswati" from: (a) the home screen unlocked, (b) the lock screen with the display off, (c) with `GrandparentRole`/Saral mode active.
- [ ] **Step 3:** Record which package connects in each case, whether `isCallerAllowed` admits it, and whether the song actually starts playing (listen, or `adb shell dumpsys media_session | grep -A5 "state="`).
- [ ] **Step 4:** Repeat with a bare "Hey Google, play `<song>`" (no app named), a pure artist name ("...play `<artist>` on Saraswati", no song title), a local playlist name ("...play `<playlist>` on Saraswati"), and the same three phrases substituting "on Saras" for "on Saraswati" — recording what happens for each (correct thing plays / nothing plays / Assistant offers a chooser / a different app opens).
- [ ] **Step 5: HUMAN CHECKPOINT.** Share every result above with the owner before Task 2 starts coding. If any connecting package is denied by `isCallerAllowed`, stop and bring the exact denied package name (from the `"ControllerPolicy denied pkg=..."` log line) to the owner — do not touch `ControllerPolicy` without their sign-off on the real verified signing digest.
- [ ] **Step 6:** Remove the temporary logging added in Step 1 once findings are recorded.

### Task 2: Resolve artist and playlist voice queries (extends the shared path)

**Files:**
- Modify: `app/src/main/kotlin/com/metrolist/music/playback/VoiceSearchMatcher.kt`
- Modify: `app/src/test/kotlin/com/metrolist/music/playback/VoiceSearchMatcherTest.kt`
- Modify: `app/src/main/kotlin/com/metrolist/music/playback/MediaLibrarySessionCallback.kt` (only the `MusicService.SEARCH` branch of `onSetMediaItems`)

**Interfaces:**
- New pure function `VoiceSearchMatcher.findBestArtist(query: String, candidates: List<ArtistEntity>): ArtistEntity?`, built from the same `tokenize`/`jaroWinkler` primitives `stripArtistTokens` already uses, returning a match for a query that is substantially just an artist's name (the case `scoreCandidate` already flags at confidence 0.5 and today discards).
- The existing local-playlist name check in the `SEARCH` branch becomes fuzzy (token-set comparison via `VoiceSearchMatcher.tokenize`, not just `.equals(ignoreCase = true)`).

- [ ] **Step 1:** Add `findBestArtist` to `VoiceSearchMatcher`, matching the spoken query's tokens against `database.artistsByCreateDateAsc()` names with the same Jaro-Winkler threshold style already used in `stripArtistTokens`.
- [ ] **Step 2:** In the `SEARCH` branch's `isVoiceSearch` block, order the checks: (1) fuzzy local-playlist name match (extend the existing exact check), queuing that playlist's songs exactly as today; (2) if no playlist, `findBestArtist` against local artists — on a hit, build that artist's songs into the queue the same way the existing `MusicService.ARTIST` branch does, apply `HideExplicitKey`/`HideVideoSongsKey`, and start radio via `AutoRadioQueueKey` exactly as the song path already does; (3) if still nothing, fall through unchanged to today's `VoiceSearchMatcher.findBest` song match.
- [ ] **Step 3:** Add test cases to `VoiceSearchMatcherTest.kt` for pure-artist queries (no song-title words) asserting `findBestArtist` resolves them, plus a regression case confirming `findBest` is unaffected for genuine song-title queries.
- [ ] **Step 4:** Do not touch `MusicService.CAR_STATIONS` or `MusicService.CAR_LOCAL`'s own branches — they already have separate, working logic for browsing; this task only extends the one `SEARCH` branch both Android Auto's and the phone's voice path funnel through, confirmed shared by Task 1. Nothing here is duplicated.
- [ ] **Step 5:** Run the JVM test suite (`source scripts/dev-env.sh && sar_gradle testFossDebugUnitTest` or the project's equivalent) and confirm it passes.

### Task 3: No-match handling and safety-filter parity

**Files:**
- Modify: `app/src/main/kotlin/com/metrolist/music/playback/MediaLibrarySessionCallback.kt` (same `SEARCH` branch)

**Interfaces:** behavior only, no new public interface.

- [ ] **Step 1:** Confirm (by reading, then by the Task 6 device test) that the new artist-queue and fuzzy-playlist paths call `.filterExplicit(context.dataStore.get(HideExplicitKey, false))` and `.filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))` before `service.adoptQueue`, exactly like the existing song-radio path already does.
- [ ] **Step 2:** Keep `defaultResult` as the answer when nothing matches at any level (no playlist, no artist, no song ≥ threshold) — do not invent a lower-confidence fallback. If Task 1 found Assistant's own "I couldn't find that" response already covers this acceptably on-device, no further change is needed. If Task 1 found Assistant shows nothing useful, bring that back to the owner rather than guessing at new UI (the app has no screen to show anything while it wasn't the one that opened it).
- [ ] **Step 3:** Add one `android.util.Log.w` (same tag/pattern as the existing `"ControllerPolicy denied"` line) at each no-match exit in the `SEARCH` branch, logging only query length and which stage failed (never the raw transcript beyond what's already logged elsewhere), so a real "it didn't work" report from the owner is debuggable from `adb logcat` afterward.

### Task 4: Best-effort App Actions declaration

**Files:**
- Modify: `app/src/main/res/xml/shortcuts.xml`

- [ ] **Step 1:** Add a `<capability android:name="actions.intent.PLAY_MEDIA">` block to `shortcuts.xml`'s existing `<shortcuts>` root, with a `// SARASWATI:`/`<!-- SARASWATI: -->`-marked comment stating plainly: this is best-effort only, has no verified effect for a sideloaded, unpublished app, and does not change whether a bare "Hey Google, play X" (no app named) reaches Saraswati — that requires a Google Play Console App Actions test this fork will never undergo.
- [ ] **Step 2:** Rebuild and confirm the manifest/shortcuts merge cleanly; no functional test is expected to show a behavioral difference, per Step 1's framing — this task exists so the declaration isn't missing, not because it's expected to change outcomes.

### Task 5: Owner-facing documentation

**Files:**
- Modify: whichever rollout/setup doc the family already uses (the Phase 3 GitHub Pages setup guide, or `AndroidAutoSettings`'s on-screen description text — locate the right one before editing)

- [ ] **Step 1:** Add one short section stating the exact, tested phrase ("Hey Google, play `<name>` on Saraswati"), that it works from the lock screen and home screen (per Task 1/Task 6's confirmed results — do not write this until those are confirmed), and that it needs the same "Battery: Unrestricted" setting already required for Android Auto (per the design doc) — no new per-phone setup step beyond what's already documented there.
- [ ] **Step 2:** State plainly that a bare "Hey Google, play X" with no app named will not reliably pick Saraswati, and that this is an Android/Google Assistant platform limitation for sideloaded apps, not a bug in this app.

## Security items — ruled 2026-10-21 (owner decisions)

1. Locked phone executing a voice-triggered play command with no further confirmation: **accept** (matches existing lock-screen media control behavior; no `KeyguardManager` check added).
2. Behavior while `GrandparentRole` (Saral) is active: **same everywhere** — no special-casing, no disabling of the voice-search branch during Saral.
3. Voice-identity gating (whose voice triggered the command): **Assistant's own Voice Match is enough** — no app-side identity check added; there is no clean signal for "which family member spoke" in the MediaSession callback anyway.
4. Dedicated rate limit on this entry point's outbound YouTube searches: **not needed** — rely on existing general caps (`AndroidAutoSearchLocalLimitKey` etc.); voice commands are inherently rate-limited by how fast a person can speak, and the caller gate already restricts who can connect.

All four rulings favor the simpler path: this plan adds no `KeyguardManager` check, no Saral special-case, no app-side voice-identity check, and no dedicated rate limiter.

## "Saras" as a shorter invocation name

The owner notes Saraswati is colloquially called **"Saras"** and is easier to say to Assistant. There is no standard Android mechanism for an app to register an alternate name Assistant will match on for app-targeted voice routing ("play X on `<app>`" matches against the installed app's label via `PackageManager`) — Assistant *may* already fuzzy/phonetically match "Saras" to the "Saraswati" label on its own, but this is unverified. Changing the app's actual label, or adding a second launcher alias labeled "Saras," are both out of scope for this plan (the latter would add a confusing second launcher icon) — add "on Saras" as an additional phrase in Task 1's on-device test instead of assuming either way; only revisit a label/alias change afterward if "Saras" alone doesn't route correctly and the owner wants to pursue it.

## Test Plan

Verify on the Pixel; no Android Auto DHU session needed for any of this.

1. **Real phrase, real device (authoritative test).** Unlocked, home screen: "Hey Google, play `<song title already in the library>` on Saraswati" — confirm it starts playing via `adb shell dumpsys media_session | grep -A5 "state="` or by listening. Repeat for a pure artist name, a local playlist name, and a title with no match at all (confirm nothing crashes and nothing unexpected plays).
2. **Locked phone.** Lock the screen (`adb shell input keyevent 26`), repeat the song/artist/playlist phrases without unlocking, and confirm via `adb shell dumpsys media_session` (still reachable over USB while locked) that playback state changed as expected.
3. **Saral/grandparent mode.** Enable `GrandparentRole`, repeat Steps 1-2, and record the result for the owner's ruling in the Security section above — this test's outcome is itself input to that decision, not just a pass/fail.
4. **Caller-identity regression.** Using Google's open-source "Android Media Controller" test tool (`googlesamples/android-media-controller` on GitHub — a sideloadable debug APK, not a project dependency) connected to `dev.diamondspark.saraswati`'s session, confirm: (a) it is denied if it doesn't present as a trusted/system caller (mirrors `ControllerPolicy`'s existing deny path — check `adb logcat` for `"ControllerPolicy denied"`), proving an arbitrary app still cannot drive playback; (b) a controller presenting as a system app or already-trusted controller can call `playFromSearch(query, null)` and reach the same matching logic exercised in Steps 1-3, without needing Assistant at all — useful for fast iteration on Task 2/3's matching changes.
5. **Bare command sanity check.** Say "Hey Google, play `<song>`" with no app named at least twice on two different days (Assistant's app choice for ambiguous bare commands can vary); record whether it ever picks Saraswati unprompted. Expected per this plan's findings: it will not reliably do so — this test exists to confirm that expectation stays true, not to chase making it succeed.
6. **Regression.** Run the existing Android Auto voice-search flow (say the same phrases while connected to a DHU or real head unit, or via the Media Controller tool with the Android Auto package's identity) to confirm Task 2/3's changes to the shared `SEARCH` branch haven't broken the in-car path.
