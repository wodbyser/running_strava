# Garmin Feature Backlog Progress

Status of every item in `docs/garmin-feature-backlog.md`. Update this file whenever a feature moves status. Nothing here is in scope until the owner moves it into a phase.

Plan and phase rules: `GARMIN_ROADMAP.md`. Session progress: `GARMIN_PROGRESS.md`.

**Last updated:** 2026-10-31 by: thirteenth session (Phase 4 built; its 22 items DONE, phase AWAITING APPROVAL)

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
| 1 | Personal HRV baseline | A | Very high | Phase 2 | DONE | Gate 1: HRV nightly average, weekly average, 5-minute readings, Garmin baseline band all available since 2024-12-20. HRV measure/unit UNVERIFIED (owner decision on labelling) Built 2026-10-25 (`recovery-v1`, `/recovery`), offline tests pass, phase APPROVED. |
| 2 | HRV trend analysis | A | Very high | Phase 2 | DONE | #1. Built 2026-10-25 (`recovery-v1`, `/recovery`), offline tests pass, phase APPROVED. |
| 3 | Resting heart rate analysis | A | Very high | Phase 2 | DONE | Gate 1: daily `restingHeartRate` verified (dailyHeartRate, dailysummaries). Measurement method UNVERIFIED Built 2026-10-25 (`recovery-v1`, `/recovery`), offline tests pass, phase APPROVED. |
| 4 | Recovery Readiness (overall score) | A | Very high | Phase 2 | DONE | #1, #3, #6, #8, components shown Built 2026-10-25 (`recovery-v1`, `/recovery`), offline tests pass, phase APPROVED. Four components, no score, no recent load (load comes with Phase 4 #5, #81). |
| 5 | Training response analysis | A | Very high | Phase 4 | DONE | Load model (Phase 4), #1 Built 2026-10-31 (`load-v1`, `/load/response`, offline tests pass). On real data tempo and race abstain (7 and 6 isolated sessions); long run HRV the first morning clearly lower. Phase AWAITING APPROVAL. |
| 6 | Sleep quality analysis | A | Very high | Phase 2 | DONE | Verified sleep fields (Gate 1) Built 2026-10-25 (`recovery-v1`, `/recovery`), offline tests pass, phase APPROVED. |
| 7 | Sleep debt | A | High | Phase 2 | DONE | #6. Owner input (2026-10-25): sleep need at least 7h30 and enough deep sleep. Garmin also returns its own `sleepNeed` (baseline 480, unit UNVERIFIED). Owner to confirm the exact number and the deep-sleep target Built 2026-10-25 (`recovery-v1`, `/recovery`), offline tests pass, phase APPROVED. |
| 8 | Body Battery trends | A | High | Phase 2 | DONE | Verified Body Battery cadence (Gate 1) Built 2026-10-25 (`recovery-v1`, `/recovery`), offline tests pass, phase APPROVED. |
| 9 | Autonomic stress analysis | A | High | Phase 2 | DONE | Verified stress fields (Gate 1) Built 2026-10-25 (`recovery-v1`, `/recovery`), offline tests pass, phase APPROVED. |
| 10 | Recovery time estimation | A | Very high | Phase 4 | DONE | Load model, #1 to #9 history Built. Real data: easy runs give 'longer than 5 mornings' because of a structural HRV offset (design section 12); other classes abstain. Phase AWAITING APPROVAL. |
| 11 | Early fatigue detection | A | Very high | Phase 4 | DONE | Load model, validation, no diagnosis (section 8) Built (`/load`): six signals, base rate over 12 months, no score. 0 of 6 active on 2026-10-30. Phase AWAITING APPROVAL. |
| 12 | Illness pattern detection | A | Medium | Later | NOT STARTED | Needs strict medical rules (section 8 rule 9) |
| 13 | Rest day effectiveness | A | High | Phase 4 | DONE | Load model, #1, #3 Built. A rest day is a day without a run (owner 2026-10-31); other activities are flagged. Phase AWAITING APPROVAL. |
| 14 | Deload effectiveness | A | Very high | Phase 4 | DONE | Load model, #1, #3 Built. 8 deload weeks detected on real data, summary shown. Phase AWAITING APPROVAL. |
| 15 | Recovery trend forecast | A | High | Phase 4 | DONE | Enough history, #2 Built with a walk-forward check against 'same as today'. On real data it is not better, so the page shows no forecast. Phase AWAITING APPROVAL. |
| 16 | Aerobic efficiency index | B | Very high | Phase 3 | DONE | Audit EF defect fixed (section 7.1) (built 2026-10-30, `performance-v1`, offline tests pass; see `docs/garmin-phase-3-design.md` sections 8 and 13; Phase 3 APPROVED 2026-10-30) |
| 17 | Aerobic efficiency trends | B | Very high | Phase 3 | DONE | #16 (built 2026-10-30, `performance-v1`, offline tests pass; see `docs/garmin-phase-3-design.md` sections 8 and 13; Phase 3 APPROVED 2026-10-30) |
| 18 | Heart rate drift analysis | B | Very high | Phase 3 | DONE | Streams (existing), steady-run detection (built 2026-10-30, `performance-v1`, offline tests pass; see `docs/garmin-phase-3-design.md` sections 8 and 13; Phase 3 APPROVED 2026-10-30) |
| 19 | Aerobic decoupling | B | Very high | Phase 3 | DONE | Streams (existing), #18 (built 2026-10-30, `performance-v1`, offline tests pass; see `docs/garmin-phase-3-design.md` sections 8 and 13; Phase 3 APPROVED 2026-10-30) |
| 20 | Running economy proxy | B | High | Phase 3 | DROPPED | Owner decision 2026-10-31: the efficiency factor (#16, #17) already covers it; no reliable per-second power data |
| 21 | Fitness trend model | B | Very high | Phase 3 | DONE | #16, #17, #22 (owner added to Phase 3 on 2026-10-30; built as four side by side indicators, no score; design section 3.4a) |
| 22 | Threshold estimation | B | Very high | Phase 3 | DONE | Audit defects fixed, validation set (built 2026-10-30, `performance-v1`, offline tests pass; see `docs/garmin-phase-3-design.md` sections 8 and 13; Phase 3 APPROVED 2026-10-30) |
| 23 | VO2max trend validation | B | High | Later | UNAVAILABLE | Gate 1: `vO2MaxValue` is in the Swagger schema but absent from all 448 live activities |
| 24 | Heart rate zone distribution | B | Very high | Phase 3 | DONE | Streams, zones (existing `CoachService` uses estimated max HR) (built 2026-10-30, `performance-v1`, offline tests pass; see `docs/garmin-phase-3-design.md` sections 8 and 13; Phase 3 APPROVED 2026-10-30) |
| 25 | Durability analysis | B | Very high | Phase 3 | DONE | Streams, #18 (built 2026-10-30, `performance-v1`, offline tests pass; see `docs/garmin-phase-3-design.md` sections 8 and 13; Phase 3 APPROVED 2026-10-30) |
| 26 | Long run fatigue analysis | B | Very high | Phase 3 | DONE | Streams, #25, session-type classification (built 2026-10-30, `performance-v1`, offline tests pass; see `docs/garmin-phase-3-design.md` sections 8 and 13; Phase 3 APPROVED 2026-10-30) |
| 27 | Interval performance analysis | B | Very high | Phase 3 | DONE | Interval classifier validated (audit 1.1) (built 2026-10-30, `performance-v1`, offline tests pass; see `docs/garmin-phase-3-design.md` sections 8 and 13; Phase 3 APPROVED 2026-10-30) |
| 28 | Heart rate recovery | B | High | Phase 3 | DONE | Detail data supports it (to verify) (built 2026-10-30, `performance-v1`, offline tests pass; see `docs/garmin-phase-3-design.md` sections 8 and 13; Phase 3 APPROVED 2026-10-30) |
| 29 | Power efficiency analysis | B | High | Phase 3 | DONE | Reliable power data (to verify) (built 2026-10-30, `performance-v1`, offline tests pass; see `docs/garmin-phase-3-design.md` sections 8 and 13; Phase 3 APPROVED 2026-10-30) |
| 30 | Terrain-adjusted performance | B | High | Phase 3 | DONE | Elevation streams, grade (built 2026-10-30, `performance-v1`, offline tests pass; see `docs/garmin-phase-3-design.md` sections 8 and 13; Phase 3 APPROVED 2026-10-30) |
| 31 | Fatigue-adjusted performance | B | Very high | Phase 4 | DONE | Load model, #21 Built: efficiency against the own 90-day level, grouped by prior load and recovery state; no corrected 'fatigue-free' number. Phase AWAITING APPROVAL. |
| 32 | Comparable run finder | B | Very high | Phase 3 | DONE | Session classification, terrain, conditions (built 2026-10-30, `performance-v1`, offline tests pass; see `docs/garmin-phase-3-design.md` sections 8 and 13; Phase 3 APPROVED 2026-10-30) |
| 33 | Internal training load | C | Very high | Phase 4 | DONE | Model choice documented (HR-based or TRIMP) Built: Banister TRIMP per sample, b = 1.92, fixed Garmin zone reference. Spearman 0.91 with Garmin load over 413 runs (agreement check). Phase AWAITING APPROVAL. |
| 34 | External training load | C | Very high | Phase 4 | DONE | Distance, duration, elevation (existing) Built: time, distance, elevation, flat-equivalent distance, hard minutes. Power x duration (kJ, device estimate) is a column in the week table when a week has a run with a power meter (none in the current data). Phase AWAITING APPROVAL. |
| 35 | Acute training load | C | Very high | Phase 4 | DONE | #33 or #34 Built: rolling 7 days (tile, chart line, own band), today shown apart, no risk zone. Phase AWAITING APPROVAL. |
| 36 | Chronic training load | C | Very high | Phase 4 | DONE | #33 or #34, history Built: average week over the last 28 days (tile, chart line). Phase AWAITING APPROVAL. |
| 37 | Fitness-fatigue model | C | Very high | Phase 4 | DONE | #33 to #36, validation before use Built with walk-forward validation. On real data the model is not better than the naive forecasts (error 4.46 against 3.28), so the curve is hidden. Phase AWAITING APPROVAL. |
| 38 | Training monotony | C | High | Phase 4 | DONE | #34 Built: weekly monotony (population SD, hand check 0.788). Phase AWAITING APPROVAL. |
| 39 | Training strain | C | High | Phase 4 | DONE | #38 Built: weekly strain (hand check 197.1), against the own weeks only. Phase AWAITING APPROVAL. |
| 40 | Intensity distribution | C | Very high | Phase 3 | DONE | Zone distribution (#24) (built 2026-10-30, `performance-v1`, offline tests pass; see `docs/garmin-phase-3-design.md` sections 8 and 13; Phase 3 APPROVED 2026-10-30) |
| 41 | Training load progression | C | Very high | Phase 4 | DONE | #35, #36 Built: week-over-week change, change against the 4 weeks before, rank against the own changes, after-break flag. Phase AWAITING APPROVAL. |
| 42 | Recovery-adjusted load | C | Very high | Phase 4 | DONE | #33 to #36, #4 Built: timeline of load and of the number of recovery values outside the own norm, no combined number. Phase AWAITING APPROVAL. |
| 43 | Workout effectiveness | C | Very high | Phase 4 | DONE | #17, #33, session classification Built (efficiency outcome only, no critical speed). 21 usable blocks, no clear relation. Phase AWAITING APPROVAL. |
| 44 | Optimal training frequency | C | High | Phase 4 | DONE | #34, history Built as 'Trainingsfrequentie en reactie' (no optimal frequency). Frequency and volume move together. Phase AWAITING APPROVAL. |
| 45 | Optimal long run duration | C | High | Phase 4 | DONE | #26, #34 Built as 'Lengte van je lange duurloop' (no best length). Two of four groups abstain. Phase AWAITING APPROVAL. |
| 46 | Training consistency | C | High | Phase 3 | DONE | Activity history (existing) (built 2026-10-30, `performance-v1`, offline tests pass; see `docs/garmin-phase-3-design.md` sections 8 and 13; Phase 3 APPROVED 2026-10-30) |
| 47 | Detraining analysis | C | High | Phase 4 | DONE | Load model, #16 Built (efficiency outcome only, no critical speed). 6 episodes, 1 usable, no summary. Phase AWAITING APPROVAL. |
| 48 | Training adaptation lag | C | High | Phase 4 | DONE | #17, #36 Built with a block-permutation threshold. On real data a lag of 2 weeks (0.42 against 0.35) is reported with the association note. Phase AWAITING APPROVAL. |
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
| 81 | Session-type readiness | J (new) | Very high | Phase 4 | DONE | #4, #33, #36, session classification Built (`/load`): five session types, components for or against, four levels, no number. Uses the fixed fallback of 2 or 3 days and says so when the own recovery time is not known. Phase AWAITING APPROVAL. |

## Open questions for the owner

1. ~~Confirm the phase mapping~~ CONFIRMED 2026-10-25. Phase 4 scope CONFIRMED 2026-10-31 (full roadmap list).
2. ~~Who defines "personal sleep need" for #7?~~ ANSWERED 2026-10-25: owner target 7 h 30 (adjustable in config).
3. Is injury history (#63) something you want to record in the app? It needs an input UI. (Later item; history given 2026-10-25.)

## Log

Newest first.

- **2026-10-31:** #20 running economy DROPPED by the owner (covered by the efficiency factor #16, #17; no reliable per-second power).
- **2026-10-31 (thirteenth session):** Phase 4 built (`load-v1`, pages `/load` and `/load/response`). Items 5, 10, 11, 13 to 15, 31, 33 to 39, 41 to 45, 47, 48, 81 DONE (offline tests pass, checked in a browser on real data); notes per item. Phase AWAITING APPROVAL.
- **2026-10-31:** Phase 4 scope confirmed by the owner (full roadmap list). Items 5, 10, 11, 13 to 15, 31, 33 to 39, 41 to 45, 47, 48, 81 IN PROGRESS (design `docs/garmin-phase-4-design.md`, AWAITING APPROVAL, no code).
- **2026-10-30:** Phase 3 APPROVED by the owner after the redesign (see `GARMIN_PROGRESS.md` log, eleventh session). Items 16 to 19, 21, 22, 24 to 30, 32, 40, 46 DONE. Phase 2 items marked as approved (they were approved 2026-10-25; the per-row text was stale).
- **2026-10-24:** Created this file from `docs/garmin-feature-backlog.md`. All 81 items NOT STARTED. Phase mapping proposed, not confirmed.
