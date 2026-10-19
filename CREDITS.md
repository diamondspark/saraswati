# Credits

Saraswati is a fork of Metrolist and builds on the work of the people and projects below. The list is carried over from Metrolist's own README (see [docs/UPSTREAM_README.md](docs/UPSTREAM_README.md)). Upstream copyright and license headers are kept in the source.

## Base project

| Project | Authors | License |
|---|---|---|
| [Metrolist](https://github.com/MetrolistGroup/Metrolist) | MetrolistGroup, created by Mo Agamy ([mostafaalagamy](https://github.com/mostafaalagamy)) with the [Metrolist contributors](https://github.com/MetrolistGroup/Metrolist/graphs/contributors) | GPL-3.0 |

Saraswati is based on upstream commit `76fa5a78b5110674442884b6f21c0cef51cdb782` (2026-10-08).

## Main inspirations

Metrolist names these two projects as its main inspirations, and Saraswati inherits code and ideas from them through Metrolist.

| Project | Authors |
|---|---|
| [InnerTune](https://github.com/z-huang/InnerTune) | Zion Huang ([z-huang](https://github.com/z-huang)), [Malopieds](https://github.com/Malopieds) |
| [OuterTune](https://github.com/OuterTune/OuterTune) | Davide Garberi ([DD3Boh](https://github.com/DD3Boh)), Michael Zh ([mikooomich](https://github.com/mikooomich)) |

## Libraries and integrations

| Project | Contribution |
|---|---|
| [Better Lyrics](https://better-lyrics.boidu.dev) | Time-synced lyrics with word-by-word highlighting and YouTube Music integration |
| [metroserver](https://github.com/MetrolistGroup/metroserver) | Listen-together real-time backend |
| [MusicRecognizer](https://github.com/aleksey-saenko/MusicRecognizer) (now Audile) | Music recognition feature and Shazam API integration |
| [zemer-cipher](https://github.com/ZemerTeam/zemer-cipher) | YouTube cipher deobfuscation and PoToken generation |
| [Blacksmith](https://www.blacksmith.sh) | GitHub Actions runners that powered upstream's CI |
| [Weblate](https://hosted.weblate.org/projects/Metrolist/) | Hosting for Metrolist's community translations |

Saraswati removes or replaces some of these features. They stay credited because their code is part of the base commit and the git history.

## Fonts

<!-- SARASWATI: Task 9 — the typeface of Saral, the grandparents' screen. -->

| Font | Authors | License | Where |
|---|---|---|---|
| [Noto Sans Devanagari](https://github.com/notofonts/devanagari) (variable, `wdth` and `wght`; version 2.007) | The Noto Project Authors | [SIL Open Font License 1.1](app/third_party/noto-sans-devanagari/OFL.txt) | `app/src/main/res/font/noto_sans_devanagari.ttf`, taken unmodified from [google/fonts](https://github.com/google/fonts/tree/2bc5d431b584d89e646214bf6cfa75494e76ceea/ofl/notosansdevanagari) at commit `2bc5d431b584d89e646214bf6cfa75494e76ceea` (sha256 `14ec4af41f27482216d1c2229f417ff9b1425e1babb014e57d1d40d03229853e`) |

Thank you to the whole open-source community, for every library, tool and API this project uses.
