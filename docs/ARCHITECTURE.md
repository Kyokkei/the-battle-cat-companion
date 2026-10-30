# Architecture and data plan

**Status:** proposed; refine after source/license research and Android SDK decision.  
**Design principles:** local-first, modular, deterministic core logic, data provenance, region-aware, testable, zero-warning target.

## 1. Proposed layers

```text
app (Compose UI, navigation, permissions, user flows)
  ├── feature-seed (seed profiles, prediction display, roll logging)
  ├── feature-stage-overlay (bubble, one-shot capture, OCR/matching/result)
  ├── feature-reference (stage/enemy/event browser, image viewer)
  ├── feature-updater (GitHub release discovery, download, validation, installer handoff)
  ├── feature-schedule (optional, isolated future feature)
  ├── domain-seed (pure deterministic roll and track rules)
  ├── domain-stage (stage identity, region-aware matching and confidence)
  ├── data-local (Room, repositories, migrations, data-pack ingestion)
  ├── data-source (source adapters/importers, all with rate/freshness/rights metadata)
  └── core-ui / core-model / core-test
```

These are logical boundaries, not a mandate to create many Gradle modules on day one. Start with a small number of modules/packages, extract modules when independent testing/build ownership benefits. Keep Android framework types out of pure domain logic.

## 2. Domain models (initial sketch)

- `GameRegion`: `EN`, `JP` (extend explicitly, do not overload language).
- `LocaleName`: locale tag, display value, aliases, source.
- `StageId`, `EnemyId`, `BannerId`, `EventId`: stable internal IDs independent of translated labels.
- `StageRecord`: ID, region, names, aliases, chapter/map context, enemy composition, available structured stage details, source metadata, image references.
- `SeedProfile`: ID, starting seed, region, created/updated time, label/notes.
- `RollLogEntry`: profile ID, order/time, event ID, observed result, optional expected result, user correction marker.
- `TrackPosition`: sequence position plus A/B path representation required by the upstream model; keep encoding internal and documented.
- `Prediction`: unit, rarity, position, event context, transition reasons, data version.
- `DataSourceMetadata`: source URL, license/attribution status, region, locale, upstream version, fetched-at, content hash, validation status.
- `UpdateRecord`: release tag/version, asset identifier, checksum/signing identity validation result, download state; never store APK contents as Room BLOBs.

Avoid treating a localized display name as a primary key. Similar names, aliases, and version changes are expected.

## 3. Local storage (Room)

Use Room for structured app-owned state:

- seed profiles and appendable roll history;
- selected region/locale and feature preferences;
- normalized reference metadata/stage-enemy relationships if the dataset size and query patterns support it;
- data-pack version/provenance/freshness state;
- update-check/download metadata, not downloaded binaries.

Store large immutable image/data assets as files in app-private storage or an appropriately versioned asset/data-pack layout; keep hashes, IDs, dimensions and relative identifiers in Room. Define a transactional import: stage new data, validate schema/region/required references/hashes, then atomically switch active version. Retain the last known-good data pack for rollback. Use Room migrations for schema changes and migration tests from every shipped schema version.

Seed position should be derivable from the initial seed plus ordered roll log. If caching a computed position for responsiveness, treat it as a rebuildable cache and test it against replayed history.

## 4. Seed prediction engine

- Pure Kotlin first; no Android/Room/UI dependencies.
- Build a test oracle using pinned output from upstream Godfat tracker/code, with commit/version and event fixture recorded.
- Unit tests cover deterministic same-input behavior, ordinary roll advancement, all supported track switches/guaranteed behavior, event catalog differences, invalid inputs, and replay after correcting a log entry.
- Keep mechanics separate from event/unit data. Data revisions should not silently rewrite old user history; show which catalog version generated a prediction.
- Port/copy code only after license verification and required attribution. If independently reimplementing, document behavioral equivalence fixtures and upstream differences.

## 5. Stage OCR and matching pipeline

```text
Enable floating bubble (SYSTEM_ALERT_WINDOW)
  -> show Android MediaProjection consent once for this helper session
  -> keep a mediaProjection foreground service active, with the virtual display detached while idle
  -> tap paw: hide bubble, attach ImageReader surface briefly, acquire one frame, detach surface
  -> on-device ONNX Runtime OCR (PP-OCRv5 English / Manga OCR Japanese)
  -> exact/near-exact BCEN/BCJP localized stage-name candidate lookup
  -> suggest a centered top-card candidate but require the user to confirm
  -> show stage stats and an enemy icon strip (no IDs or magnification) in overlay
  -> close card; screenshot bitmap is recycled and never written/uploaded
  -> Stop helper/revocation ends projection and removes both services
```

The current MVP uses OCR text plus a simple centered/top-card geometry suggestion; it does not verify the game’s selected-card border/marker. Each card crop is trimmed to its own name line before recognition, because the recognizer scales its input by height and leftover card background shrinks the glyphs past readability. The suggestion is never auto-confirmed: the user chooses a candidate. Android grants one projection session at helper activation, then each paw tap samples a single frame without asking again. Frame capture retries the surface attach up to three times, because a freshly attached mirroring surface can stay silent on a static screen. Result-card icons are ordinary bitmaps released by the collector; they are never recycled while the overlay window that draws them may still be attached, since this device renders overlays in software. The local stage parser and OCR matcher are separately testable. Instrumentation/debug logs must not contain full screenshots, seeds, or personal file paths.

## 6. Data ingestion and images

- Provide one source adapter per source, with clear ownership/terms and fetch interval. Do not scrape a site in production until its terms, robots/access conditions, and stability are reviewed.
- Convert each source into a versioned internal schema via a deterministic importer. Validate referential integrity and required region/locale fields.
- Keep source URL/license/attribution and content hash on each imported pack; keep dataset timestamps visible in UI.
- Do not assume stage text, enemy art, banners, or event artwork share a common license. Maintain an asset rights inventory; do not bundle assets with unknown redistribution rights until cleared.
- Prefer lossless/near-lossless images. For display, decode appropriately sized variants but retain high-resolution originals for zoom; optionally download additional packs on demand.
- If source freshness is unknown, display that limitation rather than presenting data as official/current.

## 7. Updater design

1. Check latest GitHub Release via official REST endpoint or stable release metadata.
2. Select asset for app package/device and minimum supported Android; verify release tag/version and HTTPS source.
3. Download resumably with unmetered-network/idle constraints for background mode; foreground/manual check bypasses those background constraints.
4. Verify published checksum/signature and signing certificate expectations; reject mismatched package ID, signer, downgrade, or corrupt file. A hash alone protects against accidental corruption only if the expected hash itself is obtained authentically.
5. Stage APK using app-private storage, expose a notification, and invoke Android PackageInstaller/user confirmation. Handle “unknown apps” access where needed. Keep a fallback/retry state if the user cancels.
6. Do not delete the currently installed app or assume installation succeeded before OS confirmation.

The silent-install requirement cannot be promised for normal consumer Android devices. Consider Play-distributed updates or a managed-device deployment if fully unattended installation is essential. Background execution is best effort; an idle constraint is not an exact screen-off trigger.

## 8. Build and quality gates

- Pin the Gradle wrapper, Android Gradle Plugin, built-in Kotlin/Compose compiler plugin, KSP, SDK levels, and dependency versions. Current skeleton uses AGP 9.4.1 / Gradle 9.8.2 / API 37 compileSdk and targetSdk; keep the Kotlin Compose plugin aligned with the AGP built-in Kotlin compiler.
- CI gates: `assemble` (debug/release as applicable), unit tests, instrumentation tests on supported API levels, Android lint, formatting check, dependency vulnerability/license inventory, data schema validation, and fixture checks.
- Define project warning baseline at initialization and drive it to zero; treat warnings as CI failures only after known third-party/toolchain baseline is clean. Do not blanket suppress lint or compiler warnings.
- Keep tests deterministic and network-independent. Integration/source-refresh tests run separately with fixtures and explicit source versioning.
- Add screenshot-corpus regression tests and per-region seed tracker goldens before claiming the relevant feature accurate.
