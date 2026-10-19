# Saraswati rollout checklist

For the owner. Work through "Before the first phone" once, then copy the
"Per-phone checklist" for each family member and tick it off during the video
call. Nothing here is done by an agent: every family phone is set up by a
person, with the family member's own hands on their own sign-in.

Related docs:

- Setup guide the family member follows (Pixel, Samsung, Xiaomi pages):
  <https://diamondspark.github.io/saraswati/setup-guide/> (not published yet,
  Phase 3 Task 12; check that it loads before the first phone)
- Developer verification, in full: [`developer-verification.md`](developer-verification.md)
- How a build gets released and promoted: [`release-runbook.md`](release-runbook.md)
- Why each hygiene item exists: "Phone hygiene" in
  [the design](superpowers/specs/2026-10-17-saraswati-design.md)

## Before the first phone

Owner only. Do this once.

### The family hub (Cloudflare Worker)

- [ ] Your Cloudflare account has 2-Step Verification on, and `wrangler login`
      is done on your own machine.
- [ ] Your checkout includes commit `53351555` (or anything after it):
      `git merge-base --is-ancestor 53351555 HEAD && echo ok` prints `ok`.
      The hub's roster table has no migration for its newest column
      (`claimSecret`), so deploy from this commit or later onto a fresh hub.
      Do not deploy an older commit and upgrade it later.
- [ ] Deploy: `cd worker && npx wrangler deploy`
- [ ] Check it is up: `curl https://saraswati-sangat.pandey-mohitk.workers.dev/ping`
      returns `ok` (this is the address the app is built to use,
      `HUB_BASE_URL`; use the one `wrangler deploy` printed if it differs).
- [ ] Make the admin key, in your own terminal only:
      `openssl rand -hex 32`. Copy the output. Do not paste it into chat, a
      file, a note-to-self message or a screenshot.
- [ ] Give it to the hub: `cd worker && npx wrangler secret put ADMIN_KEY`, and
      paste the value at the prompt.
- [ ] Paste the same value once into the Manage Family setup screen on your own
      phone. The app stores it encrypted; only your phone ever holds it. How you
      carry it from the terminal to the phone is not specified by the design,
      so use a channel you already trust for passwords.
- [ ] To replace the key later, repeat the last two steps with a new value.

### The build

- [ ] A Saraswati release that includes the Phase 3 app (Manage Family and
      pairing) is published on the channel the family phones follow. The design
      puts the family on Stable, which only gets builds promoted from Beta after
      48 hours. See "How to release" and "How to promote" in the runbook.
- [ ] Your own phone has that build and Manage Family opens.

### Developer verification

- [ ] Both package names are registered. This was done on 2026-10-18; see
      [`developer-verification.md`](developer-verification.md).
- [ ] You can generate a device-authorization QR code or link in the Android
      Developer Console. You need one per phone, up to 20 phones in total.

## Per-phone checklist

Copy this block once per phone.

**Person:** ________  **Phone model:** ________  **Brand:** Pixel / Samsung / Xiaomi / other
**Android version:** ________  **Started:** ________  **Role:** adult / grandparent

### 1. Before you install

- [ ] Android 8.0 or newer (the app's minimum is API 26).
- [ ] The phone has an arm64 processor. The Saraswati build is arm64-only
      (`arm64-v8a`); Phase 1 assumed all family phones are 2022 or newer.
      If you can plug the phone in:
      `adb -s <serial> shell getprop ro.product.cpu.abilist` must list
      `arm64-v8a`. For a far-away phone, get the exact model from Settings,
      About phone, and look up its chipset. Phase 1 planned to record this per
      phone in `docs/device-checks.md`, but no phone is recorded there yet.
      If a phone lacks arm64, stop: `armeabi-v7a` would have to be added to the
      build first.
- [ ] Their Google account has 2-Step Verification on.
- [ ] A video call is booked. The guide is designed to be walked through live.
- [ ] Leave Metrolist alone if it is installed (package `com.metrolist.music`).
      It stays as a spare for the first month; see section 9.

### 2. Developer verification for this phone

Full procedure: [`developer-verification.md`](developer-verification.md). Not
repeated here.

- [ ] Generate this phone's device-authorization QR code or link in the Android
      Developer Console and send it to them privately.
- [ ] They open it on the phone and confirm the on-device consent prompt.
- [ ] Add the phone to the rollout log at the bottom (slot number out of 20).

Order note: `developer-verification.md` puts this before installing Saraswati
Updates. The design's "Remote setup" lists it last. Per
`developer-verification.md`, enforcement for regions outside Brazil, Indonesia,
Singapore and Thailand begins with the global rollout in 2027, so either order
works today; this checklist uses the one that still works once enforcement
applies.

### 3. Install

Follow the setup guide, with them on the call.

- [ ] Samsung only: turn Auto Blocker off for now (Settings, Security and
      privacy, Auto Blocker).
- [ ] They allow Chrome to install apps, then install **Saraswati Updates**.
- [ ] In Saraswati Updates they install **Saraswati**, following its prompts
      (the buttons read "Allow installing (one time)" and "Install").
- [ ] When Saraswati Updates says "All done. Now switch the permission off.",
      they switch it off, and they switch off Chrome's "Install unknown apps"
      permission too. The menu path differs by brand; the setup guide has each
      one.
- [ ] Samsung only: Auto Blocker is back on. One UI 8.5 may switch it back on by
      itself after 30 minutes, but check anyway.

### 4. Phone hygiene

From the design's "Phone hygiene". Tick each one on the phone itself.

- [ ] Play Protect is on.
- [ ] Samsung only: Auto Blocker is on (your decision).
- [ ] No app holds "Install unknown apps". Check Chrome, Saraswati Updates and
      Saraswati; all show off.
- [ ] Developer options are off. Far-away phones never need them, because setup
      is by link.
- [ ] Every Google account used on the phone has 2-Step Verification.
- [ ] Samsung and Xiaomi: battery for Saraswati is set to "Unrestricted", so
      playback survives a locked screen. The design lists no battery step for
      Pixel.
- [ ] Xiaomi only: Autostart is on for Saraswati.

### 5. Pair the phone

You and the family member, together. A pairing link expires after 15 minutes
and works once, so create it when they are ready to open it. If you send it
earlier in the call and it lapses, make another.

- [ ] You: in Manage Family, tap create pairing, then send the link to that
      person only, as a private message.
- [ ] They: open the link on the phone. The phone claims it and then waits for
      your approval.
- [ ] You: the phone appears in Manage Family as pending. Check the name matches
      the phone you are on the call with. Approve it.
- [ ] They: do nothing more. The phone collects its own sign-in automatically; it
      checks about every 5 seconds.

If the link has expired, or the phone says it was already used, create a new
one. If the phone just keeps waiting, see "If something goes wrong".

### 6. Role: adult or grandparent

- [ ] Once approved, they pick who the phone is for. The exact screen wording
      is not final in the build yet, so confirm it against the app.
- [ ] They sign in to YouTube inside Saraswati with their own hands. Never ask
      for or send a password.
- [ ] **Adult:** the normal app. Nothing more to do.
- [ ] **Grandparent (Saral):** once approved, the phone turns into the station
      tiles on its own within a few seconds. The favourite-singers step is not
      built yet (deferred in Task 9: the build has no taste-profile call), so
      there is nothing to pick here.
- [ ] Grandparent: the station tiles you set in Manage Family show up and look
      right.
- [ ] Grandparent: they start a station and pause it with no help. This is the
      design's own test for Saral.
- [ ] Grandparent: do every other in-app step first, signing in to YouTube
      included. Saral replaces the whole app with no Settings and no other
      tabs, the moment the owner approves the phone, and nothing on that phone
      switches it off. The easy order is to pair the phone as Adult, finish
      the sign-in, then have the owner set it to grandparent in Manage Family.
      To bring the full app back later, the owner sets the phone to Adult
      there; the phone follows the next time it is opened.

### 7. Car (only if they use Android Auto)

- [ ] In the Android Auto app, tap Version 10 times to reveal developer
      settings, then turn on "Unknown sources". Without it, Android Auto hides
      apps installed outside the Play Store. This is separate from the phone's
      Developer options and doesn't trip banking apps.
- [ ] Connect to the car. Saraswati is listed and its tabs load (Home, Library,
      Stations, Family). In this phase the Family tab only shows a "coming soon"
      placeholder.
- [ ] A station plays from the car screen.
- [ ] Sign-in only happens on the phone; the car shows "Sign in on your phone".

### 8. Before you hang up

- [ ] A song plays, and keeps playing with the screen locked.
- [ ] In Manage Family health view the phone shows the version you installed and
      a local time that matches the phone's clock.
- [ ] "Install unknown apps" is off for Chrome and Saraswati Updates (recheck).
- [ ] Add the row to the rollout log below.

### 9. The Metrolist spare: 30 days

Metrolist stays installed on this phone for the first month, so music still
plays if Saraswati has a problem.

- [ ] Day 0: calendar reminder set for 30 days from today: ________
- [ ] Day 30: ask them how it has been. The design doesn't define "confirmed
      stable". A reasonable test: they use it most days, nothing keeps crashing
      or failing to play, and the health view shows a recent check-in.
- [ ] If stable, uninstall Metrolist (`com.metrolist.music`). This deletes
      anything stored only inside Metrolist, such as downloads; their YouTube
      Music library is in their Google account and is not affected.
- [ ] If not stable, keep Metrolist, note why, and set another reminder.

## Rollout log

One row per phone. Keep a tally of Google's 20-device cap here: the Worker
hub cannot see Google's authorizations, and the health view built in Phase 3
shows name, version, last seen and local time, not authorization slots.

| # | Person | Model / brand | arm64 | Dev. verification slot (of 20) | Paired and approved | Role | Car | Uninstall Metrolist on |
|---|--------|---------------|-------|--------------------------------|---------------------|------|-----|------------------------|
| 1 |        |               |       |                                |                     |      |     |                        |
| 2 |        |               |       |                                |                     |      |     |                        |
| 3 |        |               |       |                                |                     |      |     |                        |

## Known limits

- **20 owner actions a minute.** The hub caps owner (admin) requests at 20 per
  minute across the app. If Manage Family is refreshed too often it can say to
  try again in a minute. Wait a minute and stop tapping refresh.
- **The free Cloudflare plan has a daily request quota.** It is a residual risk
  if a phone misbehaves and hammers the hub. The fix is to revoke that phone in
  Manage Family.
- **One phone per pairing link**, valid for 15 minutes.
- **Google's cap is 20 authorized devices.** How to free a
  slot when a phone is replaced isn't covered in `developer-verification.md`;
  check the Android Developer Console.

## If something goes wrong

- **The phone says the link is invalid or used:** it expired or was already
  claimed. Create a new pairing link.
- **The phone keeps waiting after you approved it:** give it a few seconds
  (it checks about every 5), then check that its row in Manage Family shows
  approved and not revoked. If it still doesn't move, create a fresh pairing
  link and start section 5 again.
- **A phone behaves badly or is lost:** revoke it in Manage Family.
- **Saraswati Updates won't install on a Samsung:** Auto Blocker is probably
  still on. Turn it off, install, turn it back on.
