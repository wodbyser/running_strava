# Garmin Progress

Single source of truth for where the project stands. Every agent session reads this file first and updates it last.

- Plan: `GARMIN_ROADMAP.md`
- How to work: `GARMIN_AGENT_PROMPT.md`
- Spec: `GARMIN_API.md`, `GARMIN_API_AGENT_REFERENCE.md`
- Backlog status (81 items): `docs/garmin-feature-backlog-progress.md`

**Last updated:** 2026-10-31 by: thirteenth session (Phase 4 built, tests pass, checked in a browser on real data; AWAITING APPROVAL)

## Next session starts here

- **Next action:** Phase 4 (training load) is BUILT and AWAITING APPROVAL by the owner. Do not start Phase 5 until the owner sets the Phase 4 cell to APPROVED. If the owner asks for changes, apply them to `docs/garmin-phase-4-design.md` first (sections 11 and 12 list the build decisions and the validation report), then to the code. Read `docs/ui-guidelines.md` before any UI work.

## Approvals

The owner changes the status column. The agent never sets APPROVED itself. The agent only reads this table before starting a gate or phase.

| Gate or phase | Status | Approved on | Owner comment |
|---|---|---|---|
| Gate 0 | APPROVED | | Report in `docs/garmin-sync-design.md` |
| Gate 1 | APPROVED | | Report in `docs/garmin-capability-matrix.md` |
| Gate 2 | APPROVED | | Report in `docs/garmin-data-provenance.md` |
| Gate 3 | APPROVED | | Code, offline tests and the live PostgreSQL verification on a test copy are done. Open items: questions 18 to 20, production not yet touched by Garmin. Design and results in `docs/garmin-sync-design.md` |
| Phase A (audit remediation) | APPROVED | | Report in `docs/garmin-audit-remediation.md`. Owner confirmed in session on 2026-10-25 that Phase 1 is approved and Phase A may start |
| Phase 2 (recovery) | APPROVED | | Design was APPROVED (owner set the cell, confirmed in session 2026-10-25 that it also covers building the code). Code and offline tests done (229 pass; owner asked for sync button and week/month view in session, see log). Report: `docs/garmin-phase-2-design.md` sections 7 and 10. Page redesigned and periods made adjustable at the owner's request on 2026-10-30 (section 11); approval unchanged |
| Phase 3 (performance) | APPROVED | 2026-10-30 | Approved by the owner in session 2026-10-30 after his review and the redesign he asked for (the agent edited the cell on the owner's written instruction: "approve everything of phase 3"). Scope: roadmap list plus #21 (owner addition). Answers P3-1 to P3-11 recorded below. Design and post-build changes: `docs/garmin-phase-3-design.md` sections 12 and 13 |
| Phase 4 (load) | AWAITING APPROVAL | 2026-10-31 (design) | Design approved by the owner 2026-10-31 (the agent edited that cell on his written instruction: "Je mag het plan goedkeuren"). Code built the same day (`load-v1`, `/load`, `/load/response`), 454 offline tests pass, checked in a browser on real data. The owner chose the background warm-up for the first-render speed (design section 11). Scope: full roadmap list, answers P4-1 to P4-12 below. Report: `docs/garmin-phase-4-design.md` sections 8, 11 and 12. The owner sets APPROVED himself |
| Phase 5 (race) | NOT STARTED | | |

Status values: NOT STARTED, IN PROGRESS, AWAITING APPROVAL, APPROVED, REJECTED (say why).

## Current state

- **Current phase:** Phase 4 (training load): built, AWAITING APPROVAL (design APPROVED 2026-10-31). Phase 1, Phase A, Phase 2 and Phase 3 APPROVED.
- **Blocked on:** the owner's approval of Phase 4 (and his reading of the real-data results in `docs/garmin-phase-4-design.md` section 12).

## Phase 1 gate status

| Gate | Status | Notes |
|---|---|---|
| Gate 0: environment check | DONE (approved) | Build passes; key in `.env`. Result in `docs/garmin-sync-design.md` |
| Gate 1: fixtures and capability discovery | DONE (approved) | See `docs/garmin-capability-matrix.md` |
| Gate 2: identity matching design | DONE (approved) | `docs/garmin-data-provenance.md`; owner answered its 3 open points |
| Gate 3: Phase 1 implementation | DONE (approved) | Code and offline tests pass (152 now). Verified live on a PostgreSQL copy: schema, token, syncs, idempotency, match dry run. First production backfill done 2026-10-25 (see log) |

## Phase 1 deliverables

| Item | Status | Location | Notes |
|---|---|---|---|
| `docs/garmin-sync-design.md` | DONE | `docs/` | Gates 0, 1, 3 sections. Gate 3 lists decisions D1 to D11 for owner review. Verification results appended 2026-10-25 |
| `docs/garmin-field-inventory.md` | DONE | `docs/` | Gate 1. Units marked DOCUMENTED / OBSERVED / UNVERIFIED |
| `docs/garmin-capability-matrix.md` | DONE | `docs/` | Gate 1 |
| `docs/garmin-data-provenance.md` | DONE | `docs/` | Gate 2. Thresholds are proposals until the Gate 3 dry run |
| Sanitized fixtures | DONE | `runner/src/test/resources/garmin/` | 22 files, identifiers replaced, checked against the raw responses; no key, JWT, ZIP or FIT committed |
| SPI `GarminApiClient` | DONE | `core/.../garmin/spi` | Tested offline |
| Domain models (HRV, sleep, resting HR, Body Battery, stress, activity reference) | DONE | `core/.../garmin/domain` | Tested offline. Also steps and heart rate zone records |
| Use cases `BackfillGarminData`, `SyncGarminData` | DONE | `core/.../garmin/usecase` | Tested offline |
| Repository SPI for Garmin records | DONE | `core/.../garmin/spi` | Tested offline |
| `GarminApiClientImpl` | DONE | `runner/.../garmin/adapter` | Tested offline |
| `GarminTokenProvider` | DONE | `runner/.../garmin/adapter` | Tested offline |
| JPA entities and repositories | DONE | `runner/.../garmin/db` | Tested offline |
| `GarminSyncScheduler` | DONE | `runner/.../garmin/scheduler` | Tested offline |
| `GarminSyncController` | DONE | `runner/.../garmin/web` | Tested offline. Also read-only `GET /garmin/matches/review` and `/matches/report` |
| `GarminProperties` | DONE | `runner/.../config` | Tested offline. Extra keys: see design D10 |
| `GarminActivityMatcher` + match report (dry run) | DONE | `core/.../garmin/matching` | Pure, read-only. Dry run run 2026-10-25 on a production copy: 440 MATCHED, 0 REVIEW, 8 UNMATCHED. Thresholds provisional |
| Body Battery series (`garmin_body_battery_sample`) | DONE | core, runner | Owner decision 2026-10-25. 69 743 samples live. Status and version columns UNVERIFIED and not stored |
| Strava `external_id`, `device_name` columns | DONE | `runner/.../strava` | Nullable, additive. Filled on next Strava fetch |

## Phase 1 acceptance criteria (GARMIN_API.md section 6.3)

| # | Criterion | Status |
|---|---|---|
| 1 | Manual run gets a token; simulated 401 triggers one re-exchange | DONE: real token and 6 syncs on the test copy; simulated 401 gives exactly one re-exchange (`GarminApiClientImplTest`) |
| 2 | Backfill and incremental sync are idempotent | DONE: offline tests, and live on PostgreSQL: 0 added, 0 removed, 0 changed rows, 0 duplicates across repeated sync and backfill runs (design doc, verification section) |
| 3 | Missing fields stored as `null`; status reports the count | DONE (`nullFieldsPerEndpoint`) |
| 4 | Failed day or endpoint retried with backoff; run continues | DONE: offline tests, and observed live: 429 on 3 calls after retries, run continued, status PARTIAL_FAILURE |
| 5 | Status shows last run, last success, records per endpoint, last error category | DONE (`GarminControllerTest`) |
| 6 | Flag off: no Garmin calls. Flag on, no or invalid key: app starts, status reports state | DONE for flag off and no key (`GarminWiring*Test`, full Spring context). Invalid key: AUTH path tested with a mock server; a real rejected key was not tried, so that part is NOT verified live. Flag off and no key were also checked in a full Spring context, not on a running server |
| 7 | Offline tests pass; existing Strava tests pass (changes only per the audit-decision rule, old and new values listed) | DONE: 151 tests, 0 failures. No existing Strava test changed |

## Other phases

| Phase | Status | Design doc | Notes |
|---|---|---|---|
| Phase A: audit remediation | DONE (approved, see Approvals table) | `docs/garmin-audit-remediation.md` | All items VERIFIED or FIXED except 4 owner points. One fix: removed `GET /coach/sync`; new test |
| Phase 2: recovery | DONE, APPROVED | `docs/garmin-phase-2-design.md` | Backlog #1 to #4, #6 to #9 built (`recovery-v1`): `core/.../garmin/recovery`, `RecoveryService`, `RecoveryText`, `RecoveryController` (`/recovery`, `/recovery/data`), `recovery.html`, conditional AI guardrail (`/ai?herstel=true`). 68 new offline tests, plus sync button and week/month view requested by the owner in session. Owner opened the page on real data: first view showed an empty today (sync not run yet) |
| Phase 3: performance | DONE, APPROVED | `docs/garmin-phase-3-design.md` | Built: #16 to #19, #21, #22, #24 to #30, #32, #40, #46 (#21 added by the owner 2026-10-30; #20 out). `performance-v1`, on demand with an in-memory cache, no schema change. #29 activity level only (no power stream). After the owner's review: full redesign, adjustable periods, "Detaildata aanvullen", hilly auto-lap classifier rule (design section 13). 330 offline tests |
| Phase 4: load | DONE (built), AWAITING APPROVAL | `docs/garmin-phase-4-design.md` | Built 2026-10-31: backlog #5, #10, #11, #13 to #15, #31, #33 to #39, #41 to #45, #47, #48, #81 (`load-v1`). Core `com.running.load` (pure), `LoadService`, `LoadController` (`/load`, `/load/response`, `/load/data`), templates `load.html`, `load-response.html`, nav entry "Belasting", link card on `/recovery`, `garmin.load.*` config, background warm-up. 121 new offline tests (454 in total). On real data: the fitness-fatigue model and the recovery forecast fail their walk-forward checks and are hidden (as designed); several items abstain with counts. No schema change |
| Phase 5: race | NOT STARTED | `docs/garmin-phase-5-design.md` | Needs Phases 3 and 4 approved, Phase A complete |
| Later items | NOT STARTED | per item | Owner decision |

## Open questions for the owner

1. ~~Phase mapping~~ CONFIRMED by owner 2026-10-25.
2. ANSWERED 2026-10-25: sleep need at least 7 h 30 and enough deep sleep. Deep sleep: 15 to 20 % of total sleep as indicative reference (app estimate), separate from sleep debt; target total 7 h 30. Answered 2026-10-25.
3. ANSWERED 2026-10-25: history given (shin stress fracture about 1.5 years ago, one knee dislocation, none now). Recording it in the app stays a Later item (needs input UI and medical review).
4. ANSWERED: defaults kept (3 attempts, 1 s / 4 s / 16 s). See open question 10 for the one exception found in Gate 1.
5. ANSWERED: 409, no HTTP calls.
6. ANSWERED: kept, no expiry. See open question 12 about name and image fields in activity payloads.
7. ANSWERED in `docs/garmin-capability-matrix.md` section 10. Unproven items are marked UNVERIFIED (HRV measure and unit, rate limit, exact search limit).
8. ANSWERED: owner wants the Strava secret left as it is. No rotation. Audit item 0.1 is recorded as accepted by the owner.
9. ANSWERED: key may be read from `.env`, never printed.
10. ANSWERED: page size 100, retry rule unchanged.
11. ANSWERED: re-fetch the last 3 days.
12. ANSWERED: drop only profile image URLs, keep the owner name.
13. ANSWERED: label "HRV (Garmin, nachtgemiddelde)", no RMSSD.

Phase 2 design questions P2-1 to P2-8 (details in `docs/garmin-phase-2-design.md` section 8). ALL ANSWERED 2026-10-25 in session, all defaults accepted:

- P2-1: Garmin `<herstel>` block only on request in copy-paste prompts; guardrail conditional.
- P2-2: readiness without recent load accepted (four components, no 0 to 100 score).
- P2-3: sleep debt main number is the shortfall; net balance second.
- P2-4: deviation bands (1 and 2 robust SD) and 70 % minimum data rule kept.
- P2-5: 28 d primary window, 8 h minimum stress time, 10 % unmeasurable sleep rule kept.
- P2-6: Garmin `sleepNeed` shown without unit, marked UNVERIFIED.
- P2-7: Europe/Brussels for sleep clock times, no schema change.
- P2-8: page `/recovery`, nav entry "Herstel".

Phase 3 design questions P3-1 to P3-11 (details in `docs/garmin-phase-3-design.md` section 9). ALL ANSWERED 2026-10-30 in session:

- P3-1: Garmin `RUNNING` zones, fallback app zones, `?zones=` switch.
- P3-2, P3-3: steady-run definition, minimum counts and trend rule kept (config values).
- P3-4: threshold shown as "kritische snelheid", Garmin lactate threshold HR beside it.
- P3-5: power at activity level only; FIT storage stays Later.
- P3-6: Minetti grade-adjusted pace, app estimate.
- P3-7: on-demand computation, in-memory cache, no new table (stop and ask if the overview exceeds 5 s).
- P3-8: pages `/performance`, `/performance/run/{id}`, `/performance/data`, nav "Prestaties", plus a link from `/activity/{id}`.
- P3-9: #21 IN Phase 3 (owner request); #20 stays out. The training load model is Phase 4 (confirmed to the owner).
- P3-10: no prompt change.
- P3-11: three-class intensity grouping as an app grouping.

Phase 4 design questions P4-1 to P4-12 (details in `docs/garmin-phase-4-design.md` section 9). ALL ANSWERED 2026-10-31 in session:

- Scope: full roadmap list (owner chose "Hele lijst zoals roadmap"; no split into 4a/4b, no additions).
- P4-1: Banister TRIMP per sample from the HR stream; Garmin load only as a cross-check.
- P4-2: coefficient 1.92 (men).
- P4-3: fixed HR reference from the Garmin `RUNNING` zone row, applied to all history; fallbacks as in the design.
- P4-4: other sports with HR count in the internal load, shown separately; km running only.
- P4-5 to P4-12: all defaults accepted (7 d against 4 weeks without risk zones; model shown only after validation, no TSB number; pages `/load` and `/load/response`; session classes and hard-session rule; no AI prompt change; automatic deload and detraining detection; isolation rule and minimum counts; session-type readiness with counter-signals, no number).
- Design APPROVED by the owner 2026-10-31.
- Review notes (2026-10-31, to settle at the start of the build, not blocking): (a) count isolated sessions per class on real data (counts only) before building #5, #10, #45, and report if most classes would abstain; (b) ANSWERED 2026-10-31: a rest day for #13 is a day without a run; walks and other sports are allowed but those days are flagged so they can be told apart (update design section 3.10); (c) census inconsistency: 487 runs from Q4 2024 but 480 with HR, correct design section 1.2; (d) #81 must say clearly when it uses the fixed fallback (2 / 3 days) instead of the owner's own recovery time; (e) build and show `/load` before `/load/response` to keep the pages readable.
- Review notes settled at the start of the build (2026-10-31): (a) isolated sessions per class counted on real data (easy 89 of 306, long run 25 of 62, tempo 7 of 50, interval 18 of 83, race 6 of 9): tempo and race abstain in #5, #10 abstains in all classes but easy (design 12.4); (b) rest day = day without a run, already in design 3.10; (c) census corrected (480 of 487 runs from 2024-10 have HR, design 1.2); (d) #81 says when it uses the fixed fallback of 2 or 3 days; (e) `/load` was built and read before `/load/response`.
- Speed (answered 2026-10-31): the first request after a start took 15 to 17 s, later ones 1.5 s. The owner chose the background warm-up (option B); after the change the first request takes 2 s.
- Build decisions the owner may overrule are listed in `docs/garmin-phase-4-design.md` section 11 (critical speed not part of #43 and #47, long-run median per run date, history readiness on own norm only, rest-day reference added, hero colour rule, and others).
- OPEN for the owner (not blocking the approval): (1) #10 for easy runs says "longer than 5 mornings or not measurable" because the owner's HRV lies about 0.3 SD under the 28-day median on every morning after a non-running day too; comparing with the rest-day reference instead of with zero would change results and needs his approval. (2) The Phase 2 day bands (1 and 2 robust SD) make "2 or more recovery values outside the norm" frequent; this is now visible on the "Belasting en herstel" chart. (3) #48 reports a lag of 2 weeks (0.42 against a threshold of 0.35 over 86 weeks) as a finding; he may prefer a stricter threshold.
- #20 running economy: DROPPED by the owner 2026-10-31 (the efficiency factor #16, #17 covers it; no reliable per-second power).

## Handoff for the next session

- **Current:** Phase 4 BUILT, AWAITING APPROVAL by the owner (design APPROVED 2026-10-31). Phases 1, A, 2, 3 APPROVED. Phase 5 NOT STARTED.
- **Finished this session:**
  - Review notes (a) to (e) settled (see open questions); design doc corrected (census) and extended with sections 11 (build notes and deviations, speed) and 12 (model validation report on real data).
  - Core `com.running.load` (pure, Spring-free): `Trimp`, `DailyLoad`, `HardSessions`, `Rolling`, `Ewma`, `OwnBand`, `Weekly` (monotony and strain), `Progression`, `ResponseAnalyzer` (#5, #10, rest reference), `RestDays`, `LongRunDuration`, `Deload`, `FatigueAdjusted`, `RelativeEf`, `BlockEffect`, `Frequency`, `Detraining`, `AdaptationLag`, `FitnessFatigue` (walk-forward validation), `RecoveryForecast`, `FatigueSignals`, `SessionReadiness`, `LoadStats` (seeded bootstrap, Spearman, permutation, OLS).
  - Runner: `LoadService` (on demand, per-activity cache, background warm-up), `LoadPageBuilder`, `LoadResponsePageBuilder`, `LoadWindowSettings` (every period adjustable), `LoadGlossary`, `LoadController`, templates, nav entry, link card on `/recovery`, `garmin.load.*`.
  - Tests: 454 pass, 0 fail (121 new: hand-computed values, abstain and boundary cases, walk-forward and permutation checks, service with fakes, controller in a full context, log capture). No existing test changed; `NoStateChangingGetTest` now also covers `LoadController`.
  - Browser check on real data: second instance on port 8091 (the owner's app on 8080 untouched); both pages read in full with headless Chrome; fixes made after reading (part list layout, empty n < 3 points, axis range for range bars).
  - Real-data validation (design section 12): fitness-fatigue model not better than the naive forecasts (error 4.46 against 3.28, weights stable in 9.5 % of the fits), curve hidden; recovery forecast not better than "same as today", hidden; Garmin cross-check Spearman 0.91 over 413 runs.
  - No Garmin call, no key or token handled or printed, no schema change, no Strava column change, no AI prompt change, no raw data or identifiers committed (synthetic data in tests).
- **UNVERIFIED / for later:** all Phase 4 thresholds and minimum counts are PROPOSALS (`garmin.load.*`), no external validation. How Garmin computes `activityTrainingLoad` is UNVERIFIED (only an agreement check). Phase 3 open points unchanged (HR recovery for jogging rests, "Weekendje Frankrijk").
- **Owner decisions needed:** approval of Phase 4 (set the Approvals cell himself). Optional: the three open points in the open questions list (#10 reference, band frequency, #48 threshold).
- **Blocked:** Phase 5 until the owner approves Phase 4.
- **Left behind:** the database container `running-db` was started for the real-data check and stopped again; `/tmp/opencode` is empty; the second instance on port 8091 is stopped. Nothing is committed (files are in the working tree; some are staged).
- **Exact next step:** wait for the owner's review of Phase 4. If he approves: Phase 5 (race) needs Phases 3 and 4 APPROVED and Phase A complete; start with its design doc `docs/garmin-phase-5-design.md`, no code before it. If he asks for changes: change the design doc first, then the code, rerun `JAVA_HOME=~/.sdkman/candidates/java/23-open ./gradlew :core:test :runner:test`, and check the pages again in a browser on port 8091.

## Log

Newest first. One entry per session: date, gate or phase, result, what changed, what is next.

- **2026-10-31 (thirteenth session):** Phase 4 build. Result: built, AWAITING APPROVAL. Entry check: the Approvals table had Phase 4 `APPROVED (design)` and the handoff and roadmap agreed (design approved, build next). Asked the owner one question in session (first-render speed, see below). Changed: core `com.running.load` (new, pure), runner `LoadService` and friends, pages `/load` and `/load/response` (+ JSON `/load/data`), nav entry "Belasting", link card on `/recovery`, `garmin.load.*` config, `docs/garmin-phase-4-design.md` (census correction, sections 8, 11, 12), backlog progress (22 items DONE), roadmap note, AGENTS.md endpoint row. Tests: 454 pass, 0 fail (121 new); no existing test changed, `NoStateChangingGetTest` extended. Real-data check on a second instance (port 8091): the fitness-fatigue model and the recovery forecast fail their walk-forward checks and are hidden; Garmin cross-check 0.91 (413 runs); isolated-session counts per class recorded (tempo and race abstain). Speed: first request after a start 15 to 17 s against the 5 s rule; the owner chose the background warm-up and it was built (first request now 2 s). Build decisions and deviations (critical speed not in #43 and #47, long-run median per run date, own-norm readiness for history, rest-day reference added, kJ column only with a power meter) are in design section 11. No Garmin call, no key or token handled, no schema change, no AI prompt change. Next: the owner reviews and approves Phase 4.

- **2026-10-31 (twelfth session, follow-up):** Phase 4 design reviewed with the owner and APPROVED by him in session ("Je mag het plan goedkeuren"); the agent set the Approvals cell on that written instruction. Review notes (a) to (e) recorded under open questions for the start of the build; #20 running economy found unscheduled; owner set it to DROPPED. Owner defined a rest day for #13 as a day without a run (other activities allowed, flagged). No code, no tests run. Next: Phase 4 build.

- **2026-10-31 (twelfth session):** Phase 4 design. Result: design written, AWAITING APPROVAL, no code. Entry check: Approvals table had Phases 2 and 3 APPROVED and Phase 4 NOT STARTED; handoff and roadmap agreed (owner scope confirmation needed first). Asked the owner: scope confirmed as the full roadmap list. Ran a read-only, counts-only census on the production database (container started and stopped again; nothing committed). Created `docs/garmin-phase-4-design.md` (`load-v1`). Owner answered P4-1 to P4-12 in session (Banister TRIMP per sample, b = 1.92, fixed Garmin zone HR reference, other sports with HR included, all other defaults) and wants to read the design before approving. Backlog items in scope marked IN PROGRESS (design only). No tests run (no code changed), no Strava test touched, no Garmin call, no key or token handled. Next: owner approval of the design.

- **2026-10-30 (eleventh session):** Phase 3 owner review and follow-up; Phase 3 APPROVED by the owner. Result: all owner requests built, tests 330 pass, 0 fail, no existing Strava test changed. Changed, in order of the owner's requests: (1) `/performance` and `/performance/run/{id}` redesigned (owner feedback: not clear, odd dates, unexplained terms such as GAP, HR-dekking, stroomdata; zone 3/4 abstain unexplained; comparable runs criteria unreadable); (2) "Detaildata aanvullen" (`BackfillStreamsData`, `POST /backfill-streams`) after the owner noticed clustered gaps in the EF chart (227 runs without streams; `/fetch-remaining` never re-fetched them) plus per-run progress logs; (3) every performance period adjustable; (4) interval misclassification of hilly auto-lapped trails fixed with a rule the owner approved after seeing the 10 affected runs (HR-based option rejected by the owner: HR not always reliable); (5) `/recovery` redesigned like Prestaties (hero kept); (6) recovery periods adjustable. Owner feedback recorded as binding rules in `docs/ui-guidelines.md`. Design docs: Phase 3 section 13, Phase 2 section 11. No schema change; one additive repository method (`findIdsWithStreams`). No Garmin call, no key or token handled, no AI prompt change. Next: owner confirms Phase 4 scope.

- **2026-10-30 (tenth session):** Phase 3 build. Result: built, AWAITING APPROVAL. Entry check: Approvals table had Phase 3 APPROVED (design and build) and the handoff agreed. Done in order: data census (counts only, production copy), core code and offline tests, `GarminRepository.findHeartRateZones` (additive), `PerformanceService`, `PerformanceController`, templates, nav entry, link on the activity page, `garmin.performance.*` config. Tests: 315 pass, 0 fail (86 new). No existing Strava test changed; `NoStateChangingGetTest` now also covers `PerformanceController`. Timing on a production copy: 3.7 s first load, 0.3 s cached, so no persisted table. No schema change, no new Garmin call, no key or token handled, no prompt change, no raw data committed (synthetic streams only). Real data check showed 119 steady runs of 530 and that temperature is absent everywhere. Not seen in a browser. Backlog items #16 to #19, #21, #22, #24 to #30, #32, #40, #46 DONE. Next: owner review.
- **2026-10-30 (ninth session, follow-up):** at the owner's request: #21 (fitness trend model) added to Phase 3 as design section 3.4a (four indicators #17, #18, #22, #25 side by side, no score, no weights); questions P3-1 to P3-11 asked in session and answered (all defaults, #20 out, link from the activity page); Phase 3 set to APPROVED on the owner's written instruction (design and build). Confirmed to the owner that the training load model is Phase 4. Roadmap scope list unchanged; #21 recorded here as the owner's addition. Next: build.
- **2026-10-30 (ninth session):** Phase 3 design. Result: design doc written, no code. Entry check: Approvals table had Phase 2 APPROVED and Phase 3 NOT STARTED; the handoff said the owner's scope confirmation was needed, so I asked and the owner confirmed the roadmap scope (#16 to #19, #22, #24 to #30, #32, #40, #46). Note: the prompt file named in the request (`_Agent_prompt.md`) does not exist; I used `GARMIN_AGENT_PROMPT.md`. Changed: created `docs/garmin-phase-3-design.md` (algorithm version `performance-v1`); updated this file; backlog items in scope marked IN PROGRESS (design only). Design facts worth the owner's attention: no power stream exists (Strava streams have no watts, FIT files are not stored), so #29 is activity level only; #22 is a critical speed estimate, not a lactate threshold; #20 and #21 are in the backlog's Phase 3 mapping but not in the roadmap list, so they are left out (P3-9). No code, no tests run (build not re-run), no Strava test touched, no network call, no key or token handled. Next: owner review and approval of the design.

- **2026-10-25 (eighth session, review fixes):** after the agent's own review the owner asked to fix two points. (1) Sleep judged against the owner's target too: `SleepTargetCheck` = average shortfall per night over the nights with data, one band step = 20 minutes per night (PROPOSAL, UNVERIFIED; `SleepAnalysis.TARGET_BAND_MINUTES`), long nights do not offset. Sleep stays one of the four components but the larger of its own-norm deviation and its target deviation decides; the hero shows both bars and the conclusion names the cause ("slaap onder je doel van 7:30 ..."). Sleep also counts when its own norm has too little history but the target check exists. (2) Week and month view got the same conclusion card (period median against the 28 days before the period, same bands and level rules, target check on the period shortfall) and charts per metric (HRV, resting HR, sleep with the target line, Body Battery wake level) with the period median. Shared Thymeleaf fragment `recoveryHero` in `fragments.html`. Tests: 229 pass, 0 fail (5 new). Not seen in a browser by the agent. Still open from the review: day-view thresholds may flag too often (1 SD), red colour for strong deviation, mobile/contrast check. Next: owner review.

- **2026-10-25 (eighth session, hero card):** at the owner's request the day view of `/recovery` now opens with a conclusion card. Owner chose the option without a number (question asked in session: spectrum + conclusion card, no score), so P2-2 and spec section 8 rule 6 stay intact. Changed: `RecoveryText.hero`/`heroLevel` (4 levels: Binnen je eigen norm, Licht afwijkend, Duidelijk afwijkend, Onvoldoende data; rule: 0 deviating = within, 1 deviating (1 to 2 SD) = slight, 2 or more or any 2+ SD = clear, under 3 components with data = insufficient; app estimates, no external validation), per-component spectrum bar (-3 to +3 SD, own norm in the middle), conclusion text with sustained trends and sleep shortfall, collapsible "Hoe komt deze conclusie tot stand?", CSS in `layout.html`, details sections kept below. Tests: 229 pass, 0 fail (4 new). Not seen in a browser by the agent. Next: owner review.

- **2026-10-25 (eighth session, follow-up):** Phase 2 changes at the owner's written request in session (scope note: a sync button and a week/month view go beyond the design doc; the owner asked for both). Cause of the many `[onbeschikbaar]`: today had no Garmin rows yet (sync not run). Changed: `POST /recovery/garmin-update` (button "Garmin synchroniseren" on `/recovery`; same coordinator and lock as `POST /garmin/sync`, async, redirect with flash, no call without key or flag; the old JSON endpoints stay); sync status line on the page; default day falls back to the latest day with data; misleading text "onvoldoende data (n=28)" replaced by "geen waarde voor deze dag"; `view=week|month` (Mon to Sun, calendar month; medians of days with data, 70 % minimum, same number of days of the previous period, differences only, sleep shortfall of the period; `core/.../recovery/Period.kt`). Tests: 229 pass, 0 fail (11 new). No Strava test changed. Next: owner review.

- **2026-10-25 (eighth session):** Phase 2 build. Result: built, AWAITING APPROVAL. Entry check: the Approvals table said APPROVED for Phase 2 while the handoff said the owner still had to set it; I asked, and the owner confirmed in session that he set it and that it covers building the code. Changed: core `com.running.garmin.recovery` (`Baseline`, `Trend`, `MetricAnalyzer`, `HrvAnalysis`, `RestingHrAnalysis`, `SleepAnalysis`, `BodyBatteryAnalysis`, `StressAnalysis`, `ReadinessAnalysis`, `RecoveryAnalyzer`); six read methods on `GarminRepository` (+ fake, + JPA); runner `RecoveryService`, `RecoveryText`, `RecoveryController`, `recovery.html`, nav entry; `GarminProperties.recovery`; `AiAnalysisService` guardrail conditional and `<herstel>` block (`/ai?herstel=true`); `NoStateChangingGetTest` covers the new controller. Build decisions the design left open are in `docs/garmin-phase-2-design.md` section 10. Tests: 209 pass, 0 fail (57 new, offline). No Strava test changed. Backlog #1 to #4, #6 to #9 DONE. No key or token handled, no network call, no schema change. Next: owner review and approval.

- **2026-10-25 (seventh session):** Phase 2 design. Result: design doc written, AWAITING APPROVAL, no code. Entry check: Approvals table had Phase A APPROVED while the handoff and the phase status row still said AWAITING; I asked the owner, who gave the go for the Phase 2 design doc. Changed: created `docs/garmin-phase-2-design.md` (algorithm version `recovery-v1`); aligned stale status text in this file; backlog items #1 to #4, #6 to #9 marked IN PROGRESS (design only). Scope note: backlog #4 mentions recent load; no load model exists until Phase 4, so the design proposes four components without load (question P2-2). No code, no tests, no Strava tests touched; build not re-run. No key or token handled, no network call. Next: owner review of the design.
- **2026-10-25 (sixth session):** Gate 3 closed, Phase A. Result: Phase A AWAITING APPROVAL. Entry check: Approvals table said Gate 3 APPROVED while other blocks said AWAITING; the owner confirmed APPROVED in session, and also confirmed Phase 1 approved and that Phase A may start. Owner answers: Q18 pause 2 s, Q19 keep limits, Q20 730-day backfill. Changes: backfill pause default 2 s (3 places); `GET /coach/sync` removed; `NoStateChangingGetTest` added; `docs/garmin-audit-remediation.md` written. Production: pg_dump first, app started, `POST /garmin/backfill`, state OK, 0 failed calls (about 7 min), no key in the log, app and database stopped again. Tests: 152 pass, 0 fail, no existing Strava test changed. Next: owner answers question 21 and decides Phase A.
- **2026-10-25 (fifth session):** Gate 3 verification, after the owner answered questions 14 to 17. Result: verified on a PostgreSQL copy, still AWAITING APPROVAL (questions 18 to 20 open). Code: Body Battery series (`chunks=480`, merged duplicates, nulls dropped), retry delays 1 s and 4 s, 403 as FORBIDDEN, position difference as supporting component, `Retry-After` value in 429 messages, one log line per call that fails after retries, key-sorted raw payload text (Garmin changes key order between calls). Offline tests: 151 pass, 0 fail. Live (test copy `running-test-db`, port 5433, both crons off, no Strava call): schema OK, 2 syncs plus 4 backfills idempotent, one 429 episode (3 calls) in the 1 s pause run, clean with a 2 s pause. Dry run: 440 MATCHED, 0 REVIEW, 8 UNMATCHED. Production database untouched, Strava tables hash-identical before and after. No key in logs or files. Raw responses deleted. Details: `docs/garmin-sync-design.md`, `docs/garmin-data-provenance.md` section 12. Next: owner review and decisions.

- **2026-10-25 (fourth session):** Gate 3. Result: code complete, AWAITING APPROVAL, live checks pending. Entry check: Approvals table had Gate 2 APPROVED (the gate status table was stale and is aligned). Wrote the Gate 3 design first (decisions D1 to D11). Built core (`com.running.garmin`: records, SPI, `GarminRetry`, `GarminRangeSync`, sync and backfill use cases, `GarminActivityMatcher` with report) and runner (properties, token provider, `GarminApiClientImpl`, redactor, 11 JPA tables incl. `garmin_sync_status`, coordinator with single-run lock, scheduler, controller, wiring in `AppConfig` and `GarminConfig`, `garmin:` block in `application.yml`). Strava: nullable `external_id` and `device_name` (parser, entity, repository keeps stored value when a summary has none). Tests: 144 pass, 0 fail (`:core:test :runner:test`, Java 23, offline: fakes, MockRestServiceServer, H2). No existing Strava test changed (no old or new values to list). Checked the key is in no committed or doc file (presence and length only). No live Garmin call was made; no raw response, no real identifier added. Next: owner manual checks, answers to questions 14 to 17, Gate 3 approval.

- **2026-10-25 (third session):** Gate 2. Result: complete, AWAITING APPROVAL. Gate 1 was approved by owner. Wrote `docs/garmin-data-provenance.md`. Match rule: type family plus start, distance, elapsed-time tiers; no Strava ID in Garmin data, so a shared-ID step is conditional on a Strava `external_id` check (UNVERIFIED). Owner answered open questions 10 to 13, sleep targets, and the three Gate 2 points; recorded in `docs/garmin-sync-design.md`. No code, no Strava tests touched. Next: owner approves Gate 2, then Gate 3.

- **2026-10-25 (second session):** Gate 1. Result: complete, AWAITING APPROVAL. Live token exchange OK (24 h lifetime). About 200 sequential GET calls, no 429. Found range limits (stress/steps 28 d, sleep/Body Battery 31 d, daily summaries 1 y), search `limit` max between 175 and 198 (500 above), data starts 2024-12-19. Swagger loads (OpenAPI 3.0.1, v1.0.0, embedded in page script). No Strava identifier in any Garmin field. VO2max documented in schema but never returned. Three ZIPs: one 1 Hz `.fit` each. Created: `docs/garmin-field-inventory.md`, `docs/garmin-capability-matrix.md`, 22 fixtures in `runner/src/test/resources/garmin/`, Gate 1 section in `docs/garmin-sync-design.md`; notes in the backlog progress file. Raw responses, token, ZIPs were kept in `/tmp/opencode` only and deleted. Offline build still passes (no code or Strava test changed). Owner answered open questions 1 to 9 in session. Next: owner approves Gate 1, then Gate 2.

- **2026-10-25:** Gate 0. Result: pass. `:core:test :runner:test` on Java 23 BUILD SUCCESSFUL. Key is present in git-ignored `.env`, absent from the shell environment (presence only checked, nothing printed). No network call made. Changed: created `docs/garmin-sync-design.md` (Gate 0 section), updated this file. No code or Strava tests changed. Next: owner approves Gate 0, then Gate 1.

- **2026-10-24:** Planning. Reviewed Garmin spec and client source. Fixed spec issues. Chose defaults (see `GARMIN_AGENT_PROMPT.md`). Added backlog item 81 (session-type readiness). Created the backlog progress file. Created `GARMIN_ROADMAP.md` with all phases, including the audit remediation step (Phase A). No code written.
