# Saraswati rollout checklist

For the owner. Work through "Before the first phone" once, then copy the
"Per-phone checklist" for each family member and tick it off during the video
call. Nothing here is done by an agent: every family phone is set up by a
person, with the family member's own hands on their own sign-in.

Related docs:

- Setup guide the family member follows (Pixel, Samsung, Xiaomi pages):
  <https://diamondspark.github.io/saraswati/setup-guide/> (goes live with the
  next release; see "The setup guide" below and check that it loads before
  the first phone)
- Developer verification, in full: [`developer-verification.md`](developer-verification.md)
- How a build gets released and promoted: [`release-runbook.md`](release-runbook.md)
- Why each hygiene item exists: "Phone hygiene" in
  [the design](superpowers/specs/2026-10-17-saraswati-design.md)

## Before the first phone

Owner only. Do this once.

### The family hub (Cloudflare Worker)

- [ ] Your Cloudflare account has 2-Step Verification on, and `wrangler login`
      is done on your own machine.
- [ ] Your checkout includes commit `3a56ab94` (or anything after it):
      `git merge-base --is-ancestor 3a56ab94 HEAD && echo ok` prints `ok`.
      That commit is the newest schema change: the hub now stores only hashes
      of its tokens, and the Worker carries a small migration runner that
      upgrades its own database. So redeploying this commit over a hub that is
      already live migrates it in place; there is no "deploy onto a fresh hub"
      rule any more. One thing the migration cannot save: a phone caught
      halfway through pairing at the moment you redeploy has to pair again
      (revoke its entry first).
- [ ] Install the Worker's pinned tools, once: `cd worker && npm ci`. Every
      `npx wrangler ...` command in this checklist runs in `worker/` after
      this step.
- [ ] Deploy: `cd worker && npx wrangler deploy`. If a hub from an older commit
      is already live, redeploy the Worker so `/pair` and `assetlinks.json` are
      live (needed for the pairing link to open the app). Do this before the
      first phone installs Saraswati: Android reads `assetlinks.json` when the
      app is installed, so a phone that installed earlier may open the link in
      the browser instead (the setup guide tells them to paste it into the
      app). Use the app build from this same branch: the approve call no longer
      returns a session token, so an older build reports an approval as failed
      even though the hub approved the phone.
- [ ] Compare the URL `wrangler deploy` printed with the app's `HUB_BASE_URL`
      (`https://saraswati-sangat.pandey-mohitk.workers.dev`). If they differ,
      **STOP**: do not go on to the admin key until the two match, because the
      app would send the key to the wrong address.
- [ ] Check it is up: `curl https://saraswati-sangat.pandey-mohitk.workers.dev/ping`
      returns `ok` (this is the address the app is built to use,
      `HUB_BASE_URL`).
- [ ] Check the two app-link pages. They need no token and no key:
      `curl -s https://saraswati-sangat.pandey-mohitk.workers.dev/.well-known/assetlinks.json`
      prints a statement for `dev.diamondspark.saraswati` whose fingerprint is
      your release certificate in colon-separated capitals (the same value as
      `security/release-cert.sha256`), and
      `curl -s -o /dev/null -w '%{http_code}\n' https://saraswati-sangat.pandey-mohitk.workers.dev/pair`
      prints `200`. (Use a plain GET here: `curl -I` sends HEAD, and these two
      pages answer only GET.)

### The setup guide

The guide is plain HTML in `docs/setup-guide/`. A release run copies it to the
`gh-pages` branch in the same push as the channel manifest, so it needs a
push and a CI run that only you can do. Nothing publishes it until then.

- [ ] Merge this work to `main`, then run the next release
      ("How to release" in the runbook). Its publish job pushes
      `setup-guide/` to `gh-pages` next to `beta.json` or `stable.json`.
- [ ] When that run is green, check every page answers `200` (GitHub Pages can
      take a minute or two after the push):
      `for p in "" pixel.html samsung.html xiaomi.html style.css; do curl -s -o /dev/null -w "%{http_code} $p\n" "https://diamondspark.github.io/saraswati/setup-guide/$p"; done`
      prints `200` five times. If one is not `200`, look at the release run's
      "Publish the manifest and the setup guide to gh-pages" step.
- [ ] Open <https://diamondspark.github.io/saraswati/setup-guide/> on your own
      phone and read the page for your brand once, so you know what the family
      member will see.

### The build

- [ ] A Saraswati release that includes the Phase 3 app (Manage Family and
      pairing) is published on the channel the family phones follow. The design
      puts the family on Stable, which only gets builds promoted from Beta after
      48 hours. See "How to release" and "How to promote" in the runbook.
- [ ] Your own phone has that build and Manage Family opens.

### The admin key

Do all three steps in one sitting, at your own desk, so the key is never left
lying around between them. This comes after the build because the last step
needs Manage Family on your phone. A hub with no admin key has no owner: its
admin routes answer 401 to everyone. If you set a key earlier (for example
while testing) and did not handle it this carefully, replace it now by doing
the steps again.

- [ ] Make the key, in your own terminal only: `openssl rand -hex 32`. Copy the
      output. Do not paste it into chat, a file, a note-to-self message or a
      screenshot. The hub ignores any key shorter than 32 characters; this one
      is 64.
- [ ] Give it to the hub: `cd worker && npx wrangler secret put ADMIN_KEY`, and
      paste the value at the prompt.
- [ ] Paste the same value once into the Manage Family setup screen on your own
      phone. The app stores it encrypted; only your phone ever holds it. How you
      carry it from the terminal to the phone is not specified by the design,
      so use a channel you already trust for passwords.
- [ ] If you ever passed the key to a command or into a file, clear it from the
      shell history (`history -d`, or delete the line in `~/.bash_history`) and
      clear the clipboard history, or rotate the key.
- [ ] To replace the key later, repeat the steps above with a new value.

If your phone is lost or stolen, run `npx wrangler secret put ADMIN_KEY` with a
NEW value at once. The old key stops working immediately. Then enter the new
key on your new phone. (Run it from `worker/`, after the one-time `npm ci`
above.)

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
- [ ] If Play Protect asks to scan the app, they let it. They never switch it
      off.

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
earlier in the call and it lapses, make another. Before you start, check
Manage Family and revoke any entry you do not recognise, so you begin with no
pending entries.

- [ ] You: in Manage Family, tap create pairing, then send the link to that
      person only, as a private message.
- [ ] They: open the link on the phone, enter a name you will recognise, pick
      who the phone is for (see section 6), and tap Send request. The phone
      claims the link and then waits for your approval.
- [ ] You: watch Manage Family while they do this. Approve only when exactly
      one new pending entry appeared while you watched. If none appeared, or
      more than one, do not approve: revoke every entry you do not recognise
      and make a new link.
- [ ] Both: compare the 4-character code shown on both phones. It is the last
      4 characters of the device id; the app shows it. If the codes differ, do
      not approve: revoke that entry.
- [ ] You: approve it. Pending requests expire after 1 hour; after that the hub
      refuses to approve them ("expired"). Revoke an expired entry and make a
      new link.
- [ ] They: keep the Join screen open until it says approved, and do nothing
      more. The phone collects its own sign-in automatically; it checks every
      6 seconds, and only while that screen is open.

If the link has expired, create a new one. If the phone's first try says
"already used", someone else used the link: revoke every pending entry you do
not recognise before making a new link. Before you start section 5 again for
the same person, revoke their previous entry. If the phone just keeps waiting,
see "If something goes wrong".

### 6. Role: adult or grandparent

- [ ] They pick who the phone is for on the Join screen, before they send the
      request. The pick travels with the request, and you see it next to the
      pending entry when you approve. The exact screen wording is not final in
      the build yet, so confirm it against the app.
- [ ] They sign in to YouTube inside Saraswati with their own hands. Never ask
      for or send a password.
- [ ] **Adult:** the normal app. Nothing more to do.
- [ ] **Grandparent (Saral):** the phone turns into the station tiles. The
      favourite-singers step is not built yet (deferred in Task 9: the build
      has no taste-profile call), so there is nothing to pick here.
- [ ] Grandparent: the station tiles you set in Manage Family show up and look
      right.
- [ ] Grandparent: they start a station and pause it with no help. This is the
      design's own test for Saral.
- [ ] Grandparent: do every other in-app step first, signing in to YouTube
      included. Saral replaces the whole app with no Settings and no other
      tabs, and nothing on that phone switches it off. The easy order, and the
      one the setup guide tells them: pair the phone as Adult, they sign in to
      YouTube, then you switch the phone to grandparent in Manage Family. The
      phone follows the next time Saraswati is opened. To bring the full app
      back later, set the phone to Adult there. If a phone that was paired
      straight as grandparent is not signed in, Saral shows one large sign-in
      tile that opens the Google sign-in and returns to Saral; treat that as a
      fallback, not the plan.

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
- **The free Cloudflare plan has a daily request quota, and anyone can burn
  it.** It is not only a misbehaving phone: anyone who knows the address can
  send requests, and revoking a phone does not stop them. Unauthenticated and
  unknown requests are now answered by the front Worker, which still costs
  Worker requests but no longer costs Durable Object time. If the quota is gone
  the hub is simply unavailable until it resets; nothing is exposed.
- **One phone per pairing link**, valid for 15 minutes. A phone that has
  claimed a link waits at most 1 hour for approval; after that its entry can
  only be revoked.
- **Request bodies over 8 KiB are refused** (413). Nothing the app sends comes
  close.
- **Google's cap is 20 authorized devices.** How to free a
  slot when a phone is replaced isn't covered in `developer-verification.md`;
  check the Android Developer Console.

## If something goes wrong

- **The phone says the link is invalid:** it expired. Create a new pairing
  link.
- **The phone's first try says the link was already used:** someone else used
  it. Revoke every pending entry you do not recognise in Manage Family, then
  create a new link. Before you start section 5 again, revoke the previous
  entry for that person.
- **The phone keeps waiting after you approved it:** make sure the Join screen
  is still open, and give it a few seconds (it checks every 6), then check that
  its row in Manage Family shows approved and not revoked. If it still doesn't
  move, revoke its entry, create a fresh pairing link and start section 5
  again. A request that waited more than 1 hour cannot be approved at all; the
  same fix applies.
- **A phone behaves badly or is lost:** revoke it in Manage Family.
- **Your own phone (the one with the admin key) is lost or stolen:** if your
  phone is lost or stolen, run `npx wrangler secret put ADMIN_KEY` with a NEW
  value at once. The old key stops working immediately. Then enter the new key
  on your new phone. (Run it from `worker/`, after `npm ci`.)
- **Saraswati Updates won't install on a Samsung:** Auto Blocker is probably
  still on. Turn it off, install, turn it back on.
