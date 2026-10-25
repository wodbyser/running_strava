# Garmin Feature Backlog Progress

Status of every item in `docs/garmin-feature-backlog.md`. Update this file whenever a feature moves status. Nothing here is in scope until the owner moves it into a phase.

Plan and phase rules: `GARMIN_ROADMAP.md`. Session progress: `GARMIN_PROGRESS.md`.

**Last updated:** 2026-10-25 by: Gate 3 session (no item changed status)

Gate 3 note: the Phase 1 sync now stores the inputs for #1, #3, #6, #7 (Garmin sleep need only), #8, #9 (HRV, resting HR, sleep, Body Battery, stress, steps, activity references, HR zones). The Body Battery 180 s series is stored (owner decision 2026-10-25). Stress durations are already in the daily summary and are not stored twice. No analytics were built, so every item stays NOT STARTED.

## Phase mapping (confirmed by owner 2026-10-25)

`GARMIN_API.md` defines Phase 1 (data foundation) and Phase 2 (physiology baselines). Phases 3 to 5 are referenced in the spec (section 3, analytics base) but not defined. The mapping below is a proposal.

| Phase | Scope | Gate to start |
|---|---|---|
| Phase 1 | Garmin data foundation (sync, storage, status) | Gates 0 to 3 approved |
| Phase 2 | Recovery baselines and assessments (section A basics) | Phase 1 approved and data verified |
| Phase 3 | Performance analytics (section B). Needs the audit defects fixed first | Phase 2 approved, audit defects in `GARMIN_PROGRESS.md` fixed or accepted |
| Phase 4 | Training load (section C). New model, no existing code | Phase 2 approved, load model validated |
| Phase 5 | Race prediction and readiness (section D) | Phases 3 and 4 approved |
| Later | Injury module (section E), advanced analytics (section F), longer-term ideas (section G) | Owner decision |

## Status

Status values: NOT STARTED, PLANNED (phase assigned), IN PROGRESS, DONE, BLOCKED (say why), DROPPED (say why).

| # | Feature | Section | Priority | Proposed phase | Status | Blocked by / notes |
|---|---|---|---|---|---|---|
| 1 | Personal HRV baseline | A | Very high | Phase 2 | NOT STARTED | Gate 1: HRV nightly average, weekly average, 5-minute readings, Garmin baseline band all available since 2024-12-20. HRV measure/unit UNVERIFIED (owner decision on labelling) |
| 2 | HRV trend analysis | A | Very high | Phase 2 | NOT STARTED | #1 |
| 3 | Resting heart rate analysis | A | Very high | Phase 2 | NOT STARTED | Gate 1: daily `restingHeartRate` verified (dailyHeartRate, dailysummaries). Measurement method UNVERIFIED |
| 4 | Recovery Readiness (overall score) | A | Very high | Phase 2 | NOT STARTED | #1, #3, #6, #8, components shown |
| 5 | Training response analysis | A | Very high | Phase 4 | NOT STARTED | Load model (Phase 4), #1 |
| 6 | Sleep quality analysis | A | Very high | Phase 2 | NOT STARTED | Verified sleep fields (Gate 1) |
| 7 | Sleep debt | A | High | Phase 2 | NOT STARTED | #6. Owner input (2026-10-25): sleep need at least 7h30 and enough deep sleep. Garmin also returns its own `sleepNeed` (baseline 480, unit UNVERIFIED). Owner to confirm the exact number and the deep-sleep target |
| 8 | Body Battery trends | A | High | Phase 2 | NOT STARTED | Verified Body Battery cadence (Gate 1) |
| 9 | Autonomic stress analysis | A | High | Phase 2 | NOT STARTED | Verified stress fields (Gate 1) |
| 10 | Recovery time estimation | A | Very high | Phase 4 | NOT STARTED | Load model, #1 to #9 history |
| 11 | Early fatigue detection | A | Very high | Phase 4 | NOT STARTED | Load model, validation, no diagnosis (section 8) |
| 12 | Illness pattern detection | A | Medium | Later | NOT STARTED | Needs strict medical rules (section 8 rule 9) |
| 13 | Rest day effectiveness | A | High | Phase 4 | NOT STARTED | Load model, #1, #3 |
| 14 | Deload effectiveness | A | Very high | Phase 4 | NOT STARTED | Load model, #1, #3 |
| 15 | Recovery trend forecast | A | High | Phase 4 | NOT STARTED | Enough history, #2 |
| 16 | Aerobic efficiency index | B | Very high | Phase 3 | NOT STARTED | Audit EF defect fixed (section 7.1) |
| 17 | Aerobic efficiency trends | B | Very high | Phase 3 | NOT STARTED | #16 |
| 18 | Heart rate drift analysis | B | Very high | Phase 3 | NOT STARTED | Streams (existing), steady-run detection |
| 19 | Aerobic decoupling | B | Very high | Phase 3 | NOT STARTED | Streams (existing), #18 |
| 20 | Running economy proxy | B | High | Phase 3 | NOT STARTED | #16, power only if reliable |
| 21 | Fitness trend model | B | Very high | Phase 3 | NOT STARTED | #16, #17, #22 |
| 22 | Threshold estimation | B | Very high | Phase 3 | NOT STARTED | Audit defects fixed, validation set |
| 23 | VO2max trend validation | B | High | Later | UNAVAILABLE | Gate 1: `vO2MaxValue` is in the Swagger schema but absent from all 448 live activities |
| 24 | Heart rate zone distribution | B | Very high | Phase 3 | NOT STARTED | Streams, zones (existing `CoachService` uses estimated max HR) |
| 25 | Durability analysis | B | Very high | Phase 3 | NOT STARTED | Streams, #18 |
| 26 | Long run fatigue analysis | B | Very high | Phase 3 | NOT STARTED | Streams, #25, session-type classification |
| 27 | Interval performance analysis | B | Very high | Phase 3 | NOT STARTED | Interval classifier validated (audit 1.1) |
| 28 | Heart rate recovery | B | High | Phase 3 | NOT STARTED | Detail data supports it (to verify) |
| 29 | Power efficiency analysis | B | High | Phase 3 | NOT STARTED | Reliable power data (to verify) |
| 30 | Terrain-adjusted performance | B | High | Phase 3 | NOT STARTED | Elevation streams, grade |
| 31 | Fatigue-adjusted performance | B | Very high | Phase 4 | NOT STARTED | Load model, #21 |
| 32 | Comparable run finder | B | Very high | Phase 3 | NOT STARTED | Session classification, terrain, conditions |
| 33 | Internal training load | C | Very high | Phase 4 | NOT STARTED | Model choice documented (HR-based or TRIMP) |
| 34 | External training load | C | Very high | Phase 4 | NOT STARTED | Distance, duration, elevation (existing) |
| 35 | Acute training load | C | Very high | Phase 4 | NOT STARTED | #33 or #34 |
| 36 | Chronic training load | C | Very high | Phase 4 | NOT STARTED | #33 or #34, history |
| 37 | Fitness-fatigue model | C | Very high | Phase 4 | NOT STARTED | #33 to #36, validation before use |
| 38 | Training monotony | C | High | Phase 4 | NOT STARTED | #34 |
| 39 | Training strain | C | High | Phase 4 | NOT STARTED | #38 |
| 40 | Intensity distribution | C | Very high | Phase 3 | NOT STARTED | Zone distribution (#24) |
| 41 | Training load progression | C | Very high | Phase 4 | NOT STARTED | #35, #36 |
| 42 | Recovery-adjusted load | C | Very high | Phase 4 | NOT STARTED | #33 to #36, #4 |
| 43 | Workout effectiveness | C | Very high | Phase 4 | NOT STARTED | #17, #33, session classification |
| 44 | Optimal training frequency | C | High | Phase 4 | NOT STARTED | #34, history |
| 45 | Optimal long run duration | C | High | Phase 4 | NOT STARTED | #26, #34 |
| 46 | Training consistency | C | High | Phase 3 | NOT STARTED | Activity history (existing) |
| 47 | Detraining analysis | C | High | Phase 4 | NOT STARTED | Load model, #16 |
| 48 | Training adaptation lag | C | High | Phase 4 | NOT STARTED | #17, #36 |
| 49 | Race performance prediction | D | Very high | Phase 5 | NOT STARTED | Audit race-prediction defect fixed (section 7.1) |
| 50 | Race readiness | D | Very high | Phase 5 | NOT STARTED | #4, #21, #49 |
| 51 | Goal feasibility analysis | D | Very high | Phase 5 | NOT STARTED | #49, owner goal input |
| 52 | Race pace recommendation | D | Very high | Phase 5 | NOT STARTED | #22, #49 |
| 53 | Pacing strategy generator | D | High | Phase 5 | NOT STARTED | #22, #49 |
| 54 | Taper optimization | D | Very high | Phase 5 | NOT STARTED | Race history, #36 |
| 55 | Race day readiness check | D | Very high | Phase 5 | NOT STARTED | #4, #50 |
| 56 | Performance confidence interval | D | Very high | Phase 5 | NOT STARTED | #49 |
| 57 | Race simulation | D | High | Later | NOT STARTED | Needs #49 to #56 |
| 58 | Post-race analysis | D | Very high | Phase 5 | NOT STARTED | #49, race history |
| 59 | Race preparation retrospective | D | Very high | Phase 5 | NOT STARTED | #58, #36 |
| 60 | Load spike detection | E | Very high | Later | NOT STARTED | Separate module, no injury claims (section E) |
| 61 | Impact exposure tracking | E | Very high | Later | NOT STARTED | Separate module |
| 62 | Recovery deficit detection | E | Very high | Later | NOT STARTED | Separate module, #4 |
| 63 | Injury history correlation | E | Very high | Later | NOT STARTED | Owner gave history 2026-10-25 (stress fracture of the shin about 1.5 years ago, one knee dislocation, no current issues). Not recorded in the app. Needs input UI and medical-rules review |
| 64 | Pain and symptoms journal | E | Very high | Later | NOT STARTED | New input UI, medical rules |
| 65 | Return-to-running monitor | E | Very high | Later | NOT STARTED | #64, professional guidance rules |
| 66 | Warning signal detection | E | Very high | Later | NOT STARTED | Must not predict injury (section 8 rule 9) |
| 67 | Training tolerance analysis | E | High | Later | NOT STARTED | Separate module |
| 68 | Bone stress exposure monitor | E | High | Later | NOT STARTED | No bone stress claims |
| 69 | Personal physiological profile | F | Very high | Later | NOT STARTED | Phases 2 to 5 |
| 70 | Training responder analysis | F | Very high | Later | NOT STARTED | #5, #43 |
| 71 | Recovery pattern learning | F | Very high | Later | NOT STARTED | #4, #10 |
| 72 | Performance anomaly detection | F | Very high | Later | NOT STARTED | Phase 3 metrics |
| 73 | Contextual performance explanation | F | Very high | Later | NOT STARTED | Correlation rules (section 8 rule 5) |
| 74 | Historical similarity search | F | Very high | Later | NOT STARTED | #32 |
| 75 | Training block comparison | F | Very high | Later | NOT STARTED | Block detection |
| 76 | Personal best conditions | F | High | Later | NOT STARTED | Audit PB fix, #32 |
| 77 | Adaptation efficiency | F | High | Later | NOT STARTED | #36, #17 |
| 78 | Longitudinal athlete model | F | Very high | Later | NOT STARTED | Phases 2 to 5 |
| 79 | Explainable AI insights | F | Very high | Later | NOT STARTED | Dutch copy-paste prompts; no raw health data to external AI |
| 80 | Automated weekly coaching review | F | Very high | Later | NOT STARTED | Copy-paste AI prompt, phases 2 to 5 |
| 81 | Session-type readiness | J (new) | Very high | Phase 4 | NOT STARTED | #4, #33, #36, session classification |

## Open questions for the owner

1. Confirm the phase mapping above, especially Phases 3 to 5.
2. Who defines "personal sleep need" for #7?
3. Is injury history (#63) something you want to record in the app? It needs an input UI.

## Log

Newest first.

- **2026-10-24:** Created this file from `docs/garmin-feature-backlog.md`. All 81 items NOT STARTED. Phase mapping proposed, not confirmed.
