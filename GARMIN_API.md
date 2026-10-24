# Garmin Personal API: Specification and Agent Brief

**Status:** preparation only. No Garmin code exists yet.
**Verified:** 2026-10-24 against the official client source in `connect-personal-api-python-main/` (`client.py`, `auth.py`, `constants.py`, `errors.py`, `downloads.py`, `README.md`).
**Not verified:** live response bodies, JSON schemas, historical depth, rate limits, activity ZIP contents. These are the Gate 1 outputs.

The previous Dutch version of this file is in git history (commit `4ce225f`).

---

## 0. Read order

1. `AGENTS.md`: project architecture and conventions.
2. This file: scope, decisions, constraints, gates.
3. `GARMIN_API_AGENT_REFERENCE.md`: endpoint table, auth contract, library behaviour, unknowns.
4. `docs/audit/phase-1-report.md`: defects in existing analytics that Garmin work must not inherit.
5. `docs/garmin-feature-backlog.md`: future ideas. **Not in scope.**
6. `connect-personal-api-python-main/`: reference implementation. Read only. Do not run it or modify it.

---

## 1. Goal

Add Garmin Connect data as a second data source beside Strava.

- **Strava stays the activity source of truth** (identity, distance, time, pace, streams, laps, best efforts).
- **Garmin adds physiological and recovery data**: HRV, resting heart rate, sleep, Body Battery, stress, and optionally activity files.
- **Analytics come after the data is verified.** Phase 1 is foundation only.

---

## 2. Scope

### 2.1 In scope now: Phase 1

- Garmin authentication: API key to bearer token, in-memory token lifecycle, re-exchange on 401.
- A Garmin HTTP client covering the endpoints in section 5 of the reference file.
- Sanitized fixture capture, field inventory, capability matrix (Gate 1).
- Garmin to Strava identity-matching design (Gate 2), then implementation.
- Sync: historical backfill, incremental sync, manual trigger, status reporting, retries.
- Persistence of normalized daily physiological data, with optional raw payload storage.
- Offline tests.

### 2.2 Phase 2: after Phase 1 is approved

HRV baseline, resting HR baseline, sleep analytics, Body Battery analytics, stress analytics, recovery assessment, recovery dashboard.

### 2.3 Later: not scheduled

Everything in `docs/garmin-feature-backlog.md`.

### 2.4 Out of scope

- **Menstrual cycle endpoints.** Sensitive and not needed for running analytics.
- **Medical claims.** No diagnosis, no injury or illness prediction, no "safe to train" verdicts.
- **External AI with Garmin data.** Raw health data must not be sent to any external AI provider. The app's AI remains copy-paste only.
- **Multi-user.** The app is single-user (`strava_tokens` uses row id 1). Garmin follows the same model.
- **Commercial use.** The Personal API is for personal use only.
- **Metrics not in the client.** Training Readiness, Training Status, VO2max, lactate threshold, Recovery Time, acute training load. Treat as **UNVERIFIED** until a live response or the Swagger schema proves them.

---

## 3. Decisions

These are the defaults. The implementing agent must follow them. If the owner changes one, change it here first.

| Topic | Decision | Reason |
|---|---|---|
| Runtime | The app calls Garmin directly from Kotlin through Spring `RestClient`. It never starts a Python process. | The Python client is only a reference for endpoints and login steps. A second runtime adds installation, maintenance, and deployment cost. The calls are plain HTTPS GET requests with a bearer token. |
| Python client | Reference only. Read-only. Stays in the repo. | Source of truth for endpoints and auth behaviour. |
| Licensing | Apache-2.0. If code is ported, keep the license header and add attribution in a NOTICE entry. | License requirement. |
| API key env var | `GARMIN_PERSONAL_API_KEY`. Mapped to property `garmin.api-key`. | Same name as the official client. `.env` currently has `GARMIN_API_KEY`; the owner renames it. |
| Base URL | `https://personal.connectapi.garmin.com`, property `garmin.base-url`. | Official default. Same override idea as `GARMIN_PERSONAL_API_BASE_URL`. |
| Bearer token storage | **In memory only.** No disk cache. Exchange the key again on startup or when the token is near expiry. | Avoids a plaintext token file. Costs one POST per app start. |
| Raw payloads | Stored in a table, controlled by `garmin.store-raw-payloads` (default `true` in Phase 1). | Allows recalculation when algorithms change. Local DB only. |
| Activity ZIPs | Not stored in the database in Phase 1. Downloaded only for Gate 1 inspection. Decide storage after Gate 1. | Unknown content and size. |
| Feature flag | `garmin.enabled`, default `true`. Acts as an emergency off switch, not as a feature toggle. | The owner wants Garmin active at all times. With the flag on and no valid key, the app must still start and report the state (see "Missing or invalid key" below). |
| Missing or invalid key | The app starts normally. The Garmin status shows `NOT_CONFIGURED` (no key) or `AUTH_FAILED` (key rejected or expired). The scheduler skips the run and logs one warning per run, without the key. No retry loop. | Prevents crashes and log spam. Status is visible in `GET /garmin/status`. |
| Scheduler | Separate `GarminSyncScheduler`, cron `garmin.sync.cron` (default `0 30 6 * * *`). Guarded by `garmin.enabled` and key presence. | Mirrors `StravaSyncScheduler`, runs after the Strava sync. |
| Source precedence | Strava owns activity identity, distance, pace, route, laps, streams. Garmin owns daily physiology. Garmin activity files may add time series only after a confirmed match. | Never average incompatible measurements. |
| Resting HR precedence | Garmin daily resting HR when recorded, else the manual `strava_tokens.resting_hr` value, else none. The UI shows the source. | Manual value exists today in `StravaTokenEntity.restingHr`. |
| Time handling | Garmin calendar dates stored as `LocalDate`. Source timestamps stored in UTC. Activity local time follows audit decision Q4. | Consistent with `docs/audit/phase-1-report.md`. |
| Schema changes | Keep `ddl-auto: update`. Add new tables only. Do not rename or drop Strava columns. Flyway is a separate decision. | Current app convention. |
| Databases | Must work on PostgreSQL 17 (prod) and H2 in PostgreSQL mode (dev profile). | `application-dev.yml`. |
| Sync endpoints | New Garmin endpoints use **POST** for actions that change state. | Audit item 2.10: existing `GET /sync` and `/fetch-all` change state and must not be copied. |
| Language | Code, docs, and agent text in English. UI and AI prompt text in Dutch. | Existing app is Dutch in its UI and prompts. |
| AI | Keep copy-paste prompts. If Garmin data appears in prompts, update the guardrail lines in `AiAnalysisService` that currently say HRV and sleep are not in the data. | Guardrails are currently wrong once Garmin is present. |
| Analytics base | Phases 1 and 2 (recovery: HRV, resting HR, sleep, Body Battery, stress) do not depend on the existing performance code and may start immediately. Phase 3 (performance analytics) and Phase 5 (race predictions and readiness) must not build on the existing efficiency factor, PB, race-prediction, or lap-classification code until the defects in section 7.1 are fixed or explicitly accepted. | The defective calculations would otherwise carry their errors into every Garmin-based performance metric. |

---

## 4. Constraints

### 4.1 Security and health data

- The API key is read from the environment only. Never put it in code, docs, fixtures, test output, logs, or Git.
- The bearer token lives in memory. It is never logged and never returned by any API.
- Logs: request paths and record counts only. No health values, no tokens, no key.
- Frontend and JSON responses never expose the key or token.
- Fixtures: keep structure, units, and value shapes. Remove names, account IDs, activity IDs, coordinates, device serials, and any other identifier. Do not commit a raw response from the real account.
- Garmin data is health data. Treat the database and any export as sensitive.

### 4.2 Architecture (from `AGENTS.md` and the code)

- **`core`**: domain models, SPI interfaces, use cases. No Spring. Jackson annotations only.
- **`runner`**: adapters (`RestClient`), JPA entities and repositories, schedulers, controllers, `@ConfigurationProperties`.
- Use cases are wired in `runner/.../config/AppConfig.kt` the same way as the Strava use cases.
- Existing pattern to copy: `StravaApiClient` (SPI in core, `StravaApiClientImpl` in runner), `ActivityRepository` (SPI in core, `ActivityRepositoryImpl` in runner), `SyncStravaDataImpl` (use case with overlap and recheck windows), `StravaSyncScheduler`.
- Business logic stays out of controllers and repositories.
- Thymeleaf templates in `runner/src/main/resources/templates/`. No JS build tool.

### 4.3 Data quality rules

- Every stored Garmin value keeps: source endpoint, source timestamp, sync timestamp, original unit, and the calendar date it belongs to.
- Derived values also keep: calculation method, algorithm version, and the input window.
- Use the existing labels for displayed and prompt values: `[gemeten]` measured, `[berekend]` computed, `[app-schatting]` app estimate. Add `[onbeschikbaar]` where data is missing.
- Never fabricate a value. Missing means `null` with a reason.
- Garmin sleep stages, Body Battery, and stress are proprietary estimates. Label them as such. Do not present them as clinical measurements.

### 4.4 Testing

- All automated tests run offline. No real credentials, no network.
- Use fake SPIs and stubbed HTTP (for example Spring `MockRestServiceServer`). Follow `FakeActivityRepository` for the repository fake.
- Build and test on Java 23: `JAVA_HOME=~/.sdkman/candidates/java/23-open ./gradlew :core:test :runner:test`. Java 21 gives a misleading "Inconsistent JVM target" error. Do not change buildSrc.
- The existing Strava tests must still pass unchanged.

---

## 5. Gates

Stop at each gate, report, and wait for approval before the next one.

**Gate 0: Environment check**
- `GARMIN_PERSONAL_API_KEY` is available to the process. Confirm presence only. Do not print it.
- Java 23 build passes on the current code.
- Record the result in `docs/garmin-sync-design.md`.

**Gate 1: Fixture capture and capability discovery**
- Call every endpoint in the reference file's table for a small date range (about 7 days), with throttling.
- Save sanitized JSON fixtures under `runner/src/test/resources/garmin/`.
- Write `docs/garmin-field-inventory.md`: for each endpoint, every JSON path with type, nullability, unit, sample cadence, timezone or date semantics, and historical coverage (how far back data exists).
- Download 2 to 3 activity ZIPs. Record member names, file types, and FIT field coverage (timestamp, heart rate, speed, cadence, altitude, position, power). Save metadata only, not the files.
- Write `docs/garmin-capability-matrix.md`: endpoint, method, parameters, response fields, types and units, historical availability, sync requirement, Strava equivalent (yes or no), verification status (VERIFIED, UNVERIFIED, UNAVAILABLE).
- Try the Swagger page. Record the URL and version only if it loads. Do not guess schema URLs.
- Answer every question in section 9 from evidence. Mark any answer that cannot be proven as UNVERIFIED.

**Gate 2: Identity matching design**
- Write `docs/garmin-data-provenance.md`.
- Matching order: (1) a stable shared identifier if the fixtures show one, (2) start time, duration, and distance within tolerances, with a confidence score, (3) otherwise no match.
- Never auto-merge an ambiguous match. Ambiguous matches go to a manual review list.
- Strava parser extension: store `device_name` and `external_id` only if they are useful for matching. Add them in Gate 3, not before.

**Gate 3: Phase 1 implementation**
- Only after Gates 0 to 2 are approved.

---

## 6. Phase 1 deliverables

### 6.1 Documents

- `docs/garmin-capability-matrix.md`
- `docs/garmin-field-inventory.md`
- `docs/garmin-data-provenance.md`
- `docs/garmin-sync-design.md`
- Sanitized fixtures in `runner/src/test/resources/garmin/`

### 6.2 Code (after Gate 3 approval)

Proposed names. The agent may adjust them to fit the existing style, but must state the change.

**core**
- SPI `GarminApiClient`: one method per confirmed endpoint, returning domain types.
- Domain models: daily HRV, daily sleep, daily resting HR, daily Body Battery, daily stress, activity reference. Each with source metadata and original unit.
- Use cases: `BackfillGarminData`, `SyncGarminData` (incremental), following the `SyncStravaData` pattern.
- Repository SPI for Garmin records.

**runner**
- `GarminApiClientImpl` (`RestClient`), following `StravaApiClientImpl`.
- `GarminTokenProvider`: exchanges the key, holds the token in memory, refreshes it within 1 hour of expiry, re-exchanges once on 401, then fails clearly.
- JPA entities and repository implementations for Garmin tables.
- `GarminSyncScheduler`.
- `GarminSyncController`: `POST /garmin/sync`, `POST /garmin/backfill` (date range as parameters), `GET /garmin/status`.
- `GarminProperties` (`@ConfigurationProperties(prefix = "garmin")`).

### 6.3 Acceptance criteria

1. A manual run with the real key obtains a token, and a simulated 401 triggers exactly one re-exchange.
2. Backfill and incremental sync are idempotent: running twice creates no duplicate rows.
3. Missing fields are stored as `null`, and the status endpoint reports the count.
4. A failed day or endpoint is retried with backoff and does not stop the whole run.
5. Sync status shows last run time, last success, records written per endpoint, and last error category. No secrets.
6. With `garmin.enabled=false`, there are no Garmin HTTP calls and no scheduler activity. With the flag on and no key or an invalid key, the app starts, `GET /garmin/status` reports `NOT_CONFIGURED` or `AUTH_FAILED`, and no retry loop runs.
7. All offline tests pass, and the existing Strava tests pass unchanged.

---

## 7. Existing application facts (verified in the code)

- **Stack:** Kotlin 2.1.20, Spring Boot 3.4, Java 23, PostgreSQL 17 (prod), H2 dev profile, Thymeleaf, Chart.js.
- **Persistence:** JPA entities in `runner/.../strava/db/`. Tables: `activities`, `activity_streams`, `activity_laps`, `sync_status`, `strava_tokens`. `strava_tokens.resting_hr` is a manual value.
- **Strava sync:** `SyncStravaDataImpl` re-reads 7 days before the last stored start, and re-checks the last 30 days for edits. Detail and stream calls are per activity.
- **Strava parser:** `StravaApiClientImpl.parseActivity` does **not** read `device_name`, `external_id`, or `start_date_local`. The `activities` table has no Garmin column.
- **Scheduler:** `StravaSyncScheduler`, cron `strava.sync.cron`, skips if no token.
- **Existing analytics:** `CoachService` (max HR estimate, resting HR, %HRmax and Karvonen zones, race predictions via Riegel), `PeriodComparisonService` (efficiency factor per period), `LapClassifier` and `WorkoutStructure` (interval detection), `BestEffortFinder` (fastest segment from streams), `BestEffortService`, `AiAnalysisService` (four copy-paste prompts).
- **No fitness-fatigue model exists.** There is no ATL, CTL, TRIMP, or training-load calculation in the code. Garmin-based load work is new, not an extension.
- **AI:** no provider integration. Prompts are plain text for copy-paste.
- **Config:** `application.yml` with `strava.*`. `.env` in the project root is loaded into `bootRun` by `spring-boot-module.gradle.kts` and is git-ignored.

### 7.1 Known defects to resolve before Garmin analytics

From `docs/audit/phase-1-report.md`. Status was re-checked in code on 2026-10-24. Verify each before relying on it.

- **Max pace:** `FrontendController` now passes `maxSpeed` (m/s) to `Format.pace`. The audit's km/h error appears fixed. Verify with a unit test.
- **Cadence:** `Cadence.toSpm` exists. Verify that all views and prompts use it (spm, both feet).
- **Efficiency factor** (`PeriodComparisonService`): mixes populations (speed over all runs, HR over runs with HR). Open.
- **PBs** (`FrontendController.calculatePBs`): whole-activity ±5% selection. Open. `BestEffortFinder` is the correct method and should replace it.
- **Race predictions** (`CoachService.predictFromAnchors`): minimum over all anchors, including interval reps. Open.
- **Interval classification** (`LapClassifier`, `WorkoutStructure`): unvalidated thresholds. Open.
- **Times:** weekly and monthly grouping uses UTC dates. Open (decision Q4).
- **Averages:** dashboard averages are unweighted. Open (decision Q5).
- **Verdict:** the efficiency verdict has no minimum sample size. Open (decision Q8).

---

## 8. Scientific and product rules

These apply to every Garmin-based metric.

1. **Document the formula, the assumptions, and the minimum data needed.** Store the algorithm version.
2. **Personal baselines over population thresholds.** Show the person's own history as the reference.
3. **Compare like with like.** Do not mix incompatible HRV protocols, workout types, temperatures, or terrain without flagging it.
4. **Abstain when evidence is thin.** Say "insufficient data" with the counts.
5. **Correlation is not causation.** Say so in the output where a relationship is shown.
6. **No arbitrary 0–100 scores** without showing the components.
7. **Higher HRV is not always better.** Interpret deviations from the personal baseline, in both directions.
8. **Do not interpret normal HRV or Body Battery as evidence an injury has healed.** Pain and symptoms outweigh any favourable score.
9. **No injury or illness prediction** from wearable metrics.
10. **Use temporal validation** for any model: no future data in training, held-out periods for evaluation.

---

## 9. Open questions (answer in Gate 1 from evidence)

1. HRV: which measure (RMSSD or another)? Is the value a nightly average, a reading list, or both? What is the measurement window?
2. Resting HR: where is the daily value, and is it measured during sleep?
3. Sleep: which fields exist, which sleep stages, and how is a night attributed to a calendar date?
4. Body Battery and stress: sample cadence, value ranges, and how the day boundary is defined.
5. Historical depth for each endpoint: how far back does data go?
6. Activity list: does any field identify a Strava upload or match a Strava start time?
7. Activity ZIP: member list, file type (FIT?), and which FIT fields are present.
8. Rate limits and 429 or other throttling responses: what happens, and what is the retry hint?
9. Key expiry: the owner created a key with a 1-year expiry. **Record the creation and expiry dates here:** `<I created it 24 october 2026. So will expire 24 october 2027`.
10. Swagger: loads or not, and its version if it does.
11. Empty-day behaviour: `null` body, empty object, or HTTP error?

---

## 10. Start prompt for the implementing agent

Copy this into a new session:

```text
Read GARMIN_API.md first, then GARMIN_API_AGENT_REFERENCE.md, then the files listed in section 0.
Start at Gate 0. Stop after each gate and report. Do not skip gates.
Do not print the Garmin API key or any token. Do not commit fixtures from the real account.
Do not start Phase 2 or any analytics.
If a fact is not in the fixtures or in the Python client source, mark it UNVERIFIED instead of guessing.
```
