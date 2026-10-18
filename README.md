# Saraswati

Saraswati is a private, family-only music app for Android. It is a hardened fork of [Metrolist](https://github.com/MetrolistGroup/Metrolist), an open-source YouTube Music client, and it is licensed under GPL-3.0.

It keeps YouTube Music's catalog and recommendations. Each person signs in with their own YouTube Music account, so the recommendations are theirs. There are no ads and no tracking SDKs.

The source is public because the license requires it. The app is built for one family: it is not on any app store, and no support is offered to anyone outside it (see [SECURITY.md](SECURITY.md)).

## Status

Work in progress. `main` is upstream Metrolist pinned at commit `76fa5a7` (2026-10-08), with upstream's CI removed and these project docs added. That commit is used instead of the `v13.7.0` tag because it carries two later playback fixes (InnerTubeX `v0.8.4`).

Phase 1 hardens the fork: fewer permissions, HTTPS only, a smaller exported surface and the Saraswati application id (`dev.diamondspark.saraswati`). Phase 2 adds signed releases, the updater app and the upstream sync pipeline.

## Build

Build and test instructions are in [docs/build-baseline.md](docs/build-baseline.md), which Phase 1 adds. You need JDK 21 and the Android SDK.

## Design and plans

- [Design](docs/superpowers/specs/2026-10-17-saraswati-design.md)
- [Phase 1: fork and harden](docs/superpowers/plans/2026-10-17-saraswati-phase1-fork-harden.md)
- [Phase 2: updates and pipeline](docs/superpowers/plans/2026-10-17-saraswati-phase2-updates-pipeline.md)

## Branches

- `main`: the Saraswati line.
- `upstream-main`: an untouched copy of the pinned upstream commit, kept so later upstream changes can be merged in.

## Upstream, credits and license

- The original Metrolist README is kept as [docs/UPSTREAM_README.md](docs/UPSTREAM_README.md). Its images and links are relative to the upstream repository root, so the images do not render there.
- [CREDITS.md](CREDITS.md) lists the projects and people this work builds on.
- Licensed under the GNU General Public License v3.0, see [LICENSE](LICENSE). Upstream copyright and license headers are kept in the source.

## Disclaimer

Saraswati is not affiliated with, funded by, authorized by or endorsed by YouTube, Google LLC, Metrolist Group LLC or the Metrolist maintainers. All trademarks belong to their respective owners.
