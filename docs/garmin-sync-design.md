# Garmin Sync Design

Status: Gate 0 only. Later sections are added in Gates 1 to 3.

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
