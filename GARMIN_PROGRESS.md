# Garmin Progress

Single source of truth for where the project stands. Every agent session reads this file first and updates it last.

- Plan: `GARMIN_ROADMAP.md`
- How to work: `GARMIN_AGENT_PROMPT.md`
- Spec: `GARMIN_API.md`, `GARMIN_API_AGENT_REFERENCE.md`
- Backlog status (81 items): `docs/garmin-feature-backlog-progress.md`

**Last updated:** 2026-10-25 by: Gate 1 session (docs and fixtures only, no application code)

## Next session starts here

- **Next action:** owner reviews Gate 1 (`docs/garmin-capability-matrix.md`, `docs/garmin-field-inventory.md`, fixtures, `docs/garmin-sync-design.md` Gate 1 section) and sets Gate 1 to APPROVED. Then Gate 2 (identity matching design).
- **Owner must do first:** review Gate 1 and answer the open questions 10 to 12 below.

## Approvals

The owner changes the status column. The agent never sets APPROVED itself. The agent only reads this table before starting a gate or phase.

| Gate or phase | Status | Approved on | Owner comment |
|---|---|---|---|
| Gate 0 | APPROVED | | Report in `docs/garmin-sync-design.md` |
| Gate 1 | APPROVED | | Report in `docs/garmin-capability-matrix.md` |
| Gate 2 | NOT STARTED | | |
| Gate 3 | NOT STARTED | | |
| Phase A (audit remediation) | NOT STARTED | | |
| Phase 2 (recovery) | NOT STARTED | | |
| Phase 3 (performance) | NOT STARTED | | |
| Phase 4 (load) | NOT STARTED | | |
| Phase 5 (race) | NOT STARTED | | |

Status values: NOT STARTED, IN PROGRESS, AWAITING APPROVAL, APPROVED, REJECTED (say why).

## Current state

- **Current phase:** Phase 1, Gate 1 finished (APPROVED). Gate 0 APPROVED by owner.
- **Blocked on:** owner approval of Gate 1 before Gate 2 can start.

## Phase 1 gate status

| Gate | Status | Notes |
|---|---|---|
| Gate 0: environment check | DONE (approved) | Build passes; key in `.env`. Result in `docs/garmin-sync-design.md` |
| Gate 1: fixtures and capability discovery | APPROVED | Live run done 2026-10-25. 22 sanitized fixtures, field inventory, capability matrix, Swagger read, section 9 answered (some UNVERIFIED) |
| Gate 2: identity matching design | NOT STARTED | Needs Gate 1 approval |
| Gate 3: Phase 1 implementation | NOT STARTED | Needs Gate 2 approval |

## Phase 1 deliverables

| Item | Status | Location | Notes |
|---|---|---|---|
| `docs/garmin-sync-design.md` | IN PROGRESS | `docs/` | Gate 0 and Gate 1 sections done; Gate 2 and 3 follow |
| `docs/garmin-field-inventory.md` | DONE | `docs/` | Gate 1. Units marked DOCUMENTED / OBSERVED / UNVERIFIED |
| `docs/garmin-capability-matrix.md` | DONE | `docs/` | Gate 1 |
| `docs/garmin-data-provenance.md` | NOT STARTED | `docs/` | Gate 2 |
| Sanitized fixtures | DONE | `runner/src/test/resources/garmin/` | 22 files, identifiers replaced, checked against the raw responses; no key, JWT, ZIP or FIT committed |
| SPI `GarminApiClient` | NOT STARTED | `core` | Gate 3 |
| Domain models (HRV, sleep, resting HR, Body Battery, stress, activity reference) | NOT STARTED | `core` | Gate 3, after field inventory |
| Use cases `BackfillGarminData`, `SyncGarminData` | NOT STARTED | `core` | Gate 3 |
| Repository SPI for Garmin records | NOT STARTED | `core` | Gate 3 |
| `GarminApiClientImpl` | NOT STARTED | `runner` | Gate 3 |
| `GarminTokenProvider` | NOT STARTED | `runner` | Gate 3 |
| JPA entities and repositories | NOT STARTED | `runner` | Gate 3 |
| `GarminSyncScheduler` | NOT STARTED | `runner` | Gate 3 |
| `GarminSyncController` | NOT STARTED | `runner` | Gate 3 |
| `GarminProperties` | NOT STARTED | `runner` | Gate 3 |

## Phase 1 acceptance criteria (GARMIN_API.md section 6.3)

| # | Criterion | Status |
|---|---|---|
| 1 | Manual run gets a token; simulated 401 triggers one re-exchange | NOT STARTED |
| 2 | Backfill and incremental sync are idempotent | NOT STARTED |
| 3 | Missing fields stored as `null`; status reports the count | NOT STARTED |
| 4 | Failed day or endpoint retried with backoff; run continues | NOT STARTED |
| 5 | Status shows last run, last success, records per endpoint, last error category | NOT STARTED |
| 6 | Flag off: no Garmin calls. Flag on, no or invalid key: app starts, status reports state | NOT STARTED |
| 7 | Offline tests pass; existing Strava tests pass (changes only per the audit-decision rule, old and new values listed) | NOT STARTED |

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
2. ANSWERED 2026-10-25: sleep need at least 7 h 30 and enough deep sleep. Still open: the exact deep-sleep target (hours or percentage) for Phase 2.
3. ANSWERED 2026-10-25: history given (shin stress fracture about 1.5 years ago, one knee dislocation, none now). Recording it in the app stays a Later item (needs input UI and medical review).
4. ANSWERED: defaults kept (3 attempts, 1 s / 4 s / 16 s). See open question 10 for the one exception found in Gate 1.
5. ANSWERED: 409, no HTTP calls.
6. ANSWERED: kept, no expiry. See open question 12 about name and image fields in activity payloads.
7. ANSWERED in `docs/garmin-capability-matrix.md` section 10. Unproven items are marked UNVERIFIED (HRV measure and unit, rate limit, exact search limit).
8. ANSWERED: owner wants the Strava secret left as it is. No rotation. Audit item 0.1 is recorded as accepted by the owner.
9. ANSWERED: key may be read from `.env`, never printed.
10. Retry rule exception: an activity search with `limit` over about 175 returns HTTP 500 that never succeeds on retry. Proposal: page size 100 so it cannot occur, keep the retry rule as is. Confirm or change.
11. Incremental sync overlap: proposal is to re-fetch the last 3 days on every run, because the current day is incomplete and sleep/HRV attach to the wake-up date. Confirm or change (decide in Gate 3 review).
12. Raw payloads: the activity search response has owner name and profile image URL fields. Proposal: drop those fields before storing raw JSON. Confirm.
13. HRV: the API does not state the measure or unit. Phase 2 labelling: show `HRV (Garmin, nachtgemiddelde)` without claiming RMSSD. Confirm.

## Handoff for the next session

- **Current:** Phase 1, Gate 1 finished, AWAITING APPROVAL. Gate 0 APPROVED.
- **Finished:** live token exchange, 15 endpoint types probed, pagination and range limits found, 3 activity ZIPs inspected (metadata only), Swagger read, 22 sanitized fixtures, field inventory, capability matrix, Gate 1 section in the sync design.
- **Blocked:** Gate 2 until the owner sets Gate 1 to APPROVED and answers open questions 10 to 13 (none of them blocks Gate 2 itself).
- **Exact next step:** if Gate 1 is APPROVED in the Approvals table, start Gate 2: write `docs/garmin-data-provenance.md` (identity matching design, `GARMIN_API.md` section 5). Use the matching fields in `docs/garmin-capability-matrix.md` section 8. No code in Gate 2. Otherwise stop.

## Log

Newest first. One entry per session: date, gate or phase, result, what changed, what is next.

- **2026-10-25 (second session):** Gate 1. Result: complete, AWAITING APPROVAL. Live token exchange OK (24 h lifetime). About 200 sequential GET calls, no 429. Found range limits (stress/steps 28 d, sleep/Body Battery 31 d, daily summaries 1 y), search `limit` max between 175 and 198 (500 above), data starts 2024-12-19. Swagger loads (OpenAPI 3.0.1, v1.0.0, embedded in page script). No Strava identifier in any Garmin field. VO2max documented in schema but never returned. Three ZIPs: one 1 Hz `.fit` each. Created: `docs/garmin-field-inventory.md`, `docs/garmin-capability-matrix.md`, 22 fixtures in `runner/src/test/resources/garmin/`, Gate 1 section in `docs/garmin-sync-design.md`; notes in the backlog progress file. Raw responses, token, ZIPs were kept in `/tmp/opencode` only and deleted. Offline build still passes (no code or Strava test changed). Owner answered open questions 1 to 9 in session. Next: owner approves Gate 1, then Gate 2.

- **2026-10-25:** Gate 0. Result: pass. `:core:test :runner:test` on Java 23 BUILD SUCCESSFUL. Key is present in git-ignored `.env`, absent from the shell environment (presence only checked, nothing printed). No network call made. Changed: created `docs/garmin-sync-design.md` (Gate 0 section), updated this file. No code or Strava tests changed. Next: owner approves Gate 0, then Gate 1.

- **2026-10-24:** Planning. Reviewed Garmin spec and client source. Fixed spec issues. Chose defaults (see `GARMIN_AGENT_PROMPT.md`). Added backlog item 81 (session-type readiness). Created the backlog progress file. Created `GARMIN_ROADMAP.md` with all phases, including the audit remediation step (Phase A). No code written.
