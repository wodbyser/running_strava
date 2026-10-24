# Garmin Personal API — Verified Integration Reference for Coding Agent

Verified against Garmin's official `garmin/connect-personal-api-python` README, accessed 2026-10-24. The public Swagger landing page was reachable, but its interactive OpenAPI schema and response JSON schemas were not retrievable in the accessible page content. Do not invent response schemas, auth URLs, base URLs, or undocumented endpoints.

Official sources:
- https://developer.garmin.com/gc-developer-program/personal/swagger-docs/
- https://developer.garmin.com/gc-developer-program/personal/personal-api
- https://github.com/garmin/connect-personal-api-python
- https://connect.garmin.com/app/settings/personalApiToken

## Confirmed HTTP endpoints (official client wrappers)

All are GET. Dates use YYYY-MM-DD. A positive integer activity ID is required for downloads.

| Domain | Python wrapper / CLI command | HTTP path |
|---|---|---|
| Stress | `get_daily_stress(start_date,end_date)` / `stress` | `/usersummary-service/stats/stress/daily/{start_date}/{end_date}` |
| Steps | `get_daily_steps(start_date,end_date)` / `steps` | `/usersummary-service/stats/steps/daily/{start_date}/{end_date}` |
| Hydration | `get_daily_hydration(start_date,end_date)` / `hydration` | `/usersummary-service/stats/hydration/daily/{start_date}/{end_date}` |
| Weight goals | `get_effective_weight_goals(start_date,end_date)` / `effective-weight-goals` | `/goal-service/goal/user/effective/weightgoal/{start_date}/{end_date}` |
| Weight | `get_weight_measurements(start_date,end_date)` / `weight-measurements` | `/weight-service/weight/range/{start_date}/{end_date}?includeAll=true` |
| Food | `get_daily_food_logs(calendar_date)` / `food-logs` | `/nutrition-service/food/logs/{calendar_date}` |
| Profile | `get_user_profile_settings()` / `profile-settings` | `/userprofile-service/userprofile/settings` |
| Sleep daily | `get_daily_sleep(calendar_date)` / `sleep` | `/sleep-service/sleep/dailySleep?date={calendar_date}` |
| Sleep range | `get_daily_sleep_summaries(start_date,end_date)` / `sleep-summaries` | `/sleep-service/sleep/dailySleepSummariesByDate?startDate={start_date}&endDate={end_date}` |
| Body Battery | `get_daily_body_battery(start_date,end_date,chunks=None)` / `body-battery` | `/wellness-service/wellness/bodyBattery/reports/daily?startDate={start_date}&endDate={end_date}[&chunks={chunks}]` |
| Daily summary | `get_daily_summaries(start_date,end_date)` / `daily-summaries` | `/usersummary-service/v2/dailysummaries?startDate={start_date}&endDate={end_date}` |
| Daily HR | `get_daily_heart_rate(calendar_date)` / `heart-rate` | `/wellness-service/wellness/dailyHeartRate?date={calendar_date}` |
| HRV daily | `get_daily_hrv(calendar_date)` / `hrv` | `/hrv-service/hrv/{calendar_date}` |
| HRV range | `get_daily_hrv_summaries(start_date,end_date)` / `hrv-summaries` | `/hrv-service/hrv/daily/{start_date}/{end_date}` |
| HR zones | `get_heart_rate_zones()` / `heart-rate-zones` | `/biometric-service/heartRateZones/v2` |
| Menstrual summary | `get_menstrual_cycle_summary(calendar_date)` / `menstrual-cycle-summary` | `/periodichealth-service/menstrualcycle/summary/{calendar_date}` |
| Menstrual day | `get_menstrual_cycle_day_view(calendar_date,today_date)` / `menstrual-cycle-day-view` | `/periodichealth-service/menstrualcycle/dayview/{calendar_date}?todayDate={today_date}` |
| Fitness age | `get_fitness_age(calendar_date)` / `fitness-age` | `/fitnessage-service/fitnessage/v2/{calendar_date}` |
| SpO2 | `get_daily_spo2(calendar_date)` / `spo2` | `/wellness-service/wellness/daily/spo2acclimation/{calendar_date}` |
| Wellness activity | `get_wellness_activity_summaries(start_date,end_date)` / `wellness-activity-summaries` | `/wellnessactivity-service/activity/summary/range/{start_date}/{end_date}` |
| Activity search | `search_activities(start=0,limit=20,start_date=None,end_date=None,activity_type=None)` / `activities` | `/activitylist-service/activities/search/activities?start={start}&limit={limit}[&startDate={start_date}&endDate={end_date}&activityType={activity_type}]` |
| Activity details | `get_activity(activity_id)` / `activity` | `/activity-service/activity/{activity_id}` |
| Activity workouts | `get_activity_workouts(activity_id)` / `activity-workouts` | `/activity-service/activity/{activity_id}/workouts` |
| Activity ZIP | `download_activity(activity_id)` / `activity-download` | `/download-service/files/activity/{activity_id}` |

## Authentication and library behavior

- Python >=3.12; `pip install garmin-connect-personal-api-python` (installs requests).
- API key created in Garmin Connect Personal API Tokens settings; library does not create keys.
- `PersonalApiAuthenticator.obtain_token(api_key)` exchanges key for a bearer token.
- `BearerToken.access_token` and `BearerToken.expires_at` (epoch seconds).
- Token cache default: `~/.config/garmin/connect-personal-api-bearer-token.json`.
- Cache reused only when key matches and JWT expiry is >1 hour away; otherwise a new token is obtained.
- Revoked token may cause HTTP 401; client does not auto-retry. Explicit refresh: `obtain_token(api_key, force_refresh=True)` then `client.update_bearer_token(token.access_token)`.
- CLI key sources, precedence: `--api-key`, `--api-key-file`, `GARMIN_PERSONAL_API_KEY`, default `~/.config/garmin/connect-personal-api-key`.
- Avoid `--api-key` because of process list / shell history exposure.
- Credential files are plaintext; POSIX newly created token cache uses permissions 0600; protect key file with chmod 600.
- Client `get(path, params=...)` can access supported GET endpoints without wrappers; it returns decoded JSON or None for empty successful responses.
- `download_activity` returns ZIP bytes in memory (not streaming); CLI defaults to activity-ID.zip, supports -o and --force.
- `HttpError` includes status_code, method, url, response_text; other exceptions include GarminPersonalApiError, OSError, and requests exceptions.
- Library does not automatically retry 401.

## Minimal Python smoke test

```python
import os
from datetime import date, timedelta
from garmin_personal_api import PersonalApiAuthenticator, GarminPersonalApiClient

api_key = os.environ['GARMIN_PERSONAL_API_KEY']
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

Never print raw credentials, token values, or sensitive health data into shared logs.

## Discovery and implementation tasks (mandatory)

1. Inspect the existing application and Strava schema first; reuse existing analytics and avoid duplicate activities.
2. Obtain an authorized Garmin key through secure local configuration. Do not request or paste keys into AI chats.
3. Try to access official Swagger/OpenAPI specification in an authorized browser session. Record actual schema URL and version only if verified. Do not infer guessed schema endpoints.
4. Use authenticated smoke tests for the documented wrappers, respecting access terms and throttling. Save **sanitized** response fixtures and a field inventory per endpoint (JSON path, type, nullability, units, sample cadence, timezone/date semantics, historical coverage).
5. Confirm whether HRV is RMSSD or another measure and its measurement context before comparing values. Check resting HR, sleep stages, Body Battery, stress and activity time series field availability rather than assuming.
6. Inspect downloaded ZIP content types and FIT records; validate available fields (timestamp, HR, pace/speed, power, cadence, altitude, GPS) against real files.
7. Establish an explicit Garmin–Strava identity-matching policy, preferably shared identifiers; use cautious fuzzy matching only with ambiguity handling.
8. Build per-endpoint sync windows, pagination, incremental cursors/watermarks, retries, backoff and 401 refresh; do not assume undocumented rate limits.
9. Store source provenance, original units, timestamps, raw payload where permitted, normalized metrics, data quality and algorithm version. Treat local-day sleep boundaries and DST carefully.
10. Implement analytics in stages: HRV/RHR/sleep baseline; recovery response; activity-level drift and aerobic decoupling; comparable runs; load-response relationships; race readiness. Add scientific caveats and abstain when insufficient data.
11. Validate all models with unit tests and historical holdout periods; never present unvalidated injury predictions, diagnoses, or causal claims.
12. Document confirmed vs unconfirmed Garmin capabilities in a capability matrix and report access limitations clearly.

## Unknowns — DO NOT FABRICATE

- Exact API host/base URL and token-exchange HTTP contract (inspect official client source or actual Swagger).
- Exact response schemas, fields, nullability, units, data retention, and historical limits.
- Rate limits, authorization scope details, pagination guarantees, and allowed usage limits.
- Availability of Garmin Training Readiness, Training Status, VO2max, acute load, lactate threshold or other metrics not listed above.
- Exact ZIP archive members and FIT field coverage until inspected.
- Whether additional Swagger endpoints exist beyond the documented client wrappers.

## Recommended deliverables for the coding agent

- `docs/garmin-capability-matrix.md`
- `docs/garmin-field-inventory.md`
- `docs/garmin-data-provenance.md`
- `docs/garmin-sync-design.md`
- Sanitized JSON/ZIP metadata fixtures
- Integration adapter + integration tests
- Strava/Garmin activity identity mapping
- Incremental sync and historical backfill
- Recovery/performance analytics integration and user-facing explanations
