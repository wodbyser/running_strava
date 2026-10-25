# Garmin Progress

Single source of truth for where the project stands. Every agent session reads this file first and updates it last.

- Plan: `GARMIN_ROADMAP.md`
- How to work: `GARMIN_AGENT_PROMPT.md`
- Spec: `GARMIN_API.md`, `GARMIN_API_AGENT_REFERENCE.md`
- Backlog status (81 items): `docs/garmin-feature-backlog-progress.md`

**Last updated:** 2026-10-25 by: Gate 3 session (implementation and offline tests; live manual checks still open)

## Next session starts here

- **Next action:** owner runs the three manual checks listed in the Handoff (live token and sync, match dry run, DDL on PostgreSQL), reads the results, and sets Gate 3 to APPROVED, or reports what is wrong.
- **Owner must do first:** the manual checks, then review Gate 3 and the design decisions D1 to D11 in `docs/garmin-sync-design.md`.

## Approvals

The owner changes the status column. The agent never sets APPROVED itself. The agent only reads this table before starting a gate or phase.

| Gate or phase | Status | Approved on | Owner comment |
|---|---|---|---|
| Gate 0 | APPROVED | | Report in `docs/garmin-sync-design.md` |
| Gate 1 | APPROVED | | Report in `docs/garmin-capability-matrix.md` |
| Gate 2 | APPROVED | | Report in `docs/garmin-data-provenance.md` |
| Gate 3 | AWAITING APPROVAL | | Code and offline tests done. Live checks pending (see Handoff). Design in `docs/garmin-sync-design.md` (Gate 3 section) |
| Phase A (audit remediation) | NOT STARTED | | |
| Phase 2 (recovery) | NOT STARTED | | |
| Phase 3 (performance) | NOT STARTED | | |
| Phase 4 (load) | NOT STARTED | | |
| Phase 5 (race) | NOT STARTED | | |

Status values: NOT STARTED, IN PROGRESS, AWAITING APPROVAL, APPROVED, REJECTED (say why).

## Current state

- **Current phase:** Phase 1, Gate 3 (implementation) finished in code, AWAITING APPROVAL. Gates 0 to 2 APPROVED.
- **Blocked on:** owner approval of Gate 3. Phase A needs Phase 1 approved.

## Phase 1 gate status

| Gate | Status | Notes |
|---|---|---|
| Gate 0: environment check | DONE (approved) | Build passes; key in `.env`. Result in `docs/garmin-sync-design.md` |
| Gate 1: fixtures and capability discovery | DONE (approved) | See `docs/garmin-capability-matrix.md` |
| Gate 2: identity matching design | DONE (approved) | `docs/garmin-data-provenance.md`; owner answered its 3 open points |
| Gate 3: Phase 1 implementation | AWAITING APPROVAL | Code and 144 offline tests pass. Live token check, match dry run and PostgreSQL start are manual and NOT done |

## Phase 1 deliverables

| Item | Status | Location | Notes |
|---|---|---|---|
| `docs/garmin-sync-design.md` | DONE | `docs/` | Gates 0, 1, 3 sections. Gate 3 lists decisions D1 to D11 for owner review. Dry-run results and live-check results to be appended by the owner session |
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
| `GarminActivityMatcher` + match report (dry run) | DONE | `core/.../garmin/matching` | Pure, read-only. Dry run on real history NOT yet run |
| Strava `external_id`, `device_name` columns | DONE | `runner/.../strava` | Nullable, additive. Filled on next Strava fetch |

## Phase 1 acceptance criteria (GARMIN_API.md section 6.3)

| # | Criterion | Status |
|---|---|---|
| 1 | Manual run gets a token; simulated 401 triggers one re-exchange | IN PROGRESS: simulated 401 test DONE (`GarminApiClientImplTest`). Manual run with the real key NOT done |
| 2 | Backfill and incremental sync are idempotent | DONE (`GarminRangeSyncTest`, `GarminRepositoryImplTest`) |
| 3 | Missing fields stored as `null`; status reports the count | DONE (`nullFieldsPerEndpoint`) |
| 4 | Failed day or endpoint retried with backoff; run continues | DONE (`GarminRetryTest`, `GarminRangeSyncTest`) |
| 5 | Status shows last run, last success, records per endpoint, last error category | DONE (`GarminControllerTest`) |
| 6 | Flag off: no Garmin calls. Flag on, no or invalid key: app starts, status reports state | DONE for flag off and no key (`GarminWiring*Test`, full Spring context). Invalid key: AUTH path tested with a mock server; real rejected key not tried |
| 7 | Offline tests pass; existing Strava tests pass (changes only per the audit-decision rule, old and new values listed) | DONE: 144 tests, 0 failures. No existing Strava test changed |

## Other phases

| Phase | Status | Design doc | Notes |
|---|---|---|---|
| Phase A: audit remediation | NOT STARTED | `docs/garmin-audit-remediation.md` | Needs Phase 1 approved |
| Phase 2: recovery | NOT STARTED | `docs/garmin-phase-2-design.md` | Needs Phase 1 approved |
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

14. OPEN (D1): the agent did not call stress daily (row 1), steps daily (row 2) and the Body Battery report (row 10). `dailysummaries` carries the same daily values. Say if you want the Body Battery 3-minute series or stress durations in seconds stored too.
15. OPEN (D4): "3 attempts" was read as 3 calls in total (waits 1 s, 4 s). Say if you meant 3 retries (4 calls, waits 1 s, 4 s, 16 s). One config value changes it.
16. OPEN (D7, D8, D9): matches are computed on demand and not stored; the position tie-break between 200 m and 2 km counts as Weak; only four Garmin type keys map to Strava types. Confirm after reading the dry run.
17. OPEN: the 403 status is treated as a normal client error (CLIENT), not AUTH, because no live 403 was ever seen (UNVERIFIED).

## Handoff for the next session

- **Current:** Phase 1, Gate 3 finished in code and offline tests, AWAITING APPROVAL. Gates 0 to 2 APPROVED.
- **Finished:** design (`docs/garmin-sync-design.md`, Gate 3 section), core SPI, models, retry, sync and backfill use cases, matcher and dry-run report; runner token provider, RestClient adapter, redactor, JPA tables, status table, coordinator (409), scheduler, controller, properties; nullable Strava columns. 144 offline tests, 0 failures. No existing Strava test changed.
- **Not done (needs the owner, live):** (1) start the app with the real key (`GARMIN_PERSONAL_API_KEY` in `.env`, `./gradlew :runner:bootRun`), call `POST /garmin/backfill?from=2026-10-01&to=2026-10-24`, check `GET /garmin/status`, run it twice and check the record counts stay the same; (2) run a Strava fetch so `external_id` and `device_name` fill, then call `GET /garmin/matches/report` and `/garmin/matches/review` and read the distributions to approve or change the thresholds; (3) check the new tables are created on PostgreSQL (only H2 was exercised).
- **Blocked:** Phase A and everything after it until the owner approves Phase 1. The agent did not run live calls: they write health data into the owner's database.
- **Exact next step:** owner does the manual checks above and decides on questions 14 to 17, then sets Gate 3 (and so Phase 1) to APPROVED. If approved, the next session starts Phase A with `docs/garmin-audit-remediation.md`. Otherwise fix what the owner reports.

## Log

Newest first. One entry per session: date, gate or phase, result, what changed, what is next.

- **2026-10-25 (fourth session):** Gate 3. Result: code complete, AWAITING APPROVAL, live checks pending. Entry check: Approvals table had Gate 2 APPROVED (the gate status table was stale and is aligned). Wrote the Gate 3 design first (decisions D1 to D11). Built core (`com.running.garmin`: records, SPI, `GarminRetry`, `GarminRangeSync`, sync and backfill use cases, `GarminActivityMatcher` with report) and runner (properties, token provider, `GarminApiClientImpl`, redactor, 11 JPA tables incl. `garmin_sync_status`, coordinator with single-run lock, scheduler, controller, wiring in `AppConfig` and `GarminConfig`, `garmin:` block in `application.yml`). Strava: nullable `external_id` and `device_name` (parser, entity, repository keeps stored value when a summary has none). Tests: 144 pass, 0 fail (`:core:test :runner:test`, Java 23, offline: fakes, MockRestServiceServer, H2). No existing Strava test changed (no old or new values to list). Checked the key is in no committed or doc file (presence and length only). No live Garmin call was made; no raw response, no real identifier added. Next: owner manual checks, answers to questions 14 to 17, Gate 3 approval.

- **2026-10-25 (third session):** Gate 2. Result: complete, AWAITING APPROVAL. Gate 1 was approved by owner. Wrote `docs/garmin-data-provenance.md`. Match rule: type family plus start, distance, elapsed-time tiers; no Strava ID in Garmin data, so a shared-ID step is conditional on a Strava `external_id` check (UNVERIFIED). Owner answered open questions 10 to 13, sleep targets, and the three Gate 2 points; recorded in `docs/garmin-sync-design.md`. No code, no Strava tests touched. Next: owner approves Gate 2, then Gate 3.

- **2026-10-25 (second session):** Gate 1. Result: complete, AWAITING APPROVAL. Live token exchange OK (24 h lifetime). About 200 sequential GET calls, no 429. Found range limits (stress/steps 28 d, sleep/Body Battery 31 d, daily summaries 1 y), search `limit` max between 175 and 198 (500 above), data starts 2024-12-19. Swagger loads (OpenAPI 3.0.1, v1.0.0, embedded in page script). No Strava identifier in any Garmin field. VO2max documented in schema but never returned. Three ZIPs: one 1 Hz `.fit` each. Created: `docs/garmin-field-inventory.md`, `docs/garmin-capability-matrix.md`, 22 fixtures in `runner/src/test/resources/garmin/`, Gate 1 section in `docs/garmin-sync-design.md`; notes in the backlog progress file. Raw responses, token, ZIPs were kept in `/tmp/opencode` only and deleted. Offline build still passes (no code or Strava test changed). Owner answered open questions 1 to 9 in session. Next: owner approves Gate 1, then Gate 2.

- **2026-10-25:** Gate 0. Result: pass. `:core:test :runner:test` on Java 23 BUILD SUCCESSFUL. Key is present in git-ignored `.env`, absent from the shell environment (presence only checked, nothing printed). No network call made. Changed: created `docs/garmin-sync-design.md` (Gate 0 section), updated this file. No code or Strava tests changed. Next: owner approves Gate 0, then Gate 1.

- **2026-10-24:** Planning. Reviewed Garmin spec and client source. Fixed spec issues. Chose defaults (see `GARMIN_AGENT_PROMPT.md`). Added backlog item 81 (session-type readiness). Created the backlog progress file. Created `GARMIN_ROADMAP.md` with all phases, including the audit remediation step (Phase A). No code written.
