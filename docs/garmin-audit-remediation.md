# Garmin Phase A: audit remediation

Date: 2026-10-25. Source: `docs/audit/phase-1-report.md` (2026-09-25), `GARMIN_API.md` section 7.1, `GARMIN_ROADMAP.md` Phase A.

Method: each item re-read in the current code, and matched to an existing test. Status values: VERIFIED (correct in code and covered by a test), FIXED (changed in this session), OPEN, UNVERIFIED, ACCEPTED (owner decision).

Result: the code already applies almost every audit decision. The `GARMIN_API.md` 7.1 list was stale. One real defect was open (state change over GET) and is fixed now.

## 1. Re-verification (roadmap step 1)

| Audit item | Decision | Status | Evidence in code | Test |
|---|---|---|---|---|
| 0.1 Strava secret in script | Q9, owner chose no rotation | ACCEPTED by owner | `test-strava-connection.sh` has no fallback (`CLIENT_SECRET="${STRAVA_CLIENT_SECRET:-}"`); no secret found by a `git grep` for a 20+ hex value. History not rewritten (Q9). Rotation not done: owner decision, recorded in question 8 | none (script check) |
| 0.2 Max pace | | VERIFIED | `FrontendController` passes `a.maxSpeed` (m/s) to `Format.pace` | `pace is rounded to the nearest second` (4.68 m/s gives 3:34) |
| 0.3 Cadence | Q3 | VERIFIED | `Cadence.toSpm` used for dashboard, detail, CSV, JSON, prompts; unit "spm" | `running cadence per foot is doubled to spm`, `training context ... doubles cadence` |
| 0.4 PBs | Q1 | VERIFIED | `BestEffortFinder.fastest` (interpolated exact distance, elapsed time, GPS jump filter), `BestEffortService` with Strava cross-check, trainers excluded | 6 `BestEffortFinderTest` cases (21.9 km run gives HM at exactly 21097.5 m; fast 5 km inside 10 km; 4.9 km is not 5 km), `personal records are best efforts over the exact distance` |
| 1.1 Interval classification | Q2 | VERIFIED | `SessionClassifier` and `LapClassifier`: at least 2 reps, glitch filter, tempo and strides categories, races excluded, Strava `workout_type` used. `MIN_SPEED_RATIO` 1.15 stays a heuristic (see 2) | 30+ golden cases in `LapClassifierGoldenTest` and `WorkoutStructureTest`, including every probe case from the audit |
| 1.2 Efficiency factor | Q2 | VERIFIED | `EfficiencyFactor.pooled` uses only samples with HR and speed, distance over heartbeats; easy EF excludes races, tempo, strides, trail, treadmill, hard efforts | `EF uses only samples with HR`, `period comparison - EF population, exclusions, verdict`, `an untagged race-effort run is not an easy run` |
| 1.3 Verdict | Q8 | VERIFIED | `EfVerdict`: min 5 easy runs, 3 interval sessions, spread check, wording "indicatie" | `verdict requires minimum sample size`, `verdict threshold and spread`, `four easy runs is not enough` |
| 1.4 150 bpm reference | Q7 | VERIFIED (removed) | no reference-pace code left | `comparison prompt ... has no 150 bpm reference` |
| 1.5 Race predictions | Q6 | VERIFIED | `RacePredictor`: median, rounding, range, min 2 anchors, 3.5-230 min; anchors are races, best efforts of at least 3.5 min and hard whole runs; reps never anchors | `riegel matches the published formula`, `prediction is the median...`, `no prediction from a single anchor...`, `predicted paces slow down with distance`, `no predictions from interval reps alone` |
| 1.6 Local time | Q4 | VERIFIED with one note | `ActivityTime.local` used for display, weekly volume, `DateRange`, monthly trend. Note: the dashboard "last sync" time uses `ZoneId.systemDefault()` (not an activity time; see 2) | `local time uses the activity timezone`, `weekly volume ... local dates`, `date range is inclusive on local dates` |
| 1.7 Weighted averages | Q5 | VERIFIED | `RunAggregates`: distance over time, time-weighted HR and cadence, null when no data | `aggregates are distance and time weighted and null when data is missing` |
| 1.8 AI prompts | Phase 3 in the audit | VERIFIED for the current data | guardrail line in `AiAnalysisService` (line 105) still says the data holds no sleep, HRV or resting HR. This is true while no Garmin data is in prompts. It MUST change in Phase 2 before Garmin data enters a prompt (kept as a Phase 2 entry condition) | `general prompt no longer asks for undeterminable conclusions as fact` |
| 2.1 NaN display | | VERIFIED | aggregates return null, `Format` returns "-" | `aggregates ... null`, `hr is a whole number` |
| 2.2 Pace rounding | | VERIFIED | one `Format.pace` | `pace is rounded...` |
| 2.3 Prompt formatting | | VERIFIED | `Format` used in prompts | `AiPromptTest` |
| 2.4 Resting HR estimate | | VERIFIED (removed) | Karvonen only with a user value | `hr zones only use karvonen with a user resting hr` |
| 2.5 Max HR as fact | | VERIFIED | labelled "schatting", non-overlapping zones | `hr zones are contiguous and non overlapping`, `max hr estimate is the 95th percentile` |
| 2.6 Zone texts | | VERIFIED | neutral descriptions in `HrZones` | none (wording) |
| 2.7 Profile labels | | VERIFIED | "Elite" gone, label marked as app rule | none (wording) |
| 2.8 Threshold sensitivity | covered by 1.1 | UNVERIFIED (see 2) | thresholds are still heuristics | golden cases only |
| 2.9 Sync gaps | | VERIFIED | `SyncStravaDataImpl`: 7-day overlap and 30-day re-check | `sync always looks back at least 30 days for edits`, `activities edited on Strava are updated...` |
| 2.10 State change over GET | | **FIXED 2026-10-25** | `GET /coach/sync` removed (it called `sync`). `GET /sync`, `/fetch-all`, `/backfill-laps` were already POST. The templates already post | new `NoStateChangingGetTest` |
| 2.11 DB password | | ACCEPTED | `${DB_PASSWORD:admin}`, local only, overridable in `.env`. Owner has not asked for more | none |
| 2.12 Period boundaries | | VERIFIED | one inclusive `DateRange` on local dates | `date range is inclusive on local dates` |

## 2. Items that are not "FIXED with a test" and need an owner decision

1. **Heuristic thresholds** (`MIN_SPEED_RATIO` 1.15, glitch, tempo and rep constants, 10 %/15 % tolerances, profile cut-offs, 85 % HRmax hard-effort rule). The audit asked for golden tests, and these exist, but the numbers themselves have no external validation (audit item 2.8, UNVERIFIED). The name-label confusion table from audit 1.1 was not re-run in this session. Propose: accept as labelled heuristics, or ask for a re-run of the confusion table on the production data in a later session.
2. **Dashboard last-sync time** uses the server time zone, not Europe/Brussels. Cosmetic. Propose: accept, or change in a later session.
3. **AI guardrail** (audit 1.8, roadmap Phase A line "Prompt guardrails must match the data"): correct today, becomes wrong when Phase 2 adds Garmin data to prompts. Propose: handled as a Phase 2 entry condition (already in the roadmap, Phase 2 work item 6).
4. **Strava secret** (roadmap Phase A step 4): the script has no fallback. Rotation is not done; the owner accepted this (question 8).

## 3. Changes in this session

- Removed `GET /coach/sync` (`CoachController`).
- Added `runner/src/test/.../frontend/NoStateChangingGetTest.kt`: fails if a GET path in `FrontendController` or `CoachController` contains sync, fetch, backfill or rhr.
- Corrected the 7.1 status here (GARMIN_API.md is not edited: the status list there is a dated snapshot).

No existing Strava test changed. Old and new values: none.

## 4. Strava test impact

All existing tests pass (152 tests in total with the new one, 0 failures, Java 23).

## 5. Exit check (roadmap)

"Every section 7.1 item is FIXED with a passing test, or the owner has explicitly accepted it as OPEN." Open for the owner: the 4 points in section 2. Everything else is VERIFIED or FIXED with tests.

## 6. Owner answers (2026-10-25)

Section 2 points: (1) heuristics accepted as labelled heuristics; (2) server time zone accepted for the last-sync time; (3) AI guardrail update is a Phase 2 entry condition; (4) Strava secret not rotated. These four items are now ACCEPTED. Phase A approval itself is set by the owner.
