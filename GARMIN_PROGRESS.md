# Garmin Progress

Single source of truth for where the project stands. Every agent session reads this file first and updates it last.

- Plan: `GARMIN_ROADMAP.md`
- How to work: `GARMIN_AGENT_PROMPT.md`
- Spec: `GARMIN_API.md`, `GARMIN_API_AGENT_REFERENCE.md`
- Backlog status (81 items): `docs/garmin-feature-backlog-progress.md`

**Last updated:** 2026-10-24 by: planning session (no code yet)

## Next session starts here

- **Next action:** Gate 0 (environment check), then Gate 1 (live fixtures). Both need the owner to have the real key in the environment and network access.
- **Owner must do first:** confirm the phase mapping and the open questions below. Then run or allow the Gate 0 and Gate 1 session.

## Approvals

The owner changes the status column. The agent never sets APPROVED itself. The agent only reads this table before starting a gate or phase.

| Gate or phase | Status | Approved on | Owner comment |
|---|---|---|---|
| Gate 0 | NOT STARTED | | |
| Gate 1 | NOT STARTED | | |
| Gate 2 | NOT STARTED | | |
| Gate 3 | NOT STARTED | | |
| Phase A (audit remediation) | NOT STARTED | | |
| Phase 2 (recovery) | NOT STARTED | | |
| Phase 3 (performance) | NOT STARTED | | |
| Phase 4 (load) | NOT STARTED | | |
| Phase 5 (race) | NOT STARTED | | |

Status values: NOT STARTED, IN PROGRESS, AWAITING APPROVAL, APPROVED, REJECTED (say why).

## Current state

- **Current phase:** Phase 1, Gate 0
- **Blocked on:** owner running Gate 0 and Gate 1 with the real key, and confirming the open questions.

## Phase 1 gate status

| Gate | Status | Notes |
|---|---|---|
| Gate 0: environment check | NOT STARTED | Record result in `docs/garmin-sync-design.md` |
| Gate 1: fixtures and capability discovery | NOT STARTED | Needs live key and network |
| Gate 2: identity matching design | NOT STARTED | Needs Gate 1 approval |
| Gate 3: Phase 1 implementation | NOT STARTED | Needs Gate 2 approval |

## Phase 1 deliverables

| Item | Status | Location | Notes |
|---|---|---|---|
| `docs/garmin-sync-design.md` | NOT STARTED | `docs/` | Gate 0 and later |
| `docs/garmin-field-inventory.md` | NOT STARTED | `docs/` | Gate 1 |
| `docs/garmin-capability-matrix.md` | NOT STARTED | `docs/` | Gate 1 |
| `docs/garmin-data-provenance.md` | NOT STARTED | `docs/` | Gate 2 |
| Sanitized fixtures | NOT STARTED | `runner/src/test/resources/garmin/` | No real identifiers |
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

1. Confirm the phase mapping in `GARMIN_ROADMAP.md` and `docs/garmin-feature-backlog-progress.md`, especially Phases 3 to 5.
2. Who defines your personal sleep need for backlog item 7 (sleep debt)? You, or a default?
3. Do you want injury history recorded in the app (backlog item 63)? That needs an input screen and a medical-rules review.
4. Retry numbers (default: 3 attempts, 1 s / 4 s / 16 s). Confirm or change.
5. Concurrent sync behaviour (default: 409, no HTTP calls). Confirm or change.
6. Raw payload retention (default: kept, no expiry). Decide.
7. Gate 1 answers to `GARMIN_API.md` section 9: pending the live run.
8. Strava client secret rotation (audit item 0.1): done or not?

## Handoff for the next session

Replace this block at the end of every session with one clear instruction for the next agent. Include: current gate or phase, what was finished, what is blocked, and the exact next step.

- Not written yet. The planning session did not start any gate.

## Log

Newest first. One entry per session: date, gate or phase, result, what changed, what is next.

- **2026-10-24:** Planning. Reviewed Garmin spec and client source. Fixed spec issues. Chose defaults (see `GARMIN_AGENT_PROMPT.md`). Added backlog item 81 (session-type readiness). Created the backlog progress file. Created `GARMIN_ROADMAP.md` with all phases, including the audit remediation step (Phase A). No code written.
