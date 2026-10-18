# Saraswati: the family's music app

YouTube Music's brain, Spotify's craft, built for one family. It's safe on phones that hold bank apps and works in the car. The full-screen player is **Veena**.

## Context

- **Who:**
  - you, other adults, and grandparents (some live far away)
  - phones: Pixel, Samsung Galaxy and Xiaomi/Redmi/Poco, all 2022 or newer, all with **banking apps**
  - yours is a Pixel 8 Pro on Android 17 (API 37), connected over adb
- **Cast targets at home:** Chromecast/Google TV, Android TVs with Cast built in, and Nest speakers. Echo and Bluetooth speakers connect over Bluetooth.
- **Today:**
  - Metrolist 13.6.3 works.
  - OuterTune is dead: YouTube returns 403 errors since early 2026, and its maintainer removed YouTube support.
- **Goal:** your own free app with YouTube Music's catalog and personalised recommendations, unchanged, plus Spotify's craft and togetherness.
- **Your priorities, in order:**
  1. **Airtight security.** You chose manual updates over silent ones on every phone, and Samsung Auto Blocker stays on.
  2. **Stable and fast.**
  3. **Works seamlessly on Android Auto.**
  4. **Runs without your computer.**
- **Must-haves:**
  - Sangat (listen together)
  - Parivaar Mix and family playlists
  - Cast
  - local music files
  - Saral, an easy mode for grandparents
- **Also requested:** install REA (Reverse Engineer Anything) as an MCP server and skill at user level, and test on the phone, or in the browser and on the desktop where the phone doesn't apply.

## The core call: fork Metrolist, don't rebuild the YouTube engine

- **The YouTube engine is what breaks.** It handles stream URLs, PoTokens and cipher solving, and that's where OuterTune died. YouTube breaks playback about every 1–2 months. Active upstreams recover in days; Metrolist fixed the 30 Jan 2026 outage the same day.
- **Metrolist keeps the engine alive with:**
  - `MetrolistGroup/innertubex`
  - remote cipher configs (ZemerTeam/zemer-cipher), so many fixes need **no new APK**
  - a PoToken WebView
  - fallback across several YouTube clients
- **So Saraswati is a thin layer on Metrolist (GPL-3.0).** We put our effort into family features, security, speed and polish.
- **YouTube's recommendations come for free.** Each person signs into their own account, and Metrolist already reports plays over 30 s back to YouTube (`MusicService.onPlaybackStatsReady` → `YouTube.registerPlayback`).
- **Upstream risk:** Metrolist has been bug-fix-only since July 2026, and its Kotlin Multiplatform rewrite is a paid alpha whose source isn't public.
  - Our code stays isolated, we can bump `innertubex` ourselves, and REA plus Claude can patch the engine.
  - Fallback upstreams: ArchiveTune (GPL-3.0, built on Metrolist) and SimpMusic.

## Runs without your computer

| Job | Where | Cost |
|---|---|---|
| Builds, upstream sync, security checks | GitHub Actions | Free for public repos |
| Release approvals | The GitHub mobile app (one tap) | Free |
| APKs, update manifest, setup guide | GitHub Releases and GitHub Pages | Free |
| Sangat server and family hub (roster, stations, kill-switches, health view) | Cloudflare Worker plus one Durable Object | Free plan, no card |
| Crash and playback alerts | Sentry, with personal data scrubbed | Free tier |
| Cast relay, local files, downloads | Each phone | — |

## Security: built for phones with bank apps

**The bar:**
- If Saraswati were fully compromised, Android's sandbox would still keep it away from bank apps, SMS and OTPs, notifications, the screen and other apps' data, because Saraswati never holds those permissions.
- No app on the phone keeps permission to install apps between updates.
- A build that tries to add a permission fails CI.

**Threats we defend against:**
- a tampered update
- a compromised upstream or dependency
- an exploited bug (web content, network data, the LAN relay)
- a leaked YouTube sign-in
- a stolen GitHub, Cloudflare or Google account

### 1. Least privilege, enforced in CI

- **Allowed:**
  - `INTERNET`, `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, `POST_NOTIFICATIONS`
  - `FOREGROUND_SERVICE` + `_MEDIA_PLAYBACK` + `_DATA_SYNC` (downloads)
  - `READ_MEDIA_AUDIO`, at runtime and only for local files
  - `ACCESS_LOCAL_NETWORK`, once the app targets 37, for the Cast relay
- **Upstream permissions to remove:**
  - `RECORD_AUDIO` and `FOREGROUND_SERVICE_MICROPHONE`, which only song recognition uses
  - `SCHEDULE_EXACT_ALARM` and `RECEIVE_BOOT_COMPLETED`, which only the music-alarm feature uses (alarms are removed)
  - anything else the Phase 1 audit finds
- **Never, in either app:**
  - SMS, accessibility, notification listener, overlay
  - `QUERY_ALL_PACKAGES`, contacts, location, phone state, camera, microphone
  - and, in Saraswati, `REQUEST_INSTALL_PACKAGES`
  - Avoiding the SMS, accessibility and notification-listener permissions also keeps us outside Play Protect's fraud-protection install blocks.
- **Voice search** uses `RecognizerIntent`, so the microphone belongs to Google's app, not ours.
- **Manifest gate:** the built release APK's manifest (permissions plus exported components) must match `security/manifest-allowlist.json`, including after an upstream merge.
- **Features removed** (they hold extra credentials or open extra channels): Discord Rich Presence, Last.fm login, AI lyric-translation keys, song recognition, music alarms, and Metrolist's in-app updater.
- **Blocked at the network level:** every lyrics provider except YouTube, LrcLib and BetterLyrics (each extra one would learn every song you play), AutoEQ online search, and Return YouTube Dislike.
- **Music-service callers:** Metrolist's `MusicService` accepts any installed app as a controller. Saraswati accepts only itself, system-trusted controllers, system apps and Android Auto (checked by its signing certificate).

### 2. Updates: manual, verified, no standing install permission

- **Saraswati Updates** (`dev.diamondspark.saraswati.updater`) is a tiny separate app:
  - no WebView, no JavaScript engine, no server
  - the only thing on the phone that can ever install
  - it installs only Saraswati and itself, and only APKs whose signing certificate matches the one it has pinned. Android enforces the same rule.
  - because it lives outside Saraswati, it can still deliver a fix if a bad build crashes on startup
- **The routine** (about one minute, guided by big buttons):
  1. Saraswati shows "Update ready". Playback fixes are flagged red. Saraswati checks daily and on launch, but has no install ability itself.
  2. Tapping it opens Saraswati Updates, which downloads the APK into private storage and checks its sha256, package name and certificate.
  3. **Allow:** a deep link opens its own "Install unknown apps" switch. On Samsung, Auto Blocker is turned off first.
  4. **Update:** Android's standard install screen appears, and you tap Install.
  5. **Lock again:** the switch is turned back off. The app confirms this itself (`canRequestPackageInstalls() == false`) and keeps reminding until it's off. On Samsung, Auto Blocker goes back on; One UI 8.5 may re-enable it automatically after 30 minutes.
  6. The phone reports its new version to the family hub's health view.
- **Fewer interruptions:** the family's Stable channel gets at most one batch a month, plus urgent playback fixes. Remote cipher configs mean many YouTube breaks need no update at all. Smart downloads keep music playing while a phone is behind.
- **Signing key:**
  - Generated offline once. It's never stored in the repo and is removed from the dev machine after setup.
  - It lives as a GitHub secret in a protected `release` environment. **Every signed release waits for your tap in the GitHub app.**
  - Two encrypted offline backups.
  - Signed with APK Signature Scheme v3.1, which allows rotating the key later.
- **Gates before anything reaches the family:**
  - the manifest gate
  - Gradle dependency verification (pinned checksums), with the third-party Chinese mirror and `mavenLocal()` removed from the repositories and a checksummed Gradle wrapper
  - CodeQL and MobSF static scans
  - a Claude `/security-review` of every upstream diff
  - 48 hours on your phone (the Beta channel)
  - your approval
- **GitHub hardening:**
  - passkey or security-key 2FA
  - branch protection
  - Actions pinned to commit SHAs, with least-privilege `permissions:`
  - secret scanning with push protection
  - Dependabot
  - the sync bot uses the default workflow token (no stored secret) and cannot merge or release

### 3. The YouTube sign-in

- **Keep only YouTube cookies.** Clear the WebView's other storage right after login.
- **Encrypt** with the Android Keystore (AES-GCM, hardware-backed where available, no extra library).
- **Never let it leave the app's private storage.** Android backup and phone-to-phone transfer are switched off (`allowBackup=false`), a startup scrubber deletes any plaintext cookie or integration token (for example from a restored Metrolist backup), and the cookie is never logged.
- **Host allowlist:** enforced by Android itself through the network security config. The app trusts no server except a short list of domains, so a bug or a hijacked config cannot send the cookie anywhere else. (Fallback if Android ignores an empty trust list: an OkHttp interceptor on every client.)
- **Stream URLs** must be on `*.googlevideo.com`. Output from the remote cipher config is validated before use.
- **Login WebView:** Google domains only, no JavaScript bridges, no file access, Safe Browsing on.
- **Escape hatch:** Google Account → Security → "Sign out of all devices".
- **Main accounts are fine,** because only YouTube cookies are ever held. Anyone may use a music-only account instead, at the cost of starting their recommendations fresh.

### 4. Network, LAN and cloud

- **HTTPS only** (cleartext is off). Only system certificates are trusted, in every build; no user-installed CA ever goes onto a phone that holds banking apps. Hosts are found from the source and from TLS failures in a debug build, not by intercepting traffic.
- **Cast relay:**
  - runs only while casting, and only on the Wi-Fi interface
  - random port, with an unguessable token for each session
  - serves only songs in the queue, by opaque ID, never file paths
- **Worker:**
  - It stores no credentials, only nicknames, room state, playlist IDs, stations, kill-switches and app versions.
  - **Pairing:** a QR code or a private link from your phone carries the family secret, which is **never in the APK**. **You approve every new phone** in "Manage family".
  - Admin actions need an admin key that lives only on your phone.
  - It is rate-limited, and the Cloudflare account has 2FA.
- **Sentry:** crashes and playback-failure events only. `beforeSend` strips URLs, headers, breadcrumbs and device identifiers. Screenshots and replays are off.

### 5. Phone hygiene (checklist on every family phone)

- **Play Protect stays on.**
- **Samsung Auto Blocker stays on** (your decision).
- **No app holds "Install unknown apps"** outside the update routine. Chrome's permission is revoked right after the first install.
- **Phone Developer options stay off.** Banking apps check for them.
  - Far-away phones never need them, because setup is by link.
  - Android Auto's own "Unknown sources" setting is separate and doesn't trip banking apps.
- **Every family Google account has 2-Step Verification.**
- **Battery set to "Unrestricted"** for Saraswati on Samsung and Xiaomi, plus Autostart on Xiaomi, so playback and Android Auto survive a locked screen.
- **Developer verification:** both packages are registered under a free Google "limited distribution" account (no ID, no fee, up to 20 devices authorised by QR code or link). This keeps installs working once the 2027 rules arrive.

## Android Auto: seamless in the car

Metrolist already gives us a Media3 `MediaLibraryService` with a browse tree, `onSearch` and voice ("Hey Google, play X on Saraswati", via `onSetMediaItems` and `VoiceSearchMatcher`), `onPlaybackResumption`, and `MEDIA_PLAY_FROM_SEARCH`. We add:

- **Car tabs** (at most 4, in `MediaLibrarySessionCallback.onGetChildren(ROOT)`):
  - Home (YouTube's shelves)
  - Family (Parivaar Mix, family playlists, join Sangat)
  - Library (playlists, liked, downloaded, on this phone)
  - Stations (Saral's station tiles, which also make ideal car buttons)
- **Instant and offline-first:**
  - The browse tree is served from cache, with no network wait.
  - Parivaar Mix and Stations are downloaded for offline use.
  - Skip-to-playable covers tunnels.
  - The last queue resumes when the car connects.
- **Sangat in the car** (Spotify's in-car Jam):
  - The driver's phone hosts.
  - Family passengers get a "Papa started Sangat in the car" prompt from the hub and **join as remote controllers**: they add songs, but nothing plays on their phones.
  - Family members are auto-approved.
  - **Only songs you add count toward your own taste.**
- **Car-safe behaviour:**
  - The update routine never runs in car mode.
  - Sign-in happens only on the phone; the car shows "Sign in on your phone".
  - Errors are short, car-friendly messages.
  - No lyrics on the car screen, per driver-distraction rules.
  - Like, shuffle and Sangat are custom actions on the car's player screen.
- **Setup:** anyone who uses Android Auto turns on "Unknown sources" in Android Auto's developer settings (tap Version 10×). Otherwise Android Auto hides apps installed outside the Play Store.
- **Testing:**
  - Google's Desktop Head Unit (`extras;google;auto`) on your computer, driving the Pixel over USB.
  - The Media Controller Test app for the browse tree and actions.
  - A real car drive before each Stable release that touches playback or browsing.

## Why this beats Premium Family for your family

- **Grandparents in another house are fine.** YouTube Premium Family re-checks addresses every 30 days.
- **A simple mode finally exists.** Neither Spotify nor YouTube Music has one.
- **Everyone's taste stays their own.** Sangat and Parivaar Mix plays don't count by default, and there's a toggle to change that.
- **Cast plays from the phone's own account,** never "the wrong account".
- **Free, with no ads,** plus background play, downloads, and unlimited synced lyrics.

## Product principles

1. **Safe by default.** Least privilege, no secrets in the APK, and no standing install permission.
2. **Music in under a second.** Measured on your Pixel every release.
3. **Never silent.** Downloads, local files and remote cipher fixes.
4. **YouTube's brain, our face.** No home-made recommender, and no taste pollution.
5. **Grandparent-proof and car-proof.** One tap in Saral, zero fiddling in the car.
6. **Look human to YouTube.** Modest prefetch, throttled downloads, no VPN. Rapid skipping already triggers about one-day lockouts (#4192).
7. **Every feature pays rent.** Keep the diff from upstream small.

**Anti-goals:** a public or Play Store release, ads, tracking, paid services, a social network, and rebuilding the YouTube plumbing.

## Experience design

- **Navigation:** Home · Search · Library · **Family**, plus the mini-player, which opens **Veena**.
- **Home:** YouTube's shelves stay unchanged. We add a shortcuts grid (extending SpeedDial), a Parivaar Mix card, a "Sangat live" banner and an "Update ready" banner.
- **Veena:** colours from the album art, lyrics, queue, and Cast and Sangat one tap away. Restrained motion.
- **Family tab:**
  - Parivaar Mix, family playlists, start or join Sangat, Cast.
  - **Manage family** (you only): approve phones, set stations, kill-switches, and a **health view** showing each phone's version and whether it needs an update, so you know when to call.
- **Saral (grandparents):**
  - 4–6 huge station tiles. Each is a YouTube radio seed you set remotely.
  - Giant play/pause and next buttons.
  - Voice search through `RecognizerIntent`.
  - Large text, no settings.
  - Taste seeding at setup (`set_tasteprofile`).
  - Turns on automatically for the grandparent role.
- **Look:** a dark, content-first theme coloured by the album art, with a marigold "Basant" accent. The icon is a veena or lotus mark. Hindi feature names always carry English subtitles.
- **Design gate:** clickable HTML mockups open in your browser before any Kotlin UI work.

## Architecture

- **Repo:** `diamondspark/saraswati`, a public fork, which satisfies GPL-3.0. The local clone goes in `/home/mohit/saraswati`.
  - **Keep every upstream copyright notice**, and credit Metrolist, InnerTube and OuterTune. OpenTune was DMCA'd for stripping notices.
- **Base:** upstream `main` at commit `76fa5a78b5110674442884b6f21c0cef51cdb782` (2026-10-08), not the `v13.7.0` tag. The tag pins InnerTubeX v0.5.2; the commit pins v0.8.4, and the playback fixes, the Cast fix (`bc6ee5ea4`) and the Android 17 media-control fix all landed after the tag. Verified: it builds and passes its 186 unit tests with our toolchain. Later upstream changes arrive through the sync bot.
- **Rebrand:** three one-line edits in `app/build.gradle.kts` (application id `dev.diamondspark.saraswati`, app name `Saraswati`, debug name `Saraswati Debug`), launcher icons in `res/mipmap-anydpi-v26|v31` and `res/drawable/ic_launcher_*`, and the accent colour in `ui/theme/Theme.kt` (`0xFFED5564`). The `METROLIST_*` environment variables stay unset, so a build can never produce Metrolist's package id.
- **Build:**
  - the `gms` flavour (for Cast), arm64-v8a only (all family phones are 2022+; each phone's CPU list is checked before rollout)
  - **keep Media3 at 1.10.1** (1.11.1 hides media controls on Android 17)
  - delete upstream's workflows (they run on a third-party runner with signing secrets) and use our own `saraswati-*.yml`
  - overrides for version, ABI and flavor flags go in one block appended to the build file, so upstream's version bumps never conflict
- **versionCode:** the number of commits in git history (`scripts/version.py`; it refuses shallow clones), which only goes up. The upstream version goes in versionName (e.g. `13.7.0-s4123`), so switching upstreams can never cause a "downgrade" error.
- **Data:**
  - Saraswati's own Room database (`saraswati.db`), so upstream's database versions are never touched.
  - Local songs are `local:` rows in Metrolist's `song` table, using its existing `isLocal` column (verify). They are never sent to YouTube, lyrics or sync.
- **Isolation:** new code lives in `saraswati/{family,saral,localmedia,castrelay,security,car}`, plus `:baselineprofile`, `:updater` (the Saraswati Updates app) and `:update-core` (shared, fully tested update rules). Upstream files are touched only at hook points marked `// SARASWATI:`:

  | Area | Files (`M` = `app/src/main/kotlin/com/metrolist/music`) |
  |---|---|
  | Navigation | `M/ui/screens/NavigationBuilder.kt`, `M/MainActivity.kt` |
  | Home | `M/ui/screens/HomeScreen.kt`, `M/viewmodels/HomeViewModel.kt` |
  | Player | `M/ui/player/*` |
  | Playback and car | `M/playback/{MusicService,MediaLibrarySessionCallback}.kt`, `StreamUrlCache.kt` |
  | Settings | `M/constants/PreferenceKeys.kt` |
  | Updater (removed) | `M/utils/Updater.kt` |
  | Backup | `settings/BackupAndRestore.kt` |
  | Listen together | `M/listentogether/*` (server URL, remote-controller mode) |
  | Cast | `app/src/gms/.../playback/CastConnectionHandler.kt` |
  | YouTube API | `innertube/.../YouTube.kt` |
- **Worker (`saraswati-sangat`):**
  - **Why a single Durable Object:** the app sends the room code only in its first message, and a reconnect sends only a session token. So one Durable Object holds every room plus the family hub.
  - State lives in the Durable Object's SQLite storage, because hibernation wipes memory. Alarms replace the Go timers.
  - It speaks metroserver's protobuf+gzip protocol (`app/src/main/proto/listentogether.proto`), so the app only changes its URL.
  - **Tested from the outside:** replay recorded message traces against both the Go metroserver and the Worker, and compare.
  - **Free-plan budget:** the app pings every 25 s, so run time is the limit that bites, about 28 h awake per day. A family fits.
- **Parivaar Mix:**
  - Each phone keeps an unlisted "Saraswati · ⟨name⟩ top songs" playlist, refreshed weekly from local stats, and registers it with the hub.
  - Mix order: songs two or more people love, then each person's top songs taken in turns, then YouTube radio from the overlap.
  - It refreshes daily and is downloaded for offline use.
- **Cast relay:**
  - The phone serves audio from its cache, downloads, local files or a live fetch.
  - Relay URLs don't expire, so the **whole queue** goes to Google's Default Media Receiver. That fixes the rolling-window sync bug (#4359).
  - It covers Chromecast, Google TV, Android TV and Nest. Spike it on your devices first.

## Remote setup (for far-away family)

1. **You send two private messages:** the setup link (a GitHub Pages guide with steps for Pixel, Samsung and Xiaomi) and the family pairing link. Help them over a video call.
2. **Install the updater:**
   - Samsung: turn Auto Blocker off temporarily.
   - Allow Chrome to install, then install **Saraswati Updates**.
3. **Install Saraswati:** Saraswati Updates installs Saraswati. Then it guides them to **switch Chrome's install permission off again**, turn Auto Blocker back on (Samsung), and set the battery to Unrestricted (Samsung and Xiaomi, plus Autostart on Xiaomi).
4. **In Saraswati:** open the pairing link, then **you approve the phone**. They pick who the phone is for, sign into YouTube, and grandparents pick favourite singers so Saral is ready.
5. **Car and verification:** if they drive with Android Auto, enable "Unknown sources" in it. Then authorise the device for Google developer verification by link.

## Speed and stability budgets (Pixel 8 Pro)

| Metric | Budget | How it's measured |
|---|---|---|
| Cold start to cached Home | ≥ 20% faster than Metrolist 13.6.3, and ≤ 1.0 s | Macrobenchmark `StartupTimingMetric` in both `Partial` and `None` modes |
| Tap to first audio, not cached | p50 ≤ 1.0 s on Wi-Fi | Media3 `AnalyticsListener` |
| Tap to audio, cached or downloaded | ≤ 300 ms | Same |
| **Car:** connect to browse tree shown | ≤ 1.0 s (cached) | Desktop Head Unit plus logs |
| **Car:** tap to audio with the phone locked | p50 ≤ 1.5 s | Desktop Head Unit plus `AnalyticsListener` |
| Gap between tracks | Gapless | Resolve the next URL after 15 s; debounce lookups during rapid skips |
| Scroll jank on Home and Library | < 1% janky frames | `FrameTimingMetric` |
| Crash-free sessions | ≥ 99.5% | Sentry |
| Playback failures | < 0.5% of plays, alert on spikes | Sentry |

Smart downloads are limited to about 50 songs a night per phone, on Wi-Fi while charging.

## Phases

### Status

1. **Phase 0 is done** (2026-10-17): REA 6.5.0 registered at user level, JDK 21 and Android SDK 37 installed, and a spike build of the chosen base passed all 186 unit tests.
2. **Detailed plans written:** `2026-10-17-saraswati-phase1-fork-harden.md` and `2026-10-17-saraswati-phase2-updates-pipeline.md` (same folder).
3. **Next:** the user reviews the plans and chooses how they are executed; then Phase 1 starts.

### Phase 0: tools (about 1 h)

1. **REA from official sources only** (`morluto/rea`, rea.tools, npm `rea-agents`). Never look-alikes such as `NabilRFG12/*` or `REA-Gith/REA`.
   - Dry run: `XDG_DATA_HOME="$HOME/.local/share" npx -y rea-agents@latest setup --client claude_code --dry-run`.
   - Then the same command with `--yes`. This writes `mcpServers.rea` to `~/.claude.json` and the skill to `~/.claude/skills/reverse-engineer-anything`.
   - Prerequisites are already met: Node 22.23, procps, Chrome.
2. **JDK 21** (Temurin, `~/tools/jdk-21`) and the **Android SDK** command-line tools (`~/Android/Sdk`: platform-tools, `platforms;android-37`, build-tools, and `extras;google;auto` for the Desktop Head Unit). User-space installs, no sudo.
3. **jadx-headless-mcp 0.7.1:** check its SHA-256 (`6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75`) and put it in `/home/mohit/.local/share/rea/android/`. Add `REA_JADX_MCP_JAR` and `JAVA_HOME` to the rea MCP server's environment.
4. **Verify:** `npx rea-agents doctor --client claude_code --skill --json`, `claude mcp list` and `java -version`. Then restart Claude Code.

### Phase 1: fork, audit, harden (days 1–2)

1. Fork, rebrand, add credits.
2. **Security audit:**
   - REA (JADX) and MobSF on the upstream APK.
   - A traffic capture on a debug build.
   - Output: `docs/security-audit.md`.
3. **Strip** permissions and features. Add the manifest gate, cookie encryption, host allowlists and a network security config.
4. Build the `gms` arm64 release and install it side by side with Metrolist.
5. **Baselines** against Metrolist 13.6.3, saved to `docs/perf-baseline.md`: startup, tap-to-audio, scroll jank, and Android Auto (Desktop Head Unit browse, voice, resume).

### Phase 2: updates and pipeline (days 3–5). No family rollout until this passes

1. **Signing key ceremony** and the protected `release` environment.
2. **CI gates.**
3. **The Saraswati Updates app** and the manual routine (including the Samsung steps).
4. **The sync bot.**
5. **Sentry.**
6. **`:baselineprofile`** (Macrobenchmark plus Baseline Profile).
7. **Developer verification.**

### Phase 3: hub, Saral, car, rollout (days 6–10)

1. **Mockups** of Saral, Veena, onboarding and the updater.
2. **Worker hub:** pairing with your approval, roster, stations, kill-switches, health view.
3. **Saral v1** and the **car tabs** (Stations, Family).
4. **The GitHub Pages setup guide.**
5. **Rollout**, phone by phone, with the security checklist. Metrolist stays installed as a spare for the first month.

### Phase 4: family features (weeks 2–5), each behind a kill-switch

1. **Sangat on the Worker,** with car mode (hub prompt, remote controllers).
2. **Parivaar Mix.**
3. **Family playlists.** Use REA plus browser capture on music.youtube.com to confirm the InnerTube calls for collaborative playlists.
4. **The Cast relay** (spike, then build).
5. **Spotify import** (port ArchiveTune's, GPL-3.0).

### Phase 5: local files, speed, never silent (weeks 4–7)

1. **Local files** (including "On this phone" in the car).
2. **Baseline Profiles.**
3. **Smart downloads.**
4. **Skip-to-playable.**

### Phase 6: craft (picked by family demand)

1. **Prahar**, a time-of-day mix.
2. **Smart shuffle.**
3. **Radio-tuning chips** (via REA).
4. **Family Recap.**
5. **Snooze a song.**
6. **Speed-dial resume.**
7. **An optional "Ask Saraswati".**

## Verification

- **Every build:** CI gates and unit tests. Then `adb install -r` on the Pixel and a scripted smoke test: launch, search, play, skip, lyrics, and logcat with no errors. REA's device tools capture screenshots and logs.
- **Security:**
  - The MobSF report is clean.
  - The manifest gate fails on a permission added on purpose.
  - `dumpsys package` shows only allowed permissions.
  - A traffic capture shows the cookie going only to YouTube and Google hosts, and lists every endpoint.
  - The updater rejects an APK signed with a different key.
  - After an update, "Install unknown apps" is confirmed off.
  - A restored Metrolist backup leaves no plaintext cookie or token behind.
  - The relay rejects requests without the token.
  - The Worker rejects an unpaired or unapproved phone.
- **Updates:**
  - The manual routine works on the Pixel, a Samsung with Auto Blocker on, and a Xiaomi.
  - A deliberately crash-on-start build gets fixed through Saraswati Updates.
  - The health view shows each phone's version.
- **Android Auto:**
  - The Desktop Head Unit run passes: browse all 4 tabs, voice "play X on Saraswati", resume on connect, Sangat from the car with a passenger acting as remote controller, offline playback with Wi-Fi off, and phone-locked playback against the budget.
  - A real car drive before each Stable release that touches playback.
- **Family features:**
  - Sangat across two phones.
  - Cast 20+ songs in a row on a Chromecast, an Android TV and a Nest.
  - Parivaar Mix with two or more accounts.
  - A grandparent plays a station and pauses with no help.
- **Design:** mockups approved in the browser before UI code. Contrast and tap-target checks.

## Risks

| Risk | Mitigation |
|---|---|
| YouTube breaks playback | Remote cipher configs (no APK needed); urgent releases flagged red; the health view tells you who to nudge; downloads and local files keep music playing. |
| Family members skip manual updates | Monthly batches only, red "fixes playback" banners, the health view, and your remote help. |
| Upstream or dependency compromise | Manifest gate, checksums, CodeQL and MobSF, a Claude security review, 48 h on Beta, your approval. |
| Signing key theft | Kept off the repo and the dev machine, in a protected environment that needs your approval. v3.1 rotation is possible. |
| Cookie leak | Only YouTube cookies, encrypted, host allowlist, never backed up; "Sign out of all devices". |
| Android Auto hides the app, or brands kill it | "Unknown sources" in Android Auto, battery set to Unrestricted, and Autostart, all part of setup and verified on each brand. |
| Google account action | Low (no documented ban wave). Family-only use, normal listening. |
| Metrolist upstream stalls | Isolated code, our own `innertubex` bumps, fallback upstreams. |
| Google developer verification | Limited-distribution account before 2027. |
| GPL takedown | Keep notices and credits; source is public. |

## Decisions

**Yours:**
- Name Saraswati
- Android only
- Manual updates on every phone
- Samsung Auto Blocker stays on
- No dependence on your computer
- Adults and grandparents
- All four family features

**Mine (veto any):**
- App ID `dev.diamondspark.saraswati`
- Public repo
- `gms` build
- arm64 APK
- **A separate Saraswati Updates app**
- **Features removed:** Discord RPC, Last.fm, AI translation keys, song recognition, music alarms
- Base on a reviewed upstream commit, not the v13.7.0 tag
- Lyrics from YouTube, LrcLib and BetterLyrics only; AutoEQ online search and Return YouTube Dislike off
- Listen Together stays visible but disconnected until Phase 4
- Backups switched off for the app (a new phone starts fresh and signs in again)
- Main Google accounts, hardened
- The Worker on Cloudflare
- Sentry, scrubbed
- Sangat and Parivaar Mix plays don't count toward your taste
- Car passengers join as remote controllers
- Hindi feature names with English subtitles
- Marigold accent, dark by default
- Player name Veena
