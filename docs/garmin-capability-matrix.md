# Garmin Capability Matrix

Gate 1 output. Evidence: live calls on 2026-10-25 with the owner's key (about 200 GET calls, 1 s apart, sequential), the official Swagger document, and the official Python client source (read only). Field-level detail is in `garmin-field-inventory.md`. Fixtures are in `runner/src/test/resources/garmin/`.

Verification labels: **VERIFIED** (live response or client source), **UNVERIFIED**, **UNAVAILABLE** (documented or expected but not returned).

## 1. Token exchange

| Item | Result | Label |
|---|---|---|
| `POST /personalapitoken-service/personal-api/personal-api-key/token` with `Authorization: ApiKey <key>` | HTTP 200. Body is a raw 3-segment JWT as plain text. | VERIFIED |
| JWT claims present | `exp`, `iat`, `iss`, `jti`, `scope`, and client and user identifiers. Values not recorded. | VERIFIED |
| Token lifetime | `exp - iat` = 86 400 s (24 h). Still use `exp`, not a constant. | VERIFIED |
| Invalid key at exchange | HTTP 401, empty body | VERIFIED |
| Invalid bearer on a data call | HTTP 401, empty body | VERIFIED |
| Key expiry | The owner's key expires 2027-10-24 (365 days, per owner). Not proven by a live call. | UNVERIFIED |

## 2. Swagger

| Item | Result |
|---|---|
| Page | `https://developer.garmin.com/gc-developer-program/personal/swagger-docs/` loads (HTML, JavaScript-rendered). |
| Spec location | Embedded as an inline JSON literal in the page's component script (`component---src-pages-gc-developer-program-personal-swagger-docs-*.js`). No separate schema URL was guessed or used. |
| Version | OpenAPI 3.0.1, title "Garmin Connect Personal API", API version `1.0.0`, server `https://personal.connectapi.garmin.com`. |
| Paths | 26 GET paths, 109 schemas. |
| Paths **not** in the Python client | `GET /usersummary-service/usersummary/daily?calendarDate=`, `GET /weight-service/weight/all/{start}/{end}`. Not used. Not verified live. |
| `/swagger-ui/index.html` on the API host | HTTP 404 `{"error":"NOT_FOUND","status":404}` |

## 3. Endpoint matrix (in-scope rows, `GARMIN_API_AGENT_REFERENCE.md` section 3)

Strava equivalent: "no" means Strava has no such data. History: first date with data for this account (see section 5).

| # | Endpoint (relative path) | Method | Parameters | Max range | Response shape | Units and cadence | Sync need | Strava equivalent | Status |
|---|---|---|---|---|---|---|---|---|---|
| 1 | `/usersummary-service/stats/stress/daily/{s}/{e}` | GET | path dates | 28 days (DOCUMENTED, and 29 days gave 400) | array, one object per day (days without data omitted: `[]`) | durations in seconds, level index | daily | no | VERIFIED |
| 2 | `/usersummary-service/stats/steps/daily/{s}/{e}` | GET | path dates | 28 days (DOCUMENTED, 400 beyond) | array, one per day | count, metres | daily | no | VERIFIED |
| 8 | `/sleep-service/sleep/dailySleep` | GET | `date` | one day | object, every field `null` on empty days | seconds, epoch ms | daily | no | VERIFIED |
| 9 | `/sleep-service/sleep/dailySleepSummariesByDate` | GET | `startDate`, `endDate` | 31 days (DOCUMENTED). 32 days incl. returned 200, 33 days 400 | array, one per day, same shape as row 8 | as row 8 | daily | no | VERIFIED |
| 10 | `/wellness-service/wellness/bodyBattery/reports/daily` | GET | `startDate`, `endDate`, `chunks` | 31 days (DOCUMENTED). 32 incl. returned 200, 33 days 400 | array, one per day | level 0 to 100. Default 6 points per day. `chunks=N` gives 2N points (lowest and highest per interval). Real cadence 180 s | daily | no | VERIFIED |
| 11 | `/usersummary-service/v2/dailysummaries` | GET | `startDate`, `endDate` | 1 year (DOCUMENTED). 366 days incl. returned 200, 367 days 400 | `{"data":[...]}`, days without data omitted | mixed, one per day | daily | no | VERIFIED |
| 12 | `/wellness-service/wellness/dailyHeartRate` | GET | `date` | one day | object with 120 s samples | bpm, epoch ms | daily | partly (Strava has activity HR only) | VERIFIED |
| 13 | `/hrv-service/hrv/{date}` | GET | path date | one day | object with summary, 5-minute readings, sleep window. HTTP 204 if none | ms (UNVERIFIED), 300 s | daily | no | VERIFIED |
| 14 | `/hrv-service/hrv/daily/{s}/{e}` | GET | path dates | not documented. 367 days incl. returned 200, 368 days 400 | `{"hrvSummaries":[...]}`. HTTP 204 if all days empty | one per day | daily | no | VERIFIED. Exact limit from probing only |
| 15 | `/biometric-service/heartRateZones/v2` | GET | `generateDefaults` (optional) | n/a | array of 2 zone sets (`DEFAULT`, `RUNNING`) | bpm | on change, weekly is enough | no | VERIFIED |
| 18 | `/fitnessage-service/fitnessage/v2/{date}` | GET | path date, `fullHistoryScan` (optional) | one day | object (Swagger says array) | years | optional | no | VERIFIED. Low value for Phase 1 |
| 19 | `/wellness-service/wellness/daily/spo2acclimation/{date}` | GET | path date | one day | object. SpO2 values were `null` for the sampled day | n/a | none | no | VERIFIED shape. Data UNAVAILABLE for this device |
| 20 | `/wellnessactivity-service/activity/summary/range/{from}/{until}` | GET | path dates | not probed beyond 1 year | `[]` for every range tried | n/a | none | no | Shape UNVERIFIED (never non-empty) |
| 21 | `/activitylist-service/activities/search/activities` | GET | `start`, `limit`, `startDate`, `endDate`, `activityType`, `sortOrder`, `sortBy`, more | see section 4 | array of summaries | metres, seconds, m/s | incremental | partly (Strava is the source of truth) | VERIFIED |
| 22 | `/activity-service/activity/{id}` | GET | path id | n/a | object with `summaryDTO`, `splitSummaries`, metadata | as row 21 | on demand | partly | VERIFIED |
| 23 | `/activity-service/activity/{id}/workouts` | GET | path id | n/a | array. One workout with one step seen on 3 runs | metres, seconds | on demand | no | VERIFIED |
| 24 | `/download-service/files/activity/{id}` | GET | path id | n/a | ZIP bytes, `application/x-zip-compressed` | n/a | not stored in Phase 1 | no (Strava has streams) | VERIFIED |

Rows 3, 4, 5, 7 were not called (excluded from Phase 1). Rows 6, 16, 17 were not called (out of scope). No change to the list in the reference document.

## 4. Activity search pagination (answers `GARMIN_API_AGENT_REFERENCE.md` section 5)

| Question | Result | Label |
|---|---|---|
| Is `start` a page or a record offset? | Zero-based **record** offset (`start=1` returned the list shifted by one record; `start=5&limit=5` continued `start=0&limit=5`). | VERIFIED |
| Default order | Newest first by local start time. Swagger: `sortOrder` default `desc`, `sortBy` default `startLocal`. | VERIFIED |
| Default `limit` | 99 (DOCUMENTED). | DOCUMENTED |
| Maximum `limit` | 175 returned 200. 199 returned HTTP 500 (empty body). 200, 300, 500, and 1000 returned HTTP 500. The maximum is between 175 and 198. | VERIFIED range. Exact maximum UNVERIFIED |
| `limit=0` | HTTP 200 `[]` | VERIFIED |
| End of data | `[]` at the first offset past the last record. | VERIFIED |
| Total records | 448 activities (all types) from 2024-12-19 to 2026-10-18. Pages of 100: 100, 100, 100, 100, 48, then empty. | VERIFIED |
| Date filter | `startDate`/`endDate` inclusive, local time. A range before the data (year 2020) returns `[]`. | DOCUMENTED + VERIFIED |
| Type filter | `activityType=running` returned running only. The key must be a parent type. | DOCUMENTED + VERIFIED |
| Recommended page size | 100 (verified to work, below the failing size). | decision for Gate 3 |

## 5. Historical depth (answers section 9, question 5)

Probed with single days at six dates, and with year-long ranges.

| Endpoint | Earliest date with data | Label |
|---|---|---|
| `dailysummaries` | 2024-12-19 (none from 2023-10-26 to 2024-10-24 inclusive; sampled window 2024-10-25 to 2025-10-23 starts with data on 2024-12-19) | VERIFIED |
| HRV summaries | 2024-12-20 | VERIFIED |
| Activities | 2024-12-19 (oldest of 448) | VERIFIED |
| Sleep, heart rate, Body Battery, stress | Single days in 2020 to 2024 returned empty shapes. A day in 2025-10 and 2026-04 returned data. The exact first day was **not** probed per endpoint. Assume the same start as dailysummaries. | UNVERIFIED (first date) |

Consequence: a 730-day default backfill (2024-10-25 onward) covers all existing data. Backfilling further only returns empty days.

## 6. Limits, throttling, errors (answers section 9, questions 8 and 11)

| Item | Result | Label |
|---|---|---|
| Rate limit or HTTP 429 | **None seen** in about 200 sequential calls spaced 1 s apart. No `Retry-After` or rate-limit header on the one response whose headers were inspected. The real limit, and any retry hint, is unknown. | UNVERIFIED |
| Bad range (too long) | HTTP 400, body `{}` (2 bytes) | VERIFIED |
| Malformed date | HTTP 404, body `{}` | VERIFIED |
| Future date on `hrv/{date}` | HTTP 204, empty | VERIFIED |
| Bad `limit` (over the maximum) | HTTP 500, empty body. A 5xx here is a permanent client error, not a transient fault. A retry will not help. | VERIFIED |
| 401 | Empty body, both for a bad key and a bad bearer | VERIFIED |
| Request time | 0.2 s to 1.8 s per call. Full 100-activity page about 1 s, 410 KB. | VERIFIED |
| Swagger error codes | 400, 401, 403, 404, 500 on some endpoints | DOCUMENTED |

Design consequence: the default retry rule (retry HTTP 5xx) would retry the "limit too large" 500 three times without success. The activity page size must stay at or below 100, which avoids it. Owner decision needed only if a different rule is wanted (see `GARMIN_PROGRESS.md`).

## 7. Activity ZIP (answers section 9, question 7)

Three recent running activities downloaded. Files not stored. Metadata only.

| Item | Result |
|---|---|
| Response | HTTP 200, `Content-Type: application/x-zip-compressed`, 85 to 203 KB |
| Members | One member each: `<activityId>_ACTIVITY.fit` |
| File type | FIT, header protocol byte 16, `.FIT` signature. The detail endpoint reports `fileFormat` `fit`. |
| `record` messages | 2 544 to 5 396 per file. Sample interval **1 s** in all three (no gaps). |
| Per-record fields present (all or nearly all records) | timestamp, position (lat, long), heart rate, cadence, distance, power, enhanced speed, enhanced altitude, vertical oscillation, stance time, vertical ratio, step length |
| Present in the field list but never valid | stance time percent |
| Temperature, plain `speed`, plain `altitude` | not in the `record` messages of these files |
| Other messages | 9 to 23 laps, 1 session, 12 to 14 device info messages, plus many Garmin-specific message types (not decoded) |
| Decoding | A small throwaway FIT parser was used only for this inspection. It is not part of the app and not committed. A FIT decoder for the app is a Gate 3 question. |

## 8. Strava matching fields (answers section 9, question 6)

| Question | Result |
|---|---|
| Does any field name a Strava upload or ID? | No. The string `strava` and `externalId` appear nowhere in the 448 search records, the 3 detail records, or the Swagger document. |
| Fields usable for matching | `startTimeGMT` (seconds precision) and `beginTimestamp` (epoch ms), `distance` (m), `duration` (s), `activityType.typeKey`, `manufacturer` (`GARMIN`). |
| Device identifier on activities | `manufacturer` only. No device serial or name on the search or detail response. (`deviceId` exists in sleep need and Body Battery events, not on activities.) |
| Garmin activity ID | Stable integer in `activityId`. |

Gate 2 uses this evidence for the matching design.

## 9. Metrics not in the API (`GARMIN_API.md` section 2.4, `GARMIN_API_AGENT_REFERENCE.md` section 9)

| Metric | Result | Label |
|---|---|---|
| Training Readiness, Training Status, Recovery Time, acute load | No endpoint and no schema property in the Swagger document. | UNAVAILABLE |
| VO2max | The Swagger schema for activity summaries lists `vO2MaxValue`, but the live search returned no such key across 448 activities. | UNAVAILABLE (documented, never returned) |
| Lactate threshold | `lactateThresholdBpm` and `lactateThresholdSpeed` are in the schema but absent from live activity responses. The **heart rate zones** endpoint does return `lactateThresholdHeartRateUsed` (bpm, per sport). | Zone field VERIFIED. Activity fields UNAVAILABLE |
| Per-activity training load and training effect | Returned on every activity (`activityTrainingLoad`, `aerobicTrainingEffect`, `anaerobicTrainingEffect`, `trainingEffectLabel`). Formula and unit not documented. | VERIFIED present. Semantics UNVERIFIED |
| Sleep need | `sleepNeed` and `nextSleepNeed` objects (baseline and actual, Garmin's estimate). | VERIFIED present |

## 10. Answers to `GARMIN_API.md` section 9

1. **HRV measure:** not stated in the Swagger text (the word RMSSD appears only as an unrelated enum value). Unit **UNVERIFIED** (integer, ms by convention). The summary has `lastNightAvg`, `weeklyAvg`, `lastNight5MinHigh`, a personal `baseline` band, and a `status`. The day response also has about 100 five-minute readings across the sleep window. Both exist.
2. **Resting HR:** `restingHeartRate` in `dailyHeartRate` and `heartRate.restingValue` in `dailysummaries`. One integer per day. Measurement method UNVERIFIED (not stated whether sleep-based). `dailyHeartRate` also has `lastSevenDaysAvgRestingHeartRate`.
3. **Sleep:** fields include total, deep, light, REM, awake and unmeasurable seconds, start and end timestamps, respiration, sleep stress, average heart rate, Garmin sleep scores with qualifiers, sleep need, naps. A night belongs to the **wake-up** date.
4. **Body Battery and stress cadence:** Body Battery 180 s, level 0 to 100, day = local day. Stress daily: only totals per day (seconds per stress class and an overall level). The stress time series was **not** found on any in-scope endpoint. Day boundary is local midnight.
5. **Depth:** section 5.
6. **Strava match fields:** section 8.
7. **ZIP:** section 7.
8. **Rate limits:** section 6. None seen. UNVERIFIED.
9. **Key expiry:** per owner 365 days, 2027-10-24. Token lifetime 24 h is now VERIFIED (section 1).
10. **Swagger:** loads. OpenAPI 3.0.1, version 1.0.0 (section 2).
11. **Empty days:** see the table in `garmin-field-inventory.md`.

## 11. Fixture set

`runner/src/test/resources/garmin/` (22 files). Identifiers (account, device, activity IDs, names, URLs, coordinates, UUIDs) are replaced by constants. Health values and dates are real but contain no identifier. Series arrays are cut to a few points. Empty-day shapes are included. No JWT, no key, no ZIP, no FIT file is committed.

## 12. Additions from Gate 3 verification (2026-10-25)

| Item | Result | Label |
|---|---|---|
| HTTP 429 | Seen live once: 3 calls in a row after about 860 sequential calls in about 15 minutes (1 s pause between chunks). `Retry-After` was not captured then. The limit is unknown. A run with a 2 s pause made 426 calls without 429. | Occurrence VERIFIED. Limit and hint UNVERIFIED |
| Body Battery report with `chunks=480` | About one reading per 180 s, each reading twice, some with a null twin. 257 to 480 distinct readings per stored day. Optional status and version columns never returned. | VERIFIED. Columns UNVERIFIED |
| Key order in activity search JSON | Varies between calls (same content). | VERIFIED |
