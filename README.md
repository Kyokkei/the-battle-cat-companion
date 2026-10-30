# Battle Cats Companion (working title)

An Android companion app for **The Battle Cats (BCEN + BCJP)**. The initial product is centered on three capabilities: a local seed tracker, a user-invoked stage-info overlay, and a reference/events browser. A schedule feature is an optional future module, not part of the initial release commitment.

> **Project status:** The seed tracker is complete and parity-verified against the live tracker at `bc.godfat.org`: 30 rows for two seeds, duplicate-rare rerolls, reroll route edges, and the guaranteed column for an 11-roll banner all match. Finding a seed runs **on the phone**: the same exhaustive 2^32 sweep as the web tracker, ~3–5 s on an 8-core device, with no queue and no upload. Stage OCR now runs locally through ONNX Runtime CPU: a PP-OCRv5 DB text detector finds the stage cards on the frame, PP-OCRv6 small recognizes English, and Manga OCR handles Japanese; the models were copied from the user's Fukidashi installation and are listed in [third-party notices](THIRD_PARTY_NOTICES.md). Nothing is cropped with fixed screen fractions, so the scan does not depend on the phone's resolution or aspect ratio. The helper asks Android for screen sharing once per active session, then captures one still per paw tap and rejects a replayed older frame; the centered card suggestion still requires confirmation, and a stage name shared by several chapters opens a list of the matching records instead of picking one. Drag the paw to the bottom target to stop the helper and end screen sharing. The stage data parser loads 6,328 records and 746 enemy icons; 5,871 records carry an English name (the remainder are untranslated collaboration/event rows that stay JP-only). Validate capture/OCR on-device before treating it as release-ready. The separate NekoOptimizer gacha catalog provenance remains incomplete.

## Product goals

1. **Seed tracking — local-first.** Enter a seed, or find it: type the cats you already rolled and the app searches all 4.29 billion seeds on-device. Then browse the A/B track, tap the cat you actually got to advance, and watch duplicate rerolls and guaranteed ubers resolve. Seeds and positions stay in Room on the device. No game-account credentials, no remote seed storage, no queue.
2. **Stage info from the game — deliberate capture.** Enable the floating helper once and approve Android's screen-sharing prompt once per session. Tapping the paw captures one still frame for local OCR; it does not OCR continuously or save frames. Choose the matching stage candidate to view its enemy lineup and stage data. The user can dismiss it immediately. Incorrect nearby-stage matches are a release-blocking defect: do not silently present a low-confidence guess as confirmed.
3. **Reference and event browser.** Browse accurate stage, enemy, banner, and event references for BCEN and BCJP. Display region, source, version/update date, and confidence/freshness where possible. Images must remain clear and legible when the user opens stage info from the bubble; image quality and useful zoom take priority over APK size.
4. **Optional future schedule module.** If added, show current/upcoming special and recurring events for the selected region. Keep it separate from the stage-reference and seed-tracking milestones.

## UX direction

- Visual direction: **glassmorphism**, while preserving contrast and readability over varied game screens.
- Android-native Kotlin app; Rust or C/C++ is optional only if profiling shows a meaningful benefit. Prefer Kotlin for the first seed-engine implementation to keep debugging and iteration straightforward.
- The helper uses one Android screen-share approval per active session. Enable the helper before opening Battle Cats; then each paw tap samples one still frame, runs OCR, and presents a likely centered-card suggestion plus alternatives. The paw never triggers a new share prompt per scan. Drag it to the bottom stop target to end the helper/session.
- Target both **BCEN and BCJP**. Region/language must be explicit throughout data lookup; Japanese stage names and localized aliases must not be conflated with English entries.

## Quality bar and engineering constraints

- **Zero warnings** is the project goal: compile, lint, tests, and dependency checks should be clean before a release. Track baseline and toolchain versions; do not hide warnings with broad suppressions. A third-party or toolchain warning that cannot be fixed must be documented, scoped, and treated as an exception—not silently called zero-warning.
- Design for scalability: modular boundaries, stable domain models, versioned data imports, migrations, tests, and clear source attribution. Avoid putting stage data, OCR heuristics, UI, and roll math in one module/class.
- Use **Room** for app-owned local structured data: seed profiles, roll history, region preferences, cached/reference metadata, and update state. Keep large images/data files in appropriate app-private files rather than bloating relational rows; store a key/path/hash in Room. Define retention and migration rules.
- Accuracy beats a small APK. Bundle/download high-resolution stage images/data only after licensing, source stability, and update strategy are checked. Preserve original asset resolution; provide fit-to-screen and zoom/pan, and avoid lossy recompression that blurs text or enemy art.
- Data should be attributable, region-aware, timestamped, and refreshable. Third-party wiki/site content and artwork may have different licenses; review reuse rights before copying assets or scraping.
- User data remains local unless a later, explicit feature requires otherwise. No account login or telemetry is assumed in this scope.

## Updating the app

Offer **Check for update** in the app and an optional automatic update-check/download path backed by GitHub Releases. “Silent update” is a desired user experience, but Android generally does not let an ordinary app silently install its own APK. The app can download and validate a release when constraints are satisfied, then invoke Android’s package installer; user confirmation / enabling “Install unknown apps” may be required. A genuinely unattended installation requires privileged/device-owner or managed-device authority and is not assumed.

The desired automatic path is: check on **Wi‑Fi/unmetered network** while the device is **idle/screen-off**, download a compatible signed release APK, verify integrity/signing/version, notify the user, and hand off installation to Android. WorkManager can enforce unmetered network and idle constraints; it does not guarantee “screen is off” as a direct constraint or guarantee an exact run time. The user-triggered path should be available regardless of background constraints. Details and open decisions are in the research note.

## Milestones (proposed)

1. **Research/spec:** verify seed behavior and source license; define data sources/rights and stage-match acceptance tests; document Android permissions and updater constraints.
2. **Seed tracker MVP:** offline seed entry, banner selection, predictions, A/B track and switch handling, roll history, Room persistence, reference fixtures and regression tests against Godfat’s tracker.
3. **Reference data MVP:** versioned BCEN/BCJP stages/enemies/banners/events datasets, source metadata, image viewing/zoom, import validation, freshness indicators.
4. **Stage-info overlay MVP:** floating bubble, user-approved capture, OCR, region-aware candidate matching, confidence/review UI, enemy/stage result card, dismiss action. Measure precision/recall on a labeled screenshot set before calling it accurate.
5. **Updater:** manual check first, then constrained background check/download; integrity checks; installer handoff; rollback/failed-install UX.
6. **Optional schedule module:** only after schedule source and update ownership are established.

## Documentation

- [Research, decisions, and technical notes](docs/RESEARCH_AND_TECHNICAL_NOTES.md)
- [Product requirements](docs/PRODUCT_REQUIREMENTS.md)
- [Architecture and data plan](docs/ARCHITECTURE.md)
- [Build and test commands](docs/BUILDING.md)
