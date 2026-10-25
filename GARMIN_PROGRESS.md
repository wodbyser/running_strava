# Garmin Progress

Single source of truth for where the project stands. Every agent session reads this file first and updates it last.

- Plan: `GARMIN_ROADMAP.md`
- How to work: `GARMIN_AGENT_PROMPT.md`
- Spec: `GARMIN_API.md`, `GARMIN_API_AGENT_REFERENCE.md`
- Backlog status (81 items): `docs/garmin-feature-backlog-progress.md`

**Last updated:** 2026-10-25 by: seventh session (Phase 2 design doc written, no code)

## Next session starts here

- **Next action:** owner sets the Phase 2 row in the Approvals table to APPROVED (P2-1 to P2-8 answered, design accepted verbally in session).
- **Owner must do first:** change the status cell. The agent may not set APPROVED, even when asked in session. Phase 3 does not start before Phase 2 is approved.

## Approvals

The owner changes the status column. The agent never sets APPROVED itself. The agent only reads this table before starting a gate or phase.

| Gate or phase | Status | Approved on | Owner comment |
|---|---|---|---|
| Gate 0 | APPROVED | | Report in `docs/garmin-sync-design.md` |
| Gate 1 | APPROVED | | Report in `docs/garmin-capability-matrix.md` |
| Gate 2 | APPROVED | | Report in `docs/garmin-data-provenance.md` |
| Gate 3 | APPROVED | | Code, offline tests and the live PostgreSQL verification on a test copy are done. Open items: questions 18 to 20, production not yet touched by Garmin. Design and results in `docs/garmin-sync-design.md` |
| Phase A (audit remediation) | APPROVED | | Report in `docs/garmin-audit-remediation.md`. Owner confirmed in session on 2026-10-25 that Phase 1 is approved and Phase A may start |
| Phase 2 (recovery) | APPROVED | | Design doc only (`docs/garmin-phase-2-design.md`), no code. Owner answered P2-1 to P2-8 (all defaults) and asked in session 2026-10-25 for approval; the agent cannot set APPROVED, so the owner must change this cell |
| Phase 3 (performance) | NOT STARTED | | |
| Phase 4 (load) | NOT STARTED | | |
| Phase 5 (race) | NOT STARTED | | |

Status values: NOT STARTED, IN PROGRESS, AWAITING APPROVAL, APPROVED, REJECTED (say why).

## Current state

- **Current phase:** Phase 2 (recovery), design stage. Phase 1 and Phase A APPROVED (Approvals table).
- **Blocked on:** owner review of `docs/garmin-phase-2-design.md` and questions P2-1 to P2-8. Phase 3 needs Phase 2 approved.

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
| Phase 2: recovery | IN PROGRESS (design written, AWAITING APPROVAL) | `docs/garmin-phase-2-design.md` | Design only. No code, no tests added. Entry condition from Phase A (AI guardrail update) is part of the design, section 5 |
| Phase 3: performance | NOT STARTED | `docs/garmin-phase-3-design.md` | Needs Phase A and Phase 2 approved |
| Phase 4: load | NOT STARTED | `docs/garmin-phase-4-design.md` | Needs Phases 2 and 3 approved |
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

## Handoff for the next session

- **Current:** Phase 2 (recovery), design written, AWAITING APPROVAL. No code started. Phases 3 to 5 not started.
- **Finished:** Fixed stale status text (Phase A is APPROVED per the Approvals table). Wrote `docs/garmin-phase-2-design.md`: common baseline machinery (median, robust SD, windows 7/28/60/90, 70 % minimum data), formulas, abstain rules, labels and tests for backlog #1, #2, #3, #4, #6, #7, #8, #9, code layout, AI guardrail change, hand-computed test cases, open questions P2-1 to P2-8. Nothing built, so no test run and no test counts changed (still 152 offline tests from the last run, not re-run this session).
- **Not done:** all Phase 2 code. Live rejected-key check, Strava `external_id` check, 429 limit (unchanged, see sixth session).
- **Blocked:** the owner setting the Phase 2 Approvals cell to APPROVED. P2-1 to P2-8 are answered (defaults).
- **Left behind:** `/tmp/opencode/prod-before-garmin.sql` (contains the Strava token: delete when no longer needed); stopped containers `running-db`, `running-test-db`.
- **Exact next step:** owner sets the Phase 2 Approvals cell to APPROVED. Then the agent builds Phase 2: SPI read methods and fakes, `core/.../garmin/recovery`, `RecoveryService`, `RecoveryController` and `recovery.html`, conditional AI guardrail, tests, and stops at "AWAITING APPROVAL".

## Log

Newest first. One entry per session: date, gate or phase, result, what changed, what is next.

- **2026-10-25 (seventh session):** Phase 2 design. Result: design doc written, AWAITING APPROVAL, no code. Entry check: Approvals table had Phase A APPROVED while the handoff and the phase status row still said AWAITING; I asked the owner, who gave the go for the Phase 2 design doc. Changed: created `docs/garmin-phase-2-design.md` (algorithm version `recovery-v1`); aligned stale status text in this file; backlog items #1 to #4, #6 to #9 marked IN PROGRESS (design only). Scope note: backlog #4 mentions recent load; no load model exists until Phase 4, so the design proposes four components without load (question P2-2). No code, no tests, no Strava tests touched; build not re-run. No key or token handled, no network call. Next: owner review of the design.
- **2026-10-25 (sixth session):** Gate 3 closed, Phase A. Result: Phase A AWAITING APPROVAL. Entry check: Approvals table said Gate 3 APPROVED while other blocks said AWAITING; the owner confirmed APPROVED in session, and also confirmed Phase 1 approved and that Phase A may start. Owner answers: Q18 pause 2 s, Q19 keep limits, Q20 730-day backfill. Changes: backfill pause default 2 s (3 places); `GET /coach/sync` removed; `NoStateChangingGetTest` added; `docs/garmin-audit-remediation.md` written. Production: pg_dump first, app started, `POST /garmin/backfill`, state OK, 0 failed calls (about 7 min), no key in the log, app and database stopped again. Tests: 152 pass, 0 fail, no existing Strava test changed. Next: owner answers question 21 and decides Phase A.
- **2026-10-25 (fifth session):** Gate 3 verification, after the owner answered questions 14 to 17. Result: verified on a PostgreSQL copy, still AWAITING APPROVAL (questions 18 to 20 open). Code: Body Battery series (`chunks=480`, merged duplicates, nulls dropped), retry delays 1 s and 4 s, 403 as FORBIDDEN, position difference as supporting component, `Retry-After` value in 429 messages, one log line per call that fails after retries, key-sorted raw payload text (Garmin changes key order between calls). Offline tests: 151 pass, 0 fail. Live (test copy `running-test-db`, port 5433, both crons off, no Strava call): schema OK, 2 syncs plus 4 backfills idempotent, one 429 episode (3 calls) in the 1 s pause run, clean with a 2 s pause. Dry run: 440 MATCHED, 0 REVIEW, 8 UNMATCHED. Production database untouched, Strava tables hash-identical before and after. No key in logs or files. Raw responses deleted. Details: `docs/garmin-sync-design.md`, `docs/garmin-data-provenance.md` section 12. Next: owner review and decisions.

- **2026-10-25 (fourth session):** Gate 3. Result: code complete, AWAITING APPROVAL, live checks pending. Entry check: Approvals table had Gate 2 APPROVED (the gate status table was stale and is aligned). Wrote the Gate 3 design first (decisions D1 to D11). Built core (`com.running.garmin`: records, SPI, `GarminRetry`, `GarminRangeSync`, sync and backfill use cases, `GarminActivityMatcher` with report) and runner (properties, token provider, `GarminApiClientImpl`, redactor, 11 JPA tables incl. `garmin_sync_status`, coordinator with single-run lock, scheduler, controller, wiring in `AppConfig` and `GarminConfig`, `garmin:` block in `application.yml`). Strava: nullable `external_id` and `device_name` (parser, entity, repository keeps stored value when a summary has none). Tests: 144 pass, 0 fail (`:core:test :runner:test`, Java 23, offline: fakes, MockRestServiceServer, H2). No existing Strava test changed (no old or new values to list). Checked the key is in no committed or doc file (presence and length only). No live Garmin call was made; no raw response, no real identifier added. Next: owner manual checks, answers to questions 14 to 17, Gate 3 approval.

- **2026-10-25 (third session):** Gate 2. Result: complete, AWAITING APPROVAL. Gate 1 was approved by owner. Wrote `docs/garmin-data-provenance.md`. Match rule: type family plus start, distance, elapsed-time tiers; no Strava ID in Garmin data, so a shared-ID step is conditional on a Strava `external_id` check (UNVERIFIED). Owner answered open questions 10 to 13, sleep targets, and the three Gate 2 points; recorded in `docs/garmin-sync-design.md`. No code, no Strava tests touched. Next: owner approves Gate 2, then Gate 3.

- **2026-10-25 (second session):** Gate 1. Result: complete, AWAITING APPROVAL. Live token exchange OK (24 h lifetime). About 200 sequential GET calls, no 429. Found range limits (stress/steps 28 d, sleep/Body Battery 31 d, daily summaries 1 y), search `limit` max between 175 and 198 (500 above), data starts 2024-12-19. Swagger loads (OpenAPI 3.0.1, v1.0.0, embedded in page script). No Strava identifier in any Garmin field. VO2max documented in schema but never returned. Three ZIPs: one 1 Hz `.fit` each. Created: `docs/garmin-field-inventory.md`, `docs/garmin-capability-matrix.md`, 22 fixtures in `runner/src/test/resources/garmin/`, Gate 1 section in `docs/garmin-sync-design.md`; notes in the backlog progress file. Raw responses, token, ZIPs were kept in `/tmp/opencode` only and deleted. Offline build still passes (no code or Strava test changed). Owner answered open questions 1 to 9 in session. Next: owner approves Gate 1, then Gate 2.

- **2026-10-25:** Gate 0. Result: pass. `:core:test :runner:test` on Java 23 BUILD SUCCESSFUL. Key is present in git-ignored `.env`, absent from the shell environment (presence only checked, nothing printed). No network call made. Changed: created `docs/garmin-sync-design.md` (Gate 0 section), updated this file. No code or Strava tests changed. Next: owner approves Gate 0, then Gate 1.

- **2026-10-24:** Planning. Reviewed Garmin spec and client source. Fixed spec issues. Chose defaults (see `GARMIN_AGENT_PROMPT.md`). Added backlog item 81 (session-type readiness). Created the backlog progress file. Created `GARMIN_ROADMAP.md` with all phases, including the audit remediation step (Phase A). No code written.
