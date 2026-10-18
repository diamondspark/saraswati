# Android developer verification (limited distribution)

Google's developer-verification program requires every app's package name to be
registered to an Android Developer Console account before it can be installed
on certified Android devices in enforced regions. This covers sideloaded apps
like Saraswati and Saraswati Updates, not just Play Store apps.

## What the official pages say

Source: [Developer verification guide: limited distribution](https://developer.android.com/developer-verification/guides/limited-distribution)
and [Developer verification FAQ](https://developer.android.com/developer-verification/guides/faq)
(read 2026-10-18; quoting only what was read).

**Account type — Limited distribution:**
- Built for "developers like students and hobbyists who don't want to provide a
  government ID."
- Free: "We are waiving the fee for developers who qualify for a Limited
  Distribution account" (the $25 fee applies to Full Distribution accounts
  only).
- No government ID required: "no additional verification requirements for
  limited distribution accounts," and "you don't need to provide a government
  ID."
- Requires a Google Account with 2-Step Verification on, and a linked Google
  payments profile (for legal name and address) plus a contact email.
- Migration is one-way: limited-distribution accounts can later migrate to a
  full account, but not back.

**What gets registered:**
- Package names are registered to the account ("register your package names").
- The guide does not describe a separate signing-key proof-of-ownership step
  for apps outside Play App Signing. For a package name already claimed by
  someone else, the console may say "your key is not eligible to claim it
  outright," and registration then needs "an additional review" — not expected
  to apply here since these are new, never-before-registered package names.

**Device cap and authorization:**
- Apps can be shared with "up to 20 devices that end-users have explicitly
  authorized" — exactly our family's device count ceiling.
- Authorization is "a secure handshake process involving QR codes or links,
  user consent on the device, and registration using the Android Developer
  Console" — i.e. each new family phone gets authorized individually via a
  QR code or link, with the device owner confirming consent on-device.

**Enforcement dates:**
- "Android developer verification protections take effect in Brazil,
  Indonesia, Singapore and Thailand on September 30" (year not stated on the
  limited-distribution guide page itself).
- The FAQ states this explicitly with a year: "If you don't verify your
  identity and register your apps by September 30, 2026, your apps cannot be
  installed from participating stores in select regions on certified Android
  devices." This only applies to participating stores in those regions, not
  to sideloaded installs there yet.
- Global rollout: "the global rollout begins in 2027" — no specific date
  given.
- Applies to "all certified Android devices running Android 7 or higher."
- None of our family's phones are physically located in Brazil, Indonesia,
  Singapore or Thailand, so the September 30, 2026 deadline does not apply to
  us directly — but registering now means Saraswati keeps installing
  everywhere once the global 2027 rollout lands, with no late scramble.

## Registration sheet

| Field | Value |
|---|---|
| Package 1 (app) | `dev.diamondspark.saraswati` |
| Package 2 (updater) | `dev.diamondspark.saraswati.updater` |
| Release signing certificate SHA-256 | `bcf9b96c06911f4851f6998358de16806aa1fd33fc80015206e276c640d75bac` (from `security/release-cert.sha256`) |
| Account type | Limited distribution (free, no government ID) |
| Device cap | 20 |

## Registration status

**Not yet registered.** This is a human checkpoint: the account owner signs in
to the [Android Developer Console](https://play.google.com/console/) with the
Google account that has 2-Step Verification and a linked payments profile, and
registers both package names above under a Limited Distribution account. The
agent does not perform this sign-in or registration.

Record here once done: registration date, Developer Console account
identifier (not the Google account password or any secret), and confirmation
both package names show as registered.

## Device authorization procedure (for Phase 3 rollout)

Once the packages are registered, each family phone needs its own device
authorization before Saraswati can be installed there under enforcement:

1. From the Android Developer Console, generate the device-authorization QR
   code or link for the phone being set up.
2. On that phone, open the link (or scan the QR code) and confirm the
   on-device consent prompt — this is the "secure handshake" the guide
   describes.
3. The phone is now one of the (up to 20) authorized devices for both
   package names.
4. Repeat per phone during the Phase 3 remote-setup flow (see "Remote setup"
   in the design spec) — this step slots in alongside the existing
   "Unknown sources"/Auto Blocker/battery steps already in that flow, before
   the family member installs Saraswati Updates.
5. Track how many of the 20 device slots are used in the family hub's health
   view (Phase 3), so you know how much headroom is left before a phone
   upgrade or replacement needs a slot freed up.
