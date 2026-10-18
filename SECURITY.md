# Security policy

Saraswati is a private app for one family. The source is public because the GPL-3.0 license requires it, but the app is not a general release, and no support is offered to anyone outside the family.

## Reporting a vulnerability

Report it privately with GitHub's private vulnerability reporting:

1. Open the [Security tab](https://github.com/diamondspark/saraswati/security) of this repository.
2. Choose **Report a vulnerability** (direct link: <https://github.com/diamondspark/saraswati/security/advisories/new>).
3. Describe what you found, how to reproduce it and which version you tested.

Do not open a public issue, pull request or discussion for a security problem. Do not post cookies, tokens, keys or screenshots that contain them anywhere public. If you exposed a credential by accident, rotate it first, then report.

## Scope

In scope:

- The two APKs: the Saraswati app (`dev.diamondspark.saraswati`) and the Saraswati Updates app (`dev.diamondspark.saraswati.updater`).
- The release pipeline that builds, signs and publishes those APKs: the GitHub Actions workflows, the signing setup and the update manifest.
- Code, configuration and docs in this repository.

Out of scope:

- Problems in upstream Metrolist that Saraswati's code does not touch. Report those to [MetrolistGroup/Metrolist](https://github.com/MetrolistGroup/Metrolist).
- YouTube, Google, GitHub, Cloudflare or any other third-party service.
- Builds that were not published from this repository or are not signed with the family's release key.
- Attacks that need a rooted, already compromised or unlocked phone in the attacker's hands.

## What to expect

No support is promised. This is a personal project: reports are read when time allows, and there is no response-time guarantee, no fix commitment and no bug bounty. Good-faith reports are welcome.
