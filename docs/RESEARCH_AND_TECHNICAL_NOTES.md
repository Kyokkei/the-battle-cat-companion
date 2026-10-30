# Research and technical notes

**Prepared:** 2026-10-28  
**Purpose:** Capture findings and caveats before implementation. Web pages and community databases can change; re-check sources before relying on them for shipped data.

## 1. Seed tracking: what the app is modeling

Battle Cats seed tracking predicts the Rare Capsule sequence from a player seed and selected gacha event/pool. Event identity, pool order, rate weights, user position, and current A/B lane all affect results. Godfat’s tracker is the behavior oracle; do not substitute generic RNG or unverified catalogs.

### User parity fixture

- [Godfat URL: seed 1169179061, last 199, event 2026-10-23_946](https://bc.godfat.org/?seed=1169179061&last=199&event=2026-10-23_946&count=220)
- Verified against the rendered table on **2026-10-30**: 200A–210A is Salon Cat, Fortune Teller Cat, Weightlifter Cat, Cat Toaster, Shitakiri Sparrow, Pirate Cat, Onmyoji Cat, Stilts Cat, Cat Gunslinger, Sniper the Recruit, Viking Cat; 200B–210B is the alternate lane. Both are recorded in [GodfatReferenceFixture.kt](../app/src/main/java/com/projectnyanko/companion/domain/seed/GodfatReferenceFixture.kt).
- **The first capture of this fixture was wrong.** It was read from an app build that never applied Godfat's initial seed advance, so it labelled track B's rows as track A. The engine was off by one PRNG step: a pasted seed produced a table shifted half a roll. Fixed in `SeedRollEngine`, and the fixture re-read from the live page.
- `GodfatTableParityTest` now pins the computed output against the live table for two seeds and 30 rows, including duplicate-rare rerolls (`8BR Mer-Cat`, `10AR Bishop Cat`), the reroll route edge (`8BR → 10A`), and the guaranteed column for an 11-roll banner.

### Algorithm notes

- Godfat’s canonical Ruby [`gacha.rb`](https://gitlab.com/godfat/battle-cats-rolls/-/blob/master/lib/battle-cats-rolls/gacha.rb) uses a 32-bit xorshift: `<<13`, `>>17`, `<<15`, modulo `2^32`; initialization advances once. Each lane row uses a rarity seed and a separate slot seed. In `roll_both!`, A consumes its rarity and slot seeds; B uses the next seed as rarity and current seed as its slot, preserving the upstream interleaved sequence.
- A row therefore consumes **two** PRNG steps, not three: `A = (t0, t1)` and `B = (t1, t2)`, where `t1` is both A's slot seed and B's rarity seed. That shared step is what "track B is half a roll ahead" means, and it is why a rerolled B cat lands two rows later on track A.
- Rarity score is `raritySeed % 10000`. Event weights split Rare, Super Rare, Uber Rare, and remainder Legend Rare. Pool order within each rarity matters for `slotSeed % slots.size`.
- Upstream `GachaPool` computes guaranteed draws as 11 when guaranteed is flagged, otherwise 15 for step-up, otherwise 0. Future Uber IDs are prepended to the Uber pool.
- Rare duplicate replacement creates a reroll node, removes the chosen slot before choosing among remaining same-rarity slots, advances a local reroll seed, and alters the `next` graph. `finish_rerolled_links` reattaches the appropriate next row/lane using `next_index` and `next_track`.
- Guaranteed nodes are overlays attached to each start node after following `guaranteed_rolls - 1` graph edges; the guaranteed result uses the referenced rarity seed to choose an Uber slot and connects to the next node on the alternate lane. A step-up is represented as 15 pulls (3+5+7) with the final guarantee.
- The bundled NekoOptimizer pools use the same slot order as Godfat's `gacha.yaml`; only the unit ids differ (NekoOptimizer ids are Godfat ids minus one). That equivalence is what makes cross-checking the two tables meaningful.

### Finding the seed (`SeedSeeker`)

- Port of [`Seeker-VampireFlower.c`](https://gitlab.com/godfat/battle-cats-rolls/-/blob/master/Seeker/Seeker-VampireFlower.c), the program behind `bc.godfat.org/seek`. The search space is all 4,294,967,296 unsigned 32-bit seeds; ten observed cats pin one down.
- Two strategies, chosen by estimated candidate count exactly like upstream: stride through the first cat's slot residues, or stride through the first cat's rarity band. Both are exhaustive.
- The first cat may itself be the result of a duplicate reroll, so after a miss the search retries with the `size - 1` divisor (`runType` 1 and 2, ordered by which side of the slot the shadow could be on).
- Every survivor is replayed exactly (`verify`) before being reported; the cheap filter only skips the rarity check for cats after the first, because matching the slot is strictly stronger.
- Deliberate deviations from the C original: duplicate rares are compared by unit id rather than slot index (equivalent unless a pool repeats a unit, and it is what the game does), the `size - 1` retry also applies to the rarity strategy, and the last-thread wraparound special case is replaced by disjoint half-open ranges so every seed is visited exactly once.
- Measured on a Z2352N (8 cores): **~3.1–4.5 s** for a full sweep that visits ~172 million candidates. The web tracker runs the same sweep but serialises requests, so a busy queue can mean hours.

### Data-source findings and limitations

- [Godfat Battle Cats Rolls source](https://gitlab.com/godfat/battle-cats-rolls) is the upstream tracker; its README/source reports Apache-2.0 for code. The checked-in EN event TSV listing ended at **2021-11-22**, so it cannot supply a current 2026 event; the live table is built from app data / `bc-en.yaml`.
- [NekoOptimizer](https://github.com/IliyanGavrilov/NekoOptimizer) supplies the bundled EN/JP JSON catalogues (units, gacha events, pools, series). GitHub repository metadata reports **no declared license**; the user approved attribution-based local use while rights remain unverified. See [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).
- The bundled snapshot resolves 2,161 EN events and 585 pools; 411 events carry an 11-roll guarantee and 86 a 15-roll step-up.
- Candidate general catalogs include [Battle Cats Data](https://www.battlecatsdata.com/), [Battle Cats DB](https://battlecats-db.com/), and [BCData](https://github.com/fieryhenry/BCData), but verify region/version, update state, license, and gacha pool completeness before use.

### Interaction model (corrected 2026-10-30)

The first build got this wrong: it tracked an absolute roll position and drew "last / next" markers on a table that kept counting from 1. The web tracker does something different, and the difference matters.

- **The table always restarts at 1A.** 1A is the *next* roll. The only history the tracker keeps is the cat you rolled just before (`last`), and it exists solely to decide whether row 1 is a duplicate and therefore rerolls.
- **Clicking a cat's name navigates.** It links to `?seed=<that cat's slot_seed>&last=<its id>&pos=1A`, i.e. "I rolled this", and the table re-bases so that cat is the previous roll. The slot seed works uniformly for normal, rerolled and guaranteed cats, because a guaranteed roll consumes exactly one PRNG call.
- **The paw opens the cat page.** `link_to_roll` wraps the name in `?seed=…&last=…` and adds a
  separate `<a href="/cats/<id>">🐾</a>` next to it, so the paw is a link, not part of the roll
  action. The app mirrors that: `TrackCell.unitId` feeds `CatStatsDialog`, which reads the bundled
  `stats.json` (level 30) joined with `units.json`.
- **Clicking anywhere else in a cell previews a path.** It links to `?pick=<marker>`, which walks the real `next` edges from your position to the target and marks every cat you would roll on the way. If the target sits on a branch this track never takes, nothing is marked.
- **A duplicate does not add a cat, it replaces one.** 9A Wushu then 10A Wushu means the 10th roll gives you the *rerolled* cat (Bishop), and because the reroll is a half roll you continue on the other lane: 11B Viking. The web tracker renders the reroll as a sub-row, `Bishop Cat → 11B`, with the arrow pointing at the lane you land on (`← 10A` when it goes the other way). The app mirrors that, and dims the duplicate it replaced.
- The `→ 11B` arrow is why a reroll on track B lands two rows later on track A: `next_index = index + ((track + steps) / 2) + 1`.

### Implementation boundaries

- Pure Kotlin engine types are separate from Compose and Room. `GachaPool` stores region, event, weights, ordered slots, guarantee/step-up count, future Ubers, and catalog version.
- `SeedRollEngine` builds base A/B rows, duplicate reroll graph links, guaranteed result nodes, and an optional synthetic "previous cat" node. That node must exist **before** row 1 is rolled, otherwise row 1's reroll node is created after `finishRerolledLinks` has run and never gets its `next` edge.
- `SeedTrackGraph` is the read-only view the UI queries: `nextNode()`, `cell(marker)`, `nextMarkerAfter(marker)`, `pathTo(target)`.
- A profile stores `seed` + `lastCatId`/`lastCatName`, never an absolute position. Advancing replaces both: `seed = cat.slotSeed`, `lastCatId = cat.id`.
- Engine tests cover seed stepping/retreat, slot order/rates, normal lane links, duplicate rerolls, guarantee/step-up nodes, route continuity, re-basing, path previews, and golden parity against the live table.


## 2. Screenshot stage lookup: OCR is only one part

The screenshot examples show a stage selection screen with multiple stage labels/cards, a map route, and a selected stage represented by a distinct emphasized/white card and selected-map marker. The correct target is the stage the player is standing at/has selected, not simply the closest OCR text. OCR can recognize several neighboring names and still select the wrong stage if selection semantics are ignored.

### Recommended one-shot flow

1. User taps the floating bubble and chooses Stage info.
2. Obtain a current screenshot only through the Android-approved screen-capture flow; do not run continuous capture.
3. Detect the game screen/layout and its scale/orientation. Find candidate stage cards and text in English/Japanese.
4. Determine the active selection using visual cues (selected card border/background/size/status, selected marker/current position, and layout relationships); OCR boxes alone are insufficient.
5. Normalize text conservatively and match only records for the detected region/language. Avoid fuzzy matching that maps similar adjacent names without a confirmation gate.
6. Require agreement among selected visual state, OCR, region-specific record, and geometry. Low-confidence cases show candidates or “couldn’t identify,” never a confident wrong stage.
7. Show the local stage/enemy record in an overlay card; discard the screenshot after processing by default. The user closes with OK.

### OCR/platform findings

- Stage OCR runs entirely locally through ONNX Runtime CPU: a PP-OCRv5 mobile DB text detector (shared by both locales), the PP-OCRv6 small recognizer for English, and Manga OCR for Japanese. Models were copied from `E:/Fukidashi/models/ocr` with sizes/checksums in `app/src/main/assets/ocr/source-manifest.json`; no network is used for inference or model loading.
- **Two independent defects caused the 2026-10-29 bug reports, and neither was the recognizer.**
  1. **The captured frame was not always the current screen.** `StageCaptureService` detached the mirroring surface between scans to save power and re-attached it on each tap. A re-attached mirroring surface can hand back the composition it was showing before it was detached, and because the game screen is static no second frame arrives to correct it. The helper therefore OCR'd a screen the player had already left — the `last-stage` report read `Slumbering Cavern` / `Cozy Corpses` while `Sodden Bones` was on screen — and produced nonsense such as `cYanmaGentaYellow` when the stale composition had the card row elsewhere. Which frame arrives varies per tap, which is exactly the "pure RNG" the report described. A frame is now accepted only if its `Image.getTimestamp()` is newer than every frame seen before the tap; a replayed composition is dropped and the attempt retried. Comparing timestamps needs no knowledge of the device clock base.
  2. **Fixed crop bands only fitted the screen they were measured on, and clipped the selected card.** `0.140–0.180` with five fixed centres worked on a plain 2400x1080 capture, but the highlighted card is drawn larger and lower than its neighbours: on the `first-stage` frame its glyphs run `y 154–207`, so the band cut the bottom third of the name. Two of the five slots also landed on empty map. That is why the neighbouring names read and the selected one did not.
- **Geometry is now detected, not assumed.** `PpOcrTextDetector` runs DB detection over the whole frame scaled to a 960 long side, so nothing depends on resolution or aspect ratio. `TextLineBuilder` rejoins boxes the detector splits (the blue position arrow cut `Hangover Couch` into `Hangover` + `C` + `c`), and the only remaining geometry is three scale-free filters: a label is `1.5–12%` of the frame height, at most `45%` of its width, and in the upper `45%` of the frame.
- **PP-OCRv6 small replaced PP-OCRv5 English for recognition.** The v5 English recognizer truncates long names: on the `Duel on the Prarie (Vanguard)` card it returned `uel on the` from a tight crop, and `or on tho` when padded, while v6 returns the full name. v6 is 21 MB instead of 7.5 MB; accuracy wins per the product rules. Padding a crop before recognition is actively harmful — it lowers the recognizer's time-step count — so crops are fed tight.
- Measured on the Z2352N (Android 13): detection ≈ 0.3 s, recognition ≈ 1.3 s for 11 lines, so one scan is ≈ 1.7 s plus the capture settle delay.
- **English stage names were missing for 1,221 of 6,328 stages.** `tstage.tsv` lists the Japanese and Traditional-Chinese names in the opposite columns from `stage.tsv` for some blocks (e.g. `jp=魯神五號 / tw=ウラルー` for `Urura`), so the exact `(jp, tw)` join missed and those stages got no English name at all — the OCR could read the card perfectly and the matcher still found nothing, which is the `legacy` report. The join now tries the reversed pair and then each name alone, recovering 764 stages; 457 untranslated collaboration/event rows remain, and they stay JP-only rather than being guessed.
- **The centred-card suggestion no longer requires exactly one candidate in a window.** It now takes the candidate closest to the horizontal centre, within half a carousel spacing, so a readable neighbour can never be offered as the selected stage.
- Fuzzy stage-name matching was widened from 16% to 24% of the longer normalized name (and the length gap from 2 to 3) so real PP-OCR substitutions such as `Snitches Oot Sutches` still reach `Snitches Get Stitches`. Candidates stay advisory: the user still confirms every selection.
- Numbers are matched exactly before the fuzzy tolerance is applied: if a candidate carries the same digits that were read, candidates with other numbers are dropped (`Downpour Jungle Area 16` no longer offers Areas 11-15, `Floor 42` no longer offers `Floor 4`). The number is the part of a card the recognizer gets right, and an area/floor number is what distinguishes two otherwise identical names. A name that is the shorter of the two cut off inside its number (`floor4` vs `floor42`) is never accepted as a typo match.
- Tower floors carry a series label from `assets/stages/tower_labels.json` (inclusive decoded stage-ID ranges) because the local stage file only stores `42階` / `Floor 42` once per tower: `6000000–6000049` Heavenly Tower, `6006000–6006049` Inferno Tower, `6010000–6010049` Heavenly Tower (Alt). Buttons and the details title read `Floor 42 · Inferno Tower`.
- Regression coverage: `StageOcrFixtureTest` runs the real pipeline on the Z2352N over five 2400x1080 captures (`AI Fishing`, `Eager Entryway`, `Sodden Bones`, `Duel on the Prarie (Vanguard)`, `Urura`) and asserts the centred card resolves to the right stage name.
- **Duplicate records are narrowed by the energy the game prints.** The stage-select screen shows the centred card's cost, and `StageCaptureService` reads it (`Energy -250`, or the `Energy` + `-250` pair the detector splits) from the row under the card. The overlay drops every record whose base energy disagrees before asking anything: Aku Realms `South Africa` (11 records) collapses to the single one costing 103. When the read is missing or ambiguous the list stays.
- **Energy is base 36, and decoding it as decimal first was silently wrong.** `2v` is 103 and `28` is 80. `parseEncodedLong` tried decimal before base 36, so every energy whose text happened to contain only digits was read as a decimal number: 1,446 of the 6,328 stages had the wrong cost. It surfaced when the three Into the Future Moons read `28 / 90 / 100` instead of `80 / 90 / 100`. Energy now decodes base 36 first; base HP and stage length are plain decimal and stay that way (no health value in the file needs base 36). `StageCatalogParserTest` pins the three Moon costs.
- **The Empire of Cats Moons were in the file all along, under the wrong Japanese name.** Map 3000's stages 47, 49 and 50 carry `西表島 第N章` in `name_jp` while `name_tw` says `月球 第N章` — a collab name leaked into the Japanese column. `tstage.tsv` only knows `西表島` / `月球` without the chapter number, so every exact join missed, the stages ended up with no English name, and they were listed under their Japanese label instead of `Moon`. The join now retries with a trailing `第N章` stripped (exactly three rows in the file are affected, all three Moons). Because all three chapters share map 3000, `chapter_labels.json` also accepts per-stage keys (`3000:47`) so each Moon carries its own chapter.
- **Enemy stats came from the same source as the stage tables.** `assets/neko/data/en/enemy_stats.json` is generated from battlecatsinfo (`enemy.tsv`, `tenemy.tsv`, `units_scheme.json`; MIT). That project is where the bundled `stage.tsv` / `tstage.tsv` / `stage_scheme.json` already came from — the file sizes match exactly — so enemy and stage data share one provenance instead of adding a second licence question.
- **Two columns are encoded, not plain.** `trait` is a bitmask resolved through `units_scheme.json`'s 17-entry trait list (Doge = 256 = Traitless, Pigge = 1 = Red, Zoge = 64 = Zombie), and `attack_type & 2` is the area/single flag. `ability` is a `&`/`|`-separated string of `id@param!param` tokens; only the ids are read, and each was validated against an unmistakable enemy family before being named — relic enemies carry Curse, alien barrier-carriers carry Barrier, zombies carry Burrow and Revive, wave attackers carry Wave. Ids that could not be named with confidence (9, 37, 50, 100) are dropped rather than guessed. `EnemyStatsTableTest` pins these cases.
- **Overlay windows are sized once, so rotation must dismiss them.** Swiping out to a portrait launcher left the landscape-sized result card on screen with its close button past the right edge — undismissable until something forced landscape again. `StageOverlayService.onConfigurationChanged` now closes the result card; the floating bubble stays. Any future overlay window should do the same, or re-measure on config change.
- The table loads on the first enemy tap, not at startup: 114 KB parsed with `org.json` costs a few milliseconds and most sessions never open an enemy card.
- **Chapter labels close the duplicate problem.** `assets/stages/chapter_labels.json` maps the map index encoded in each stage id (`id / 1000`) to a chapter, and `StageCatalog.chapterNameFor` exposes it. Duplicate rows now read `Aku Realms · Energy 103` and `Empire of Cats · Energy 20` instead of two anonymous stat lines, sorted in chapter order. Map 3000 holds all three Empire of Cats chapters — they share stages 0-46 and the Moons are stages 47, 49 and 50 — so it carries one label rather than pretending to split. Indexes the user has not labelled fall back to stats, so an unlabelled map degrades rather than lies. `StageCatalogParserTest` asserts every labelled `South Africa` record lands in its own chapter.
- **Still unresolved: telling the chapters apart automatically.** The stage file encodes a map index in its base-36 id (`id / 1000`: 3000–3018 cover the Empire of Cats / Into the Future / Aku Realms / zombie blocks, 6000+ the towers) but no asset maps that index to a chapter name, and inferring names from the ranges would be a guess. Reading the `Energy` the game prints under each card is the strongest available signal — it matched the catalog base cost in 4 of 5 fixtures and would collapse `South Africa` to one record — but cleared stages print a reduced cost, so it can only rank, never filter, until that is characterised.
- [MyGamatoto stage-name search](https://mygamatoto.com/allstages/search-by-stage-name) is a discovery reference. Its page did not establish an approved data API/export or image reuse rights.
- Candidate indexes: [Battle Cats Data](https://www.battlecatsdata.com/), [Battle Cats Database stage index](https://battlecats-db.com/stage/), and [MyGamatoto](https://mygamatoto.com/); freshness and rights must be checked before importing.

### Android permissions and capture constraints

- [Android MediaProjection guide](https://developer.android.com/media/grow/media-projection): capture is user-consented and must stop/clean up when revoked.
- [Android 14 foreground-service type changes](https://developer.android.com/about/versions/14/changes/fgs-types-required): a projection foreground service for apps targeting API 34+ needs the declared projection type/permission and required order of operations.
- [Overlay permission reference](https://developer.android.com/reference/android/Manifest.permission#SYSTEM_ALERT_WINDOW): overlay permission is a user-granted special access distinct from screenshot capture permission.

### Session lifetime and the one-time consent (2026-10-29)

- **The screen-share approval belongs to the process, not to the activity.** `MediaProjection` hands out a token that cannot be persisted or re-created, so "approve once, then just spawn the bubble" holds for as long as the app process lives and no longer. Swiping the task away must not end it, so both services declare `android:stopWithTask="false"` and the projection lives in `StageCaptureService`. If the OS kills the process, a new approval is unavoidable — that is a platform rule, not a design choice, and the UI says "one-time approval per session" rather than pretending otherwise.
- **The bubble must never be hidden just because this app is in front.** Hiding it on `onResume` looked tidy but broke the first-run flow: the consent dialog returned, the activity resumed, the freshly shown bubble was hidden, and the press appeared to have failed, so the button had to be pressed twice. `onResume` now *ensures* the bubble is visible when a session is live.
- **The helper button asks the service, not the screen.** `StageHelperScreen` used its own `captureActive` snapshot to decide between "show the bubble" and "ask for consent". When that snapshot lagged, a live session was sent down the consent path, and `requestConsent` deliberately no-ops while a projection is active — so the press silently did nothing. The click handler now reads `StageCaptureService.isProjectionActive` directly.
- Both services log `onTaskRemoved`, so if a device still drops the session, logcat shows whether task removal even reached the service.

### Overlay window lifetime and capture reliability (2026-10-29)

- **Never recycle a bitmap that an attached overlay window still references.** Both crashes seen on the Z2352N (`Canvas: trying to use a recycled bitmap`) came from recycling icon bitmaps around a card swap: first while registering new icons before the old card was detached, then while detaching the card itself. That device renders overlay windows through `ViewRootImpl.drawSoftware`, so a draw traversal queued before `removeView` can still run afterwards. Result cards now only detach the window and leave bitmaps to the garbage collector; enemy icons are 44 dp sprites, so the memory saving was not worth the crash.
- **A single frame-wait is not reliable for a static game screen.** A mirroring surface that has been detached since the last scan may not deliver a frame until the mirrored content changes, which produced the "first paw tap does nothing" report. `StageCaptureService` detaches and re-attaches the reader surface per attempt, retries up to 3 times at 1.5 s each with a 350 ms gap, and only then reports "No frame arrived". The `StageCapture` logcat tag records each request, attempt, received frame, and OCR text so a future failure can be diagnosed instead of guessed.
- **A re-attached surface can replay its previous composition, so a frame must be freshness-checked, not just awaited.** Every frame's `timestamp` is recorded; a frame is only accepted when it is newer than the newest frame seen before the tap, and a replayed one is logged as `discarding stale frame` and skipped. When every attempt only produced stale frames the card says so instead of showing a result for the wrong screen.
- **Hiding the bubble has to happen while the mirroring surface is live (2026-10-29, second report).** Freshness checking alone turned "wrong stage" into "four taps do nothing, the fifth works". The bubble was hidden *before* the surface was attached, so the hide was composed while nothing was listening and the newly attached surface could only replay the pre-hide composition — which the freshness check then correctly rejected, leaving no frame to read until the game happened to animate something. The order is now attach → drain the probe window → hide → accept the first frame newer than the probe. The hide is a real composition change on a live surface, so its frame is both current and free of the overlay. A retry shows the bubble again first, so the next hide is another real change instead of a no-op.
- **One stage name maps to many records, and the app used to pick one silently.** 11 local records are called `South Africa`: Empire of Cats, Into the Future, the Aku Realms (which reuses the Empire of Cats labels), and their zombie variants. The scan of Aku Realms South Africa opened the Empire of Cats stage because the candidate list is ordered by OCR confidence and the first record for a name won. The local data carries no chapter/map name — `stage_scheme.json` groups maps that are not the `id/1000` map index the stage file encodes — so the app cannot name the chapter and must ask instead. Candidates are now grouped one row per name (`StageCandidateGroups`, unit tested); a name with several records opens a list where each record is labelled `Energy · Base HP`, which is distinct for every duplicate and which the game itself displays under the card. An ambiguous centred card is offered as "Choose which …" and never as "Likely selected".

### Local stage-data audit (2026-10-29)

- User-supplied local files are available at `app/src/main/assets/stages/`: `stage.tsv` (6,328 unique stage rows), `tstage.tsv` (6,924 rows with JP/TW/EN/KR translations), and `stage_scheme.json` (categories, conditions, fixed lineups, and tips). The English stage label join tries the exact Japanese+Traditional-Chinese pair, then the same pair reversed, then each name on its own, and stops at the first source that actually carries English. Where `tstage.tsv` itself is column-shifted (parts of the Empire of Cats country block), the English name follows the file rather than being guessed from another row; that residual source misalignment is a data problem, not a parser one.
- `app/src/main/assets/enemies/` contains 746 icons with numeric IDs from 000–760. Some IDs are absent; 43 distinct enemy IDs referenced by stage spawn records have no matching local icon. Stage details render enemies as an icon strip, so an enemy without a local icon is omitted from that strip rather than shown as an ID; a stage whose enemies are all missing icons therefore shows no enemy section at all.
- No dedicated enemy display-name table was present in the workspace during this audit. Enemy icons and exact spawn parameters are available, and the stage-details card intentionally shows no enemy names, IDs, or magnification values: the user asked for a minimal card, and per-crown magnification differs between the 1–4 crown variants of the same stage, so a single spawn percentage would be misleading.
- The local importer validates every stage row, decodes base-36 identifiers/parameters, records the source manifest, and computes SHA-256 hashes. The supplied data is not re-fetched at runtime.

## 3. Data accuracy, locale, and imagery

BCEN and BCJP can differ in names, banners, stages, and release timing. Region must be a first-class data key. Catalog assets should carry schema/version, region, source URL, source commit, fetched timestamp, hash, validation state, and license/rights status. Stable IDs—not localized names—are identifiers. Do not bundle artwork/data with unclear redistribution terms without explicitly recording the rights caveat and getting permission where required.

## 4. Event/schedule research

- [Miraheze recurring events](https://battlecats.miraheze.org/wiki/Battle_Cats_Wiki#Recurring_Events) fetch returned 403; schedule freshness/rights remain unverified.
- [Fandom Event Schedule](https://battle-cats.fandom.com/wiki/Event_Schedule) was reported last updated March 7, 2025, too stale for live state without refresh.
- [Regional Differences](https://battle-cats.fandom.com/wiki/Regional_Differences) indicates JP differs from EN.
- [Battle Cats DB JP recurring schedule](https://battlecats-db.com/schedule/regular.html) is community-maintained, not official.

## 5. GitHub Release updater

- [GitHub REST API: releases](https://docs.github.com/en/rest/releases/releases) can locate release assets; keep requests infrequent and handle rate limits.
- [WorkManager constraints](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work) can require unmetered network/device idle, but cannot promise exact run times or a direct screen-off condition.
- [Android PackageInstaller](https://developer.android.com/reference/android/content/pm/PackageInstaller) ordinary-app update flows can require user confirmation; silent install is not promised on consumer devices.

## 6. Source index

- [Godfat gacha implementation](https://gitlab.com/godfat/battle-cats-rolls/-/blob/master/lib/battle-cats-rolls/gacha.rb)
- [Godfat pool implementation](https://gitlab.com/godfat/battle-cats-rolls/-/blob/master/lib/battle-cats-rolls/gacha_pool.rb)
- [Godfat live seed/event oracle](https://bc.godfat.org/?seed=1169179061&last=199&event=2026-10-23_946&count=220)
- [NekoOptimizer EN/JP candidate catalogs](https://github.com/IliyanGavrilov/NekoOptimizer/tree/main/neko/data)
- [BCData candidate](https://github.com/fieryhenry/BCData)
- [Battle Cats Database stages](https://battlecats-db.com/stage/)
- [Fukidashi Android ONNX OCR implementation](https://github.com/yozoracat/fukidashi-mcp/tree/main/android) (user's local codebase; model/runtime details in `THIRD_PARTY_NOTICES.md`).
- [Android MediaProjection](https://developer.android.com/media/grow/media-projection)

## 3. UI direction and what the template established (2026-10-29)

The supplied mockups (`nyanko-UI-template`) replace the earlier glassmorphism direction: warm paper (`#F8EEDA`), heavy ink outlines (`#2B2118`, 3 dp), one yellow accent (`#F5C55A`), and hand-drawn artwork. `ui/theme/BattleCatsTheme.kt` now carries that palette and a heavy display typography; `ui/theme/NyankoComponents.kt` holds the shared primitives (panel, numbered step badge, pill button, bordered field, list row, bottom nav).

- **The hero band was dropped.** The mockups open with a tall brown banner carrying the drawn title. It was cropped in and shipped first, but it read as a sticky header over a scrolling screen and was removed on request; the nav icons and the paw glyph are still cut out of the mockups and colour-keyed to transparent. Without the banner the screens start directly on paper, so the window paints its status and navigation bars with `Paper` as well — the theme's default grey band above the content was the first thing that looked wrong.
- **Attribution is no longer printed in the UI.** The stage-data caption was removed on request; `THIRD_PARTY_NOTICES.md` and the README still record the CC BY-SA 4.0 provenance of the wiki-derived stage records, which is where that notice belongs rather than on the player's screen.
- **The display font is still the one real gap.** `DisplayFamily` in the theme is the system sans at Black weight because no font file was supplied; pointing it at a TTF in `res/font` would give the drawn letterforms.
- **The app icon is the template's `catto-icon.png`**, and the floating bubble is that icon cropped to an ink-ringed circle, so the helper is recognisable over the game.
- **The overlay card was restyled too.** It is a `View`-based window, not Compose, so it uses `GradientDrawable` equivalents of the same tokens: cream card with a 3 dp ink border, yellow pills for the primary action and paper pills for the rest. `Material3 Button` was dropped there because it cannot be outlined the way the template does.
- **The `Event` tab is deliberately absent.** The mockup has three tabs, but no event data is imported and a dead tab is worse than a missing one; the nav renders two.
- The seed tracker keeps its existing logic and view model; only `GlassCard` (now `NyankoPanel`), the input controls and the palette changed. `Scaffold` gets zero content insets because the activity already draws edge-to-edge and owns the system-bar insets.
