# Saraswati Phase 5: Local Files, Speed, Never Silent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Music keeps playing when YouTube or the signal fails. This phase adds songs stored on the phone (in the app and under "On this phone" in the car), skip-to-playable for tunnels and YouTube outages, nightly smart downloads, offline Stations, next-song pre-resolve, and the measurements and Baseline Profile coverage the speed budgets need.

**Architecture:**
- **Local songs** are `song` rows with id `local:<MediaStore _ID>` and `isLocal = 1`, so the schema doesn't change. A routing `DataSource` plays them: it sends `local:` keys to the platform content source and everything else to the existing resolver and cache chain.
- **Egress backstop:** a Ktor plugin on InnerTube's single `HttpClient` refuses any request that names a `local:` id, behind the app-side guards.
- **Never silent** (`saraswati/offline/`): a pure `NeverSilentController` picks the next offline-playable song from caches only, and `MusicService` drives it through a small port.
- **Smart downloads:** a non-persisted `JobScheduler` job, re-armed on every process start, feeds Media3's existing `DownloadManager` directly, with no foreground service.
- **Speed:** trace sections that Macrobenchmark measures.

Compose UI comes last because of the design gate.

**Tech Stack:** Kotlin/Compose (existing), Media3 1.10.1 (`ContentDataSource`, `DefaultExtractorsFactory`, `DownloadManager`, `AnalyticsListener`, all existing), Android `MediaStore`, `JobScheduler` and `android.os.Trace` (platform APIs), Ktor client plugin API (existing), kotlinx.serialization + DataStore (existing), Robolectric (existing test dependency), androidx Macrobenchmark 1.5.0 (existing `:baselineprofile`), Python 3 stdlib.

**Spec:** `docs/superpowers/specs/2026-10-17-saraswati-design.md`: Phase 5, Product principles ("Never silent", "Look human to YouTube"), Speed and stability budgets, Android Auto ("Instant and offline-first"), Security §1 and §4, Architecture (Data, Cast relay). Format precedent: `2026-10-19-saraswati-phase4-family-features.md` (on branch `phase4-family-features`).

## Preconditions (Phases 3 and 4 complete, stacked, unmerged)

Branch `phase5-local-speed` off `phase4-family-features`. That branch is stacked on Phase 3: it holds Phase 3's Worker work and Phase 4 Tasks 1–2. Phase 3's app work is on `phase3-app`. **Start only when `phase4-family-features` contains every Phase 3 and Phase 4 deliverable.** If either phase merges to `main` first, rebase onto the new base. Each task greps for what it consumes and stops if it is missing, rather than re-creating it.
- **Phase 3:**
  - car constants `MusicService.CAR_HOME/_LIBRARY/_STATIONS/_FAMILY`, with the `CAR_LIBRARY` branch serving the old `AndroidAutoSection` list
  - `StationsCache` and `Station(id, title, seedVideoId, iconIndex)`
  - the station-start path in Saral and in `CAR_STATIONS`
  - the launch-time hub refresh call site (grep `reportHealth(`)
- **Phase 4:**
  - `FamilyFeatureGate.isEnabled(FamilyFeature)`
  - `ParivaarMixStore.cached()` and `MixTrack`
  - `YouTubePlaylistPort.radio(seedVideoId)`
  - `RelayAudioSource`/`RelayStream`, `CacheRelayAudioSource`, `CastRelayController.mediaItems`
  - Sangat via `ListenTogetherManager`
  - the Taste gate inside `MusicService.onPlaybackStatsReady`'s extracted helper

## Spec notes (decisions where the spec is silent or conflicts with itself)

1. **"`local:` rows using its existing `isLocal` column (verify)."** Verified: `song.isLocal` exists in schema v38, but `Migration16To17` dropped its old `localPath` column. Upstream's unmerged `upstream/feature/local-music` re-adds `downloadUri`/`localPath` with a schema bump and `READ_EXTERNAL_STORAGE`, so this plan does not port it. Instead, the id `local:<MediaStore _ID>` *is* the location, and the content URI is derived from it. **No schema change**, only new DAO queries.
2. **The `READ_MEDIA_AUDIO` allowlist vs. older phones.** The permission exists only on Android 13 (API 33) and later. Below that, local files need `READ_EXTERNAL_STORAGE`, which is off the allowlist and also reads photos. Ruling:
   - local files need Android 13+, and older phones show "Needs Android 13 or newer"
   - the gate now forbids `READ_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE`, `READ_MEDIA_IMAGES` and `READ_MEDIA_VIDEO`
   - the rollout checklist records each phone's Android version (Task 17)
3. **"~50 songs a night on Wi-Fi while charging" vs. the boot-permission ban.** WorkManager merges `RECEIVE_BOOT_COMPLETED`. Media3's `PlatformScheduler` builds persisted jobs, which need that same permission: `javap` on media3-exoplayer 1.10.1 shows `setPersisted(true)` unconditionally. Neither our manifest nor Media3's declares `PlatformSchedulerService`.
   - Ruling: one **non-persisted** `JobScheduler` job: a platform API, with no dependency, no permission and no boot receiver. Its constraints are an unmetered network, charging, an idle device and storage that isn't low. `App.onCreate` re-arms it, so any process start (a launch, a car connection or a media button) restores it after a reboot.
   - The cost: a phone that reboots and is never opened downloads nothing until Saraswati next starts.
   - `ExoDownloadService.getScheduler()` becomes `null` (Task 11), because the existing `PlatformScheduler` call cannot succeed without the permission.
4. **"Never sent to YouTube, lyrics or sync" vs. Sangat and Cast.**
   - Cast: the spec routes local files through the relay, which stays on the LAN as bytes behind an opaque id.
   - Sangat: the spec is silent. Ruling: local songs never join a room, because their title would reach the hub and guests couldn't play them.
5. **"Never silent" vs. "Look human" (rapid-skip lockouts, #4192).** Skip-to-playable chooses only from caches, downloads and local files, so it makes **zero** YouTube calls while skipping.
6. **Unowned spec items this phase takes:**
   - "Stations are downloaded for offline use" (Tasks 9–11)
   - the budget row "resolve the next URL after 15 s; debounce during rapid skips" (Task 8)
7. **Baseline Profiles.** Phase 2 delivered `:baselineprofile`, the profile and cold start of 357/316 ms (`None()`/`Partial()`), which meets "≤ 1.0 s". This phase does only what is still open:
   - the "≥ 20% faster than Metrolist" comparison
   - tap-to-audio and the car budgets, never measured
   - profile coverage of the Phase 3–5 screens

   It adds no `.dm` install path, because `None()` already meets the budget.
8. **No kill-switch keys.** The hub's four keys stay fixed (Phase 4 note 6). Phase 5 features are phone-local. Smart downloads get a phone setting instead (Task 15).
9. **Design gate.** As in Phase 4: Compose UI (Tasks 14–15) waits for Task 1's approval. The car browse tree is rendered by Android Auto's templates, so it is not gated.
10. **"A traffic capture" (Verification) vs. "not by intercepting traffic" (Security §4).** §4 wins, because no user CA ever goes on a family phone. The local-egress check (Task 17 Step 4) relies on the guard's own refusals and the account's visible state.

## Global Constraints

- **Branch** `phase5-local-speed` (see Preconditions). `M/` = `app/src/main/kotlin/com/metrolist/music/`. Tests mirror under `app/src/test/kotlin/com/metrolist/music/…`.
- **Phase 1–4 constraints still apply:**
  - `// SARASWATI:` (or `<!-- SARASWATI: -->`) markers on upstream files
  - `source scripts/dev-env.sh && sar_gradle …`, gms flavor only
  - commit format `type(scope): …` plus the harness attribution trailer
  - strings only in `app/src/main/res/values/metrolist_strings.xml`
  - the JVM suite and the gate green before each commit
- **The gate,** short for: `sar_gradle :app:assembleGmsRelease && python3 -I security/apk_gate.py check --apk app/build/outputs/apk/gms/release/app-gms-release-unsigned.apk --allowlist security/manifest-allowlist.json`.
- **New packages:** `saraswati/localmedia`, `saraswati/offline` (skip-to-playable, prefetch, offline Stations, smart downloads) and `saraswati/perf`, plus additions to `saraswati/car` and `saraswati/castrelay`.
- **Permissions:** exactly one is added, `READ_MEDIA_AUDIO`, in the Saraswati-owned `app/src/gms/AndroidManifest.xml`. It is requested only by the Allow button on the On this phone screen (Task 14). No new exported component: the `JobService` is `exported="false"` behind `BIND_JOB_SERVICE`. No WorkManager, no `RECEIVE_BOOT_COMPLETED`, no persisted job, and no foreground service for smart downloads.
- **No new Gradle dependency. No new network host:** `python3 -I -m unittest discover -s security -p 'test_*.py'` stays green.
- **No DB schema change.** Add DAO `@Query` methods only. `git diff --exit-code app/schemas` stays empty after every build.
- **Local-song invariant:** a row has `isLocal = 1` exactly when its id starts with `local:`. Local songs never reach YouTube (including `registerPlayback` and library or playlist sync), a lyrics provider or the hub. They leave the phone only as bytes through the LAN Cast relay.
- **Look human:** no network call while choosing a song to skip to; at most one pre-resolve per track; smart downloads one at a time, 60 s apart, at most 50 a night.
- **Car (from Phases 3–4):** `onGetChildren` reads Room and cached snapshots only, never MediaStore or the network. Errors are one short phrase. Permission requests happen on the phone only.
- **Copy:** "On this phone", "Allow on your phone", "Downloaded" (existing `R.string.downloaded_songs`), "Needs Android 13 or newer", "Songs on this phone can't join Sangat". Everything else comes from the approved mockups.
- **Commands:**
  - App: `sar_gradle :app:testGmsDebugUnitTest --tests '<pattern>'`
  - InnerTube: `sar_gradle :innertube:test --tests '<pattern>'`
  - Python: `python3 -I -m unittest discover -s scripts -p 'test_*.py'`
- **Human checkpoints** are Task 1 (gates Tasks 14–15) and Tasks 16–19, each of which gates only itself. The agent asks, then carries on with unblocked work. Agents never sign in to a family account and never approve a release.

## Review Focus

1. **A local song leaves the phone** through a like or playlist synced to YouTube, a lyrics lookup, or a Sangat `TrackInfo`. The Ktor guard must refuse before any socket opens, and lyrics and Sangat must never be called. Owner: Tasks 2 and 4.
2. **Private audio plays as a song in the family car:** WhatsApp voice notes, call recordings, ringtones. The filter drops `Android/…`, recording folders, `IS_RECORDING` rows and anything under 30 s. Owner: Task 3.
3. **A tunnel leaves the car silent, or skip-to-playable spins.** With nothing playable ahead it tries the bridge, then upstream's network wait. It never loops, never probes YouTube, and never acts while casting or as a Sangat guest. Owner: Task 7.
4. **Smart downloads break their limits:** mobile data, battery, more than 50 a night, or a half-done request resuming on mobile data the next morning (`DownloadManager` resumes queued requests on any network). Unfinished smart requests are stopped with `SMART_STOP_REASON` at the end of each run and at every process start. Owner: Task 11.
5. **The permission misbehaves:** it is requested anywhere but the Allow button, or the app crashes when it is denied or revoked mid-song. A revoked local song counts as unplayable. Owner: Tasks 3, 6, 7 and 14.

---

### Task 1: Phase 5 mockups (design gate)

**Files:** scratch only (throwaway HTML, as in Phase 4 Task 1).

**Interfaces:** Produces the owner's approval record that Tasks 14–15 require.

- [ ] **Step 1:** Extend the approved Phase 3/4 look with clickable mockups for:
  - the Library entry to "On this phone" (a row or card above Library's content, showing the song count)
  - the On this phone screen in each state: never asked (what it reads, "they never leave this phone", Allow); denied twice (open app settings); Android 12 or older ("Needs Android 13 or newer"); scanning; empty; the list (play all, shuffle, rows)
  - the reduced song menu for a local song
  - the Smart downloads setting with its status line ("Last night: 37 songs")

  Propose a Hindi name with an English subtitle for Smart downloads, as the spec's naming rule requires. The owner decides. Publish with the Artifact tool (quickstart `intent: "design"`).
- [ ] **Step 2: HUMAN CHECKPOINT (non-blocking).** Send the link. Tasks 2–13 continue. Tasks 14–15 start only after explicit approval. Record the approved copy in the ledger.

### Task 2: Local song ids and egress guards

**Files:**
- Create: `M/saraswati/localmedia/LocalSongIds.kt`; `innertube/src/main/kotlin/com/metrolist/innertube/saraswati/LocalIdEgressGuard.kt`
- Modify (`// SARASWATI:`):
  - `innertube/.../InnerTube.kt`: `createClient()` installs the guard
  - `M/utils/InnerTubeXPlayer.kt`: `playerResponseForPlayback`
  - `M/lyrics/LyricsHelper.kt`: `getLyrics`
  - `M/playback/DownloadUtil.kt`: every `download(…)` overload
  - `M/playback/MusicService.kt`: `onPlaybackStatsReady`'s helper
- Test: `innertube/src/test/kotlin/com/metrolist/innertube/LocalIdEgressGuardTest.kt`, `LocalSongIdsTest.kt`, `LocalEgressTest.kt` (Robolectric)

**Interfaces:**
- Produces:
  - `object LocalSongIds`:
    - `const val PREFIX = "local:"`
    - `fun of(mediaStoreId: Long): String`
    - `fun isLocal(id: String?): Boolean`
    - `fun mediaStoreId(id: String): Long?`
    - `fun contentUri(id: String): Uri?`, via `ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, n)`
  - `class LocalSongEgressException : IllegalStateException`
  - `val LocalIdEgressGuard: ClientPlugin<Unit>`, which intercepts `HttpSendPipeline` and throws when `mentionsLocalId(url)` or, for `TextContent` bodies, `mentionsLocalId(text)`
  - `internal fun mentionsLocalId(text: String): Boolean`, regex `(?i)local(:|%3A)\d+`

- [ ] **Step 1: Write failing tests:**
  - **Guard** (`MockEngine`, plugin installed):
    - `a JSON body naming local:42 never reaches the engine and throws LocalSongEgressException`
    - `a URL containing local%3A42 is refused`
    - `an ordinary next request passes unchanged`
    - `a search for the word local passes`
  - **Ids:**
    - `of and mediaStoreId round-trip`
    - `isLocal is false for null, a video id and "local:"`
    - `contentUri is null for a video id`
  - **Egress** (Robolectric):
    - `playerResponseForPlayback fails for a local id without any request`
    - `getLyrics returns not-found for a local id without calling a provider`
    - `download of a local id enqueues nothing`
    - `a local play writes its Event row and never calls registerPlayback`

  Run: FAIL.
- [ ] **Step 2:** Implement.
  - The app guards are one early return each and run before any I/O.
  - `getLyrics` returns the same value it returns when no provider matches.
- [ ] **Step 3:** PASS. Run the full InnerTube and app suites.
- [ ] **Step 4: Commit** `feat(localmedia): local song ids and egress guards`.

### Task 3: Finding songs on this phone

**Files:**
- Create in `M/saraswati/localmedia/`: `MediaStoreAudioRow.kt`, `LocalLibraryFilter.kt`, `LocalAccess.kt`, `MediaStoreAudioReader.kt`, `LocalLibrarySync.kt`, `LocalSongsRepository.kt`
- Modify (`// SARASWATI:`):
  - `M/db/DatabaseDao.kt`: queries only
  - `app/src/gms/AndroidManifest.xml`: `READ_MEDIA_AUDIO`
  - the Phase 3 launch-time refresh call site (grep `reportHealth(`): also call `LocalSongsRepository.syncIfDue`, on `Dispatchers.IO`
- Modify: `security/manifest-allowlist.json` (permission plus the four new forbidden ones); the permission-rationale doc (grep `FOREGROUND_SERVICE_DATA_SYNC` under `docs/`)
- Test: `LocalLibraryFilterTest.kt`, `LocalLibrarySyncTest.kt`, `LocalSongsRepositoryTest.kt`

**Interfaces:**
- Consumes: Task 2.
- Produces:
  - `data class MediaStoreAudioRow(val id: Long, val title: String?, val artist: String?, val album: String?, val albumId: Long?, val durationMs: Long, val mimeType: String?, val relativePath: String?, val displayName: String?, val dateModifiedSec: Long, val isRecording: Boolean)`
  - `object LocalLibraryFilter`:
    - `MIN_DURATION_MS = 30_000L`
    - `EXCLUDED_SEGMENTS = setOf("android", "recordings", "call", "calls", "call_rec", "call recordings", "sound_recorder", "voice recorder", "voice notes", "ringtones", "notifications", "alarms")`
    - `fun accept(row: MediaStoreAudioRow): Boolean`, which drops `isRecording`, short files, and any `relativePath` segment that matches case-insensitively
  - `enum class LocalAccess { GRANTED, NOT_GRANTED, UNSUPPORTED }` and `fun localAccess(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): LocalAccess` (`UNSUPPORTED` below 33)
  - `fun interface MediaStoreAudioReader { suspend fun rows(): List<MediaStoreAudioRow> }`, and `ContentResolverAudioReader(resolver)`, selecting `IS_MUSIC != 0`
  - `interface LocalSongStore` with `suspend fun localSongIds(): List<String>`, `suspend fun upsert(song: SongEntity, artistNames: List<String>)` and `suspend fun deleteLocal(ids: List<String>)`, plus `RoomLocalSongStore(database)`. Artists are created as `SongMenu`'s edit does: `artistByName(name) ?: ArtistEntity(generateArtistId(), name, isLocal = true)`.
  - `class LocalLibrarySync(store: LocalSongStore)` with `suspend fun sync(rows: List<MediaStoreAudioRow>): LocalSyncReport` and `data class LocalSyncReport(val added: Int, val updated: Int, val removed: Int)`
  - `class LocalSongsRepository` with `val songs: Flow<List<Song>>`, `val count: Flow<Int>`, `suspend fun syncNow(): Result<LocalSyncReport>` and `suspend fun syncIfDue(now: Instant)` (`RESCAN_INTERVAL = Duration.ofHours(6)`, tracked by `LocalLastScanKey`)
  - New DAO queries: `localSongsByTitle(): Flow<List<Song>>`, `localSongsPage(limit, offset)`, `localSongCount(): Flow<Int>`, `localSongIds()` and `deleteLocalSongs(ids)` (with `AND isLocal = 1`)
- **Row mapping (pinned):**
  - `id = LocalSongIds.of(row.id)`
  - `title = row.title`, else `displayName` without its extension
  - `duration` in seconds
  - `thumbnailUrl = "content://media/external/audio/albums/<albumId>"`
  - `isLocal = true` and `inLibrary = null`, so upstream's library and sync flows never list them
  - artists are split on `;` and `,`, and `"<unknown>"` artists and albums are dropped

  Rows that vanished or now fail the filter are deleted; foreign-key cascades remove their maps and events.

- [ ] **Step 1: Write failing tests:**
  - **Filter:**
    - `drops files shorter than 30 seconds`
    - `drops anything under Android/ such as WhatsApp and Telegram media`
    - `drops Recordings, Call, call_rec, Voice Recorder, Ringtones, Notifications and Alarms folders in any case`
    - `drops IS_RECORDING rows`
    - `keeps mp3s under Music/ and Download/`
    - `keeps a folder named Recall Songs`
  - **Sync:**
    - `inserts local:<id> rows with isLocal true and inLibrary null`
    - `title falls back to the file name without extension`
    - `<unknown> artist and album are dropped`
    - `artists split on semicolon and comma`
    - `a changed dateModified updates the row`
    - `a vanished file's row is deleted`
    - `a row that now fails the filter is deleted`
  - **Repository:**
    - `never reads MediaStore without the permission`
    - `syncIfDue runs at most every 6 hours`
    - `a reader failure keeps existing rows`

  Run: FAIL.
- [ ] **Step 2:** Implement. PASS.
- [ ] **Step 3:** Run the gate. Expected: PASS with `READ_MEDIA_AUDIO`. As a one-off check, remove the permission from a scratch copy of the allowlist and confirm the gate fails against that copy. `git diff --exit-code app/schemas` is empty.
- [ ] **Step 4: Commit** `feat(localmedia): index songs on this phone from MediaStore`.

### Task 4: Playing local files, kept out of Sangat

**Files:**
- Create: `M/saraswati/localmedia/LocalRoutingDataSource.kt`, `LocalAwareExtractorsFactory.kt`, `LocalSangatPolicy.kt`
- Modify (`// SARASWATI:`):
  - `M/playback/MusicService.kt`:
    - `createMediaSourceFactory` wraps the existing factory and extractors
    - `playQueue` filters for Sangat
  - `M/listentogether/ListenTogetherManager.kt`: every path that builds a `TrackInfo` (grep `TrackInfo(`), plus `suggestTrack`
- Test: `LocalRoutingDataSourceTest.kt`, `LocalAwareExtractorsFactoryTest.kt`, `LocalSangatPolicyTest.kt`

**Interfaces:**
- Consumes: Task 2.
- Produces:
  - `class LocalRoutingDataSource.Factory(local: DataSource.Factory, remote: DataSource.Factory) : DataSource.Factory`. It routes on `dataSpec.key ?: dataSpec.uri.toString()`. A local key opens `dataSpec` with its URI replaced by `LocalSongIds.contentUri`, through `DefaultDataSource.Factory(context)`: no cache, no resolver, no network.
  - `class LocalAwareExtractorsFactory(remote: ExtractorsFactory, local: ExtractorsFactory = DefaultExtractorsFactory()) : ExtractorsFactory`. `createExtractors(uri, headers)` uses `local` when `uri.scheme` is `content` or `local`. Otherwise it uses the existing Matroska, FragmentedMp4 and Mp4 set.
  - `object LocalSangatPolicy`:
    - `fun mayShare(mediaId: String): Boolean`
    - `fun filterForRoom(status: Queue.Status, inRoom: Boolean): Queue.Status?`, which keeps the same song selected and returns `null` when nothing remains

    Filtered items show the toast "Songs on this phone can't join Sangat" once per `playQueue`.

- [ ] **Step 1: Write failing tests:**
  - `a local: key opens the content URI and never touches the remote factory`
  - `a video id goes to the remote factory unchanged`
  - `a deleted local file fails open with FileNotFoundException`
  - `content and local schemes get extractors that include mp3, flac, ogg, wav and adts`
  - `https keeps exactly Matroska, FragmentedMp4 and Mp4`
  - `in a room, local items are dropped and the index stays on the same song`
  - `a room with only local items plays nothing`
  - `a local track is never sent as TrackInfo`

  Run: FAIL.
- [ ] **Step 2:** Implement. PASS together with the existing `listentogether` tests.
- [ ] **Step 3: Commit** `feat(localmedia): play local files; keep them out of Sangat`.

### Task 5: Local files over the Cast relay

**Files:**
- Create: `M/saraswati/localmedia/LocalFileOpener.kt`, `M/saraswati/castrelay/LocalRelayAudioSource.kt`, `RoutingRelayAudioSource.kt`
- Modify (`// SARASWATI:`):
  - Phase 4's `CastRelayController.kt`: build with the routing source; take a local song's MIME type from `LocalFileOpener`
  - `app/src/gms/.../CastConnectionHandler.kt`: the fallback window drops local items
- Test: `LocalRelayAudioSourceTest.kt`; extend `CastRelayControllerTest.kt` and `RelayHttpServerTest.kt`

**Interfaces:**
- Consumes: Phase 4 `RelayAudioSource`, `RelayStream`, `CacheRelayAudioSource`; Task 2.
- Produces:
  - `fun interface LocalFileOpener { fun open(mediaStoreId: Long): LocalFile? }` with `data class LocalFile(val mimeType: String, val length: Long, val input: InputStream)`, and `ContentResolverFileOpener` (`openAssetFileDescriptor` plus the MediaStore `MIME_TYPE`)
  - `class LocalRelayAudioSource(opener: LocalFileOpener) : RelayAudioSource`
  - `class RoutingRelayAudioSource(local: RelayAudioSource, remote: RelayAudioSource) : RelayAudioSource`
  - `fun castableWithoutRelay(items: List<MediaItem>): List<MediaItem>`, which removes local items

- [ ] **Step 1: Write failing tests:**
  - `serves a local file with its MediaStore mime type and length`
  - `a range skips to its first byte and limits the length`
  - `a missing file gives null`
  - `routing sends local ids to the local source and video ids to the cache source`
  - `mediaItems uses the local mime type`
  - `a relay URL for a local song contains neither its id nor a path`
  - `without the relay, local items are left out of the Cast window and the rest still cast`

  Run: FAIL.
- [ ] **Step 2:** Implement. PASS. Run the gate (no manifest change).
- [ ] **Step 3: Commit** `feat(castrelay): cast songs on this phone through the LAN relay`.

### Task 6: Car: Downloaded and On this phone

**Files:**
- Create: `M/saraswati/car/CarLocalTree.kt`
- Modify (`// SARASWATI:`):
  - `M/playback/MusicService.kt`: companion `CAR_LOCAL = "car_local"`
  - `M/playback/MediaLibrarySessionCallback.kt`: the `CAR_LIBRARY` tail, the `CAR_LOCAL` branch, and `onSetMediaItems` for `car_local/…`
- Test: `CarLocalTreeTest.kt`; update Phase 3's `CAR_LIBRARY` test

**Interfaces:**
- Consumes: Task 3 (`localSongsPage`, `localSongCount`, `localAccess`), `downloadUtil.downloads`, the existing `androidAutoPageRequest`, `drawableUri`.
- Produces: `object CarLocalTree`, plain non-suspend functions over values already loaded:
  - `fun libraryRows(access: LocalAccess, localCount: Int, downloadedCount: Int): List<MediaItem>`, appended after the `AndroidAutoSection` rows:
    1. "Downloaded": browsable, id `"${MusicService.PLAYLIST}/${PlaylistEntity.DOWNLOADED_PLAYLIST_ID}"`
    2. "On this phone": browsable, id `CAR_LOCAL`. Hidden when `UNSUPPORTED`, or when `GRANTED` with 0 songs. Its subtitle is "Allow on your phone" when `NOT_GRANTED`.
  - `fun children(access: LocalAccess, songs: List<Song>, includeShuffle: Boolean): List<MediaItem>`:
    - without the permission: one disabled item, "Allow on your phone"
    - otherwise: `"$CAR_LOCAL/${MusicService.SHUFFLE_ACTION}"`, then `"$CAR_LOCAL/<songId>"` per song
    - every row's art is `drawableUri(R.drawable.music_note)`, because Android Auto cannot read our content URIs
  - Selecting `car_local/<songId>` queues every local song in title order, starting from that one. Shuffle queues them shuffled.

- [ ] **Step 1: Write failing tests:**
  - `Library ends with Downloaded then On this phone`
  - `On this phone is hidden below Android 13`
  - `hidden when granted with no songs`
  - `without permission the folder holds one disabled Allow on your phone row`
  - `children come from Room only` (the MediaStore reader and network fakes are never called)
  - `selecting a local song queues all local songs from it`
  - `shuffle queues all local songs shuffled`

  Run: FAIL.
- [ ] **Step 2:** Implement. PASS. Run the gate.
- [ ] **Step 3: Commit** `feat(car): Downloaded and On this phone in the car Library`.

### Task 7: Skip-to-playable

**Files:**
- Create in `M/saraswati/offline/`: `OfflinePlayability.kt`, `SkipToPlayable.kt`, `NeverSilentState.kt`, `OfflineBridge.kt`, `NeverSilentController.kt`
- Modify (`// SARASWATI:`): `M/playback/MusicService.kt`. It implements `QueuePort` over the player and calls the controller from:
  - `onPlayerError`'s offline branch, before `waitOnNetworkError()`
  - `handleFinalFailure()`
  - `onMediaItemTransition`
- Test: `SkipToPlayableTest.kt`, `OfflinePlayabilityTest.kt`, `NeverSilentStateTest.kt`, `OfflineBridgeTest.kt`, `NeverSilentControllerTest.kt`

**Interfaces:**
- Consumes: Tasks 2–3.
- Produces:
  - `fun interface OfflinePlayability { suspend fun isPlayableOffline(mediaId: String): Boolean }` and `DefaultOfflinePlayability(downloads: () -> Map<String, Download>, playerCache: Cache, contentLength: suspend (String) -> Long?, access: () -> LocalAccess)`. A song is playable offline when it is local and `GRANTED`, its download is `STATE_COMPLETED`, or `playerCache.isCached(id, 0, length)` holds for a known length.
  - `object SkipToPlayable` with `MAX_SCAN = 200` and `suspend fun nextPlayable(order: List<Int>, idAt: (Int) -> String, playable: suspend (String) -> Boolean): Int?`. `order` is the play order after the current item; it wraps once under repeat-all and never includes the current item.
  - `class NeverSilentState(now: () -> Long)` with `onFinalFailure(mediaId)`, `onNetworkPlaybackStarted()` and `preferOffline(online: Boolean): Boolean`. It becomes degraded after `DEGRADED_AFTER_FAILURES = 2` distinct songs fail within `DEGRADED_WINDOW_MS = 600_000`.
  - `object OfflineBridge` with `BRIDGE_SIZE = 10` and `fun pick(pool: List<String>, exclude: Set<String>, random: Random): List<String>`
  - `interface QueuePort` with `playOrderAfterCurrent(): List<Int>`, `idAt(index): String`, `jumpTo(index)` (seek, prepare, play) and `suspend insertAfterCurrent(ids)`
  - `class NeverSilentController(port, playability, state, bridgePool: suspend () -> List<String>, isCasting: () -> Boolean, isSangatGuest: () -> Boolean, inRoom: () -> Boolean)`. These methods each return true when they handled the event: `suspend onOffline()`, `suspend onFinalFailure(mediaId, online)`, `suspend onTransition(mediaId, online)`.
- **Rules:**
  - Jump to the next playable item. If there is none, insert a bridge and play it. If the bridge is empty too, return false so upstream's behaviour runs unchanged.
  - Never act while casting, or as a Sangat guest or remote controller. Never bridge in a room.
  - `onTransition` acts only when `preferOffline` is true and the item isn't playable offline.

- [ ] **Step 1: Write failing tests:**
  - **Core:**
    - `finds the next downloaded song after streamed ones`
    - `wraps once in repeat-all and never returns the current index`
    - `returns null after one full pass`
    - `stops scanning at 200`
  - **Playability:**
    - `a local song is playable only with the permission`
    - `a completed download is playable`
    - `a fully cached song is playable`
    - `a partly cached song is not`
    - `unknown length is not`
  - **State:**
    - `two final failures within 10 minutes prefer offline`
    - `one failure does not`
    - `a network playback start clears it`
    - `offline always prefers offline`
  - **Bridge:**
    - `picks up to 10 distinct songs not in the queue`
    - `an empty pool gives nothing`
  - **Controller:**
    - `offline with a downloaded song ahead jumps to it`
    - `offline with nothing ahead inserts downloads after the current song`
    - `offline with no downloads returns false`
    - `a streamed transition while offline skips at once`
    - `does nothing while casting`
    - `does nothing for a Sangat guest`
    - `never bridges in a room`
    - `choosing makes no network call` (only `playable` is consulted, counted by the fake)

  Run: FAIL.
- [ ] **Step 2:** Implement and wire. Scanning runs on `Dispatchers.IO`; the jump runs on the main thread. PASS. Run the full JVM suite.
- [ ] **Step 3: Commit** `feat(offline): skip-to-playable for tunnels and YouTube outages`.

### Task 8: Next-song pre-resolve

**Files:**
- Create: `M/saraswati/offline/NextStreamPrefetcher.kt`
- Modify (`// SARASWATI:`): `M/playback/MusicService.kt`
  - Extract the resolver's fetch-and-`songUrlCache.put` block into `private suspend fun resolveAndCacheStream(mediaId: String)`, unchanged in behaviour and used by both the resolver and the prefetcher.
  - Call the prefetcher on each media item transition.
- Test: `NextStreamPrefetcherTest.kt`

**Interfaces:**
- Consumes: Task 7 `OfflinePlayability`.
- Produces: `class NextStreamPrefetcher(scope: CoroutineScope, shouldResolve: suspend (String) -> Boolean, resolve: suspend (String) -> Unit)` with:
  - `fun onTrackStarted(nextId: String?)`
  - `fun cancel()`
  - `PREFETCH_DELAY_MS = 15_000`

  `shouldResolve` is true only when all of these hold: online, not local, not playable offline, and a `songUrlCache` miss.

- [ ] **Step 1: Write failing tests** (virtual time):
  - `resolves the next song 15 s after a track starts`
  - `five skips within 15 s make no lookup`
  - `never resolves a local, downloaded or already resolved song`
  - `no lookup while offline`
  - `one lookup per track`

  Run: FAIL.
- [ ] **Step 2:** Implement. PASS, plus the existing `StreamUrlCache` and playback tests.
- [ ] **Step 3: Commit** `perf(playback): pre-resolve the next stream 15 s into a track`.

### Task 9: Offline-first Stations

**Files:**
- Create: `M/saraswati/offline/StationOfflineStore.kt`, `OfflineFirstStationQueue.kt`
- Modify (`// SARASWATI:` where upstream): Phase 3's station-start path (grep `seedVideoId` in `saraswati/saral/` and `MediaLibrarySessionCallback.kt`), so both Saral and the car build an `OfflineFirstStationQueue`
- Test: `StationOfflineStoreTest.kt`, `OfflineFirstStationQueueTest.kt`

**Interfaces:**
- Consumes: Phase 3 `Station`/`StationsCache`; Phase 4 `MixTrack`; Task 7 `OfflinePlayability`.
- Produces:
  - `fun MixTrack.toMediaMetadata(): MediaMetadata`, in `StationOfflineStore.kt`, unless Phase 4 already defines a mapping (grep `MixDownloader`), in which case reuse that one
  - `@Serializable data class StationOfflineSet(val stationId: String, val seedVideoId: String, val tracks: List<MixTrack>, val refreshedAt: Long)`
  - `class StationOfflineStore(dataStore)` with:
    - `fun cached(): Map<String, StationOfflineSet>` (synchronous)
    - `suspend fun save(set: StationOfflineSet)`
    - `suspend fun retain(stations: List<Station>)`, which drops sets for removed stations and sets whose seed changed
  - `class OfflineFirstStationQueue(title: String, online: Queue, offline: List<MediaMetadata>, isOnline: () -> Boolean, playable: OfflinePlayability, random: Random) : Queue`
    - online: delegates to the existing radio queue
    - offline, or online when the radio fails: plays the station's offline-playable tracks, shuffled, with no next page

- [ ] **Step 1: Write failing tests:**
  - `online uses the radio queue unchanged`
  - `offline plays the station's downloaded songs shuffled`
  - `a radio failure online falls back to downloaded songs`
  - `offline with none downloaded gives an empty status`
  - `retain drops removed stations and changed seeds`
  - `corrupt data reads as empty`

  Run: FAIL.
- [ ] **Step 2:** Implement and wire. An empty status in the car shows Phase 4's "No connection". PASS.
- [ ] **Step 3: Commit** `feat(offline): Stations play their downloaded songs offline`.

### Task 10: Smart download plan and ledger

**Files:**
- Create: `M/saraswati/offline/SmartDownloadPlan.kt`, `SmartDownloadLedger.kt`
- Test: `SmartDownloadPlanTest.kt`, `SmartDownloadLedgerTest.kt`

**Interfaces:**
- Produces:
  - `enum class CandidateSource { PARIVAAR_MIX, STATION, TOP_PLAYED, LIKED }` and `data class Candidate(val metadata: MediaMetadata, val source: CandidateSource)`
  - `@Serializable data class LedgerEntry(val downloadedAt: Long? = null, val pendingSince: Long? = null, val failures: Int = 0)`
  - `@Serializable data class LedgerSnapshot(val entries: Map<String, LedgerEntry>, val night: String?, val doneTonight: Int, val failuresInARow: Int, val lastNightCount: Int)`
  - `object SmartDownloadPlan` with `NIGHTLY_MAX = 50`, `KEEP_MAX = 300`, `MAX_FAILURES_PER_SONG = 2` and:
    - `fun nightKey(now: Instant, zone: ZoneId): String`, the ISO date of `now - 12 h` in local time
    - `fun pick(candidates: List<Candidate>, isDownloaded: (String) -> Boolean, ledger: LedgerSnapshot, night: String): List<Candidate>`
    - `fun evict(ledger: LedgerSnapshot, keep: Set<String>): List<String>`
  - `class SmartDownloadLedger(dataStore)` with `snapshot()`, `pendingIds(): Set<String>`, and the suspend functions `markPending(id, now)`, `markDone(id, now, night)`, `markFailed(id, night)` and `remove(ids)`
- **Pick rules (pinned):**
  - Order by source: mix, stations, top played, liked. On duplicates, the first wins.
  - Skip local ids, already-downloaded ids, and ids with `failures >= 2`.
  - Take at most `NIGHTLY_MAX - doneTonight`.
  - A new `night` resets `doneTonight` and `failuresInARow`, and moves the old count into `lastNightCount`.
- **Eviction rules:** while completed ledger entries exceed `KEEP_MAX`, remove the oldest `downloadedAt` that is not in `keep`. An id that isn't in the ledger (a user download) is never returned.

- [ ] **Step 1: Write failing tests:**
  - **Plan:**
    - `orders mix, then stations, then top played, then liked`
    - `skips local, downloaded and twice-failed songs`
    - `never exceeds 50 a night across calls`
    - `a new night resets the count and records last night's`
    - `night changes at noon local time, not midnight`
    - `evicts beyond 300 oldest first`
    - `never evicts current candidates`
    - `never evicts a user download`
  - **Ledger:**
    - `round-trips`
    - `corrupt data reads as empty`
    - `pendingIds lists only unfinished entries`

  Run: FAIL.
- [ ] **Step 2:** Implement. PASS.
- [ ] **Step 3: Commit** `feat(offline): smart download plan and ledger`.

### Task 11: Smart download job

**Files:**
- Create in `M/saraswati/offline/`: `SmartDownloadScheduler.kt`, `SmartDownloadJobService.kt` (`@AndroidEntryPoint`), `SmartDownloadRunner.kt`, `SmartDownloadExecutor.kt`, `SmartDownloadCandidates.kt`
- Modify (`// SARASWATI:`):
  - `M/playback/DownloadUtil.kt`: extract `suspend fun prepareDownload(mediaMetadata: MediaMetadata): DownloadRequest?` (the DB and artwork preparation, returning `null` when already complete or local); `download()` keeps its behaviour by calling it
  - `M/playback/ExoDownloadService.kt`: `getScheduler()` returns `null` (Spec note 3)
  - `M/App.kt`: `onCreate` launches one `Dispatchers.IO` job, off the startup path, that calls `SmartDownloadScheduler.ensureScheduled` and then the injected executor's `stopPending()`. It reads the ledger first and touches `DownloadUtil` only when the ledger lists pending ids, so cold start is unchanged.
  - `M/constants/PreferenceKeys.kt`: `SmartDownloadsEnabledKey = booleanPreferencesKey("saraswatiSmartDownloads")` (default `true`) and the ledger key
  - `app/src/gms/AndroidManifest.xml`: `<service android:name="com.metrolist.music.saraswati.offline.SmartDownloadJobService" android:permission="android.permission.BIND_JOB_SERVICE" android:exported="false"/>`
- Test: `SmartDownloadSchedulerTest.kt` (Robolectric `ShadowJobScheduler`), `SmartDownloadRunnerTest.kt`, `SmartDownloadCandidatesTest.kt`

**Interfaces:**
- Consumes: Task 10; Task 9 `StationOfflineStore`; Phase 3 `StationsCache`; Phase 4 `ParivaarMixStore.cached()`, `YouTubePlaylistPort.radio`, `FamilyFeatureGate`; DAO `mostPlayedSongs(fromTimeStamp, limit)` and `likedSongsByCreateDateDesc(limit, offset)`; Task 9's `MixTrack.toMediaMetadata()`.
- Produces:
  - `object SmartDownloadScheduler` with `JOB_ID = 5205`, `RESCHEDULE_DELAY_MS = 60_000`, `ensureScheduled(context, enabled: Boolean)`, `scheduleNext(context, notBefore: Instant?)` and `cancel(context)`. Job constraints: `NETWORK_TYPE_UNMETERED`, `setRequiresCharging(true)`, `setRequiresDeviceIdle(true)`, `setRequiresStorageNotLow(true)`, `setPersisted(false)`.
  - `interface SmartDownloadExecutor`, implemented by `DownloadManagerExecutor(downloadUtil)`. It calls `downloadManager.addDownload` and `resumeDownloads()` directly and never starts `ExoDownloadService`.
    - `suspend fun download(c: Candidate, timeoutMs: Long): Boolean`
    - `stop(ids)`, which calls `setStopReason(id, SMART_STOP_REASON)`
    - `resume(ids)`, `remove(ids)`
    - `stopPending()`, which stops every id in `ledger.pendingIds()`
  - `class SmartDownloadCandidates` with `suspend fun gather(now: Instant): List<Candidate>`:
    - mix tracks, only while `parivaar_mix` is on
    - station sets, refreshed when older than 7 days, with `STATION_OFFLINE_TRACKS = 8` each, saved to `StationOfflineStore`
    - the top 30 songs played over 30 days, excluding local ones
    - the latest 20 liked
  - `class SmartDownloadRunner` with `suspend fun runOnce(budgetMs: Long = RUN_BUDGET_MS): RunResult`, where `data class RunResult(val downloaded: Int, val stop: StopCause, val nextNotBefore: Instant?)`
- **Pinned values:**
  - `SMART_STOP_REASON = 5205`
  - `RUN_BUDGET_MS = 480_000` (under the platform's 10-minute job cap)
  - `SPACING_MS = 60_000`, also between station-radio fetches
  - `SONG_TIMEOUT_MS = 180_000`
  - `STOP_AFTER_FAILURES = 2` in a row, after which the run ends for tonight
- **Run flow:**
  1. Resume the runner's own stopped ids.
  2. Gather, then plan.
  3. Download one song at a time, spaced.
  4. At the end, or on `onStopJob`: stop the unfinished smart ids, evict, then schedule the next run: the next noon boundary when tonight is done or stopped by failures, otherwise `RESCHEDULE_DELAY_MS`.
  5. `onStopJob` returns `false`, because the runner reschedules itself.

- [ ] **Step 1: Write failing tests:**
  - **Scheduler:**
    - `schedules one non-persisted job requiring unmetered, charging, idle and storage not low`
    - `is a no-op while the job is pending`
    - `disabled cancels the job`
  - **Runner:**
    - `downloads at most 50 a night across runs`
    - `waits 60 s between songs`
    - `two failures in a row end tonight and schedule the next noon`
    - `unfinished smart downloads are stopped with SMART_STOP_REASON when the budget ends`
    - `stopPending stops leftover smart downloads at process start`
    - `resumes its own stopped downloads next run`
    - `never stops, resumes or removes a user download`
    - `evicts past 300`
  - **Candidates:**
    - `skips the mix when parivaar_mix is off`
    - `refreshes a week-old station set and keeps 8 tracks`
    - `never includes local songs`

  Run: FAIL.
- [ ] **Step 2:** Implement. PASS. Run the gate: no new permission, and the exported list is unchanged.
- [ ] **Step 3: Commit** `feat(offline): nightly smart downloads on Wi-Fi while charging`.

### Task 12: Tap-to-audio and car tracing

**Files:**
- Create: `M/saraswati/perf/TapToAudioClock.kt`, `TapToAudioTracer.kt`
- Modify (`// SARASWATI:`):
  - `M/playback/MusicService.kt`: `player.addAnalyticsListener(tracer)`
  - `M/playback/MediaLibrarySessionCallback.kt`: `Trace.beginSection("SarCarChildren")`/`endSection` around `onGetChildren`'s work
- Test: `TapToAudioClockTest.kt`

**Interfaces:**
- Produces:
  - `class TapToAudioClock(now: () -> Long)` with:
    - `fun onTap(mediaId: String, offline: Boolean)`
    - `fun onAudioAdvancing(): TapSample?`
    - `data class TapSample(val mediaId: String, val offline: Boolean, val elapsedMs: Long)`
    - `MAX_SAMPLE_MS = 30_000`
  - `class TapToAudioTracer(clock: TapToAudioClock, isOfflineNow: (String) -> Boolean) : AnalyticsListener`:
    - A tap is `onMediaItemTransition` with reason `PLAYLIST_CHANGED` or `SEEK`. The end is `onAudioPositionAdvancing`.
    - It emits async trace sections `SarTapToAudioOffline` or `SarTapToAudioNetwork`, and in debug builds only, the Timber line `tapToAudio offline=<b> ms=<n>`.
    - `isOfflineNow` is a synchronous check: the id is local, or `downloadCache`/`playerCache.isCached(id, 0, CHUNK_LENGTH)` is true.

- [ ] **Step 1: Write failing tests:**
  - `a playlist change or seek starts timing`
  - `an automatic transition does not`
  - `first audio ends it once`
  - `a second tap before audio drops the first`
  - `samples over 30 s are dropped`
  - `resuming after a pause yields nothing`
  - `offline is decided at tap time`

  Run: FAIL.
- [ ] **Step 2:** Implement. PASS. Release builds still write no log lines: `grep -rn 'Timber' app/src/main/kotlin/com/metrolist/music/saraswati/perf/` hits only the debug-gated call.
- [ ] **Step 3: Commit** `perf: trace tap-to-audio and car browse for the speed budgets`.

### Task 13: Benchmarks, profile journeys, startup comparison

**Files:**
- Create: `baselineprofile/src/main/kotlin/com/metrolist/music/baselineprofile/TapToAudioBenchmark.kt`, `MetrolistStartupBenchmark.kt`
- Modify: `BaselineProfileGenerator.kt`, `HomeScrollBenchmark.kt`, `scripts/perf_stats.py`, `scripts/test_perf_stats.py`, `docs/perf-baseline.md` (method sections only)

**Interfaces:**
- Consumes: Task 12 section names; Task 3's permission.
- Produces:
  - `TapToAudioBenchmark`:
    - It reads the instrumentation argument `sarVideoIds`: 10 comma-separated ids that the owner chooses at run time and that are never committed.
    - Pass 1 opens each id as `https://music.youtube.com/watch?v=<id>` with `TraceSectionMetric("SarTapToAudioNetwork", Mode.First)`.
    - Pass 2 repeats the same ids under `SarTapToAudioOffline`.
  - `MetrolistStartupBenchmark`: `StartupBenchmark`'s body against `COMPETITOR_PACKAGE = "com.metrolist.music"` in `None()` and `Partial()`.
  - `HomeScrollBenchmark.scrollOnThisPhone`: open Library, then On this phone, and fling.
  - The generator adds these journeys:
    - `device.executeShellCommand("pm grant $TARGET_PACKAGE_NAME android.permission.READ_MEDIA_AUDIO")`
    - Library, then On this phone, then scroll
    - the Family tab
    - Library, then Downloaded

    It keeps `includeInStartupProfile = true`.
  - `parse_am_start_total_time(out)` falls back to `WaitTime` when `TotalTime` is absent. That fixes the gap `docs/perf-baseline.md` records for the Pixel's Android 17 output.

- [ ] **Step 1: Write a failing test** in `scripts/test_perf_stats.py`: `test_falls_back_to_wait_time_when_total_time_is_missing`, using the block `Status: ok / LaunchState: COLD / Activity: … / WaitTime: 612 / Complete`, which gives `612`. Run: FAIL. Implement. PASS.
- [ ] **Step 2:** Write the benchmark and generator changes. Run `sar_gradle :baselineprofile:assemble`. Expected: BUILD SUCCESSFUL. They run on the Pixel in Task 16.
- [ ] **Step 3: Commit** `perf: tap-to-audio and Metrolist startup benchmarks, wider profile journeys`.

### Task 14: On this phone screen and local song menus

**Gated on Task 1 approval.**

**Files:**
- Create in `M/saraswati/localmedia/`: `LocalSongsViewModel.kt`, `LocalSongsScreen.kt`, `LocalAccessPrompt.kt`, `LocalSongMenuPolicy.kt`
- Modify (`// SARASWATI:`):
  - `M/ui/screens/library/LibraryScreen.kt`: the entry row
  - `M/ui/screens/NavigationBuilder.kt`: route `"local_songs"`
  - `M/ui/menu/SongMenu.kt`, `PlayerMenu.kt`, `QueueMenu.kt`: hide items through the policy
  - `M/ui/menu/AddToPlaylistDialog.kt`: local songs list only playlists with `browseId == null`
- Test: `LocalSongsViewModelTest.kt`, `LocalSongMenuPolicyTest.kt`

**Interfaces:**
- Consumes: Tasks 3 and 7.
- Produces:
  - `LocalSongsViewModel`:
    - `state: StateFlow<LocalSongsState>`, where the states are `Unsupported`, `NeedsPermission(deniedCount: Int)`, `Scanning`, `Empty` and `Songs(list)`
    - `fun onPermissionResult(granted: Boolean)`
    - `fun play(index: Int)`, `fun shuffle()`
    - `fun refresh()`
  - Permission comes from `ActivityResultContracts.RequestPermission()`, launched only by the Allow button. After two denials, the button opens the app's settings page instead.
  - `enum class SongAction` and `object LocalSongMenuPolicy { fun visible(action: SongAction, isLocal: Boolean): Boolean }`
    - Hidden for local songs: start radio, share, download, remove from cache, add to or remove from library, suggest to host.
    - Kept: play next, add to queue, add to playlist, edit, remove from history, like (local only, with `toggleLike(syncToYouTube = false)`).

- [ ] **Step 1: Write failing tests:**
  - **ViewModel:**
    - `below Android 13 shows Unsupported and never requests`
    - `nothing is requested until allow`
    - `a grant triggers one sync`
    - `two denials switch to open-settings`
    - `play starts a ListQueue from the tapped index`
    - `shuffle starts shuffled`
  - **Policy:**
    - `local songs hide the YouTube-only actions`
    - `local songs keep the local actions`
    - `streamed songs are unchanged`

  Run: FAIL. Implement. PASS.
- [ ] **Step 2:** Build the screens to the approved mockups. Use keyed `LazyColumn` rows and upstream's song list item. Run the JVM suite and the gate.
- [ ] **Step 3: Commit** `feat(localmedia): On this phone screen and local song menus`.

### Task 15: Smart downloads setting

**Gated on Task 1 approval.**

**Files:**
- Modify (`// SARASWATI:`): `M/ui/screens/settings/StorageSettings.kt`
- Create: `M/saraswati/offline/SmartDownloadsSettingViewModel.kt`
- Test: `SmartDownloadsSettingViewModelTest.kt`

**Interfaces:** Consumes Tasks 10–11. Exposes `enabled`, `fun setEnabled(Boolean)` and `status: StateFlow<String>`, built from the ledger with the approved copy.

- [ ] **Step 1: Write failing tests:**
  - `turning off cancels the job and stops pending smart downloads`
  - `turning on schedules the job`
  - `status shows last night's count`
  - `status shows never-run before the first night`

  Run: FAIL. Implement. PASS.
- [ ] **Step 2:** Build the row to the mockup. Run the JVM suite.
- [ ] **Step 3: Commit** `feat(offline): smart downloads setting`.

### Task 16: HUMAN CHECKPOINT: Pixel measurement pass and profile regeneration

**Files:** `app/src/gmsRelease/generated/baselineProfiles/{baseline-prof.txt,startup-prof.txt}`; `docs/perf-baseline.md`. Delete `app/src/main/baseline-prof.txt` only if Step 2 confirms that AGP reads the generated location.

- [ ] **Step 1: HUMAN CHECKPOINT.** The owner sets up the Pixel: unlocked throughout, battery above 50%, on Wi-Fi, Metrolist 13.6.3 signed in, and a few MP3s under `Music/`. The agent then runs `sar_gradle :app:generateGmsBaselineProfile`.
- [ ] **Step 2:** The agent confirms that the release APK contains `assets/dexopt/baseline.prof`, and that `sar_gradle :app:assembleGmsRelease --info | grep -i startup-prof` shows R8 received the startup profile. If R8 didn't, record why and keep the single baseline file.
- [ ] **Step 3:** The agent runs `sar_gradle :baselineprofile:connectedGmsBenchmarkReleaseAndroidTest -Pandroid.testInstrumentationRunnerArguments.sarVideoIds=<owner's ids>` and `scripts/perf-baseline.sh` for both packages. In `docs/perf-baseline.md` it marks each budget met or missed: cold start (≥ 20% faster than Metrolist, ≤ 1.0 s), tap to audio (p50 ≤ 1.0 s on Wi-Fi, ≤ 300 ms cached), and jank (< 1%).
- [ ] **Step 4:** A missed budget becomes a follow-up naming its hot path from the trace; it is not fixed here. **Commit** `perf: regenerate Baseline Profile and record Phase 5 measurements`.

### Task 17: HUMAN CHECKPOINT: real phones, Cast and the car (DHU)

**Files:** `docs/android-auto-baseline.md`, `docs/rollout-checklist.md` (adds "Android version ≥ 13 for local files" and "allow music on this phone, if wanted"); fixes go to their owning tasks with a regression test.

- [ ] **Step 1: HUMAN CHECKPOINT.** On the Pixel and one Samsung or Xiaomi:
  - allow access
  - WhatsApp voice notes and call recordings are absent
  - an MP3, a FLAC and an M4A play
  - revoking access mid-song moves on to the next song with no crash
- [ ] **Step 2: HUMAN CHECKPOINT.** A queue mixing local and streamed songs casts in full to the Chromecast or Google TV through the relay.
- [ ] **Step 3: HUMAN CHECKPOINT.** On the DHU with the Pixel locked:
  - Library shows Downloaded and On this phone, and a local song plays
  - with Wi-Fi and data off, a streamed queue reaches a downloaded song within one track, and a Station plays its downloaded set
  - connect-to-browse is ≤ 1.0 s (`SarCarChildren`)
  - locked tap-to-audio has p50 ≤ 1.5 s (`tapToAudio`, debug build)

  The agent records the results in `docs/android-auto-baseline.md`.
- [ ] **Step 4: HUMAN CHECKPOINT.** On a signed-in debug build, like a local song, add it to a YouTube-synced playlist and open its lyrics. On the web, YouTube Music shows no like, playlist entry or history entry for it, and debug logcat shows any refused attempt as `LocalSongEgressException` (Spec note 10). **Commit** the docs.

### Task 18: HUMAN CHECKPOINT: two nights of smart downloads

**Files:** none (results go in the ledger; fixes go to Tasks 10–11).

- [ ] **Step 1: HUMAN CHECKPOINT.** Night 1: the Pixel and a grandparent-role phone charge on home Wi-Fi. In the morning:
  - `adb shell dumpsys jobscheduler | grep -A5 5205` shows a non-persisted job
  - the ledger shows ≤ 50 songs
  - the Parivaar Mix and Station sets are downloaded
  - Saral plays a Station in airplane mode
- [ ] **Step 2: HUMAN CHECKPOINT.** Night 2: unplug at midnight. Downloading stops, and nothing resumes on mobile data in the morning. After a reboot, opening Saraswati re-arms the job. With a user download queued, airplane mode causes no crash (the `null` scheduler).

### Task 19: HUMAN CHECKPOINT: real car drive, Beta, Stable

**Files:** none (fixes go to their owning tasks).

- [ ] **Step 1:** Ship to Beta through Phase 2's release workflow, after the owner's approval tap. 48 h on the owner's phone.
- [ ] **Step 2: HUMAN CHECKPOINT.** A real car drive that includes a tunnel or dead zone, with a streamed queue, as the spec requires for any release touching playback or browsing:
  - music never stops
  - Downloaded and On this phone are reachable in two taps
  - no lyrics appear on the car screen
- [ ] **Step 3:** Promote to Stable with the owner's approval.

## Self-review notes

- **Spec coverage:**
  - **Local files:**
    - indexing and the permission (Task 3)
    - playback (Task 4)
    - Cast (Task 5)
    - the car (Task 6)
    - the phone screen (Task 14)
    - "never sent to YouTube, lyrics or sync" (Tasks 2 and 4, checked in Task 17 Step 4)
  - **Baseline Profiles:** Tasks 13 and 16.
  - **Smart downloads:** Tasks 10–11, 15 and 18, including "Parivaar Mix and Stations downloaded for offline use" (Tasks 9 and 11).
  - **Skip-to-playable:** Task 7.
  - **Budgets:**
    - cold start, tap to audio and jank (Tasks 12, 13 and 16)
    - car browse and locked tap to audio (Task 17)
    - gapless pre-resolve (Task 8)
    - crash-free sessions and playback failures need Sentry, which this base doesn't have (no `io.sentry` anywhere), so they are out of scope
  - **Verification:** the DHU offline run (Task 17) and the real car drive before Stable (Task 19).
- **Type consistency:**
  - `LocalSongIds` (Task 2) and `LocalAccess` (Task 3) are used unchanged in Tasks 4–7, 11 and 14.
  - `OfflinePlayability` (Task 7) is consumed by Tasks 8 and 9.
  - `MixTrack.toMediaMetadata()` and `StationOfflineStore` (Task 9) are consumed by Task 11.
  - `Candidate`/`LedgerSnapshot` (Task 10) are consumed by Tasks 11 and 15.
  - `SMART_STOP_REASON` and `JOB_ID` are defined once, in Task 11.
- **Review Focus:** each of the five has a named test:
  - Task 2 guard tests and Task 4 `a local track is never sent as TrackInfo`
  - Task 3 filter tests
  - Task 7 controller tests
  - Task 11 `unfinished smart downloads are stopped…` and `stopPending…`
  - Task 6 and Task 14 permission tests, plus Task 7 `a local song is playable only with the permission`
- **Proportion:** 19 tasks: 14 agent tasks (2–15, with 14–15 gated on the mockups), 1 design gate and 4 device or car checkpoints. Every step gives signatures, pinned values and test names. No implementation bodies.
