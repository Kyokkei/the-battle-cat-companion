# Third-party notices

This project's own source code is licensed **GPL-3.0-or-later** (see [LICENSE](LICENSE)). This file lists everything that is *not* ours, along with the terms it arrives under. Where a component's terms are unknown or unverified, that is stated rather than assumed.

## Battle Cats artwork and game data (PONOS Corporation)

**All cat artwork and all enemy artwork in this app belong to PONOS Corporation.** That covers the cat portraits shown in the stat card, the enemy icons shown in the stage card, the unit sprites, the unit and enemy names, the stat tables, and the stage data. *The Battle Cats* is a trademark of PONOS.

These assets are displayed locally as in-game reference, in the same spirit as a community wiki. They are **not** licensed under this project's GPL license, they are not relicensed by inclusion here, and nothing in this repository grants any right to reuse, redistribute, or sell them. The GPL applies to the source code only.

If PONOS asks for the artwork to be removed, removing it does not break the app: every lookup still resolves by ID, and missing sprites already fall back to showing the ID.

## Battle Cats Rolls

The seed-tracking mechanics were researched against [Battle Cats Rolls](https://gitlab.com/godfat/battle-cats-rolls), an upstream Ruby tracker distributed under the Apache License 2.0 according to its repository README. This project contains an independently written Kotlin implementation of selected documented mechanics and does not bundle upstream Ruby source files. It does bundle NekoOptimizer gacha/event datasets as described below; their redistribution rights remain unverified. The project is not affiliated with PONOS or the Battle Cats Rolls authors.

The independently authored Kotlin graph simulation follows the documented mechanics; no upstream Ruby files are copied into the app. The live Godfat HTML table is stored as a small test/reference fixture and is not used as a local prediction catalog.

## Battle Cats stage and enemy reference assets

The local stage catalog, localized text table, stage scheme and enemy icons under `app/src/main/assets/stages/` and `app/src/main/assets/enemies/` were supplied by the user as a Battle Cats Wiki/Miraheze reference snapshot. The user identifies the source text as CC BY-SA 4.0. The app preserves attribution to Battle Cats Wiki contributors and records the user's license assertion in `stages/source-manifest.json`; the original snapshot revision/date were not supplied. Stage catalog TSV/JSON hashes are computed by the local parser. Enemy sprite IDs that are absent from the local icon set remain visible by ID in results.

Wiki text is attributed under CC BY-SA 4.0; if modified copies of those text records are redistributed, preserve attribution and the same license for those derived records. The user requested the enemy sprites be used as fair-use/wiki-reference imagery; they are displayed locally as in-game references and are not separately fetched.

## ONNX Runtime and OCR model assets

The OCR adapters are based on the author's own [Fukidashi MCP Android probes](https://github.com/Kyokkei/fukidashi-mcp/tree/main/android) for PP-OCR CTC decoding and Manga OCR encoder/decoder caching. Fukidashi is a separate Apache-2.0 project by the same author (Kyokkei / Phan Chí Vỹ), so reusing it inside a GPL-3.0 application raises no licensing conflict — see [the included license](licenses/Fukidashi-ONNX-OCR-APACHE-2.0.txt). ONNX Runtime Android 1.31.1 is MIT-licensed; see [licenses/ONNX-RUNTIME-MIT.txt](licenses/ONNX-RUNTIME-MIT.txt). The model files themselves are third-party and keep their own Apache-2.0 terms regardless of who copied them into the bundle.

Seven model/vocabulary assets were copied from the user's local `E:/Fukidashi/models/ocr` folder into `app/src/main/assets/ocr/` (PP-OCRv5 mobile text detector shared by both locales, PP-OCRv6 small recognizer for English, and Japanese Manga OCR; 90,812,946 bytes total). Their exact source paths, file sizes, SHA-256 values, and stated Apache-2.0 model licenses are recorded in `app/src/main/assets/ocr/source-manifest.json`. The assets were found locally and were not downloaded for this change.

## Enemy stats table

`app/src/main/assets/neko/data/en/enemy_stats.json` (792 enemies, ~114 KB) is generated from the
[battlecatsinfo](https://github.com/battlecatsinfo/battlecatsinfo.github.io) data set —
`data/enemy.tsv`, `data/tenemy.tsv` and the trait/ability decoders in `data/units_scheme.json`.
That repository is MIT licensed, and it is the same source the bundled `stage.tsv`, `tstage.tsv` and
`stage_scheme.json` came from, so the stage and enemy tables share one provenance.

Two encodings had to be decoded to produce the table, and both are recorded in the JSON's own
`notes` field: the `trait` column is a bitmask resolved through `units_scheme.json`'s trait list, and
`attack_type & 2` distinguishes area attacks from single-target ones. Ability ids that could not be
named with confidence were dropped rather than guessed. Enemy names and stats are game data from
The Battle Cats; the table is a derived index, not a redistribution of artwork.

## NekoOptimizer data source candidate

The user selected attribution-based local use of NekoOptimizer's regional JSON catalogs. Repository: [IliyanGavrilov/NekoOptimizer](https://github.com/IliyanGavrilov/NekoOptimizer). The workspace now contains EN and JP `gacha_events.json`, `gacha_pools.json`, and `units.json` snapshots copied under `app/src/main/assets/neko/data/`; the parser computes SHA-256 hashes at runtime. The captured EN snapshot contains event `2026-10-23_946`; the JP snapshot does not contain that event ID, as expected for region-specific schedules. GitHub repository metadata reported no declared license, and the snapshot commit/fetch timestamp was not included with the supplied folder. Attribution is included as requested, but it is not a substitute for permission or a license; redistribution rights remain unverified. Do not publish an APK containing these copied NekoOptimizer data files unless the upstream rights are resolved.

If upstream code or data is later copied/ported/bundled, include the complete applicable license and notices, preserve copyright and attribution, record the exact upstream commit, and verify any additional asset/data licenses before distribution.
