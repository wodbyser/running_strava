# Garmin Personal API: Technical Reference

Read `GARMIN_API.md` first for scope, decisions, and gates.

**Verified against:** `connect-personal-api-python-main/` (source files `client.py`, `auth.py`, `constants.py`, `errors.py`, `downloads.py`, `README.md`), checked 2026-10-24.

**Official sources:**
- Personal API overview: https://developer.garmin.com/gc-developer-program/personal/personal-api
- Swagger documentation: https://developer.garmin.com/gc-developer-program/personal/swagger-docs/
- Python client: https://github.com/garmin/connect-personal-api-python
- Token page (create or revoke keys): https://connect.garmin.com/app/settings/personalApiToken

**Verification labels:**
- **VERIFIED**: confirmed in the official client source.
- **UNVERIFIED**: not confirmed. Must be checked in Gate 1 before use.

Do not invent endpoint paths, response fields, units, or limits. The Swagger schema was not retrievable during the check.

---

## 1. Authentication contract (VERIFIED)

### 1.1 Key to bearer token

```
POST {base_url}/personalapitoken-service/personal-api/personal-api-key/token
Headers:
  Authorization: ApiKey <api-key>
  Accept: */*
Body: none
```

- Success: HTTP 2xx. The **response body is the raw JWT as plain text**, not JSON. Trim whitespace.
- Expiry: read the `exp` claim from the JWT payload (second dot-separated segment, base64url, padding added). Signature is not verified. Expiry is epoch seconds.
- Failure: non-2xx raises an HTTP error with status, method, URL, and body. The body may contain Garmin's error text. Do not log it in full if it could echo the key.
- Missing or non-JWT response, or no `exp`: treat as a failed exchange.

### 1.2 Token lifetime

- The token is valid for 24 hours, or until the key expires or is deleted, whichever comes first. (FAQ, VERIFIED)
- The official client reuses a cached token only if it was issued for the same key and base URL and has **more than 1 hour** left. Otherwise it requests a new one.
- Revocation: a revoked or deleted key makes its tokens fail. Expect HTTP 401 on data calls.

### 1.3 Data calls

```
GET {base_url}{path}[?query]
Headers:
  Authorization: Bearer <access-token>
  Accept: application/json        (ZIP download uses the accept list in section 4)
```

- The official client does **not** retry 401 automatically. Recovery is: request a new token with the key, then repeat the call once.
- Non-2xx raises an HTTP error with status, method, URL, and response body.
- An empty successful response (no content) is returned as `null`/`None` in the official client. Treat it as "no data for this request", not as an error.

### 1.4 Key lifecycle (FAQ, VERIFIED)

- Key expiry options: 30, 90, 180, or 365 days. The owner chose 365 days. Creation date: `I created it 24 october 2026. So will expire 24 october 2027`.
- Garmin emails about one week before expiry.
- The key can be deleted at any time on Garmin Connect Web. Deleting it invalidates all tokens from it.
- Expiry date to record in `docs/garmin-sync-design.md`. Set a reminder.

### 1.5 Official client settings (VERIFIED)

| Setting | Value |
|---|---|
| Default base URL | `https://personal.connectapi.garmin.com` |
| Base URL override env var | `GARMIN_PERSONAL_API_BASE_URL` |
| Default key env var | `GARMIN_PERSONAL_API_KEY` |
| Default key file | `~/.config/garmin/connect-personal-api-key` (CLI only, not used by this project) |
| Default bearer cache file | `~/.config/garmin/connect-personal-api-bearer-token.json` (not used by this project: tokens stay in memory) |
| Default timeout | 30 seconds |
| Token path | `/personalapitoken-service/personal-api/personal-api-key/token` |

The official client's cache fingerprint is SHA-256 of `base_url + NUL + key`. This project keeps tokens in memory, so no fingerprint file is needed.

---

## 2. Date and parameter conventions (VERIFIED)

- Dates: `YYYY-MM-DD` strings, or date objects formatted that way.
- Date ranges are **inclusive** on both ends, as the wrapper docstrings state.
- The API performs its own validation. The client does not check date ranges.
- Activity IDs: positive integers. The download call rejects non-positive or non-numeric IDs before sending.

---

## 3. Endpoint table (VERIFIED: paths and parameters from client source)

All are GET. Response schema **UNVERIFIED** for every row until Gate 1.

| # | Domain | Official wrapper | Path (relative to base URL) | Parameters |
|---|---|---|---|---|
| 1 | Stress (daily) | `get_daily_stress(start, end)` | `/usersummary-service/stats/stress/daily/{start}/{end}` | path dates |
| 2 | Steps (daily) | `get_daily_steps(start, end)` | `/usersummary-service/stats/steps/daily/{start}/{end}` | path dates |
| 3 | Hydration (daily) | `get_daily_hydration(start, end)` | `/usersummary-service/stats/hydration/daily/{start}/{end}` | path dates |
| 4 | Effective weight goals | `get_effective_weight_goals(start, end)` | `/goal-service/goal/user/effective/weightgoal/{start}/{end}` | path dates |
| 5 | Weight measurements | `get_weight_measurements(start, end)` | `/weight-service/weight/range/{start}/{end}` | `includeAll=true` (fixed) |
| 6 | Food logs | `get_daily_food_logs(date)` | `/nutrition-service/food/logs/{date}` | path date. **Out of scope** (section 2.4 of GARMIN_API.md) |
| 7 | User profile settings | `get_user_profile_settings()` | `/userprofile-service/userprofile/settings` | none |
| 8 | Sleep (single day) | `get_daily_sleep(date)` | `/sleep-service/sleep/dailySleep` | `date={date}` |
| 9 | Sleep summaries (range) | `get_daily_sleep_summaries(start, end)` | `/sleep-service/sleep/dailySleepSummariesByDate` | `startDate`, `endDate` |
| 10 | Body Battery (range) | `get_daily_body_battery(start, end, chunks=None)` | `/wellness-service/wellness/bodyBattery/reports/daily` | `startDate`, `endDate`, optional `chunks` (integer) |
| 11 | Daily summaries (range) | `get_daily_summaries(start, end)` | `/usersummary-service/v2/dailysummaries` | `startDate`, `endDate`. Same start and end gives one day. Response keeps an object with a `data` array |
| 12 | Daily heart rate | `get_daily_heart_rate(date)` | `/wellness-service/wellness/dailyHeartRate` | `date={date}` |
| 13 | HRV (single day) | `get_daily_hrv(date)` | `/hrv-service/hrv/{date}` | path date |
| 14 | HRV summaries (range) | `get_daily_hrv_summaries(start, end)` | `/hrv-service/hrv/daily/{start}/{end}` | path dates |
| 15 | Heart rate zones | `get_heart_rate_zones()` | `/biometric-service/heartRateZones/v2` | none |
| 16 | Menstrual cycle summary | `get_menstrual_cycle_summary(date)` | `/periodichealth-service/menstrualcycle/summary/{date}` | **Out of scope** |
| 17 | Menstrual cycle day view | `get_menstrual_cycle_day_view(date, today)` | `/periodichealth-service/menstrualcycle/dayview/{date}` | `todayDate={today}`. **Out of scope** |
| 18 | Fitness age | `get_fitness_age(date)` | `/fitnessage-service/fitnessage/v2/{date}` | path date |
| 19 | SpO2 and acclimation (day) | `get_daily_spo2(date)` | `/wellness-service/wellness/daily/spo2acclimation/{date}` | path date |
| 20 | Wellness activity summaries (range) | `get_wellness_activity_summaries(start, end)` | `/wellnessactivity-service/activity/summary/range/{start}/{end}` | path dates |
| 21 | Activity search | `search_activities(start=0, limit=20, start_date, end_date, activity_type)` | `/activitylist-service/activities/search/activities` | `start`, `limit`, optional `startDate`, `endDate`, `activityType` |
| 22 | Activity details | `get_activity(activity_id)` | `/activity-service/activity/{activity_id}` | path ID |
| 23 | Activity workouts | `get_activity_workouts(activity_id)` | `/activity-service/activity/{activity_id}/workouts` | path ID |
| 24 | Activity file (ZIP) | `download_activity(activity_id)` | `/download-service/files/activity/{activity_id}` | path ID. Binary response (section 4) |

### 3.1 Endpoints in scope for Phase 1

Rows 1, 3 (if useful), 5 (if useful), 8, 9, 10, 11, 12, 13, 14, 15, 18, 19, 20, 21, 22, 23, 24. Rows 2 and 4 are optional. Rows 6, 7, 16, and 17 are out of scope for analytics. Row 7 may be fetched once for profile context if the owner allows it.

Decide the final list in Gate 1 and record the decision in the capability matrix.

### 3.2 Units and types

All **UNVERIFIED**. Document from fixtures. Do not assume units.

---

## 4. Activity file download (VERIFIED: client behaviour)

- Method: GET `/download-service/files/activity/{activity_id}`.
- Accept header: `application/zip, application/x-zip-compressed, application/octet-stream`.
- Response: the original activity file, as ZIP bytes. The official client does not extract it.
- Empty body is treated as an error by the official client.
- The official client holds the whole ZIP in memory and does not stream.
- Member names and file types inside the ZIP: **UNVERIFIED**. Gate 1 must inspect at least 2 to 3 archives.
- FIT field coverage: **UNVERIFIED**.

---

## 5. Activity search pagination (UNVERIFIED behaviour)

- `search_activities` takes `start` (default 0) and `limit` (default 20).
- The official client does not document a maximum `limit`, ordering, or whether `start` is a page offset or a record offset.
- Gate 1 must determine these by testing with small, sequential requests. Record the results in the sync design.
- Do not assume unlimited backfill depth.

---

## 6. Errors (VERIFIED: client behaviour)

| Condition | Official client behaviour | Project handling |
|---|---|---|
| Missing or empty key | Error before any request | Fail startup check with a clear message, no key in the message |
| Non-positive timeout | Error | Config validation |
| Key rejected at token exchange | HTTP error with status and body | Mark sync as failed with category `AUTH`. Do not retry in a loop |
| 401 on a data call | HTTP error. No automatic retry | Re-exchange the key once, then retry the call once. If it fails again, category `AUTH` |
| Non-2xx other than 401 | HTTP error | Record status code. Retry only for categories confirmed in Gate 1 (for example 429, 5xx) |
| Empty success body | `null` | Treat as no data for that request |
| Invalid JSON | Underlying parse error | Record category `PARSE`, keep the raw body only if raw storage is on and it contains no token |
| Network or timeout | Underlying network error | Retry with backoff |
| JWT without `exp` | Error | Treat as failed exchange |

Rate-limit behaviour: **UNVERIFIED**. The official client has no rate-limit handling. Do not invent limits. Use conservative throttling and record actual responses in Gate 1.

---

## 7. Official client behaviour summary (VERIFIED)

- Python 3.12 or newer. Package name: `garmin-connect-personal-api-python`.
- `PersonalApiAuthenticator.obtain_token(api_key)` returns a token object with `access_token` and `expires_at` (epoch seconds).
- `GarminPersonalApiClient(access_token)` makes authenticated GET requests.
- Requests are logged at INFO with the path and query string. Project logging must not log tokens. Paths and counts are acceptable.
- Client `get(path, params=...)` calls any supported GET path without a wrapper.
- Errors: `GarminPersonalApiError` (base), `HttpError` (status, method, URL, response text), plus `OSError` and `requests` exceptions for I/O and network.

---

## 8. Smoke test (run locally, do not paste output into chats)

The official client's smoke test, adapted for this project. Use the key from the environment. Do not print the key or the token. Do not commit output.

```python
import os
from datetime import date, timedelta
from garmin_personal_api import PersonalApiAuthenticator, GarminPersonalApiClient

api_key = os.environ["GARMIN_PERSONAL_API_KEY"]
with PersonalApiAuthenticator() as auth:
    token = auth.obtain_token(api_key)
with GarminPersonalApiClient(token.access_token) as client:
    today = date.today()
    week_ago = today - timedelta(days=7)
    hrv = client.get_daily_hrv_summaries(week_ago.isoformat(), today.isoformat())
    sleep = client.get_daily_sleep(today.isoformat())
    activities = client.search_activities(start=0, limit=20)
    print(type(hrv).__name__, type(sleep).__name__, type(activities).__name__)
```

Note: the official client caches tokens in `~/.config/garmin/`. Delete that cache file after the test if you do not want a plaintext token on disk. The Kotlin implementation keeps tokens in memory only.

---

## 9. Unknowns (must not be fabricated)

- Response schemas, fields, nullability, units, and sample cadence for every endpoint.
- Historical depth per endpoint.
- Rate limits, retry guidance, and pagination guarantees.
- Whether the activity list or activity details contain a Strava link or a matchable start time and device field.
- Activity ZIP members and FIT field coverage.
- Whether the Swagger document describes endpoints beyond the 24 above.
- Availability of Training Readiness, Training Status, VO2max, lactate threshold, Recovery Time, or acute load. Not in the client. **UNAVAILABLE** unless proven.
- Empty-day response shape for each endpoint.

---

## 10. Deliverables this reference feeds

- `docs/garmin-capability-matrix.md` (Gate 1)
- `docs/garmin-field-inventory.md` (Gate 1)
- `docs/garmin-data-provenance.md` (Gate 2)
- `docs/garmin-sync-design.md` (Gate 0 and later)
- Sanitized JSON fixtures in `runner/src/test/resources/garmin/`

---

## 11. Verification log

| Date | What was checked | Result |
|---|---|---|
| 2026-10-24 | Official client `client.py`: 24 endpoint methods, parameters, paths, `includeAll`, `chunks`, dailysummaries v2 | Matches section 3 |
| 2026-10-24 | Official client `auth.py`: token exchange, JWT `exp`, 1-hour refresh window, cache fingerprint | Matches section 1 |
| 2026-10-24 | Official client `downloads.py`, `client.py` download path: ZIP, empty-body error | Matches section 4 |
| 2026-10-24 | Official client `errors.py`: `HttpError` fields | Matches section 6 |
| 2026-10-24 | Official README: key sources and precedence, FAQ on key expiry and token lifetime | Matches sections 1.2 and 1.4 |
| 2026-10-24 | Live API calls | **Not done.** Gate 1 |
| 2026-10-24 | Swagger document | **Not retrieved.** Gate 1 |
