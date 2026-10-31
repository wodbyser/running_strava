# Garmin Roadmap

The full plan from today to "everything built". Every agent session follows this file together with `GARMIN_PROGRESS.md` (where we are) and `GARMIN_AGENT_PROMPT.md` (how to work). Nothing here overrides the rules in `GARMIN_API.md`.

## How the phases fit together

```
Phase 1 (Gates 0 to 3): Garmin data foundation
   |
Phase A: audit remediation (existing analytics made correct)   <- required before Phase 3
   |
Phase 2: recovery baselines and assessment
   |
Phase 3: performance analytics                                   <- needs Phase A
   |
Phase 4: training load and recovery-adjusted analysis
   |
Phase 5: race prediction and readiness                           <- needs Phases 3 and 4
   |
Later: injury module, advanced analytics, longer-term ideas     <- owner decision per item
```

Phase 2 does not depend on the existing performance code (`GARMIN_API.md` section 3, "Analytics base"). Phase A is needed before Phase 3 and Phase 5 because those build on the defective calculations.

The phase mapping for the backlog is in `docs/garmin-feature-backlog-progress.md`. **It is a proposal. The owner has not confirmed it yet.**

## Rules for every phase

1. **Entry:** the previous phase is approved in `GARMIN_PROGRESS.md` (Approvals table). The owner has not confirmed Phases 3 to 5 yet. Ask before starting them.
2. **Design first:** write the phase design doc before code. Each metric lists its formula, assumptions, minimum data, algorithm version, and validation (`GARMIN_API.md` section 8).
3. **Build:** code and tests, offline only. Strava tests must still pass.
4. **Exit:** all exit criteria below pass. The agent writes a report and marks the phase "AWAITING APPROVAL". It does not start the next phase.
5. **Approval:** only the owner marks a phase APPROVED in `GARMIN_PROGRESS.md`.
6. **UI:** every page follows `docs/ui-guidelines.md` (owner feedback after Phases 2 and 3): the conclusion first, charts with reference bands over long tables, plain Dutch with `?` explanations for hard terms only, Dutch dates, every period user-adjustable with the documented default, checked in a browser on real data before the phase is reported.

## Phase 1: Garmin data foundation (Gates 0 to 3)

Detailed rules: `GARMIN_API.md` sections 5 and 6, `GARMIN_API_AGENT_REFERENCE.md`.

| Gate | Work | Needs |
|---|---|---|
| Gate 0 | Key present (presence only), Java 23 build passes, record in `docs/garmin-sync-design.md` | Owner: key in environment |
| Gate 1 | Live fixtures for in-scope endpoints (rows 1, 2, 8 to 15, 18 to 24), 7 days. Field inventory, capability matrix, pagination probe, answers to section 9 | Owner: key and network |
| Gate 2 | `docs/garmin-data-provenance.md` (identity matching design) | Gate 1 approved |
| Gate 3 | Code: core SPI and models, runner adapters, scheduler, controller, status, tests | Gate 2 approved |

**Exit criteria (Phase 1):** acceptance criteria 1 to 7 in `GARMIN_API.md` section 6.3, each with a test or a documented manual check. Status is shown on `GET /garmin/status`.

## Phase A: audit remediation

Source: `docs/audit/phase-1-report.md` and `GARMIN_API.md` section 7.1. Each item needs a decision from the audit (Q1 to Q9, already answered on 2026-09-25) and a golden test.

Work, in this order:
1. Re-verify each section 7.1 item in code. Write the current status to `docs/garmin-audit-remediation.md`. Mark each item VERIFIED, FIXED, OPEN, or UNVERIFIED.
2. Fix OPEN items that the audit decided on:
   - Efficiency factor: only items with both HR and speed, time-weighted, excluding races and tempo runs (Q2, audit 1.2).
   - PBs: fastest segment over the exact distance from streams, cross-checked with Strava best efforts (Q1, audit 0.4).
   - Race predictions: continuous efforts only, median, rounded, shown as a range; no prediction with too few anchors (Q6).
   - Interval classification: at least 2 reps, glitch filter, tempo category, races excluded from easy EF (Q2).
   - Local time everywhere (Q4), weighted averages (Q5), 150 bpm reference pace removed (Q7), verdict minimum n (Q8).
   - Prompt guardrails in `AiAnalysisService` must match the data (no "not in the data" claims once Garmin is present).
3. Golden tests for every case in the audit. The Strava regression tests must pass. A Strava test may change only when a documented audit decision requires it, with the old and new values listed in `docs/garmin-audit-remediation.md`.
4. Owner decision: the Strava client secret. Check that `test-strava-connection.sh` has no fallback secret (it has none as of 2026-10-24). Rotation is an owner action; record whether it was done.

**Exit criteria:** every section 7.1 item is FIXED with a passing test, or the owner has explicitly accepted it as OPEN. Owner approval.

## Phase 2: recovery baselines and assessment

Backlog items: #1 HRV baseline, #2 HRV trend, #3 resting HR analysis, #4 recovery readiness (overall score), #6 sleep quality, #7 sleep debt (needs owner's sleep need), #8 Body Battery trends, #9 stress analysis.

Spec basis: `GARMIN_API.md` section 2.2 (Phase 2 scope) and section 3 (analytics base).

Work:
1. Design doc `docs/garmin-phase-2-design.md`. For each metric: formula, minimum data, abstain rule with counts, labels (`[gemeten]`, `[berekend]`, `[app-schatting]`, `[onbeschikbaar]`), algorithm version.
2. Personal baselines: rolling 7, 28, 60, and 90 days (backlog #1). Deviations in both directions (section 8 rule 7).
3. Readiness (#4) shows its components. No unexplained 0 to 100 score (section 8 rule 6).
4. Sleep stages, Body Battery, and stress labelled as Garmin estimates (section 4.3).
5. Recovery dashboard page in Dutch, using the existing Thymeleaf layout.
6. AI prompt: Garmin data may appear in prompts only after the guardrail lines are updated (`GARMIN_API.md` section 3, AI row).
7. Tests: offline, personal-baseline calculations with hand-computed values, abstain cases.

**Exit criteria:** design doc approved; each metric has tests for normal, abstain, and deviation cases; no health data in logs; owner approval.

## Phase 3: performance analytics

Backlog items: #16 to #19, #22, #24 to #30, #32, #46, and #40 (intensity distribution, which needs zones from #24).

Needs Phase A approved. Every metric uses the corrected calculations.

Note (2026-10-30): the owner added #21 (fitness trend model) to this phase in session; scope list above is unchanged by the agent. #20 stays out. See `docs/garmin-phase-3-design.md`.

Status (2026-10-30): built, redesigned after owner review, APPROVED by the owner. Post-build changes in `docs/garmin-phase-3-design.md` section 13.

Work:
1. Design doc `docs/garmin-phase-3-design.md`. Define "steady run", session types, and the minimum sample size per metric.
2. Metrics with a formula, a sample-size rule, and a validation set. Show trends, not single-run verdicts, unless the sample is large enough.
3. Comparable-run finder (#32) matches distance, terrain, and intensity. Refuse comparisons that mix incompatible conditions (section 8 rule 3).
4. Tests: golden sets, hand-computed values, abstain cases.

**Exit criteria:** as Phase 2. Also each metric shows its sample counts and the "association, not cause" note where it shows a relationship.

## Phase 4: training load and recovery-adjusted analysis

Backlog items: #5, #10, #11, #13, #14, #15, #31, #33 to #39, #41 to #45, #47, #48, #81 (session-type readiness).

Needs Phases 2 and 3 approved (both APPROVED 2026-10-30). The owner must still confirm the Phase 4 scope before the design starts. The load model is new (no existing code).

Status (2026-10-31): scope confirmed by the owner (full list above, unchanged). Design `docs/garmin-phase-4-design.md` written and APPROVED by the owner (2026-10-31). Built the same day (`load-v1`, pages `/load` and `/load/response`), AWAITING APPROVAL. Note: build deviations are in design section 11 (for example critical speed is not part of #43 and #47), the model validation report is section 12, and the first-render speed question is open for the owner.

Work:
1. Design doc `docs/garmin-phase-4-design.md`. Choose and document the internal load model (HR-based or TRIMP variant, or session RPE). Document the fitness-fatigue model and validate it before use (#37).
2. Caution from the backlog: no single formula decides how much to train. The acute:chronic ratio is not a validated injury predictor.
3. Session-type readiness (#81): per session type, show the components, sample counts, and "insufficient data" where needed.
4. Tests: temporal validation (section 8 rule 10): no future data in training, held-out periods for evaluation.

**Exit criteria:** as Phase 2. Model validation report included. Owner approval.

## Phase 5: race prediction and readiness

Backlog items: #49 to #56, #58, #59. Item #57 (race simulation) is "Later".

Needs Phases 3 and 4 approved and Phase A complete.

Work:
1. Design doc `docs/garmin-phase-5-design.md`.
2. Race predictions as ranges (#56), with continuous-effort anchors only (Phase A Q6).
3. Readiness (#50, #55) combines fitness and recovery and keeps them separate.
4. Post-race analysis (#58) compares predicted and actual, with deviations explained.
5. Tests: paces increase monotonically with distance; hand-computed Riegel values; no prediction with too few anchors.

**Exit criteria:** as Phase 2. Owner approval.

## Later (owner decision per item)

- **Injury module (E, #60 to #68):** a separate module. Never state someone will get injured or is safe to train (section 8 rule 9). Pain and symptoms outweigh any score (section 8 rule 8). Needs owner decisions on the input UI and on injury history (see `docs/garmin-feature-backlog-progress.md`).
- **Advanced analytics (F, #69 to #80):** depends on Phases 2 to 5.
- **Longer-term ideas (G):** not scheduled.
- **Items with open prerequisites:** #12 (illness, needs strict medical rules), #23 (VO2max, UNAVAILABLE until proven), #57 (race simulation).

Each Later item needs its own design doc and owner approval before work starts.
