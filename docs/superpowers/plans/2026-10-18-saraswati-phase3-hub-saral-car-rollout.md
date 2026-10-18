# Saraswati Phase 3: Hub, Saral, Car, Rollout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Everything needed to actually bring the family onto Saraswati: approved mockups for every new screen, a Cloudflare Worker family hub (pairing with the owner's approval, roster, remotely-set stations, kill-switches, a health view), Saral v1 (the grandparent easy mode), the car's Stations and Family tabs, a GitHub Pages setup guide, and a phone-by-phone rollout runbook.

**Architecture:** One Cloudflare Worker (`saraswati-sangat`) backed by a single Durable Object class (`FamilyHub`), its state in the DO's own SQLite storage — the same DO that Phase 4 will extend with the Sangat/Listen-Together protocol, so Phase 3 only builds the hub half of it (pairing, roster, stations, kill-switches, health). The app talks to it through one small, fully-unit-tested Kotlin client (`saraswati/family/HubClient.kt`), mirroring `:update-core`'s pattern of pure, tested logic behind a thin Android shell. A new "Family" bottom-nav tab hosts the owner-only "Manage family" screens; Saral is a separate, role-gated top-level UI, not one more tab; the car's browse tree grows from a flat shelf list into four top-level folders (Home, Library, Stations, Family).

**Tech Stack:** Kotlin (existing app), Jetpack Compose, Cloudflare Workers + Durable Objects in TypeScript, `wrangler` CLI, `@cloudflare/vitest-pool-workers` for Worker unit tests, Android Keystore (existing cookie-encryption pattern) for the hub session token and admin key, Media3 `MediaLibraryService` (existing), `RecognizerIntent` (existing voice-search pattern), GitHub Pages (existing `gh-pages` branch from Phase 2).

**Spec:** `docs/superpowers/specs/2026-10-17-saraswati-design.md`. Phase 1 plan: `2026-10-17-saraswati-phase1-fork-harden.md`. Phase 2 plan: `2026-10-17-saraswati-phase2-updates-pipeline.md` (both merged first).

**Spec note:** the design doc's Risks table and "Fewer interruptions" bullet used to describe a remote-cipher-config channel for YouTube fixes that Phase 1's security audit actually found unsafe (unsigned JavaScript, finding A12-b) and switched off; this plan-writing pass corrected those stale lines in the design doc directly, so it and this plan now agree. The real, current mitigation for a YouTube break is a fast InnerTubeX bump shipped through the Phase 2 sync bot and Saraswati Updates — where a task below references "how YouTube breaks get fixed," it means that path.

## Global Constraints

- **New Kotlin packages** (per the design's isolation list): `com.metrolist.music.saraswati.family` (HubClient, pairing, roster/station/kill-switch models, Manage Family UI), `com.metrolist.music.saraswati.saral` (Saral UI, role preference), `com.metrolist.music.saraswati.car` (car-tab additions). Existing `com.metrolist.music.saraswati.{backup,security,update}` are untouched by this plan except where a task says otherwise.
- **Worker:** new top-level directory `worker/` (not a Gradle module — plain Node/TypeScript project with its own `package.json`, `tsconfig.json`, `wrangler.toml`). Worker name `saraswati-sangat`; one Durable Object class `FamilyHub` bound as `FAMILY_HUB`; state only in `ctx.storage.sql` (hibernation-safe); no other Cloudflare product (no KV, no R2, no queues) — the free plan's Workers + Durable Objects tier only, no card on file. Tests via `@cloudflare/vitest-pool-workers` running against the real Workers runtime, not a mock HTTP server.
- **Worker HTTP API** (JSON in, JSON out, plain `fetch(request)` + `new URL(request.url).pathname` switch — no router dependency):
  | Method | Path | Auth | Body → Response |
  |---|---|---|---|
  | POST | `/pairing/create` | admin | `{}` → `{"token":"<32 hex>","expiresAt":"<ISO-8601>"}` (15 min TTL, single-use) |
  | POST | `/pairing/claim` | none | `{"token":"…","deviceName":"…"}` → `{"deviceId":"<uuid>","status":"pending"}`; `410` if expired or already used |
  | GET | `/roster` | admin | → `{"devices":[{"deviceId","deviceName","status":"pending"|"approved"|"revoked","approvedAt"}]}` |
  | POST | `/roster/{deviceId}/approve` | admin | → `{"deviceId","sessionToken":"<32 hex>","status":"approved"}` |
  | POST | `/roster/{deviceId}/revoke` | admin | → `{"deviceId","status":"revoked"}` |
  | GET | `/stations` | device or admin | → `{"stations":[{"id","title","seedVideoId","iconIndex"}]}` |
  | PUT | `/stations` | admin | `{"stations":[…]}` (replaces the whole list, max 6 per the design's "4–6 huge station tiles") → same shape back |
  | GET | `/killswitches` | device or admin | → `{"sangat":bool,"parivaar_mix":bool,"family_playlists":bool,"cast_relay":bool}` |
  | PUT | `/killswitches` | admin | partial map → full map back |
  | POST | `/health` | device | `{"deviceId","versionCode":int,"versionName":"…","timezone":"<IANA tz, e.g. Asia/Kolkata>"}` → `204` (no body) |
  | GET | `/health` | admin | → `{"devices":[{"deviceId","deviceName","versionCode","versionName","lastSeenAt","timezone"}]}` |
  "admin" = header `Authorization: Bearer <ADMIN_KEY>` matching the Worker secret exactly. "device" = header `Authorization: Bearer <sessionToken>` matching an `approved` roster row; a revoked or unknown token gets `401`. Any request missing/failing its required auth gets `401` before touching storage.
- **Admin key:** one `ADMIN_KEY` Worker secret (32 random bytes, hex), generated once by the owner via `wrangler secret put ADMIN_KEY` on their own machine (never generated or seen by an agent — same "sensitive material by human hands" rule as the release signing key), then pasted once into the app's Manage Family setup screen, which Keystore-encrypts and stores it exactly like `InnerTubeCookieKey` (AES-GCM, hardware-backed where available). Only the owner's own phone ever holds it.
- **Rate limiting (DO-side, per the design's "rate-limited"):** at most 1 accepted `/health` write per device per 5 minutes (extra calls return `204` without a storage write — fail quiet, never error, per "car-safe" and "never silent" principles); at most 10 `/pairing/claim` attempts per token before it's burned early; at most 20 admin requests per minute total (a simple fixed-window counter row in DO storage) — past that, `429`.
- **Kill-switch keys are exactly** `"sangat"`, `"parivaar_mix"`, `"family_playlists"`, `"cast_relay"` (the design's "all four family features," Phase 4+'s shipping order) — a `FamilyFeature` Kotlin enum with these four entries, default `true` until the hub says otherwise, and the app must fail open (feature enabled) if the hub is unreachable, never fail closed.
- **HubClient (`saraswati/family/HubClient.kt`):** pure suspend functions over `OkHttpClient` (already a dependency), one call = one function (`createPairing()`, `claimPairing(token, deviceName)`, `approveDevice(deviceId)`, …), returning `Result<T>` — never throws past its own boundary. The Worker's base URL is a `BuildConfig` field (`HUB_BASE_URL`), not hardcoded, so a future custom domain is a one-line change.
- **Network security config:** add the Worker's `*.workers.dev` host (or the exact custom domain once chosen) to `app/src/main/res/xml/network_security_config.xml`'s allowlist — nothing else talks to arbitrary hosts.
- **Navigation:** add `Screens.Family` (`route = "family"`) following the existing `Screens.kt` pattern (see `Screens.ListenTogether` for the shape), inserted into `NavigationBuilder.kt` and the bottom nav between Library and the mini-player, matching the design's "Home · Search · Library · Family."
- **Saral is not a fifth tab.** A `GrandparentRole` boolean preference (new key in `PreferenceKeys.kt`) gates `MainActivity`'s top-level content: when set, Saral's own screen replaces the normal Home/Search/Library/Family navigation entirely (no Settings, no other tabs reachable), per "Grandparent-proof… zero fiddling."
- **Car tabs:** `MediaLibrarySessionCallback.onGetChildren`'s `MusicService.ROOT` branch changes from a flat shelf list to exactly four browsable folders, in order: Home, Library, Stations, Family. Today's `YOUTUBE_PLAYLIST`-sourced shelves become Home's children (always offered, not gated behind `AndroidAutoYouTubePlaylistsKey`); today's `AndroidAutoSection`-driven shelf list (Liked/Songs/Artists/Albums/Playlists) becomes Library's children, unchanged. Stations' children come from `HubClient.getStations()`, cached (see below). Family's children, for this phase, are exactly one row — a disabled/greyed "Sangat (coming soon)" item — since Parivaar Mix, family playlists and a connected Sangat are Phase 4 deliverables; the car must never advertise content that doesn't exist yet.
- **Car must stay instant and offline-first (per the design):** Stations' and Family's car children are served from a local cache (`DataStore`-backed, refreshed opportunistically on the phone's own app launches, never fetched live inside `onGetChildren`) and return immediately with whatever is cached (possibly empty) rather than ever awaiting a network call from the car.
- **Design gate:** no task after Task 1 that touches Compose UI may start until Task 1's mockups are explicitly approved by the user. A task description below marked "**gated on Task 1**" means exactly this.
- **Everything from Phase 1 and Phase 2's Global Constraints still applies** (identity, SDK/JDK paths, `// SARASWATI:` markers on upstream files, the commit attribution trailer, memory limits via `scripts/dev-env.sh`, no secrets in output, `security/apk_gate.py check` and the JVM test suite passing before a change lands). Work on branch `phase3-hub-saral-car`.
- **Human checkpoints (the agent stops and asks):** Task 1's mockup approval; Task 2's Cloudflare account/`wrangler login` and the Worker's first deploy; Task 2's `ADMIN_KEY` generation and paste-in; Task 9's on-device voice-search and grandparent-role walkthrough; Task 13's actual phone-by-phone rollout (each real family member's phone is set up by a human, never an agent).

## Review Focus

1. **A forged, guessed or replayed pairing token joins the family without the owner's approval.** The claim endpoint must reject any token not in storage, already used, or past its 15-minute TTL, and a claimed-but-not-yet-approved device must get zero access to `/stations`, `/killswitches` or anything else until `approve`. Owner: Task 3.
2. **A non-owner device calls an owner-only endpoint** (create a pairing, approve/revoke a device, set stations or kill-switches) by guessing a session token or omitting auth entirely — must be `401`, not merely hidden in the app's UI. Owner: Tasks 3-5.
3. **The Worker's free-plan request budget gets exhausted** by one misbehaving or compromised phone hammering `/health` or `/pairing/claim` — the rate limits above must actually cap storage writes, not just log a warning. Owner: Task 5.
4. **Saral exposes a way out to Settings or the other tabs** — a stray back-gesture, a long-press, or a deep link must not surface anything but the station tiles and transport controls while the grandparent role is active. Owner: Task 9.
5. **The car's Stations or Family folder blocks on the network** when the Worker is unreachable (no signal in a parking garage, Worker cold-start, DNS failure) — `onGetChildren` must return the cached list (even empty) immediately, never await a live `HubClient` call. Owner: Task 11.

---

### Task 1: Mockups (Design gate)

**Files:**
- Create: mockup HTML/CSS in a scratch location of the executor's choice (not committed to the repo — these are throwaway, per the design's "clickable HTML mockups," not production assets)

**Interfaces:**
- Produces: the user's explicit approval (or requested changes, re-mocked and re-approved) before any later task in this plan begins.

- [ ] **Step 1:** Build clickable, interactive mockups (not static images) covering: Saral's station-tile grid (4-6 huge tiles) with giant play/pause/next and a visible voice-search affordance, **with Saral's own UI text in Hindi** (not just feature names — this is the one surface built exclusively for grandparents in India, per the owner's explicit decision; the rest of the app stays English with Hindi feature names as already decided in the design); Veena's full-screen player (album-art-derived colour, lyrics, queue, Cast and Sangat one-tap icons); the onboarding/pairing flow (scan-or-open-link screen, the owner's "approve this phone" screen, role picking, Saral's taste-seeding step); a festival-window banner for Home and a matching ribbon for Saral (Task 10 — mock its look now so Task 10's implementer isn't inventing visual language mid-task); and a visual-consistency pass on the already-shipped Updater screens (Phase 2) against the dark/marigold theme — a light review, not a redesign, since the Updater already ships and is tested. Use this session's Artifact tool (`intent: "design"` on its quickstart) to publish them for the user to open and react to in the browser, per the design's own "Design gate: clickable HTML mockups open in your browser before any Kotlin UI work."
- [ ] **Step 2:** Pin the Hindi/Devanagari typeface in the same mockup pass: use **Noto Sans Devanagari** (Google Fonts, free, designed to pair with the Noto Latin family) for every Hindi string in Saral, bundled as an app font resource rather than relying on the system font (Android's default system font often renders Devanagari inconsistently across OEM skins — Samsung and Xiaomi in particular, both family phones). Confirm in the mockup that it visually pairs with whatever Latin typeface the rest of the app already uses; if it clashes, note the adjustment needed rather than silently picking something else.
- [ ] **Step 3: HUMAN CHECKPOINT.** Walk the user through each mockup. Record their requested changes, re-mock, and re-show until they explicitly approve. Do not proceed to Task 2's UI-facing siblings (Tasks 7-11) without that approval; Tasks 2-6 (the Worker and HubClient, which have no UI) may proceed in parallel while mockup review is still open if the user prefers, but nothing Compose-based starts first.
- [ ] **Step 4:** Record the approved look (exact colours, spacing, tile count, icon choices, the Hindi copy for every Saral string) as a short note in this plan's workspace ledger so Tasks 7-11's implementers inherit it without re-deriving it.

### Task 2: Worker scaffold and `FamilyHub` Durable Object skeleton

**Files:**
- Create: `worker/package.json`, `worker/tsconfig.json`, `worker/wrangler.toml`, `worker/src/index.ts`, `worker/src/FamilyHub.ts`, `worker/test/familyhub.smoke.test.ts`

**Interfaces:**
- Produces: a deployed, empty-but-working Worker at `https://saraswati-sangat.<subdomain>.workers.dev` with one `FamilyHub` Durable Object, a single global instance (fixed id `idFromName("family")`), and a health-check route `GET /ping` → `200 "ok"` that later tasks build on.

- [ ] **Step 1: HUMAN CHECKPOINT.** Ask the user to create (or confirm) a free Cloudflare account with 2FA on, and run `wrangler login` themselves on their own machine (an agent never holds Cloudflare credentials). Confirm the account's workers.dev subdomain.
- [ ] **Step 2:** Scaffold `worker/` with `wrangler.toml` declaring the Worker name `saraswati-sangat`, a `durable_objects` binding `FAMILY_HUB` → class `FamilyHub`, and the required `migrations` block for a new SQLite-backed Durable Object class. `package.json` pins `wrangler`, `typescript`, `vitest`, `@cloudflare/vitest-pool-workers` to exact current versions (check latest stable of each with `npm view <pkg> version` rather than guessing).
- [ ] **Step 3:** Implement `FamilyHub` extending `DurableObject` with a constructor that runs `ctx.storage.sql.exec(...)` to create tables (empty for now — later tasks add `pairing_tokens`, `roster`, `stations`, `killswitches`, `health`, `rate_limits`) and a `fetch(request)` handling only `GET /ping`.
- [ ] **Step 4:** `index.ts`'s default export routes every request to the single `FAMILY_HUB` DO instance's `fetch`.
- [ ] **Step 5: Write the failing test** `familyhub.smoke.test.ts`: a request to `/ping` through the test's `env.FAMILY_HUB` binding returns `200` with body `"ok"`.
- [ ] **Step 6: Run** `npx vitest run` in `worker/`; expect FAIL before step 3/4 exist, PASS after.
- [ ] **Step 7: HUMAN CHECKPOINT.** Ask the user to run `wrangler deploy` themselves (or explicitly authorize the agent to run it, if they'd rather) and confirm `curl https://saraswati-sangat.<subdomain>.workers.dev/ping` returns `ok`.
- [ ] **Step 8:** Generate `ADMIN_KEY` (32 random bytes, hex — `openssl rand -hex 32`) and have the user run `wrangler secret put ADMIN_KEY` themselves, pasting the value only into their own terminal, never into chat or a file this session can read back.
- [ ] **Step 9: Commit.** `feat(worker): scaffold saraswati-sangat Worker and empty FamilyHub DO`.

### Task 3: Pairing and roster

**Files:**
- Modify: `worker/src/FamilyHub.ts`
- Test: `worker/test/pairing.test.ts`, `worker/test/roster.test.ts`

**Interfaces:**
- Consumes: `ADMIN_KEY` from the environment (Task 2).
- Produces: `POST /pairing/create`, `POST /pairing/claim`, `GET /roster`, `POST /roster/{id}/approve`, `POST /roster/{id}/revoke` exactly as specified in Global Constraints' API table; a shared `authenticate(request: Request, env: Env): Promise<{role: "admin"} | {role: "device", deviceId: string} | null>` (checks the bearer token against `ADMIN_KEY` first, then against an `approved` roster row's `sessionToken`; `null` means unauthenticated) that Tasks 4 and 5 import and reuse rather than re-checking auth their own way.

- [ ] **Step 1:** Create tables: `pairing_tokens(token TEXT PRIMARY KEY, expiresAt INTEGER, usedAt INTEGER)`, `roster(deviceId TEXT PRIMARY KEY, deviceName TEXT, status TEXT, sessionToken TEXT, approvedAt INTEGER)`.
- [ ] **Step 2: Write the failing tests** (`pairing.test.ts`): `/pairing/create` without the admin bearer token returns `401`; with it, returns a 64-char hex token and an `expiresAt` ~15 minutes out; `/pairing/claim` with that token creates a `pending` roster row and returns a `deviceId`; claiming the same token twice returns `410` the second time; claiming an expired token returns `410`; an unknown token returns `404`.
- [ ] **Step 3: Run**, expect FAIL (routes don't exist yet).
- [ ] **Step 4:** Implement `authenticate()` and the four pairing/claim behaviors in `fetch`, using `crypto.randomUUID()` for `deviceId` and `crypto.getRandomValues` (hex-encoded) for both the pairing token and, on approval, the session token.
- [ ] **Step 5: Write the failing tests** (`roster.test.ts`): `GET /roster` without admin auth is `401`; with it, lists every claimed device and its status; `POST /roster/{id}/approve` on a `pending` device sets `status="approved"`, mints a session token, and returns it; approving an already-approved or unknown device is `404`; `POST /roster/{id}/revoke` sets `status="revoked"` and clears `sessionToken` to `NULL` — verify directly via a follow-up admin-authed `GET /roster` showing `status="revoked"`, not via another endpoint (Tasks 4-5 don't exist yet at this point in the task order).
- [ ] **Step 6: Run**, expect FAIL then PASS after implementing approve/revoke.
- [ ] **Step 7: Commit.** `feat(worker): pairing and roster endpoints`.

### Task 4: Stations and kill-switches

**Files:**
- Modify: `worker/src/FamilyHub.ts`
- Test: `worker/test/stations.test.ts`, `worker/test/killswitches.test.ts`

**Interfaces:**
- Consumes: `authenticate()` (Task 3).
- Produces: `GET/PUT /stations`, `GET/PUT /killswitches` per the API table; a device-authed `GET` requires an `approved` roster row (enforced by `authenticate()` already, per Task 3).

- [ ] **Step 1:** Create tables: `stations(id TEXT PRIMARY KEY, title TEXT, seedVideoId TEXT, iconIndex INTEGER, position INTEGER)`, `killswitches(key TEXT PRIMARY KEY, enabled INTEGER)`, seeded on first access with the four `FamilyFeature` keys from Global Constraints, each `enabled=1`.
- [ ] **Step 2: Write the failing tests**: `PUT /stations` without admin auth is `401`; with it, a list of 1-6 stations replaces the table and `GET /stations` (admin or an approved device) returns them in `position` order; a 7th station in the `PUT` body is rejected `400`; `GET /killswitches` returns all four keys even before any `PUT`; `PUT /killswitches` with a partial map (e.g. `{"sangat": false}`) only changes that key; an unknown key in the `PUT` body is `400`; a device with a revoked or missing session token gets `401` from both `GET` endpoints.
- [ ] **Step 3: Run**, expect FAIL.
- [ ] **Step 4:** Implement both endpoint pairs.
- [ ] **Step 5: Run**, expect PASS.
- [ ] **Step 6: Commit.** `feat(worker): stations and kill-switch endpoints`.

### Task 5: Health reporting and rate limiting

**Files:**
- Modify: `worker/src/FamilyHub.ts`
- Test: `worker/test/health.test.ts`, `worker/test/ratelimit.test.ts`

**Interfaces:**
- Produces: `POST/GET /health` per the API table; a `FamilyHub` instance method `private rateLimit(key: string, windowSeconds: number, maxCount: number): boolean` (true = allowed), reading/writing `this.ctx.storage.sql` directly — tested the same way as every other task's DO logic (through `env.FAMILY_HUB`'s `fetch`, via a temporary test-only route, or by exporting the method for direct unit construction of a `FamilyHub` instance against `vitest-pool-workers`' runtime — whichever this project's existing Worker test setup already supports; do not introduce a second, mock-based testing style just for this one function).

- [ ] **Step 1:** Create tables: `health(deviceId TEXT PRIMARY KEY, versionCode INTEGER, versionName TEXT, timezone TEXT, lastSeenAt INTEGER)`, `rate_limits(key TEXT PRIMARY KEY, windowStart INTEGER, count INTEGER)`.
- [ ] **Step 2: Write the failing tests** (`ratelimit.test.ts`, against the real DO runtime per Interfaces above): first call in a fresh window returns `true` and sets `count=1`; calls under the max in the same window return `true` and increment; a call once `count == max` returns `false` without incrementing further; a call in a new window (after `windowSeconds`) resets the counter and returns `true`.
- [ ] **Step 3: Run**, FAIL, then implement `rateLimit`, PASS.
- [ ] **Step 4: Write the failing tests** (`health.test.ts`): `POST /health` from a device without a valid session token is `401`; a valid report (including `timezone`) upserts the row and returns `204`; a second report from the same device inside 5 minutes also returns `204` but does **not** update `lastSeenAt` or `timezone` (rate-limited, silently dropped — verify via `GET /health`'s timestamp staying put); after the window, a new report does update it; `GET /health` without admin auth is `401`, with it lists every device's last report including `timezone`.
- [ ] **Step 5: Run**, FAIL, then wire `/health` through `rateLimit` with key `"health:" + deviceId`, window 300s, max 1. PASS.
- [ ] **Step 6:** Apply the same `rateLimit` helper to `/pairing/claim` with key `"claim:" + token`, window 900s (the token's own TTL), max 10.
- [ ] **Step 7: Commit.** `feat(worker): health reporting and rate limiting`.

### Task 6: Kotlin `HubClient`

**Files:**
- Create: `app/src/main/kotlin/com/metrolist/music/saraswati/family/HubClient.kt`, `app/src/main/kotlin/com/metrolist/music/saraswati/family/HubModels.kt`
- Test: `app/src/test/kotlin/com/metrolist/music/saraswati/family/HubClientTest.kt`
- Modify: `app/build.gradle.kts` (add `buildConfigField("String", "HUB_BASE_URL", ...)` per flavor, pointing at the Task 2 Worker URL for `gms`), `app/src/main/res/xml/network_security_config.xml` (add the Worker host)

**Interfaces:**
- Produces: `class HubClient(private val baseUrl: String, private val client: OkHttpClient)` with suspend functions `createPairing(adminKey: String): Result<PairingToken>`, `claimPairing(token: String, deviceName: String): Result<ClaimResult>`, `getRoster(adminKey: String): Result<List<RosterEntry>>`, `approveDevice(adminKey: String, deviceId: String): Result<ApprovedDevice>`, `revokeDevice(adminKey: String, deviceId: String): Result<Unit>`, `getStations(auth: String): Result<List<Station>>`, `setStations(adminKey: String, stations: List<Station>): Result<List<Station>>`, `getKillSwitches(auth: String): Result<Map<String, Boolean>>`, `setKillSwitches(adminKey: String, changes: Map<String, Boolean>): Result<Map<String, Boolean>>`, `reportHealth(sessionToken: String, versionCode: Int, versionName: String, timezone: String = TimeZone.getDefault().id): Result<Unit>`, `getHealth(adminKey: String): Result<List<DeviceHealth>>` (`DeviceHealth` carries `timezone: String` alongside the existing fields, sourced from each device's own `reportHealth` call — never guessed or inferred server-side). Every function builds its own `Request` with the matching `Authorization: Bearer` header and returns `Result.failure` (never throws) on any non-2xx response or `IOException`.
- Consumes: nothing from earlier Phase 3 tasks; this is a pure networking layer against Task 2-5's deployed API.

- [ ] **Step 1:** Add OkHttp's `mockwebserver` as a test dependency (not currently in `gradle/libs.versions.toml` — add it there, pinned to the same version line as the project's existing OkHttp, with a checksum entry in `gradle/verification-metadata.xml` per Phase 2's dependency-verification setup).
- [ ] **Step 2: Write the failing tests** in `HubClientTest.kt` against that `MockWebServer`: one test per function covering its success shape and its `401`/`404`/`410`/`429` failure mapping to `Result.failure`; a test confirming a network `IOException` also becomes `Result.failure`, never a thrown exception out of `HubClient`.
- [ ] **Step 3: Run**, expect FAIL (`HubClient` doesn't exist).
- [ ] **Step 4:** Implement `HubModels.kt` (`@Serializable data class`s mirroring the API table's JSON shapes exactly — field names match the Worker's wire format, not Kotlin convention where they'd otherwise differ) and `HubClient.kt`.
- [ ] **Step 5: Run**, expect PASS.
- [ ] **Step 6:** Add the admin key and session token's Keystore-sealed storage, following `InnerTubeCookieKey`'s existing encryption helper exactly (grep for it in the security package rather than re-deriving the AES-GCM setup) — new preference keys `FamilyAdminKeyKey` and `FamilyHubSessionTokenKey` in `PreferenceKeys.kt`.
- [ ] **Step 7:** Add both new keys to `SecretPreferenceKeys.all` in `app/src/main/kotlin/com/metrolist/music/saraswati/security/SecretPreferenceKeys.kt` — they are bearer credentials, same category as `InnerTubeCookieKey`, and `SecretKeyCoverageTest` (`app/src/test/kotlin/com/metrolist/music/saraswati/security/SecretKeyCoverageTest.kt`) fails the build on any sensitive-shaped key that isn't consciously classified there. This single addition also makes `BackupPolicy.NEVER_EXPORT_OR_RESTORE` (Phase 1) strip both keys from exports/restores and Sentry's scrubber (Phase 2 Task 12, if done) redact them, with no separate step needed in either place.
- [ ] **Step 8:** Run `security/apk_gate.py check` and the full JVM test suite (including `SecretKeyCoverageTest`); confirm green.
- [ ] **Step 9: Commit.** `feat(app): HubClient and Keystore-sealed hub credentials`.

### Task 7: Family tab and Manage Family (pairing + health view)

**Gated on Task 1.**

**Files:**
- Create: `app/src/main/kotlin/com/metrolist/music/saraswati/family/FamilyScreen.kt`, `app/src/main/kotlin/com/metrolist/music/saraswati/family/ManageFamilyScreen.kt`, `app/src/main/kotlin/com/metrolist/music/saraswati/family/FamilyViewModel.kt`
- Test: `app/src/test/kotlin/com/metrolist/music/saraswati/family/FamilyViewModelTest.kt`
- Modify: `app/src/main/kotlin/com/metrolist/music/ui/screens/Screens.kt` (add `Family`, following the `ListenTogether` object's exact shape), `app/src/main/kotlin/com/metrolist/music/ui/screens/NavigationBuilder.kt` (route + bottom-nav entry between Library and the mini-player)

**Interfaces:**
- Consumes: `HubClient` (Task 6).
- Produces: `FamilyViewModel` exposing `pairingState: StateFlow<PairingUiState>`, `roster: StateFlow<List<RosterEntry>>`, `health: StateFlow<List<DeviceHealth>>`, and functions `startPairing()`, `approve(deviceId: String)`, `revoke(deviceId: String)` — later Phase 4 tasks (Sangat, Parivaar Mix) extend this same screen rather than creating a parallel one.

- [ ] **Step 1: Write the failing tests** in `FamilyViewModelTest.kt` (fake `HubClient`): `startPairing()` surfaces the token/QR-able link in `pairingState`; `approve()`/`revoke()` call through and refresh `roster`; a `HubClient` failure surfaces as an error state the screen can show, never a crash.
- [ ] **Step 2: Run**, FAIL, implement, PASS.
- [ ] **Step 3:** Build `FamilyScreen` (the regular, non-owner view: for now, since Parivaar Mix/family playlists/Sangat are Phase 4, this shows only a "Manage family" entry point gated on whether this phone is the owner — a new `IsFamilyOwnerKey` boolean preference, true only on the phone that completed Task 2's setup) and `ManageFamilyScreen` (pairing QR/link display, the pending-approval list with Approve/Revoke, and the health view table: device name, version, last seen, and that device's own current local time computed from its reported `timezone` — e.g. "Mummy's Pixel · v3902 · 11:42 PM local" — so the owner (in Canada) can tell at a glance whether it's a reasonable hour to call family in India, not just a raw "3 hours ago") per Task 1's approved mockups.
- [ ] **Step 4:** Wire the pairing link's deep link (a new `https://` or custom-scheme URI the mockups settled on) to open `ManageFamilyScreen`'s claim-confirmation UI when tapped on a phone that isn't yet paired — reuse the existing deep-link validation pattern from Phase 1's hardening (grep for the existing deep-link intent filter/validator rather than adding a new unvalidated one).
- [ ] **Step 5:** Run `security/apk_gate.py check` (a new deep link and a new exported-nothing screen must not change the manifest's allowed surface unexpectedly) and the full JVM test suite.
- [ ] **Step 6: Commit.** `feat(app): Family tab, pairing and Manage Family screens`.

### Task 8: Station and kill-switch owner editor

**Gated on Task 1.**

**Files:**
- Modify: `app/src/main/kotlin/com/metrolist/music/saraswati/family/ManageFamilyScreen.kt`, `app/src/main/kotlin/com/metrolist/music/saraswati/family/FamilyViewModel.kt`
- Test: extend `FamilyViewModelTest.kt`

**Interfaces:**
- Consumes: `HubClient.getStations/setStations/getKillSwitches/setKillSwitches` (Task 6).
- Produces: `FamilyViewModel.stations: StateFlow<List<Station>>`, `killSwitches: StateFlow<Map<String, Boolean>>`, `setStations(List<Station>)`, `toggleKillSwitch(String, Boolean)`.

- [ ] **Step 1: Write the failing tests**: adding/removing/reordering a station (within the 1-6 cap enforced client-side too, so the owner gets instant feedback rather than waiting on the Worker's `400`) calls `setStations` with the full resulting list; toggling a kill-switch calls `setKillSwitches` with just that key; when `getStations()` returns empty, the editor's initial state holds the four default stations (Step 4) rather than an empty list; once any `setStations` call has happened, a later empty result is shown as genuinely empty, not re-defaulted.
- [ ] **Step 2: Run**, FAIL, implement, PASS.
- [ ] **Step 3:** Add the station editor (title, a YouTube video/URL field resolved to `seedVideoId`, an icon picker from a small fixed set — pin the exact icon set from Task 1's approved mockup) and kill-switch toggles (the four `FamilyFeature` keys, each with a one-line description of what it hides) to `ManageFamilyScreen`, owner-only.
- [ ] **Step 4:** When `getStations()` returns an empty list (first-run, nobody has set any yet), pre-fill the editor with four defaults — grounded in what this family's own library already shows, not a generic guess — rather than showing a blank list the owner has to build from nothing: "Bollywood Classics" (Hindi film songs), "Bhajans & Devotional" (Krishna bhajans and aarti), "Ghazals & Old Melodies" (golden-era romantic songs), "Peaceful & Meditation" (sleep/meditation content). Leave the remaining 2 of the 6 slots empty for the owner to personalize per grandparent. These are a starting point the owner edits or replaces freely in the same screen, never hardcoded past first save — once `setStations` is called once, the pre-fill never reappears.
- [ ] **Step 5:** Run the JVM test suite.
- [ ] **Step 6: Commit.** `feat(app): station and kill-switch owner editor`.

### Task 9: Saral v1

**Gated on Task 1 and Task 8** (Saral has nothing to show until stations can be set).

**Files:**
- Create: `app/src/main/kotlin/com/metrolist/music/saraswati/saral/SaralScreen.kt`, `app/src/main/kotlin/com/metrolist/music/saraswati/saral/SaralViewModel.kt`
- Test: `app/src/test/kotlin/com/metrolist/music/saraswati/saral/SaralViewModelTest.kt`
- Modify: `app/src/main/kotlin/com/metrolist/music/constants/PreferenceKeys.kt` (add `GrandparentRoleKey: Preferences.Key<Boolean>`), `app/src/main/kotlin/com/metrolist/music/MainActivity.kt` (when `GrandparentRoleKey` is true, host `SaralScreen` as the entire content instead of the normal nav graph — no other route reachable)

**Interfaces:**
- Consumes: `HubClient.getStations` (Task 6), cached per Global Constraints' "car must stay instant" rule (the same cache Task 11's car tabs read, so build it once here: a `StationsCache` backed by `DataStore`, refreshed on `SaralViewModel` init and on `FamilyViewModel`'s station edits).
- Produces: `StationsCache` (new, in `saraswati/family/`) reused by Task 11.

- [ ] **Step 1: Write the failing tests**: `SaralViewModel` exposes the cached stations (never blocks on network); tapping a tile starts playback of a YouTube radio seeded from that station's `seedVideoId`; `RecognizerIntent` voice search (reuse the app's existing voice-search entry point — grep for its current wiring rather than re-adding a second one) resolves to a play action the same way the existing search-driven play does.
- [ ] **Step 2: Run**, FAIL, implement, PASS.
- [ ] **Step 3:** Build `SaralScreen` per Task 1's approved mockup: 4-6 huge tiles, giant play/pause/next, large text throughout, no settings affordance of any kind, no back-navigation out of the tile grid except via the transport controls. Every string on this screen is in Hindi, set in Noto Sans Devanagari (Task 1 Step 2) — this is the one screen in the app with Hindi UI text, not just a Hindi feature name.
- [ ] **Step 4:** Add the grandparent-role toggle and taste-seeding step (`set_tasteprofile` per the design — grep Metrolist's existing taste-seeding call if InnerTube already exposes it from onboarding; if it doesn't exist yet, stub a `TODO` test marking it explicitly deferred rather than silently skipping it) to the pairing/onboarding flow from Task 7. Pre-select Hindi/Bollywood, devotional/bhajan and ghazal as the default taste-seed genres when the role being set up is grandparent — the same reasoning as Task 8's default stations, grounded in this family's own listening data — with every genre still freely deselectable in the same step; this only sets the starting selection, never a forced one.
- [ ] **Step 5: HUMAN CHECKPOINT.** Walk through Saral on the Pixel with voice search and the role toggle; confirm no path reaches Settings or another tab.
- [ ] **Step 6:** Run the JVM test suite.
- [ ] **Step 7: Commit.** `feat(app): Saral v1`.

### Task 10: Festival awareness (music and UI)

**Gated on Task 1.**

**Files:**
- Create: `app/src/main/kotlin/com/metrolist/music/saraswati/culture/FestivalCalendar.kt`
- Test: `app/src/test/kotlin/com/metrolist/music/saraswati/culture/FestivalCalendarTest.kt`
- Modify: `app/src/main/kotlin/com/metrolist/music/ui/screens/HomeScreen.kt` (new banner, alongside the existing Sangat-live/Update-ready banner slot), `app/src/main/kotlin/com/metrolist/music/saraswati/saral/SaralScreen.kt` (a matching ribbon, not a 7th tile — stays within the 4-6 tile cap)

**Interfaces:**
- Produces: `data class Festival(val name: String, val startDate: LocalDate, val endDate: LocalDate, val greetingEn: String, val greetingHi: String, val radioQuery: String)`, `object FestivalCalendar { fun current(today: LocalDate = LocalDate.now()): Festival? }`.

- [ ] **Step 1:** Source exact dates for the next 3 calendar years (today's year plus two) for Diwali, Holi, Navratri, Raksha Bandhan and Krishna Janmashtami from a reliable public Hindu calendar source (e.g. drikpanchang.com) — these are lunar-calendar festivals and shift every year; do not estimate or compute them from a formula, and do not reuse a date from a different year. Each festival's window is its calendar date through the following day (most of these are single/two-day observances; note any that traditionally span longer, like Navratri's 9 nights, and size the window accordingly).
- [ ] **Step 2: Write the failing tests** in `FestivalCalendarTest.kt`: a date inside a sourced festival's window returns that `Festival`; a date between festivals returns `null`; two festival windows never overlap in the pinned table (a structural test over the whole table, not per-festival) — if sourcing finds two festivals genuinely overlap in a given year (Navratri sometimes abuts Diwali), the test encodes that as expected rather than failing on real data.
- [ ] **Step 3: Run**, FAIL, implement `FestivalCalendar` with the sourced table, PASS.
- [ ] **Step 4:** Add the Home banner: when `FestivalCalendar.current()` is non-null, show a themed banner (per Task 1's approved mockup — a subtle accent treatment, not a full theme change) with the festival's greeting and a one-tap button that starts a YouTube radio from `radioQuery` (e.g. "Diwali songs") — a one-off suggestion, not one of the owner's fixed 6 stations, so it never consumes a station slot. No banner when `current()` is null.
- [ ] **Step 5:** Add the matching Saral ribbon: same non-null check, the Hindi greeting (`greetingHi`, Noto Sans Devanagari per Task 1 Step 2) and the same one-tap radio action, shown as a ribbon above the tile grid — the tile grid itself and its 4-6-tile cap are unchanged.
- [ ] **Step 6:** Run the JVM test suite.
- [ ] **Step 7: Commit.** `feat(app): festival-aware Home banner and Saral ribbon`.

### Task 11: Car tabs (Home, Library, Stations, Family)

**Files:**
- Modify: `app/src/main/kotlin/com/metrolist/music/playback/MediaLibrarySessionCallback.kt`
- Test: extend the existing test file for this class if one exists (grep first), else create `app/src/test/kotlin/com/metrolist/music/playback/MediaLibrarySessionCallbackCarTabsTest.kt`

**Interfaces:**
- Consumes: `StationsCache` (Task 9).
- Produces: `MusicService.ROOT`'s children restructured to exactly four `browsableMediaItem`s (Home, Library, Stations, Family, in that order), each with its own new `MusicService`-style constant media-id (`MusicService.CAR_HOME`, `_LIBRARY`, `_STATIONS`, `_FAMILY` — grep `MusicService`'s existing constant-definition style and match it) and a new `onGetChildren` branch per id.

- [ ] **Step 1: Write the failing tests**: `onGetChildren(ROOT)` returns exactly 4 items in the pinned order; `onGetChildren(CAR_LIBRARY)` returns what `ROOT` used to return (today's `AndroidAutoSection` list), unchanged; `onGetChildren(CAR_HOME)` returns what the existing `YOUTUBE_PLAYLIST` branch produces; `onGetChildren(CAR_STATIONS)` returns `StationsCache`'s current contents synchronously (no suspend/network call in the path); an empty cache returns an empty list, not an error; `onGetChildren(CAR_FAMILY)` returns exactly one disabled item titled per Task 1's approved copy for "Sangat (coming soon)".
- [ ] **Step 2: Run**, FAIL.
- [ ] **Step 3:** Implement the restructure: move today's `MusicService.ROOT` body to `CAR_LIBRARY`'s branch verbatim; move today's `YOUTUBE_PLAYLIST` body to `CAR_HOME`'s branch verbatim (dropping the `AndroidAutoYouTubePlaylistsKey` gate, since Home is now always offered); add `CAR_STATIONS` and `CAR_FAMILY` as new branches; make `ROOT` build the 4-item folder list.
- [ ] **Step 4: Run**, expect PASS.
- [ ] **Step 5:** Test on the Desktop Head Unit (per the design's existing Android Auto testing approach): browse all 4 tabs, confirm Stations plays a seeded radio, confirm Family shows the disabled placeholder and nothing more.
- [ ] **Step 6:** Run the JVM test suite and `security/apk_gate.py check`.
- [ ] **Step 7: Commit.** `feat(app): car tabs — Home, Library, Stations, Family`.

### Task 12: GitHub Pages setup guide

**Files:**
- Create: `docs/setup-guide/index.html`, `docs/setup-guide/pixel.html`, `docs/setup-guide/samsung.html`, `docs/setup-guide/xiaomi.html` (published to the existing `gh-pages` branch alongside Phase 2's `stable.json`/`beta.json` — reuse that publish step, don't invent a second deploy path)

**Interfaces:**
- Consumes: the exact pairing-link format from Task 7 (so the guide can show a real example without inventing its own).
- Produces: a static site at `https://diamondspark.github.io/saraswati/setup-guide/` covering the Remote setup steps from the design doc verbatim (install the Updater, install Saraswati, re-lock Chrome's install permission, battery settings per brand, pairing-link, car "Unknown sources," developer-verification device authorization — link to `docs/developer-verification.md`'s already-written procedure rather than re-explaining it).

- [ ] **Step 1:** Write the four static pages (plain HTML/CSS, no build step, matching the existing GitHub Pages deployment's simplicity) covering exactly the "Remote setup" section's 5 steps from the design doc, with a per-brand page for the Samsung Auto Blocker and Xiaomi Autostart specifics.
- [ ] **Step 2:** Extend whichever CI job already publishes to `gh-pages` (Phase 2's manifest publish step — grep `saraswati-release.yml`/`saraswati-promote.yml` for it) to also copy `docs/setup-guide/*` across, so the guide stays live without a separate manual publish step.
- [ ] **Step 3:** Verify: after the next `gh-pages` publish, `curl` each of the 4 URLs and confirm `200`.
- [ ] **Step 4: Commit.** `docs: GitHub Pages setup guide for remote family setup`.

### Task 13: Rollout runbook and phone-by-phone checklist

**Files:**
- Modify: `docs/release-runbook.md` (new "Phase 3 rollout" section)
- Create: `docs/rollout-checklist.md`

**Interfaces:**
- Produces: a per-phone checklist the owner actually runs through for each family member, combining Phase 1's security checklist (Play Protect, Auto Blocker, Developer options off, 2-Step Verification, battery/Autostart), this phase's pairing+approval step, and the explicit "Metrolist stays installed as a spare for the first month" decision.

- [ ] **Step 1:** Write `docs/rollout-checklist.md` as one checklist per phone: pre-install (phone model/CPU check against the arm64-only build, per Phase 1's Global Constraints), install (Updater → Saraswati, re-lock Chrome), hygiene (the Phase 1 "Phone hygiene" list, item by item), pairing (owner approves in Manage Family), role (adult vs. grandparent — Saral toggle), car (if applicable — Android Auto "Unknown sources"), developer-verification device authorization (link to `docs/developer-verification.md`), and a 30-day reminder to uninstall Metrolist once Saraswati is confirmed stable on that phone.
- [ ] **Step 2:** Add a short "Phase 3 rollout" section to `docs/release-runbook.md` pointing at the new checklist and at the setup guide (Task 12), so the runbook stays the single entry point for "how do I ship this to a phone."
- [ ] **Step 3: Commit.** `docs: rollout runbook and phone-by-phone checklist`.
- [ ] **Step 4: HUMAN CHECKPOINT.** This is where the plan ends and real rollout begins — the owner works through the checklist with each actual family member's phone, which is explicitly not something an agent does. Report the plan complete and hand the checklist over.

## Self-review notes

- **Spec coverage:** mockups, including Saral's Hindi UI text and Devanagari typeface (Task 1); Worker hub — pairing/roster (Task 3), stations/kill-switches (Task 4), health view with per-device timezone (Task 5); HubClient (Task 6); Family tab + Manage Family, health view showing each phone's local time (Task 7); station/kill-switch editor, with Indian-family-grounded default stations (Task 8); Saral v1, with Hindi UI text and Hindi/Bollywood/devotional taste-seeding defaults (Task 9); festival-aware Home banner and Saral ribbon (Task 10); car tabs — Stations and Family, plus the Home/Library restructure the design's 4-tab car model requires (Task 11); GitHub Pages setup guide (Task 12); phased rollout with the security checklist, Metrolist kept as a spare (Task 13). Deliberately out of scope here, confirmed against the design's own phase list: Sangat's live relay, Parivaar Mix, family playlists and the Cast relay are Phase 4; local files and Baseline Profile tuning beyond Phase 2's existing `:baselineprofile` module are Phase 5. Also deliberately deferred, per the owner's own call during this plan's review rather than silently dropped: a bilingual (English/Hindi) version of the setup guide — Task 12 stays English-only since the owner walks each family member through it live over video call anyway, per the design's existing "Help them over a video call" step; and time-zone-aware Sangat/cross-family notification *delivery* (not just display) — Task 7's health view now carries `timezone` precisely so a future Phase 4 task can use it for exactly this, but Phase 3 builds no notification-sending logic of its own to apply it to yet.
- **Type consistency:** `HubClient`'s function names and `Result<T>` shapes (Task 6) are used unchanged by `FamilyViewModel` (Tasks 7-8) and `SaralViewModel` (Task 9); `StationsCache`, introduced in Task 9 because Saral needs it first, is explicitly reused rather than reimplemented by Task 11's car tabs; the `FamilyFeature`/kill-switch key strings are pinned once in Global Constraints and referenced, not redefined, by Tasks 4, 6 and 8; `DeviceHealth.timezone` is produced once by Task 6's `HubClient`/`HubModels` and consumed unchanged by Task 7's health view; `Festival`'s `greetingHi` (Task 10) reuses the same Noto Sans Devanagari typeface Task 1 pins for Saral, not a second font decision.
- **Review Focus addendum:** the new Festival and locale work (Tasks 1, 7-10) introduces one more realistic failure mode beyond the five in Review Focus above — a wrong or stale festival date embarrassing the family by banner-ing the wrong day. It didn't displace any of the five security/auth-related ones (still the highest-consequence risks in this phase), so it's handled directly in Task 10's own steps instead: dates must be sourced from a real calendar, never computed or guessed, and the test suite checks the table's internal consistency rather than the real-world dates themselves (which no unit test can independently verify).
- **Proportion:** this plan is long because Phase 3 genuinely spans a new backend, two new screens, a new app mode, a locale/culture layer and a car-tree restructure — five review-worthy subsystems the existing two-phase precedent (Phase 2 alone ran 13 tasks for one subsystem) supports bundling into one phase-level plan. No task body contains a full implementation; every Worker and Kotlin step gives signatures, table/field names and exact test assertions, leaving the actual code to the implementer.
