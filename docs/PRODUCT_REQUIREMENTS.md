# Product requirements

**Status:** draft for research/MVP planning  
**Platforms:** Android phones; BCEN and BCJP  
**Primary implementation:** Kotlin  
**UI direction:** glassmorphism with strong text contrast  

## 1. Product statement

A local-first Android companion for The Battle Cats that helps a player plan capsule rolls, inspect the selected stage without leaving the game, and browse accurate region-specific reference/event information.

## 2. Users and jobs to be done

- **Seed tracker user:** “Given my seed and the banner I am rolling on, show the predicted capsule results and current track; let me record what I actually rolled and verify that future predictions remain aligned.”
- **Stage lookup user:** “While I am on a stage-selection screen in the game, let me tap a floating bubble and see the enemies/stage information for the stage I selected, without switching to a browser and typing its name.”
- **Reference user:** “Let me browse current, clearly sourced information for the English or Japanese game version.”
- **Optional schedule user:** “Show what special or recurring event is happening now and what is next.” This is a later module unless promoted after source research.

## 3. Scope and priorities

### P0 — Seed tracking (local)

- Create one or more local profiles with a seed, region, and optional notes.
- Choose the exact gacha/banner event used for the observed rolls; show relevant event identity/date so similarly named or rerun banners are not confused.
- Display predicted results with track/position labels, rarity, unit name, and upcoming track transitions.
- Record actual outcomes, allow correcting/deleting a mistaken entry, and recalculate the current position without losing the profile’s starting seed.
- Explain uncertain/conditional transitions (e.g., duplicate Rare replacement and guaranteed draws) instead of silently advancing incorrectly.
- Persist profiles and roll history in Room; provide export/backup as a later decision, with no cloud sync assumed.

### P0 — Stage info from game overlay

- Provide a movable floating bubble only after the user explicitly enables the system overlay permission.
- The user initiates capture by tapping the bubble and selecting **Stage info**. This is a one-shot capture, not continuous monitoring.
- Request Android screen-capture consent as required by the OS. Explain why capture is needed; process locally by default and discard the raw capture after extraction unless the user explicitly saves a diagnostic sample.
- Recognize the stage-selection UI, detect which stage is actively selected, read its label, and match it to the correct region/language record.
- Show stage name, region, enemy lineup, and available stage details in a compact overlay/card. Keep enemy images/text legible; allow expand/zoom where detail cannot fit.
- Provide a clear **OK/close** action; closing returns the user to the game.
- If recognition is uncertain, show candidate choices/“couldn’t identify” instead of asserting a nearby stage. The user can correct the match; corrections can be stored locally as optional test feedback.

### P1 — Reference browser

- Browse/search stage and enemy reference data without needing a screenshot.
- Support English and Japanese names and aliases, with data region/version marked explicitly.
- Show source and last-checked/update date for every dataset/feed.
- Display stage and enemy images at native or sufficiently high resolution, with fit, zoom and pan. Do not downscale master assets solely to reduce APK size.
- Clearly distinguish verified data from stale, missing, inferred, or community-contributed data.

### P2 — Events and optional schedule

- Events UI can group Gacha, Special Events, and Recurring Events as in the supplied examples.
- Show “current” and “upcoming” only when a source provides a timestamp/schedule for the selected version/region. Show timezone and when the data was last refreshed.
- A static wiki list is reference material, not proof that an event is active now. Do not label stale/cached schedule data as live.
- The schedule module is optional and should be independently releasable from seed tracking and stage lookup.

### P1 — App updater

- **Manual:** a user can tap Check for update; app displays version/changelog, downloads the compatible signed release, verifies it, and opens the Android installer flow.
- **Automatic download:** optionally check/download over unmetered Wi‑Fi while device idle, then notify and request installation through the OS. Do not claim guaranteed immediate execution or silent installation.
- A true unattended install is out of scope unless the deployment environment is a managed/device-owner or privileged device and is designed for that explicitly.

## 4. Accuracy and acceptance criteria

### Seed tracker

- Build a small, pinned reference fixture from Godfat Battle Cats Rolls: record the exact seed, event identifier/date, initial position, expected first 10–20 unit names, and all observed track transitions.
- Compare local predictions and transitions against the reference for the same inputs. Tests must include ordinary draws, a guaranteed draw, duplicate-Rare transition behavior, and at least one boundary/edge case identified from the upstream help/code.
- The user-provided URL/seed is an optional acceptance fixture: `seed=1169179061`, `last=199`, `event=2026-10-23_946`. Treat this as a test input, not proof that all bundled event data is current. Validate predicted next 10–20 cats against the referenced tracker before signing off.
- Keep deterministic golden tests; a mismatch blocks release until explained and documented.

### Stage matching / OCR

- Create a labeled screenshot corpus covering BCEN and BCJP, supported resolutions/scales, active-stage highlight states, long/short names, and confusing nearby labels.
- Correct-stage precision is the primary metric. A wrong stage shown as confirmed is worse than a failed lookup.
- Acceptance target for **confirmed** automatic results: at least 99% correct stage match on the held-out corpus; uncertain cases must fall back to candidate confirmation instead of counting as auto-confirmed. Report sample size and per-region metrics, not only an aggregate score.
- Never select “nearest OCR text” alone. A selected-stage visual cue and UI layout context must be part of the decision. OCR text, candidate ranking, and confidence should be logged locally only when a test/debug mode is enabled.
- Include tests where adjacent stages have similar names, OCR confuses characters, the wrong tile is highlighted, no stage tile is selected, and UI layout is shifted/cropped.

### Data and images

- Imported datasets validate schema, region, locale, version, source URL, fetched-at date, content hash, and required fields before becoming active.
- Keep previous known-good datasets so a failed or malformed update can be rolled back.
- Stage-info result images must be sharp at normal phone viewing and zoom; test on actual resized captures and on-device display, not just source files.

### Quality

- CI should run compile, unit/instrumented tests as appropriate, Android lint, formatting, and dependency/license checks with **zero warnings** as the release target.
- Record toolchain versions and warning baselines. No global warning suppression or “warnings as errors” workaround that hides broken checks. If upstream toolchain emits an unavoidable warning, record exact warning, source, impact, and owner.

## 5. Non-goals for initial release

- Editing Battle Cats save files, automating game actions, or interacting with the game server.
- Reading credentials or reverse engineering a player account.
- Continuous screen recording or uploading screenshots/seeds to a server.
- Guaranteeing an unattended APK installation on ordinary consumer Android devices.
- Maintaining a complete live event schedule before a trustworthy, region-specific source and update cadence are established.

## 6. Open questions to resolve during research

- Minimum Android version and target SDK policy.
- Whether app is distributed via GitHub Releases only, Play, or both (distribution channel changes updater requirements and policy constraints).
- Which authoritative/community data sources and licenses permit the desired stage/enemy data and artwork reuse.
- Whether region/version datasets should be app-bundled, downloaded as signed data packs, or both.
- How seed tracker handles banner schedule variants, event IDs and localized unit data after upstream changes.
- Whether user wants export/import of local seed profiles and history.
