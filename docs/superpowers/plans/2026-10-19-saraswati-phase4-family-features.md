# Saraswati Phase 4: Family Features Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The four family features plus Spotify import, each behind its kill-switch: Sangat (listen together) served by the family hub Worker, with a car mode where passengers join as remote controllers; Parivaar Mix; family playlists; a LAN Cast relay that sends the whole queue; and a Spotify import.

**Architecture:** Phase 3's single `FamilyHub` Durable Object gains a Sangat engine (`worker/src/sangat/`). It speaks metroserver's protobuf+gzip WebSocket protocol on `/ws` through the WebSocket Hibernation API. Every room, member, pending join and suggestion lives in the DO's SQLite, and one DO alarm replaces metroserver's timers. A few JSON hub routes carry what the protocol can't: live rooms, car mode, and the Parivaar and family-playlist registries. The app keeps Metrolist's Listen Together client, points it at the hub with the phone's hub session token, and adds a remote-controller mode for car passengers. Parivaar Mix, family playlists, the Cast relay and Spotify import are new isolated packages under `saraswati/`. Each is a pure, unit-tested core behind a thin Android shell. Compose UI comes last because of the design gate.

**Tech Stack:** TypeScript on Cloudflare Workers + Durable Objects (Hibernation API, SQLite storage, alarms), `@bufbuild/protobuf` with `buf`/`protoc-gen-es` codegen, `@cloudflare/vitest-plugin` (existing), Docker (only to run the reference Go metroserver for trace goldens), Kotlin/Compose (existing), OkHttp WebSocket (existing), Media3 Cast and `CacheDataSource` (existing), `java.net.ServerSocket` for the relay (no new library), kotlinx.serialization (existing).

**Spec:** `docs/superpowers/specs/2026-10-17-saraswati-design.md`: Phase 4, Architecture (Worker, Parivaar Mix, Cast relay), Android Auto ("Sangat in the car"), Security, Decisions. Format precedent: `2026-10-18-saraswati-phase3-hub-saral-car-rollout.md`.

## Preconditions (Phase 3 merged to `main`)

Phase 4 consumes these Phase 3 deliverables. Task 8 greps for each and adds only what is missing.
- Worker `FamilyHub` with `authenticate()`, `rateLimit()`, `throttleAdmin()`, and the `/pairing/status` session-token hand-off.
- `HubClient`/`HubModels` (`saraswati/family/`), the `FamilyFeature` enum, `BuildConfig.HUB_BASE_URL`, and the Worker host in `network_security_config.xml`.
- Keystore-sealed `FamilyHubSessionTokenKey`/`FamilyAdminKeyKey`, `IsFamilyOwnerKey`, and the deviceId stored at claim time.
- `FamilyScreen`/`FamilyViewModel`, the Home banner slot, `StationsCache`, `GrandparentRoleKey`.
- Car constants `MusicService.CAR_HOME/_LIBRARY/_STATIONS/_FAMILY`, with Family's placeholder row.

## Spec notes (decisions where the spec is silent or conflicts with itself)

1. **"The app only changes its URL" vs "the Worker rejects an unpaired phone".** The app also adds one header to the WebSocket upgrade: `Authorization: Bearer <hub session token>`. The Worker drops metroserver's User-Agent policy, because auth replaces it. Every message after the upgrade is metroserver-compatible.
2. **"A prompt from the hub" has no push channel.**
   - FCM would put a service-account credential in the Worker, which "stores no credentials".
   - WorkManager's merged manifest adds `RECEIVE_BOOT_COMPLETED`, which the manifest gate forbids.
   - So passenger phones poll `GET /sangat/live` every 30 s, only while Saraswati is in the foreground (never in Saral). They show an in-app prompt and the Home "Sangat live" banner. A passenger whose app is closed sees the prompt when they open it.
3. **Spotify import.** ArchiveTune's Spotify path needs three things the Security section forbids:
   - a login WebView on `accounts.spotify.com` (the spec allows Google-only login WebViews)
   - a stored `sp_dc` session cookie (the spec keeps only YouTube cookies)
   - a TOTP secret fetched at run time from `gist.githubusercontent.com` (the same remote-config shape the A12-b audit switched off, on a banned host)

   This plan ports only ArchiveTune's YouTube-matching half. Data comes from files the person exports themselves: Spotify's account-data export (zip or JSON) or an Exportify CSV. No Spotify host is allowlisted.
4. **Taste.** The spec says both "Sangat … plays don't count" and, in the car, "only songs you add count toward your own taste". This plan uses one rule for every Sangat: a play counts for you only if you added it. Parivaar Mix plays never count. The existing-design toggle "Count Sangat and Parivaar Mix plays" makes all of them count.
5. **Design gate vs. building UI before checkpoints.** The spec's gate ("mockups approved in the browser before UI code") wins. Compose UI (Tasks 19–21) waits for Task 1's approval. The car browse tree is template-rendered by Android Auto, not Compose, so it is not gated (as in Phase 3 Task 11).
6. **Spotify import has no kill-switch.** It is local-only and one-shot, and the hub's four keys are fixed. No fifth key is added.
7. **Parivaar list order.** `GET /parivaar/playlists` orders by SQLite's binary collation (`memberName`, then `deviceId`), so "Zed" sorts before "asha". The app sorts for display.

## Global Constraints

- **Branch** `phase4-family-features` off `main` once Phase 3 has merged.
- **Phase 1–3 constraints still apply:**
  - `// SARASWATI:` markers on upstream files
  - `source scripts/dev-env.sh && sar_gradle …`, gms flavor only
  - commit format `type(scope): …` plus the harness attribution trailer
  - new strings only in `app/src/main/res/values/metrolist_strings.xml`
  - no DB schema changes
  - `security/apk_gate.py check` and `sar_gradle :app:testGmsDebugUnitTest` green before each commit
- **Kill-switch keys are exactly** `"sangat"`, `"parivaar_mix"`, `"family_playlists"`, `"cast_relay"`.
  - **App (Task 8 `FamilyFeatureGate`):** a phone that has never reached the hub treats all four as enabled (fail open). After the first answer, the last value fetched persists across unreachability, so an owner's deliberate "off" survives a dead zone. A hub failure never turns anything off.
  - **Worker:** each Phase 4 route returns `503 {"error":"feature disabled"}` when its key is off. Flipping `sangat` off closes every Sangat socket with close code `4003`.
- **Worker:**
  - Still one DO class `FamilyHub`, one instance `idFromName("family")`, state only in `ctx.storage.sql`, no KV/R2/Queues.
  - Sangat uses the Hibernation API: `ctx.acceptWebSocket`, the `webSocketMessage`/`webSocketClose`/`webSocketError` handlers, and `serializeAttachment` holding `{userId, deviceId}`. Room state must never live only in memory; in-memory maps are caches rebuildable from SQL.
  - One DO alarm (`ctx.storage.setAlarm`), never later than the earliest pending deadline.
  - No `eval`/`new Function` anywhere (Workers forbid them; this is why protobuf-es, not protobufjs).
  - New npm deps, pinned to exact versions found with `npm view <pkg> version`: `@bufbuild/protobuf` (runtime), `@bufbuild/protoc-gen-es` and `@bufbuild/buf` (dev).
  - `wrangler.toml` is unchanged: the new tables live inside the existing SQLite class, so no migration is needed.
- **Protocol source of truth** is `app/src/main/proto/listentogether.proto`. `worker/src/gen/listentogether_pb.ts` is generated from it, never hand-edited.
- **Parity reference** is metroserver `MetrolistGroup/metroserver` at commit `9da6f9da1f3ec92183a26dc8f6fbf9c8b3f0ede8`. It is read as data, run only inside Docker, and never vendored.
  - Values copied from it into `worker/src/sangat/limits.ts`:
    - room code: 8 chars from `1234567890QWERTYUPASDFGHJLKZXCVBNM`
    - reconnect grace: 15 min
    - empty-room cleanup: 5 min
    - text limits: username ≤ 50, room code input ≤ 10, track id ≤ 200, title/artist/album ≤ 200, thumbnail ≤ 2048
    - track duration: `180000` ms when ≤ 0, capped at `86400000`
    - room limits: queue ≤ 1000, pending joins ≤ 100, pending suggestions ≤ 100, clients per room ≤ 100
    - message limits: read message ≤ 524288 B, decoded payload ≤ 1048576 B, envelope type ≤ 64
    - rates: 60 messages/s per connection (error `rate_limited`), `request_sync` answered at most once per 1000 ms
    - gzip only when the payload is over 100 B and gets smaller
  - Error `code` and `message` strings are copied verbatim (`not_host`, `room_not_found`, `unknown_message_type`, …).
  - Deliberate divergences: the auth header (Spec note 1); no UA policy (no `/uas`, no advert queue titles, every authenticated device may host); car mode set over HTTP; close codes `4001` (device revoked) and `4003` (sangat disabled).
    - a leaver's pending suggestions are deleted when they leave or are removed (metroserver may keep them)
- **New hub routes** (JSON; auth as in Phase 3: "device" = approved session token, "admin" = `ADMIN_KEY`):

  | Method | Path | Auth | Body → Response |
  |---|---|---|---|
  | GET (Upgrade) | `/ws` | device only | → `101` WebSocket; `426` without `Upgrade: websocket`; `401` for unauthenticated, pending, revoked or admin bearer; `503` when `sangat` is off |
  | GET | `/sangat/live` | device or admin | → `{"rooms":[{"roomCode","hostName","hostDeviceId","carMode":bool,"memberCount":int,"startedAt":"<ISO>"}]}` (rooms whose host is connected) |
  | PUT | `/sangat/car` | device | `{"roomCode","carMode":bool}` → same shape; `404` unknown room, `403` caller is not the room's host device |
  | PUT | `/parivaar/playlist` | device | `{"playlistId","memberName"}` → `{"deviceId","memberName","playlistId","updatedAt":"<ISO>"}` |
  | GET | `/parivaar/playlists` | device or admin | → `{"playlists":[…same shape…]}` (approved devices only, ordered by `memberName`, then `deviceId`) |
  | GET | `/family-playlists` | device or admin | → `{"playlists":[{"id","playlistId","title","addedByDeviceId","addedAt":"<ISO>"}]}` |
  | POST | `/family-playlists` | device | `{"playlistId","title"}` → `201` row; `409` duplicate `playlistId` or 20 rows already |
  | DELETE | `/family-playlists/{id}` | admin, or the device that added it | → `204`; `403` other devices; `404` unknown |

  Validation: `playlistId` matches `^[A-Za-z0-9_-]{10,64}$`; `memberName` is 1–50 chars after trim; `title` is 1–150 chars after trim; anything else is `400`. Write rate limits (fixed window, existing `rateLimit`): `parivaar:<deviceId>` 10 per 3600 s, `familyplaylists:<deviceId>` 20 per 3600 s, `429` past either.
- **App packages:**
  - `saraswati/family/{sangat,parivaar,playlists}`, `saraswati/castrelay`, `saraswati/car`, `saraswati/spotifyimport`
  - tests mirrored under `app/src/test/kotlin/com/metrolist/music/saraswati/…`
  - gms-only Cast code stays in `app/src/gms/…`
- **No new Android permission and no new Gradle dependency.**
  - No WorkManager (Spec note 2). Periodic work runs opportunistically when the app comes to the foreground, next to Phase 3's launch-time hub refresh.
  - No `ACCESS_WIFI_STATE`: the relay reads the Wi-Fi address from `ConnectivityManager` link properties under `ACCESS_NETWORK_STATE`.
  - No `ACCESS_LOCAL_NETWORK` while `targetSdk = 36`; it is added only with the future 37 bump.
- **No new network hosts.** Sangat uses the hub host Phase 3 allowlisted (wss). The relay is a LAN server, so the app makes no outbound cleartext. Spotify import makes no Spotify calls.
- **Taste rule (Spec note 4):** `counts = Solo || (Sangat && addedBySelf) || countSharedPlays`, where `countSharedPlays` is `CountSharedPlaysKey`, default `false`. A play that doesn't count writes neither `YouTube.registerPlayback` nor the local `Event`/total-play-time rows. That keeps shared plays out of next week's top-songs playlist, and out of local History.
- **Car rules (unchanged from Phase 3):**
  - `onGetChildren` reads cached snapshots only, never awaiting the network
  - no lyrics
  - errors are one short phrase
  - sign-in and Spotify import are phone-only
- **Hindi name + English subtitle copy:**
  - "Sangat · Listen together", "Parivaar Mix · Family mix"
  - car: "Start Sangat", "End Sangat", "⟨name⟩ started Sangat in the car"
  - car errors: "Pair this phone first", "Sangat is off", "No connection"
- **Commands:**
  - Worker: `cd worker && npx vitest run [test/<file>]`, `npx tsc --noEmit`
  - App: `source scripts/dev-env.sh && sar_gradle :app:testGmsDebugUnitTest --tests '<pattern>'`
- **Human checkpoints.** The agent asks, then continues with any unblocked task; each checkpoint gates only its own task.
  - Task 1: mockup approval (gates Tasks 19–21)
  - Task 22: `wrangler deploy` on the owner's Cloudflare account, two-phone Sangat, DHU car Sangat
  - Task 23: signed-in browser capture of collaborative playlists
  - Task 24: Cast spike on real home devices
  - Task 25: real-account Parivaar Mix, a real Spotify export, a real car drive, the release approval

  Agents never hold Cloudflare or Google credentials and never act on a family member's YouTube account.

## Review Focus

1. **A stranger, a pending phone or a just-revoked phone reaches a Sangat room.** `/ws` must refuse before upgrading. Knowing a room code must grant nothing. A revoke in Manage Family must cut that phone's live socket at once (`4001`) and remove it from its room, not wait for a reconnect. Owner: Tasks 4 and 6.
2. **The DO is evicted or restarted mid-drive and the driver's reconnect gets `session_not_found`.** A fresh `Sangat` instance over the same storage must answer `reconnect` with the full room. Owner: Task 4 (`a fresh Sangat instance over the same storage restores the room for a reconnecting host`).
3. **A revoked phone, or one with Sangat switched off, hammers the hub.** Without the right policy it would retry upstream's 15 reconnect attempts and keep the 30 s live poll running, burning battery and the free plan. `401`/`503` on upgrade and close codes `4001`/`4003` must end reconnecting. The live poll must stop when the switch is off or the app is backgrounded. Owner: Task 9.
4. **The Cast relay serves anything beyond the current queue to anyone beyond the Cast device's Wi-Fi.** That covers a wrong token, a path like `/../`, a mobile-data or hotspot interface, and a server still listening after casting ends. Owner: Tasks 16–17.
5. **Taste pollution.** In car Sangat a passenger's pick must not register on the driver's YouTube account. Parivaar Mix plays must not feed the next weekly top-songs playlist (a feedback loop). Owner: Task 13.

---

### Task 1: Phase 4 mockups (design gate)

**Files:** scratch only (throwaway HTML, as in Phase 3 Task 1).

**Interfaces:** Produces the owner's approval record that Tasks 19–21 require.

- [ ] **Step 1:** Extend Phase 3's approved look (its ledger note) with clickable mockups for:
  - the Family tab's sections in spec order: Parivaar Mix, Family playlists, Sangat (start, live rooms, join), Cast
  - Home's Parivaar Mix card and "Sangat live" banner, in Phase 3's banner slot
  - the passenger prompt "⟨name⟩ started Sangat in the car" (Join / Not now)
  - the passenger remote-controller screen: room's now-playing and upcoming queue, "Add a song", no transport controls, a line saying nothing plays on this phone
  - a family playlist screen, including its read-only state for non-creators
  - the "Count Sangat and Parivaar Mix plays toward my taste" toggle
  - Spotify import: choose file → matching progress → review unmatched → done

  Publish them with the Artifact tool (quickstart `intent: "design"`).
- [ ] **Step 2: HUMAN CHECKPOINT (non-blocking).** Send the link. Tasks 2–18 continue. Tasks 19–21 start only after explicit approval. Record the approved copy and layout in the ledger.

### Task 2: Worker protobuf codegen and envelope codec

**Files:**
- Create: `worker/buf.gen.yaml`, `worker/src/gen/listentogether_pb.ts` (generated), `worker/src/sangat/codec.ts`, `worker/src/sangat/limits.ts`
- Modify: `worker/package.json` (deps; scripts `gen`, `gen:check`)
- Test: `worker/test/sangat.codec.test.ts`

**Interfaces:**
- Produces:
  - `encodeEnvelope(type: string, payload: Uint8Array): Promise<Uint8Array>`
  - `decodeEnvelope(bytes: Uint8Array): Promise<{ type: string; payload: Uint8Array }>`, throwing `CodecError` with `code === "invalid_message"`
  - every limit in Global Constraints as a named export of `limits.ts`
  - protobuf-es schemas (`EnvelopeSchema`, `RoomStateSchema`, `TrackInfoSchema`, …)

- [ ] **Step 1:** Pin the three deps. Add `"gen": "buf generate ../app/src/main/proto --template buf.gen.yaml"` and a `gen:check` script that regenerates into a temp dir and `diff -r`s it against `src/gen`.
- [ ] **Step 2: Write failing tests:** `round-trips an uncompressed envelope`, `gzips a payload over 100 bytes only when that makes it smaller and sets compressed`, `leaves a 100-byte payload uncompressed`, `decodes a compressed envelope`, `rejects a payload that inflates past 1048576 bytes` (gzip bomb), `rejects an empty type`, `rejects a type longer than 64 chars`, `rejects bytes that are not an Envelope`. Run `npx vitest run test/sangat.codec.test.ts`. Expected: FAIL.
- [ ] **Step 3:** Implement with `CompressionStream`/`DecompressionStream("gzip")`. Decompression streams with a running byte count and aborts at the cap, never buffering the whole inflated payload first.
- [ ] **Step 4:** Tests PASS; `npm run gen:check` exits 0; `npx tsc --noEmit` is clean.
- [ ] **Step 5: Commit** `feat(worker): protobuf codegen and Sangat envelope codec`.

### Task 3: Reference traces from metroserver

**Files:**
- Create: `worker/scripts/record-traces.ts`, `worker/test/traces/scenarios.ts`, `worker/test/traces/normalize.ts`, `worker/test/traces/*.golden.json`, `worker/test/sangat.traces.test.ts`

**Interfaces:**
- Consumes: Task 2 schemas.
- Produces:
  - `type TraceStep = { client: string; send?: { type: string; payload: object }; close?: true; reconnect?: true }`
  - `normalize(messages: Array<{ client: string; type: string; payload: object }>): object[]`
  - `replayAgainstWorker(scenario: string): Promise<object[]>`, which Tasks 4–5 use as acceptance tests

- [ ] **Step 1:** Run metroserver in Docker:
  1. Clone metroserver at `9da6f9da1f3ec92183a26dc8f6fbf9c8b3f0ede8` into the scratchpad and run `git submodule update --init`.
  2. `docker build -t metroserver:9da6f9d .`
  3. Run it on `:8080` with `UA_POLICY_FILE` mounted read-only, allowing the recorder's User-Agent. Its default policy puts unknown User-Agents in the "advert" tier, which may not host.
- [ ] **Step 2:** Define 19 scenarios in `scenarios.ts`: `create_room`, `join_approve`, `join_reject`, `join_unknown_room`, `guest_playback_not_host`, `host_play_pause_seek`, `change_track_and_queue_add`, `queue_add_duplicate`, `queue_remove_and_clear`, `suggest_approve`, `suggest_reject`, `buffer_ready_complete`, `kick_user`, `transfer_host`, `leave_room`, `reconnect_after_close`, `reconnect_unknown_token`, `ping_pong_and_request_sync`, `invalid_and_unknown_messages`. Each lists ordered `TraceStep`s for clients `A`, `B` and `C`.
- [ ] **Step 3:** Write the recorder, run as `node --experimental-strip-types scripts/record-traces.ts ws://localhost:8080/ws`.
  - It encodes each `send` from proto3 JSON with the generated schemas.
  - It collects each client's server messages until 500 ms of quiet, then writes `<scenario>.golden.json`.
  - Normalization replaces each value, in order of first appearance, with a placeholder:
    - room codes → `$ROOM1…`
    - user ids → `$USER1…`
    - session tokens → `$TOKEN1…`
    - suggestion ids → `$SUGG1…`
    - `server_time`, `server_receive_time`, `server_send_time`, `last_update`, `captured_at_server_time` → `"$TIME"`
    - `position` while `is_playing` → `"$POS"`
  - Everything else, including error messages, is compared verbatim.
- [ ] **Step 4:** Write `sangat.traces.test.ts`. One test per golden replays the scenario through `env.FAMILY_HUB` WebSockets, with each trace client seeded as its own approved roster device via `runInDurableObject`, and asserts `toEqual` against the golden. If the pool's isolated storage refuses WebSocket tests, put the Sangat test files in a second vitest project with `isolatedStorage: false` and clear `sangat_*` tables in its setup. Never mock the WebSocket. Run: FAIL (no `/ws` yet), which is expected.
- [ ] **Step 5: Commit** `test(worker): metroserver reference traces for Sangat parity`.

### Task 4: Sangat rooms and lifecycle in `FamilyHub`

**Files:**
- Create: `worker/src/sangat/Sangat.ts`, `worker/src/sangat/schema.ts`, `worker/src/sangat/rooms.ts`, `worker/src/sangat/lifecycle.ts`
- Modify: `worker/src/FamilyHub.ts` (constructor calls `createSangatTables`; route `/ws`; `webSocketMessage`/`webSocketClose`/`webSocketError`/`alarm` delegate to `Sangat`)
- Test: `worker/test/sangat.rooms.test.ts`, `worker/test/sangat.lifecycle.test.ts`

**Interfaces:**
- Consumes: Tasks 2–3; `authenticate()` and the kill-switch reader in `FamilyHub`.
- Produces:
  - `class Sangat` with:
    - `constructor(ctx: DurableObjectState)`
    - `accept(deviceId: string): Response`
    - `onMessage(ws: WebSocket, data: ArrayBuffer | string): Promise<void>`
    - `onClose(ws: WebSocket): Promise<void>`
    - `onAlarm(): Promise<void>`
    - `liveRooms(): LiveRoom[]`
    - `setCarMode(roomCode: string, deviceId: string, carMode: boolean): "ok" | "not_found" | "not_host"`
    - `closeDevice(deviceId: string, code: number, reason: string): void`
    - `closeAll(code: number, reason: string): void`
  - `type LiveRoom = { roomCode: string; hostName: string; hostDeviceId: string; carMode: boolean; memberCount: number; startedAt: number }`
  - Tables:
    - `sangat_rooms(code PK, hostUserId, hostDeviceId, carMode INTEGER, state BLOB /* RoomState bytes */, createdAt, emptySince, hostDisconnectedAt)`
    - `sangat_members(userId PK, roomCode, deviceId, username, sessionToken UNIQUE, isHost, connected, disconnectedAt)`
    - `sangat_pending_joins(roomCode, userId, username, deviceId, PRIMARY KEY(roomCode, userId))`
    - `sangat_suggestions(id PK, roomCode, fromUserId, fromUsername, track BLOB, createdAt)`

- [ ] **Step 1: Write failing tests** (`sangat.rooms.test.ts`):
  - `426 without an Upgrade header`
  - `401 without a bearer`
  - `401 for a pending device`
  - `401 for a revoked device`
  - `401 for the admin key`
  - `503 when sangat is off`
  - `create_room answers room_created with an 8-char code from the metroserver alphabet and a 64-hex session token`
  - `join_room for an unknown code answers error room_not_found`
  - `join_room sends join_request to the host; approve_join sends join_approved with the full RoomState and user_joined to the others`
  - `reject_join sends join_rejected`
  - `leave_room sends user_left`
  - `ping answers pong echoing client_time and sequence`
  - `every room change is written to sangat_rooms before the next message is handled` (checked via `runInDurableObject`)
- [ ] **Step 2: Write failing tests** (`sangat.lifecycle.test.ts`):
  - `a socket close marks the member disconnected and sends user_disconnected`
  - `reconnect within 15 minutes answers reconnected with is_host and the current state`
  - `reconnect after 15 minutes answers session_expired` (age `disconnectedAt` in SQL, then `runDurableObjectAlarm`)
  - `a fresh Sangat instance over the same storage restores the room for a reconnecting host`
  - `an empty room is deleted 5 minutes after its last member leaves`
  - `the alarm is never later than the earliest pending deadline`
  - `when the host's 15-minute grace expires, the earliest-joined connected member becomes host and everyone gets host_changed` (metroserver picks from a Go map; the Worker makes it deterministic)

  Run: FAIL.
- [ ] **Step 3:** Implement, porting `rooms.go`/`lifecycle.go` semantics from the pinned commit. Auth and kill-switch guards run before any storage write. Ids: `user_<ms>_<0-9999>` (metroserver's format); session tokens are 32 random bytes as hex.
- [ ] **Step 4:** Room and lifecycle tests PASS, and so do the room-level trace scenarios (`create_room`, `join_*`, `leave_room`, `reconnect_*`, `ping_pong_and_request_sync`). The remaining traces still fail until Task 5.
- [ ] **Step 5: Commit** `feat(worker): Sangat rooms and lifecycle on the FamilyHub DO`.

### Task 5: Playback, queue, suggestions, buffering, moderation

**Files:**
- Create: `worker/src/sangat/playback.ts`, `worker/src/sangat/suggestions.ts`, `worker/src/sangat/sanitize.ts`
- Modify: `worker/src/sangat/Sangat.ts`
- Test: `worker/test/sangat.playback.test.ts`

**Interfaces:**
- Consumes: Task 4's `Sangat` and tables.
- Produces: handlers for `playback_action`, `buffer_ready`, `suggest_track`, `approve_suggestion`, `reject_suggestion`, `kick_user`, `transfer_host`, `request_sync`. Also `sanitizeTrackInfo(t): TrackInfo | null`, a port of `validation.go`.

- [ ] **Step 1: Write failing tests:**
  - `a guest playback_action answers not_host`
  - `host play/pause/seek broadcasts sync_playback with server_time and a rising revision`
  - `queue_add past 1000 answers queue_full`
  - `a duplicate queue_add answers duplicate_track`
  - `track text is trimmed to the limits and a zero duration becomes 180000`
  - `an approved suggestion goes to the front of the queue with suggested_by set to the suggester's username`
  - `buffer_wait lists connected members until each sends buffer_ready, then buffer_complete`
  - `kick_user of self answers cannot_kick_self`
  - `transfer_host sends host_changed`
  - `request_sync answers sync_state at most once per second`
  - `the 61st message within a second answers rate_limited`
  - `an unknown type answers unknown_message_type`

  Run: FAIL.
- [ ] **Step 2:** Implement, porting `playback.go`/`rooms.go`.
- [ ] **Step 3:** All playback tests and **all 19 trace goldens** PASS. `npx vitest run` passes for the whole Worker suite (Phase 3 tests included).
- [ ] **Step 4: Commit** `feat(worker): Sangat playback, queue, suggestions and buffering`.

### Task 6: Sangat hub routes, revoke and kill-switch enforcement

**Files:**
- Create: `worker/src/http.ts` (move `json`, `readJsonBody`, `readStrictJsonObject`, `isNonEmptyString` out of `FamilyHub.ts` unchanged)
- Modify: `worker/src/FamilyHub.ts`
- Test: `worker/test/sangat.hub.test.ts`

**Interfaces:**
- Consumes: `Sangat.liveRooms/setCarMode/closeDevice/closeAll`.
- Produces: `GET /sangat/live` and `PUT /sangat/car` per the route table.
  - `POST /roster/{id}/revoke` also calls `sangat.closeDevice(id, 4001, "revoked")` and drops that device's members.
  - `PUT /killswitches` with `"sangat": false` also calls `sangat.closeAll(4003, "sangat disabled")` and deletes all rooms.

- [ ] **Step 1: Write failing tests:**
  - `live lists only rooms whose host is connected, with hostName and memberCount`
  - `live is 401 without auth and 503 when sangat is off`
  - `car mode set by the host device shows carMode true in live`
  - `car mode from another device is 403`
  - `car mode for an unknown room is 404`
  - `car mode with a non-boolean is 400`
  - `revoking a device closes its socket with 4001 and removes it from its room`
  - `switching sangat off closes every socket with 4003 and empties sangat_rooms`
  - `a revoked device's reconnect token no longer works`

  Run: FAIL.
- [ ] **Step 2:** Implement. The existing Phase 3 tests must still pass after the `http.ts` move.
- [ ] **Step 3: Commit** `feat(worker): Sangat live rooms, car mode, revoke and kill-switch enforcement`.

### Task 7: Parivaar and family-playlist registries

**Files:**
- Create: `worker/src/hubContext.ts`, `worker/src/routes/parivaar.ts`, `worker/src/routes/familyPlaylists.ts`
- Modify: `worker/src/FamilyHub.ts` (tables; routing; export `FamilyFeatureKey`)
- Test: `worker/test/parivaar.test.ts`, `worker/test/familyplaylists.test.ts`

**Interfaces:**
- Produces:
  - `interface HubContext { sql: SqlStorage; rateLimit(key: string, windowSeconds: number, max: number): boolean; featureEnabled(key: FamilyFeatureKey): boolean; now(): number }`
  - handlers `(ctx: HubContext, request: Request, auth: AuthResult) => Promise<Response>`
  - tables `parivaar_playlists(deviceId PK, memberName, playlistId, updatedAt)` and `family_playlists(id PK, playlistId UNIQUE, title, addedByDeviceId, addedAt)`
  - the four routes in the table

- [ ] **Step 1: Write failing tests:**
  - **Parivaar:**
    - `PUT upserts this device's row and ignores any deviceId in the body`
    - `GET hides rows of revoked devices`
    - `GET orders by memberName then deviceId`
    - `admin can GET but not PUT`
    - `bad playlistId or a 51-char memberName is 400`
    - `the 11th PUT in an hour is 429`
    - `503 when parivaar_mix is off`
  - **Family playlists:**
    - `POST creates a row with a uuid id`
    - `duplicate playlistId is 409`
    - `the 21st playlist is 409`
    - `the adding device and admin can DELETE, another device gets 403`
    - `a 151-char title is 400`
    - `503 when family_playlists is off`

  Run: FAIL.
- [ ] **Step 2:** Implement. Run `npx vitest run`. Expected: PASS for the whole Worker suite.
- [ ] **Step 3: Commit** `feat(worker): Parivaar and family-playlist registries`.

### Task 8: App hub client additions, feature gate, owner self-pairing

**Files:**
- Modify: `saraswati/family/HubClient.kt`, `saraswati/family/HubModels.kt`, `saraswati/family/FamilyViewModel.kt`
- Create: `saraswati/family/FamilyFeatureGate.kt`, `saraswati/family/HubIdentity.kt`
- Test: `HubClientPhase4Test.kt`, `FamilyFeatureGateTest.kt`, extend `FamilyViewModelTest.kt`

**Interfaces:**
- Consumes: the Phase 3 items listed under Preconditions (grep each first; add a missing deviceId store as plain `FamilyDeviceIdKey`, written at claim).
- Produces:
  - **New `HubClient` functions,** each returning `Result<T>` and never throwing:
    - `getLiveSangat(sessionToken: String): Result<List<LiveSangatRoom>>`
    - `setSangatCarMode(sessionToken: String, roomCode: String, carMode: Boolean): Result<Unit>`
    - `putParivaarPlaylist(sessionToken: String, playlistId: String, memberName: String): Result<ParivaarPlaylist>`
    - `getParivaarPlaylists(sessionToken: String): Result<List<ParivaarPlaylist>>`
    - `getFamilyPlaylists(sessionToken: String): Result<List<FamilyPlaylist>>`
    - `addFamilyPlaylist(sessionToken: String, playlistId: String, title: String): Result<FamilyPlaylist>`
    - `removeFamilyPlaylist(auth: String, id: String): Result<Unit>`
    - `sangatWebSocketUrl(): String`: the base URL with `https://` → `wss://`, plus `/ws`
  - **Models** mirroring the wire names: `LiveSangatRoom`, `ParivaarPlaylist`, `FamilyPlaylist`.
  - **`interface HubIdentity`** with `suspend fun sessionToken(): String?` and `suspend fun deviceId(): String?`.
  - **`class FamilyFeatureGate`** with:
    - `val enabled: StateFlow<Map<FamilyFeature, Boolean>>`
    - `fun isEnabled(feature: FamilyFeature): Boolean` (synchronous)
    - `suspend fun refresh()`
  - **`FamilyViewModel.ensureSelfPaired(deviceName: String)`.**

- [ ] **Step 1: Write failing tests:**
  - **`HubClientPhase4Test`** (MockWebServer):
    - each function's success shape
    - `401`/`403`/`404`/`409`/`429`/`503` → `Result.failure`
    - `IOException` → `Result.failure`
    - `sangatWebSocketUrl turns https://x.workers.dev into wss://x.workers.dev/ws`
  - **`FamilyFeatureGateTest`:**
    - `every feature is enabled before any answer`
    - `a fetched false disables only that feature`
    - `a failed refresh keeps the last fetched values`
    - `an unknown key from the hub is ignored`
    - `values survive a process restart`
  - **`FamilyViewModelTest`:**
    - `an owner phone without a session token creates, claims and approves itself and stores the token`
    - `an owner phone with a token makes no hub calls`
    - `a non-owner phone never self-pairs`
    - `a failure at any step stores nothing`

  Run: FAIL.
- [ ] **Step 2:** Implement (gate cache in DataStore). PASS.
- [ ] **Step 3: Commit** `feat(app): Phase 4 hub client, feature gate and owner self-pairing`.

### Task 9: Sangat transport (hub URL, auth header, live watcher, car-mode approvals)

**Files:**
- Modify (`// SARASWATI:`):
  - `listentogether/ListenTogetherServers.kt`: one server, "Saraswati family hub", URL from `HubClient.sangatWebSocketUrl()`
  - `listentogether/ListenTogetherClient.kt`:
    - `getServerUrl()` ignores the stored `ListenTogetherServerUrlKey`
    - `connect()` adds the auth header; with no token, or with Sangat off, it does not connect and emits `ConnectionError`
    - the reconnect path consults `SangatReconnectPolicy`
    - the JOIN_REQUEST and SUGGESTION_RECEIVED auto-approve checks consult `SangatApprovalPolicy`
  - `MainActivity.kt`: start/stop the watcher in `onStart`/`onStop`, never when `GrandparentRoleKey` is set
- Create: `saraswati/family/sangat/SangatLiveWatcher.kt`, `SangatReconnectPolicy.kt`, `SangatApprovalPolicy.kt`, `SangatCarMode.kt`
- Test: `SangatLiveWatcherTest.kt`, `SangatPoliciesTest.kt`

**Interfaces:**
- Consumes: Task 8.
- Produces:
  - `class SangatLiveWatcher(hub: HubClient, identity: HubIdentity, gate: FamilyFeatureGate, clock)` with:
    - `val liveRooms: StateFlow<List<LiveSangatRoom>>`
    - `val carPrompts: SharedFlow<LiveSangatRoom>`
    - `fun start(scope: CoroutineScope)`, `fun stop()`
    - `POLL_INTERVAL_MS = 30_000`
  - `object SangatReconnectPolicy { fun shouldReconnect(httpCode: Int?, closeCode: Int?): Boolean }`
  - `object SangatApprovalPolicy { fun autoApprove(prefEnabled: Boolean, carMode: Boolean): Boolean }`
  - `class SangatCarMode(hub, identity)` with `val active: StateFlow<Boolean>` and `suspend fun markHostedRoom(roomCode: String): Result<Unit>`

- [ ] **Step 1: Write failing tests:**
  - **Watcher:**
    - `polls every 30 seconds only between start and stop`
    - `excludes rooms this device hosts`
    - `emits one car prompt per room code`
    - `a hub failure yields an empty list and no exception`
    - `does not poll while sangat is disabled`
  - **Reconnect policy:**
    - `401 and 503 on upgrade never reconnect`
    - `close codes 4001 and 4003 never reconnect`
    - `a network failure reconnects`
  - **Approval policy:**
    - `car mode auto-approves joins and suggestions whatever the preference`
    - `outside car mode the preference decides`

  Run: FAIL.
- [ ] **Step 2:** Implement and wire. `ListenTogetherClient` gets the token before building the request (it already runs on its IO scope). Inject `HubIdentity`, `FamilyFeatureGate` and `SangatCarMode` through Hilt next to Phase 3's `HubClient` provider.
- [ ] **Step 3:** PASS. Also run the existing `listentogether` tests (`MessageCodecTest`, `PlaybackSyncTest`, `QueueSyncTest`, `ServerClockTest`) unchanged, plus `apk_gate.py check`.
- [ ] **Step 4: Commit** `feat(app): point Sangat at the family hub with device auth`.

### Task 10: Remote-controller mode for car passengers

**Files:**
- Modify (`// SARASWATI:`): `listentogether/ListenTogetherManager.kt`
  - `joinRoom(…, remoteController: Boolean = false)`
  - every guest path that touches `player` goes through the policy
- Create: `saraswati/family/sangat/RemoteControllerPolicy.kt`, `saraswati/family/sangat/RemoteNowPlaying.kt`
- Test: `RemoteControllerTest.kt` (following `PlaybackSyncTest`'s fake-player style)

**Interfaces:**
- Produces:
  - `object RemoteControllerPolicy { fun drivesLocalPlayer(isHost: Boolean, remoteController: Boolean): Boolean }`
  - `ListenTogetherManager.isRemoteController: StateFlow<Boolean>`
  - `ListenTogetherManager.remoteNowPlaying: StateFlow<RemoteNowPlaying?>`
  - `data class RemoteNowPlaying(val track: TrackInfo?, val isPlaying: Boolean, val queue: List<TrackInfo>)`
  - songs are added through the existing `suggestTrack(TrackInfo)`

- [ ] **Step 1: Write failing tests:**
  - `a remote controller never calls play, pause, seek or setMediaItems on the local player for join_approved, sync_playback, sync_state or queue ops`
  - `a remote controller's remoteNowPlaying follows the room`
  - `a remote controller pauses local playback once on joining`
  - `a normal guest still drives the local player`
  - `the host always does`
  - `leaving clears remote-controller mode`

  Run: FAIL.
- [ ] **Step 2:** Implement. PASS together with the existing `listentogether` tests.
- [ ] **Step 3: Commit** `feat(app): Sangat remote-controller mode for passengers`.

### Task 11: Parivaar Mix builder and store

**Files:**
- Create: `saraswati/family/parivaar/MixTrack.kt`, `ParivaarMixBuilder.kt`, `ParivaarMixStore.kt`
- Test: `ParivaarMixBuilderTest.kt`, `ParivaarMixStoreTest.kt`

**Interfaces:**
- Produces:
  - `@Serializable data class MixTrack(val videoId: String, val title: String, val artist: String, val thumbnailUrl: String?, val durationMs: Long?)`
  - `data class MemberTopSongs(val deviceId: String, val memberName: String, val tracks: List<MixTrack>)` (rank order)
  - `object ParivaarMixBuilder`:
    - `MIX_MAX = 50`, `RADIO_MAX = 10`
    - `fun radioSeed(members: List<MemberTopSongs>): String?`
    - `fun build(members: List<MemberTopSongs>, radio: List<MixTrack>): List<MixTrack>`
  - `@Serializable data class ParivaarMix(val builtAt: Long, val tracks: List<MixTrack>)`
  - `class ParivaarMixStore(dataStore)`:
    - `val mix: Flow<ParivaarMix>`
    - `suspend fun save(mix: ParivaarMix)`
    - `fun cached(): ParivaarMix` (synchronous snapshot for the car)
- **Algorithm (pinned):**
  1. **Overlap:** every videoId present in two or more members' lists. Sort by member count descending, then by sum of ranks ascending, then by videoId.
  2. **Round-robin:** members sorted by `deviceId`. Each takes a turn adding its next not-yet-included track, until every list is exhausted or the mix reaches `MIX_MAX - RADIO_MAX` (40). The overlap counts toward the 40.
  3. **Radio:** append radio tracks not already included, until the mix reaches `MIX_MAX`.

  `radioSeed` is the first overlap track, or `null` when there is no overlap (then no radio). Fewer than two members with non-empty lists gives an empty mix.

- [ ] **Step 1: Write failing tests:**
  - **Builder:**
    - `songs shared by more members come first`
    - `remaining songs alternate between members in turns`
    - `radio fills the tail without duplicates`
    - `never exceeds 50 tracks and radio adds at most 10`
    - `fewer than two members gives an empty mix`
    - `no overlap gives no radio seed`
    - `same input gives the same output`
  - **Store:**
    - `round-trips`
    - `corrupt data reads as an empty mix`
    - `cached returns the last saved mix without suspending`

  Run: FAIL.
- [ ] **Step 2:** Implement. PASS.
- [ ] **Step 3: Commit** `feat(app): Parivaar Mix builder and store`.

### Task 12: Top-songs playlist and daily mix refresh

**Files:**
- Create in `saraswati/family/parivaar/`: `YouTubePlaylistPort.kt` (interface + `InnerTubePlaylistPort`), `TopSongsPlaylistSync.kt`, `ParivaarMixRefresher.kt`, `ParivaarMixQueue.kt`, `MixDownloader.kt`
- Modify (only if Step 1 finds innertubex cannot create unlisted playlists, with `// SARASWATI:`): `innertube/src/main/kotlin/com/metrolist/innertube/InnerTube.kt` and `YouTube.kt`, adding an overload `createPlaylist(title: String, privacyStatus: String)`. The body is `{"title":…, "privacyStatus":"UNLISTED"}`, matching ytmusicapi's `create_playlist`.
- Modify: the Phase 3 launch-time hub refresh call site (grep `reportHealth(`) to also run `syncIfDue` and then `refreshIfDue`
- Test: `TopSongsPlaylistSyncTest.kt`, `ParivaarMixRefresherTest.kt`

**Interfaces:**
- Consumes: Tasks 8 and 11; the existing `mostPlayedSongs(fromTimeStamp, limit)` DAO and `DownloadUtil.download(MediaMetadata)`.
- Produces:
  - `interface YouTubePlaylistPort` with:
    - `suspend fun createUnlisted(title: String): Result<String>`
    - `suspend fun entries(playlistId: String): Result<List<PlaylistEntry>>`
    - `suspend fun add(playlistId: String, videoId: String): Result<Unit>`
    - `suspend fun remove(playlistId: String, videoId: String, setVideoId: String): Result<Unit>`
    - `suspend fun delete(playlistId: String): Result<Unit>`
    - `suspend fun radio(seedVideoId: String): Result<List<MixTrack>>`, implemented with `YouTube.next(WatchEndpoint(videoId = seed, playlistId = "RDAMVM$seed"))`
  - `TopSongsPlaylistSync.syncIfDue(now: Instant, memberName: String): Result<Unit>`
  - `ParivaarMixRefresher.refreshIfDue(now: Instant): Result<Unit>`
  - `class ParivaarMixQueue(tracks: List<MixTrack>) : Queue`, titled "Parivaar Mix"
  - `MixDownloader.downloadMissing(tracks: List<MixTrack>)`, which enqueues only songs not already downloaded, and only on a `NET_CAPABILITY_NOT_METERED` network
- **Pinned values:**
  - `TOP_SONGS_LIMIT = 30` over the last `90` days, never including local songs
  - title exactly `"Saraswati · $memberName top songs"` (U+00B7)
  - weekly sync is due 7 days after the last success (`ParivaarTopSongsSyncedAtKey`); playlist id in `ParivaarPlaylistIdKey`
  - mix refresh is due 24 h after `builtAt`
  - `EDIT_CALL_SPACING_MS = 500` between YouTube edit calls
  - sync applies a diff (remove dropped, append new in rank order) rather than recreating, so a week costs at most 30 adds and 30 removes

- [ ] **Step 1:** Inspect innertubex `v0.8.4`'s `createPlaylist` request body with `javap -c` on the Gradle-cached jar (or REA `inspect_android_class`). Record whether it sets `privacyStatus`. That decides the optional innertube overload.
- [ ] **Step 2: Write failing tests** (fake port, fake hub):
  - **Sync:**
    - `first sync creates one unlisted playlist titled "Saraswati · Mummy top songs", adds the top 30 and registers it with the hub`
    - `later syncs add new and remove dropped songs without recreating`
    - `local songs are never added`
    - `nothing happens before 7 days`
    - `a playlist deleted on YouTube is recreated once`
    - `a hub failure keeps the playlist id and retries registration next launch`
  - **Refresher:**
    - `builds from every registered member including this phone`
    - `a member whose playlist fails to load is skipped`
    - `radio is requested only with an overlap seed`
    - `saves the mix and downloads only on unmetered networks`
    - `does nothing when parivaar_mix is off`
    - `does nothing within 24 hours of the last build`

  Run: FAIL.
- [ ] **Step 3:** Implement. PASS. Run `apk_gate.py check` (no manifest change expected).
- [ ] **Step 4: Commit** `feat(app): Parivaar top-songs playlist and daily mix refresh`.

### Task 13: Taste policy

**Files:**
- Create: `saraswati/family/TastePolicy.kt`, `saraswati/family/sangat/PassengerPlayCredit.kt`
- Modify (`// SARASWATI:`):
  - `constants/PreferenceKeys.kt`: `CountSharedPlaysKey = booleanPreferencesKey("saraswatiCountSharedPlays")`
  - `playback/MusicService.kt`: `onPlaybackStatsReady` gates both the local `Event`/`incrementTotalPlayTime` block and `registerPlayback`
- Test: `TastePolicyTest.kt`, `PassengerPlayCreditTest.kt`

**Interfaces:**
- Consumes: Task 10 (`isRemoteController`, `remoteNowPlaying`); Task 12 (`ParivaarMixQueue`).
- Produces:
  - `sealed interface PlayContext { data object Solo; data class Sangat(val addedBySelf: Boolean); data object ParivaarMix }`
  - `object TastePolicy`:
    - `fun counts(context: PlayContext, countSharedPlays: Boolean): Boolean`
    - `fun resolve(inRoom: Boolean, isHost: Boolean, myUsername: String, suggestedBy: String?, queueIsParivaarMix: Boolean): PlayContext`
  - `class PassengerPlayCredit(historyDurationMs: Long = 30_000, clock)` with `fun onRoomState(videoId: String?, suggestedBy: String?, isPlaying: Boolean, myUsername: String): String?`, which returns a videoId once, when it should be credited
  - In remote-controller mode, a credited id becomes `YouTube.player(id)` → its `videostatsPlaybackUrl` → `YouTube.registerPlayback(null, url)`

- [ ] **Step 1: Write failing tests:**
  - **`resolve` and `counts`:**
    - `solo plays count`
    - `host: a track with empty suggestedBy counts`
    - `host: a passenger's pick does not count`
    - `guest: the host's pick does not count`
    - `guest: my own pick counts`
    - `Parivaar Mix plays do not count`
    - `the toggle makes Sangat and Parivaar Mix plays count`
    - `Sangat wins over Parivaar Mix when both apply`
  - **Credit:**
    - `credits my own pick after 30 seconds of playing`
    - `never credits another person's pick`
    - `paused time does not count`
    - `credits each play once`
  - Add `a non-counting play writes no Event row` against `onPlaybackStatsReady`'s extracted helper.

  Run: FAIL.
- [ ] **Step 2:** Implement. `queueIsParivaarMix` is `currentQueue is ParivaarMixQueue` (Task 12). PASS.
- [ ] **Step 3: Commit** `feat(app): taste policy for Sangat and Parivaar Mix plays`.

### Task 14: Family playlists, before the capture

**Files:**
- Create: `saraswati/family/playlists/FamilyPlaylistsRepository.kt`, `FamilyPlaylistsCache.kt`, `CollaborationApi.kt`
- Test: `FamilyPlaylistsRepositoryTest.kt`

**Interfaces:**
- Consumes: Tasks 8 and 12 (`YouTubePlaylistPort`).
- Produces:
  - `interface CollaborationApi`:
    - `suspend fun enable(playlistId: String): Result<String /* invite */>`
    - `suspend fun join(playlistId: String, invite: String): Result<Unit>`
  - `object UnconfirmedCollaborationApi : CollaborationApi`, which always returns `Result.failure(CollaborationUnavailable)` until Task 23 replaces it
  - `class FamilyPlaylistsRepository` with:
    - `val playlists: Flow<List<FamilyPlaylist>>`
    - `fun cached(): List<FamilyPlaylist>`
    - `suspend fun refresh()`
    - `suspend fun create(title: String): Result<FamilyPlaylist>`: `createUnlisted`, then `hub.addFamilyPlaylist`, then `collaboration.enable`. A collaboration failure is tolerated, leaving the playlist read-only for others.
    - `suspend fun addSong(p: FamilyPlaylist, videoId: String): Result<Unit>`
    - `suspend fun remove(p: FamilyPlaylist): Result<Unit>`
  - Before Task 23, everyone can see and play every family playlist, and only its creator (`addedByDeviceId == own deviceId`) can add songs.

- [ ] **Step 1: Write failing tests:**
  - `refresh caches the hub list`
  - `create makes a YouTube playlist, then registers it`
  - `a hub failure after creating on YouTube deletes the orphan playlist`
  - `adding to someone else's playlist fails with CollaborationUnavailable`
  - `the creator can add`
  - `family_playlists off gives an empty list and no hub calls`

  Run: FAIL.
- [ ] **Step 2:** Implement. PASS.
- [ ] **Step 3: Commit** `feat(app): family playlists registry, read-only for non-creators`.

### Task 15: Car Family folder and the Sangat car action

**Files:**
- Create: `saraswati/car/CarFamilyTree.kt`, `saraswati/family/sangat/CarSangatController.kt`
- Modify (`// SARASWATI:`):
  - `playback/MediaLibrarySessionCallback.kt`: the `CAR_FAMILY` branch; `onSetMediaItems` for `parivaar_mix` and `sangat_toggle`; `onCustomCommand`
  - `constants/MediaSessionConstants.kt`: `ACTION_TOGGLE_SANGAT = "TOGGLE_SANGAT"`, `CommandToggleSangat`
  - `playback/MusicService.kt`: the custom layout button, shown only while `sangat` is enabled
- Test: `CarFamilyTreeTest.kt`, `CarSangatControllerTest.kt`; update Phase 3's `CAR_FAMILY` placeholder test

**Interfaces:**
- Consumes:
  - `ParivaarMixStore.cached()` (Task 11)
  - `FamilyPlaylistsRepository.cached()` (Task 14)
  - `FamilyFeatureGate.isEnabled` (Task 8)
  - `SangatCarMode` (Task 9)
- Produces:
  - `data class CarSangatState(val hosting: Boolean, val joinedCount: Int)`
  - `object CarFamilyTree { fun children(mix: ParivaarMix, playlists: List<FamilyPlaylist>, enabled: (FamilyFeature) -> Boolean, sangat: CarSangatState): List<MediaItem> }`, a plain non-suspend function. Order:
    1. "Parivaar Mix": playable, media id `parivaar_mix`
    2. each family playlist: playable, the same YouTube-playlist media-id form `CAR_HOME` rows use
    3. "Start Sangat", or "End Sangat · n joined" while hosting: playable, media id `sangat_toggle`
  - `class CarSangatController` with:
    - `val state: StateFlow<CarSangatState>`
    - `suspend fun toggle(): CarSangatResult`, where `CarSangatResult` is `Started | Ended | PairRequired | Disabled | NoConnection`
    - Username is `ListenTogetherUsernameKey`, else `Build.MODEL`. Start creates a room, then calls `markHostedRoom`.
  - Selecting `sangat_toggle` toggles Sangat and returns the player's current items, index and position unchanged.

- [ ] **Step 1: Write failing tests:**
  - **Tree:**
    - `lists Parivaar Mix, then family playlists, then Sangat`
    - `hides each feature whose switch is off`
    - `an empty cache gives only the Sangat row`
    - `the Sangat row reads "End Sangat · 2 joined" while hosting`
  - **Controller:**
    - `toggle creates a room and marks it car mode`
    - `toggle without a session token returns PairRequired and creates nothing`
    - `toggle while sangat is off returns Disabled`
    - `toggle while hosting leaves the room`
    - `selecting the Sangat row keeps the current queue and position`

  Run: FAIL.
- [ ] **Step 2:** Implement. The car error toasts use the pinned short phrases. PASS. Run `apk_gate.py check`.
- [ ] **Step 3: Commit** `feat(car): Family folder with Parivaar Mix, family playlists and Sangat`.

### Task 16: Cast relay server core

**Files:**
- Create in `saraswati/castrelay/`: `RelayHttpServer.kt`, `RelaySession.kt`, `RelayAudioSource.kt`, `WifiAddress.kt`
- Test: `RelayHttpServerTest.kt`, `RelaySessionTest.kt`

**Interfaces:**
- Produces:
  - `interface RelayAudioSource { suspend fun open(videoId: String, range: LongRange?): RelayStream? }` with `data class RelayStream(val mimeType: String, val totalLength: Long?, val input: InputStream)`
  - `class RelaySession(random: SecureRandom)` with:
    - `val token: String` (32 random bytes, hex)
    - `fun register(videoIds: List<String>): Map<String, String>` (videoId → opaque id of 16 random bytes, hex)
    - `fun resolve(opaqueId: String): String?`
  - `class RelayHttpServer(bind: InetAddress, session: RelaySession, source: RelayAudioSource)` with:
    - `fun start(): Int` (ephemeral port)
    - `fun stop()`
    - `fun urlFor(opaqueId: String): String`, giving `http://<ip>:<port>/<token>/<opaqueId>`
  - `object WifiAddress { fun current(cm: ConnectivityManager): Inet4Address? }`: the IPv4 link address of the active network, only when that network has `TRANSPORT_WIFI`
- **Rules:**
  - Listening:
    - bind only to the given address, never `0.0.0.0`
    - at most 4 concurrent connections
    - request line ≤ 8 KiB, ≤ 50 headers, 10 s header timeout
  - Requests:
    - `GET`/`HEAD` only (`405`)
    - path exactly `/<token>/<opaqueId>`
    - the token is compared with `MessageDigest.isEqual`; a wrong token gets `403` with no body
    - an unknown or unregistered id gets `404`
    - a single `Range: bytes=a-b` or `bytes=a-` gets `206` with `Content-Range`; an unsatisfiable range gets `416`
  - Responses:
    - `Content-Type` comes from `RelayStream`
    - `Access-Control-Allow-Origin: *` (the token is the gate)
    - never a file path or directory listing

- [ ] **Step 1: Write failing tests** (real sockets on `127.0.0.1`, fake source):
  - `serves a registered song with 200 and its content type`
  - `a byte range gets 206 with Content-Range`
  - `an open-ended range works`
  - `an unsatisfiable range gets 416`
  - `wrong token gets 403 and no body`
  - `unknown opaque id gets 404`
  - `a video id or ../ path gets 404`
  - `POST gets 405`
  - `an oversized request line is dropped`
  - `a fifth concurrent connection is closed without a response`
  - `binds only to the given address`
  - `stop closes the port`
  - `opaque ids differ from video ids and between sessions`
  - `a new session's token rejects the old one`

  Run: FAIL.
- [ ] **Step 2:** Implement. PASS.
- [ ] **Step 3: Commit** `feat(castrelay): token-gated LAN relay server core`.

### Task 17: Cast relay in the Cast handler, off by default

**Files:**
- Create: `saraswati/castrelay/CacheRelayAudioSource.kt`, `saraswati/castrelay/CastRelayController.kt`
- Modify (`// SARASWATI:`):
  - `saraswati/SaraswatiFeatures.kt`: `CAST_RELAY = BuildConfig.DEBUG` until Task 24
  - `playback/MusicService.kt`: expose the playback `CacheDataSource`/resolving factory to the relay
  - `app/src/gms/kotlin/com/metrolist/music/playback/CastConnectionHandler.kt`: `loadMedia` and `resolvedMediaItem` use the controller when it is active
- Test: `CastRelayControllerTest.kt`

**Interfaces:**
- Consumes: Task 16; the existing Song `FormatEntity` (mimeType) and stream resolution.
- Produces: `class CastRelayController` with:
  - `fun start(): Boolean`: true only if `SaraswatiFeatures.CAST_RELAY`, `cast_relay` is enabled, `WifiAddress.current` is non-null, and the server started
  - `fun stop()`
  - `suspend fun mediaItems(queue: List<MediaItem>): List<MediaItem>?`: the whole queue, capped at `CAST_QUEUE_MAX = 200`, with relay URIs, and `MimeTypes` from the stored format (resolved once through the existing stream path when unknown); `null` means use upstream's existing 5-item googlevideo window unchanged
- `CacheRelayAudioSource` reads cache, downloads, or a live fetch through a separate `CacheDataSource` instance with `FLAG_IGNORE_CACHE_ON_ERROR`.
- The server starts with the Cast session and stops when it ends or Wi-Fi drops.

- [ ] **Step 1: Write failing tests:**
  - `uses relay URLs for the whole queue when enabled`
  - `caps the queue at 200`
  - `mime type comes from the stored format, not a hardcoded mp4`
  - `falls back when cast_relay is off`
  - `falls back when the build flag is off`
  - `falls back with no Wi-Fi address`
  - `stops the server when the cast session ends`
  - `stops the server when Wi-Fi is lost`

  Run: FAIL.
- [ ] **Step 2:** Implement. PASS. Run `sar_gradle :app:assembleGmsDebug` and `apk_gate.py check` (no manifest change).
- [ ] **Step 3: Commit** `feat(cast): whole-queue Cast through the LAN relay, debug-only until the spike`.

### Task 18: Spotify import core (ArchiveTune's matcher, file sources)

**Files:**
- Create in `saraswati/spotifyimport/`:
  - `SpotifyExportParser.kt`
  - `ExportifyCsvParser.kt`
  - `ImportSongResolver.kt` and `ImportReplacementSearcher.kt`: ported from ArchiveTune `rukamori/ArchiveTune@57732a1859cc42e7f4edba5de64fb68dcba4dd48` `app/src/main/kotlin/moe/rukamori/archivetune/playlistimport/`, keeping its notice block plus a `Ported from ArchiveTune (GPL-3.0)` line
  - `SpotifyImportRunner.kt`
- Modify: `CREDITS.md` (credit ArchiveTune)
- Test: `SpotifyExportParserTest.kt`, `ExportifyCsvParserTest.kt`, ported `ImportSongResolverTest.kt` and `ImportReplacementSearcherTest.kt`, `SpotifyImportRunnerTest.kt`

**Interfaces:**
- Produces:
  - `data class ImportedPlaylist(val name: String, val tracks: List<ImportedTrack>)` and `data class ImportedTrack(val title: String, val artists: List<String>, val album: String?, val durationMs: Long?)`
  - `SpotifyExportParser.parse(input: InputStream, fileName: String): Result<List<ImportedPlaylist>>` accepts:
    - the export zip, scanning entries in memory only, never writing them to disk
    - `Playlist*.json`: `{"playlists":[{"name","items":[{"track":{"trackName","artistName","albumName","trackUri"}}]}]}`; items whose `track` is null are skipped
    - `YourLibrary.json`: `{"tracks":[{"artist","album","track","uri"}]}`, imported as "Spotify liked songs"
  - `ExportifyCsvParser.parse(input: InputStream, name: String): Result<ImportedPlaylist>` is header-driven, using "Track Name", "Artist Name(s)" (comma-separated), "Album Name" and "Duration (ms)", with RFC 4180 quoting.
  - `SpotifyImportRunner.run(playlist: ImportedPlaylist, onProgress: (done: Int, total: Int) -> Unit): ImportReport` with `ImportReport(matched: List<String>, unmatched: List<ImportedTrack>, skippedOverLimit: Int)`. It creates a local playlist through the existing DB API.
- **Limits:**
  - files: 20 MB; zip: 200 entries and 50 MB inflated in total
  - 10,000 tracks parsed; 500 tracks per run
  - `SEARCH_SPACING_MS = 1000` between YouTube searches
  - unsupported shapes give `ParseError.UnsupportedFormat`
  - no network besides YouTube search

- [ ] **Step 1: Write failing tests:**
  - **Export parser:**
    - `reads playlists and tracks from Playlist1.json`
    - `skips items whose track is null`
    - `reads YourLibrary.json as Spotify liked songs`
    - `reads playlists inside the export zip without writing to disk`
    - `rejects a zip that inflates past 50 MB`
    - `rejects a file over 20 MB`
    - `unrelated JSON is UnsupportedFormat`
  - **CSV parser:**
    - `maps columns by header in any order`
    - `splits Artist Name(s) on commas`
    - `handles quoted commas and doubled quotes`
    - `missing Track Name is UnsupportedFormat`
  - **Runner:**
    - `spaces searches at least 1 second apart`
    - `reports unmatched tracks instead of dropping them`
    - `stops at 500 tracks and reports the rest`
  - The ported ArchiveTune tests, adapted to Metrolist's `Song`/`SongItem` types.

  Run: FAIL.
- [ ] **Step 2:** Port and implement. PASS. Confirm that `security/test_egress_policy.py` still passes with no new host.
- [ ] **Step 3: Commit** `feat(import): Spotify import from export files, matcher ported from ArchiveTune`.

### Task 19: Sangat UI

**Gated on Task 1 approval.**

**Files:**
- Create in `saraswati/family/sangat/`: `SangatViewModel.kt`, `SangatSection.kt`, `SangatLiveBanner.kt`, `CarSangatPrompt.kt`, `RemoteControllerScreen.kt`
- Modify (`// SARASWATI:`):
  - `FamilyScreen.kt`
  - `ui/screens/HomeScreen.kt` (Phase 3 banner slot)
  - `ui/screens/Screens.kt` and `NavigationBuilder.kt` (route `sangat/remote`)
  - Veena's Sangat icon, so it opens the Sangat section
- Test: `SangatViewModelTest.kt`

**Interfaces:**
- Consumes: Tasks 8–10 and 13.
- Produces: `SangatViewModel` with:
  - `live: StateFlow<List<LiveSangatRoom>>`, `prompt: StateFlow<LiveSangatRoom?>`
  - `fun start()`, `fun join(room: LiveSangatRoom)`, `fun dismissPrompt(roomCode: String)`

- [ ] **Step 1: Write failing tests:**
  - `start hosts a normal room`
  - `joining a car room enters remote-controller mode`
  - `joining a normal room does not`
  - `a dismissed prompt never shows again for that room`
  - `everything is hidden while sangat is off`
  - `a blank username asks for one once on the phone`

  Run: FAIL. Implement. PASS.
- [ ] **Step 2:** Build the screens to the approved mockups. Run the JVM suite and `apk_gate.py check`.
- [ ] **Step 3: Commit** `feat(app): Sangat section, live banner, car prompt and remote-controller screen`.

### Task 20: Parivaar Mix, family playlists and taste toggle UI

**Gated on Task 1 approval.**

**Files:**
- Create: `saraswati/family/parivaar/ParivaarViewModel.kt`, `ParivaarMixCard.kt`, `saraswati/family/playlists/FamilyPlaylistsViewModel.kt`, `FamilyPlaylistsSection.kt`, `FamilyPlaylistScreen.kt`
- Modify (`// SARASWATI:`): `FamilyScreen.kt`, `ui/screens/HomeScreen.kt`, `Screens.kt`/`NavigationBuilder.kt`
- Test: `ParivaarViewModelTest.kt`, `FamilyPlaylistsViewModelTest.kt`

**Interfaces:** Consumes Tasks 11–14. `ParivaarViewModel` exposes `mix`, `play()` (starts a `ParivaarMixQueue`), `countSharedPlays` and `setCountSharedPlays(Boolean)`. `FamilyPlaylistsViewModel` exposes `playlists`, `create(title)`, `addSong(p, videoId)`, `canAdd(p)`.

- [ ] **Step 1: Write failing tests:**
  - `play starts a ParivaarMixQueue with the cached tracks`
  - `an empty mix shows the needs-two-members state`
  - `the toggle writes CountSharedPlaysKey`
  - `canAdd is true only for the creator before collaboration`
  - `a create failure surfaces an error, not a crash`
  - `each section is hidden while its switch is off`

  Run: FAIL. Implement. PASS.
- [ ] **Step 2:** Build the screens to the approved mockups, including "Add to family playlist" in the existing song menu. Run the JVM suite.
- [ ] **Step 3: Commit** `feat(app): Parivaar Mix card, family playlists and taste toggle`.

### Task 21: Spotify import UI

**Gated on Task 1 approval.**

**Files:**
- Create: `saraswati/spotifyimport/SpotifyImportViewModel.kt`, `SpotifyImportScreen.kt`, `ImportReviewScreen.kt` (ported from ArchiveTune's `ui/menu/ImportReviewScreen.kt` at the same commit, notice kept)
- Modify (`// SARASWATI:`): the Library screen's top-bar menu ("Import from Spotify"), `Screens.kt`/`NavigationBuilder.kt`
- Test: `SpotifyImportViewModelTest.kt`

**Interfaces:**
- Consumes: Task 18.
- File picking uses `ActivityResultContracts.OpenDocument` with mime types `application/zip`, `application/json`, `text/csv`, `text/comma-separated-values`. No storage permission.

- [ ] **Step 1: Write failing tests:**
  - `a picked zip with three playlists offers all three`
  - `progress reaches total`
  - `unmatched tracks go to review`
  - `a parse error shows a message and imports nothing`

  Run: FAIL. Implement. PASS.
- [ ] **Step 2:** Build to the mockup. Run the JVM suite and `apk_gate.py check`.
- [ ] **Step 3: Commit** `feat(app): Spotify import screens`.

### Task 22: HUMAN CHECKPOINT: deploy, two-phone Sangat, car Sangat on DHU

**Files:** none (results go in the ledger; fix-ups go in their owning task's files).

- [ ] **Step 1: HUMAN CHECKPOINT.** The owner runs `cd worker && npx wrangler deploy`, or explicitly authorizes the agent to.
- [ ] **Step 2:** Agent checks:
  - `curl -i https://<hub>/ws` → `426`
  - with `Upgrade: websocket` and no auth → `401`
  - `GET /sangat/live` without auth → `401`
- [ ] **Step 3: HUMAN CHECKPOINT.** On two phones with a debug build:
  - create, join and approve
  - play, pause and seek in sync within about 1 s
  - suggest and approve
  - airplane mode for 1 min, then the session resumes
  - revoke phone 2 in Manage Family: it drops out at once
  - switch `sangat` off: both disconnect and stop retrying (watch logcat)
- [ ] **Step 4: HUMAN CHECKPOINT.** On the DHU with the Pixel:
  1. Start Sangat from the player's custom action.
  2. The passenger phone (app open) shows the prompt within 30 s and joins with no audio.
  3. The passenger adds a song; it plays in the car.
  4. Afterwards, the driver's YouTube history holds only the driver's own picks.
- [ ] **Step 5:** After an hour of Sangat, the owner reads Durable Object duration in the Cloudflare dashboard and confirms it is inside the free plan. Fix anything found in its owning task, with a regression test.

### Task 23: HUMAN CHECKPOINT: collaborative playlists capture, then build

**Files:**
- Create: `innertube/src/main/kotlin/com/metrolist/innertube/saraswati/Collaboration.kt` (or the closest existing pattern), redacted fixtures under `innertube/src/test/resources/collab/`
- Modify: bind the real `CollaborationApi` in place of `UnconfirmedCollaborationApi`
- Test: `innertube/src/test/kotlin/.../CollaborationTest.kt` (Ktor `MockEngine`, already a test dependency)

- [ ] **Step 1: HUMAN CHECKPOINT.** The owner opens music.youtube.com in this machine's Chrome, signed in, with the agent capturing through chrome-devtools or REA `capture_browser_scenario`:
  1. Create a playlist, turn on Collaborate, copy the invite.
  2. Open the invite as a second account and join.
  3. As that account, add a song.

  The agent records the `youtubei/v1` endpoints, request bodies and response shapes. It redacts `Cookie`, `Authorization`, `X-Goog-*`, SAPISIDHASH and any visitor data before anything is written, and saves only the redacted fixtures.
- [ ] **Step 2: Owner decision.** If joining needs an invite token, should the hub store it? The spec says the Worker "stores no credentials". The default is no: the creator shares the invite through Android's share sheet, and Saraswati opens it as a validated deep link through Phase 1's `DeepLinkGuard`.
- [ ] **Step 3: Write failing tests** from the fixtures: `enable returns the invite`, `join posts the captured body`, `a collaborator's add uses addToPlaylist unchanged` (or the captured call if it differs). Implement. PASS.
- [ ] **Step 4:** In `FamilyPlaylistsViewModel`, `canAdd` becomes true for joined collaborators. If the capture shows collaboration is unavailable on these accounts, keep Task 14's read-only behaviour and record why.
- [ ] **Step 5: Commit** `feat(innertube): collaborative playlist calls from a redacted capture`.

### Task 24: HUMAN CHECKPOINT: Cast spike on home devices, then enable

**Files:** Modify `saraswati/SaraswatiFeatures.kt`, and `CastRelayController.kt` only if findings need it.

- [ ] **Step 1: HUMAN CHECKPOINT.** At home, on the debug build, cast a 25-song queue (mixing webm/opus and mp4/aac songs, some cached, some not) to a Chromecast or Google TV, an Android TV and a Nest speaker. For each device record:
  - whether 20 or more songs play in a row
  - whether seeking works
  - whether the receiver accepts `http://` relay media
  - which formats play
  - whether a 200-item queue loads
- [ ] **Step 2:** If all three pass, set `CAST_RELAY = true` and remove the debug override. If a format fails, prefer the working itag for relay items. If receivers refuse `http://` media, keep the relay off and tell the owner: a LAN certificate the receiver trusts is not possible, so Cast keeps upstream's window.
- [ ] **Step 3:** Run the JVM suite and `apk_gate.py check`. **Commit** `feat(cast): enable the LAN relay after the device spike`.

### Task 25: HUMAN CHECKPOINT: real accounts, real car, Beta

**Files:** none (fixes go to their owning tasks).

- [ ] **Step 1: HUMAN CHECKPOINT.** With two signed-in family phones:
  - each creates its "Saraswati · ⟨name⟩ top songs" playlist, which YouTube Music shows as Unlisted
  - Parivaar Mix builds with the shared songs first
  - the mix plays with Wi-Fi off
  - a mix play doesn't appear in YouTube history with the toggle off, and does with it on
- [ ] **Step 2: HUMAN CHECKPOINT.** A family member imports their real Spotify export (zip) and an Exportify CSV. Review the unmatched list. If the export's keys differ from Task 18's pinned shape, fix the parser and fixtures.
- [ ] **Step 3:** Ship to Beta through Phase 2's release workflow (the owner's approval tap). 48 h on the owner's phone.
- [ ] **Step 4: HUMAN CHECKPOINT.** A real car drive with Sangat and a passenger before Stable, as the spec requires for any release touching playback or browsing. Then promote to Stable with the owner's approval.

## Self-review notes

- **Spec coverage:**
  - **Sangat:**
    - on the Worker, in the single DO: Tasks 2–7, plus parity traces against metroserver (Task 3), per "tested from the outside"
    - hibernation-safe SQLite state and alarms (Task 4)
    - car mode: hub prompt (Tasks 6, 9, 19), remote controllers (Task 10), auto-approval (Task 9), the car Family folder and custom action (Task 15)
    - "only songs you add count" (Task 13)
  - **Parivaar Mix:**
    - the unlisted "Saraswati · ⟨name⟩ top songs" playlist, refreshed weekly and registered with the hub (Tasks 7, 12)
    - the pinned mix order and daily offline refresh (Tasks 11–12)
    - the Home card (Task 20)
  - **Family playlists:**
    - registry and read-only play before the capture (Tasks 7, 14, 20)
    - REA/browser capture, then collaboration (Task 23)
  - **Cast relay:**
    - core and integration before the spike (Tasks 16–17)
    - spike, then enable (Task 24)
    - whole queue to the Default Media Receiver, Wi-Fi-only, random port, per-session token, queue-only opaque ids (Task 16 rules)
  - **Spotify import:** ArchiveTune port (Tasks 18, 21), narrowed per Spec note 3.
  - **Kill-switches:** every feature checks `FamilyFeatureGate` in the app and its key on the Worker. The app fails open (Global Constraints).
  - **Spec verification items:**
    - "Sangat across two phones", "Sangat from the car with a passenger acting as remote controller" and "The Worker rejects an unpaired phone" (Task 22)
    - "Cast 20+ songs on a Chromecast, an Android TV and a Nest" and "the relay rejects requests without the token" (Tasks 16, 24)
    - "Parivaar Mix with two or more accounts" (Task 25)
  - **Out of scope by the spec's phase list:** local files and "On this phone" (Phase 5). The relay's `RelayAudioSource` is the hook Phase 5 plugs local files into.
- **Type consistency:**
  - `LiveRoom` (Worker) and `LiveSangatRoom` (app) share the wire names in the route table.
  - `HubIdentity`, `FamilyFeatureGate`, `YouTubePlaylistPort`, `MixTrack`/`ParivaarMix`, `CollaborationApi` and `CarSangatState` are each defined once (Tasks 8, 8, 12, 11, 14, 15) and consumed by name later.
  - `ParivaarMixQueue` is defined in Task 12 and consumed by Task 13 (taste) and Task 20 (play).
- **Review Focus:** each of the five has a named test in its owning task: Tasks 4 and 6 (revoke and auth), Task 4 (fresh instance restores the room), Task 9 (reconnect policy and watcher), Task 16 (relay rules), Task 13 (taste).
  - Two more risks are covered in-task rather than listed: gzip and zip bombs (Tasks 2 and 18), and a blocking car browse (Task 15's tree is a non-suspend function over cached snapshots).
- **Proportion:** 25 tasks for five features across a backend protocol port, four app subsystems and four owner checkpoints. Every step gives signatures, tables, pinned values and test names. No task contains an implementation body.
