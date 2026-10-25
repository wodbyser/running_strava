# Garmin Sync Design

Status: Gates 0 to 2 done and approved. Gate 3 (implementation design) added below.

## Gate 0: environment check (2026-10-25)

| Check | Result | Notes |
|---|---|---|
| `GARMIN_PERSONAL_API_KEY` in the shell environment | ABSENT (length 0) | Not exported in the agent shell. |
| `GARMIN_PERSONAL_API_KEY` in project `.env` | PRESENT (one non-empty line) | `.env` is git-ignored. It is loaded at boot by the convention plugin (`AGENTS.md`). Value was not printed. |
| `GARMIN_PERSONAL_API_BASE_URL` in shell environment | ABSENT | Default `https://personal.connectapi.garmin.com` applies. |
| Java 23 build and tests (`:core:test :runner:test`) | PASS | `JAVA_HOME=~/.sdkman/candidates/java/23-open`, BUILD SUCCESSFUL. Strava tests all pass; no code changed. |
| Network access to Garmin | NOT TESTED | No call was made. Needed for Gate 1. |

Result: the Java build passes and the key exists only in `.env`. For Gate 1 the key must be loaded into the process that makes the calls (export it from `.env`, or the owner approves reading `.env` for the fixture-capture session). Gate 1 is not started.

## Gate 1: findings that shape the design (2026-10-25)

Evidence and details: `garmin-capability-matrix.md`, `garmin-field-inventory.md`. Fixtures: `runner/src/test/resources/garmin/`. Rows 3, 4, 5, 7, 6, 16, 17 were not called.

### Owner decisions recorded (2026-10-25)

| Topic | Decision |
|---|---|
| Retries | Default kept: 3 attempts, 1 s / 4 s / 16 s, only network, timeout, 429, 5xx. AUTH: re-exchange once, retry once. |
| Concurrent sync | Default kept: second trigger returns 409, no HTTP calls. |
| Raw payload retention | Default kept: stored, no expiry. |
| Strava client secret | Owner: leave as is. It works and must keep working. No rotation work. |
| Phase mapping | Confirmed (Phases 3 to 5 as in the roadmap). |
| Gate 1 key handling | Owner allowed reading the key from `.env`. Key never printed. |
| Sleep need | Owner: at least 7 h 30 of sleep and enough deep sleep (no exact deep-sleep target given). |
| Injury history | Owner: shin stress fracture about 1.5 years ago, one knee dislocation, no current issues. Not stored in the app. |

### Design inputs from the live API

1. **Range limits (use the documented limits as chunk sizes):** stress and steps 28 days, sleep summaries and Body Battery 31 days, daily summaries 1 year, HRV summaries up to 367 days (probed, not documented; use 90). Longer ranges return HTTP 400. This replaces the "7-day chunks" default only as an upper bound; the owner default of 7-day chunks stays valid and is well inside every limit.
2. **Which endpoint per record type:** `dailysummaries` (one call per range) carries resting HR, stress, Body Battery summary, steps, calories, respiration. `sleep summaries`, `hrv summaries`, `stress daily` carry the rest. Stress as a time series was not found.
3. **Empty days:** several endpoints omit empty days or return null-filled objects, HRV returns HTTP 204. The client must treat 204, `[]`, `{"data":[]}`, and all-null objects as "no data for this day", not as errors, and must not store an all-null sleep or heart-rate row as data.
4. **Recent days change:** the current day's summary is incomplete until the day ends, and sleep and HRV are attributed to the wake-up date. Incremental sync must re-fetch an overlap window of the last few days (proposal: 3 days, owner to confirm in Gate 3 review).
5. **HTTP 500 is not always transient:** an activity search `limit` above about 175 returns 500. Use page size 100.
6. **No 429 seen:** keep the sequential calls with a 1 s pause during development and the owner's retry policy. Throttle value is a design choice, not a measured limit.
7. **Backfill depth:** all data starts at 2024-12-19. The 730-day default covers it.
8. **Key and token:** token lifetime is 24 h (JWT `iat` to `exp`). Use `exp`. Refresh inside the last hour as in the official client.
9. **Time zone:** activity detail reports `Europe/Paris`, not `Europe/Brussels`. Daily data follows local midnight. The "yesterday" rule in Europe/Brussels is consistent with this.

### Open design points for Gate 3 (not decided here)

- FIT file decoding for the app (the files are 1 Hz and rich). Not in Phase 1 scope unless the owner asks.
- Whether to store the `activity search` payload (large, includes owner name fields) as raw. If raw storage stays on, owner name and image URL fields should be dropped before storing.

### Further owner decisions (2026-10-25, Gate 1 review)

| Topic | Decision |
|---|---|
| Retry and HTTP 500 | Retry rule unchanged. Activity search page size is 100. |
| Incremental overlap | Re-fetch the last 3 days on every incremental run. |
| Raw payloads | Drop profile image URLs before storing. Keep the owner name field. |
| HRV label | "HRV (Garmin, nachtgemiddelde)". Never call it RMSSD. |
| Sleep (Phase 2) | Target 7 h 30 total sleep per night. Sleep debt from total sleep only. Deep sleep 15 to 20 % of total sleep as an indicative reference, labelled app estimate, analysed separately, creates no sleep debt. Later: compare with the personal 30-day Garmin baseline when enough data exists. Sleep stages are Garmin estimates, never exact. |
| Match review | Read-only list in Phase 1 (see `garmin-data-provenance.md`). |
| Match thresholds | Report-only dry run first. |
| Strava columns | `external_id` and `device_name` nullable columns allowed in Gate 3. |

## Gate 3: implementation design (2026-10-25)

Entry check: Approvals table shows Gates 0, 1, 2 APPROVED. (The Phase 1 gate status table in `GARMIN_PROGRESS.md` still said "AWAITING APPROVAL" for Gate 2; the Approvals table is authoritative and the status table was aligned.)

### Scope decisions made by the agent (flagged for owner review, none change phase scope)

| # | Decision | Reason |
|---|---|---|
| D1 | Rows 1 (stress daily), 2 (steps daily), 10 (Body Battery report) are **not called**. Resting HR, Body Battery summary, stress and steps come from `dailysummaries` (row 11), which carries all four per day (field inventory section 11). | One call per chunk instead of four, less load on the API. Body Battery 3-minute series and the stress class durations in seconds are not stored in Phase 1. Owner can ask to add them. |
| D2 | `dailyHeartRate` (row 12), SpO2 (19), fitness age (18), wellness activity summaries (20), activity details (22), workouts (23), ZIP (24) are **not synced**. | Not needed for Phase 1 physiology data; row 19 and 20 never returned data. |
| D3 | Heart rate zones (row 15) are synced once per run into `garmin_hr_zones` (reference data). | Provenance doc section 7. |
| D4 | "3 attempts" = 3 calls in total per request. Backoff delays 1 s then 4 s between them. The third delay (16 s) is configured and used if `garmin.retry.max-attempts` is raised. | The default wording lists three delays but says 3 attempts. Owner can say "3 retries" (4 calls). |
| D5 | Incremental end date = today (Europe/Brussels), start = last successful run date minus 3 days. Backfill default end = yesterday. | Owner: re-fetch last 3 days. Today's data is partial and fixed by the overlap. |
| D6 | Last success only advances when a run had no failed call. | A failed chunk is then covered again by the next incremental run. |
| D7 | Matches are **computed on demand, not stored** in Phase 1. Two read-only GET endpoints: report and review list. | Thresholds are not approved until the dry run is read (provenance section 11). Nothing to persist, nothing can be mislinked. |
| D8 | Coordinate tie-break: both start positions known: at most 200 m keeps a pair Strong-compatible, over 2 km fails the pair, between is Weak. | Provenance only defines the two ends. |
| D9 | Garmin type mapping used: `running` to run, `walking` to walk, `cycling` to ride, `lap_swimming` to swim. Other keys never match. | Only keys seen live; trail or treadmill keys UNVERIFIED. |
| D10 | Extra config keys: `garmin.retry.max-attempts` (3), `garmin.retry.delays` (1s,4s,16s), `garmin.backfill.chunk-days` (7), `garmin.backfill.pause` (1s), `garmin.sync.overlap-days` (3), `garmin.activity-page-size` (100). | Defaults from the owner decisions, made configurable for tests. |
| D11 | Sync and backfill POSTs start the run on a background thread and return 202. Busy returns 409. Flag off or no key returns 503 with the state. No HTTP call is made in those cases. | A 2-year backfill takes minutes. |

### Tables (all new, `ddl-auto: update`, PostgreSQL and H2)

Provenance columns on every Garmin data table: `source_endpoint`, `source_timestamp` (UTC, nullable), `synced_at` (UTC), `calendar_date`, `original_units`, `raw_payload_id` (nullable).

| Table | Key | Metric columns |
|---|---|---|
| `garmin_daily_hrv` | calendar_date | weekly_avg, last_night_avg, last_night_5min_high, baseline_low_upper, baseline_balanced_low, baseline_balanced_upper, baseline_marker_value, status, feedback_phrase |
| `garmin_daily_sleep` | calendar_date (wake-up date) | sleep_time_seconds, deep, light, rem, awake, unmeasurable, nap_time_seconds, sleep_start_gmt_ms, sleep_end_gmt_ms, avg_respiration, avg_sleep_stress, avg_heart_rate, overall_score, overall_qualifier, garmin_sleep_need_baseline, garmin_sleep_need_actual |
| `garmin_daily_resting_hr` | calendar_date | resting_hr, min_hr, max_hr (from `heartRate`) |
| `garmin_daily_body_battery` | calendar_date | min_value, max_value, charged, drained, latest_value (from `bodyBattery`) |
| `garmin_daily_stress` | calendar_date | avg_level, max_level, rest/low/medium/high/activity/uncategorized duration in ms, total_duration_ms (from `stress`) |
| `garmin_daily_steps` | calendar_date | steps, distance_m, step_goal (from `movement.steps`) |
| `garmin_activity_ref` | activity_id | start_time_gmt, type_key, parent_type_id, distance_m, duration_s, elapsed_s, moving_s, avg_hr, max_hr, aerobic_te, anaerobic_te, training_load, training_effect_label, manufacturer, is_manual, start_lat, start_lon |
| `garmin_hr_zones` | sport | training_method, resting_hr, lactate_threshold_hr, zone1..5 floor, max_hr |
| `garmin_raw_payload` | (endpoint, record_key) | payload TEXT, synced_at. One row per day element or per activity. Profile image URLs removed. Redacted if it contains the key or a token. |
| `garmin_sync_status` | id = 1 | last_run_kind, last_run_started_at, last_run_finished_at, last_success_at, last_success_date, last_error_category, last_error_detail, records_per_endpoint (JSON), null_fields_per_endpoint (JSON), failed_calls |

`activities` gets two nullable columns: `external_id`, `device_name` (additive).

Missing fields: stored as `null`. A day with all metric fields `null` (empty sleep object) is not stored. `null_fields_per_endpoint` counts null metric fields in the records written by the last run.

### Classes

core (`com.running.garmin`): `domain` (records, `GarminSyncStatus`, `GarminErrorCategory`, `GarminApiException`), `spi` (`GarminApiClient`, `GarminRepository`, `GarminSyncStatusRepository`), `usecase` (`BackfillGarminData`, `SyncGarminData`, shared `GarminRangeSync`, `GarminRetry`), `matching` (`GarminActivityMatcher`, `MatchReport`).

runner: `GarminProperties`, `GarminTokenProvider`, `GarminApiClientImpl`, `GarminRedactor`, JPA entities and `GarminRepositoryImpl`, `GarminSyncCoordinator` (single-run lock), `GarminSyncScheduler` (only when `garmin.enabled`), `GarminSyncController`.

### Auth and errors

`GarminTokenProvider` exchanges the key (POST, `Authorization: ApiKey`), parses `exp` from the JWT, keeps the token in memory, refreshes when under 1 hour is left, `invalidate()` forces a new exchange. `GarminApiClientImpl` on 401: re-exchange once, repeat the call once, then throw `AUTH`. Categories: `NOT_CONFIGURED`, `AUTH`, `NETWORK`, `TIMEOUT`, `RATE_LIMIT` (429), `SERVER` (5xx), `CLIENT` (other 4xx, not retried), `PARSE`. `GarminRetry` retries NETWORK, TIMEOUT, RATE_LIMIT, SERVER only. `AUTH` and `NOT_CONFIGURED` abort the run. Other failures are recorded and the run continues with the next chunk.

204, empty body, `[]`, `{"data":[]}` and `{"hrvSummaries":[]}` mean no data, not an error.

### Status (`GET /garmin/status`)

JSON: `state` (`DISABLED`, `NOT_CONFIGURED`, `RUNNING`, `AUTH_FAILED`, `NEVER_RUN`, `OK`, `PARTIAL_FAILURE`), `lastRunKind`, `lastRunStartedAt`, `lastRunFinishedAt`, `lastSuccessAt`, `recordsPerEndpoint`, `nullFieldsPerEndpoint`, `failedCalls`, `lastErrorCategory`, `reviewMatchCount`. Never the key, token or any health value.

### Matching (read-only)

`GET /garmin/matches/report`: counts per tier, distributions (min, median, p90, max) of start, distance and elapsed differences for the nearest compatible candidate, and the check "Strava `external_id` contains the Garmin activity ID". `GET /garmin/matches/review`: the REVIEW rows. No POST, no stored links (D7).

### Tests (all offline)

core: retry policy, range sync (chunking, idempotency, null handling, partial failure continues, AUTH aborts, overlap), matcher (provenance section 10 items 1 to 7, 10, 11). runner: token provider and client with `MockRestServiceServer` and the fixtures (401 gives exactly one re-exchange, 204, empty shapes, 429 and 5xx mapping, no secret in messages), controller and coordinator (409, flag off, no key), redactor, properties validation, JPA idempotency with H2, scheduler off.

## Gate 3: owner decisions applied (2026-10-25, second Gate 3 session)

| Topic | Decision | Change made |
|---|---|---|
| Body Battery series, stress durations (D1) | Store only if they add information beyond the daily summary and the semantics are verified. No duplicate storage. | **Body Battery series stored** in `garmin_body_battery_sample` (calendar_date, `timestamp_gmt_ms` as key, level). Verified live: `chunks=480` returns about one reading per 180 s (default is 6 points a day), level 0 to 100, timestamps epoch ms UTC, each reading appears twice with the same value, some with a null twin. The loader merges duplicate timestamps and drops nulls. The `charged` and `drained` values of the report are not stored (same as the daily summary). Not stored and UNVERIFIED: the optional status and version columns, which Garmin never returned live. How two different non-null values at one timestamp would be handled is UNVERIFIED (never seen; the first one is kept). **Stress durations are not stored again**: the daily summary already has them (milliseconds). Stress daily (row 1, seconds) and steps daily (row 2) stay uncalled. |
| Retry (D4) | 3 calls in total, waits 1 s and 4 s. | Default delays changed from `1s,4s,16s` to `1s,4s` (code, properties, `application.yml`). Tests added. |
| Matching (D7 to D9) | On demand in Phase 1. Type mapping unchanged. Position is a supporting component, thresholds stay provisional. | Position difference can only move a pair to Weak (review), never reject or link it. The 2 km fail limit was removed. |
| HTTP 403 | Non-retryable client error, no token re-exchange. | New category `FORBIDDEN`. Test: exactly one data call and one token exchange. |

Additions found during verification: the 429 message now carries the `Retry-After` header value (or "absent"), never the body. A failed call is logged once after retries (endpoint and message, no body). Raw payload text is key-sorted because Garmin sends object keys in changing order (same content, different text); without that, 348 of 448 activity raw rows were rewritten with different text on every run.

## Gate 3: verification against PostgreSQL (2026-10-25)

**Environment.** Production stayed untouched: a one-way `pg_dump` of `running-db` (read only) was restored into a new container `running-test-db` (postgres:17, port 5433, copy of the Strava data). The app ran with `DB_URL` pointing at the copy, both cron jobs off. No Strava API call was made (the copy holds a Strava token; a refresh could rotate the real one). The production database has no Garmin table, no new column, and still 585 activities. The test container is stopped, not removed. Raw responses were kept in `/tmp/opencode` only and deleted.

**A. Schema.** Spring/Hibernate `ddl-auto: update` created the 11 Garmin tables and the two nullable Strava columns on PostgreSQL 17. Content hashes of all five Strava tables (activities, laps, streams, token, sync status, without the two new columns) were identical before the boot, after the boot, after all syncs and at the end.

**D. Idempotency (test copy, real key).**

| Run | Result | Compared |
|---|---|---|
| 1. `POST /garmin/sync` (no earlier success, so default range 730 days) | OK, 0 failed calls, 432 GET calls | Baseline |
| 409 check while run 1 was active | HTTP 409, no HTTP call | |
| 2. `POST /garmin/sync` (incremental, last 3 days) | OK | 0 added, 0 removed, 0 changed in all 10 Garmin tables |
| 3. `POST /garmin/backfill` (full default range, 1 s pause) | PARTIAL_FAILURE: HTTP 429 (RATE_LIMIT) on 3 calls after retries, run continued | Rows written identical, 0 duplicates. Only raw activity text differed (key order, see above) |
| 4. `POST /garmin/backfill` (full range, `GARMIN_BACKFILL_PAUSE=2s`) | OK, 0 failed calls, 426 GET calls | 0 added, 0 removed, 0 changed in the 9 data tables. Raw activity payloads equal as JSON (0 differences) |
| 5 and 6. Two short backfills (2026-10-01 to 2026-10-24) after the key-sort fix | OK both | Run 6 vs run 5: 0 added, 0 removed, 0 changed in all 10 tables including raw payloads. Run 5 vs before: only 83 raw rows reformatted once |

Final per-table counts (identical after every run): activity refs 448, Body Battery samples 69 743, daily Body Battery 669, HRV 624, resting HR 669, sleep 620, steps 676, stress 676, HR zones 2, raw payloads 2 370. Duplicate keys: 0 (activity IDs, HRV dates, sample timestamps, raw keys). Status after the clean runs: `OK`, `failedCalls` 0. Null counts: stress 42, steps 14, sleep 212, HRV 92, activities 4, others 0 (these count null metric fields in stored rows).

**429 finding (UNVERIFIED limit).** The first 429 appeared in run 3, after about 860 sequential calls in roughly 15 minutes (1 s pause between chunks, no pause between the 4 calls of one chunk). Three consecutive attempts (waits 1 s and 4 s) did not clear it. The `Retry-After` header was not logged at that time, so the retry hint is unknown. Run 4 with a 2 s pause made 426 calls without a 429. The limit itself is still unknown.

**Key safety.** The key string appears in none of the three app logs (checked by search, nothing printed), none of the committed files and none of the docs.
