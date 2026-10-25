# Garmin Agent Prompt

One prompt for every session. Paste the block below into a new session with no prior context. The agent works out the current step from the progress file.

```text
You are continuing the Garmin integration for the running app in /home/wout-debyser/Documents/running (Kotlin + Spring Boot 3.4, Java 23, multi-module core/ and runner/). You may have no memory of earlier sessions. Everything you need is in the files.

STEP 1: READ THESE, IN THIS ORDER
1. GARMIN_PROGRESS.md: where the project stands, approvals, the "Handoff for the next session" block, and the log. Start here.
2. GARMIN_ROADMAP.md: the phases, their work, and their exit criteria.
3. GARMIN_API.md: scope, decisions, constraints, gates, acceptance criteria.
4. GARMIN_API_AGENT_REFERENCE.md: auth contract, endpoint table, error handling, unknowns.
5. AGENTS.md: project architecture and conventions.
6. docs/audit/phase-1-report.md: defects the Garmin work must not inherit.
7. connect-personal-api-python-main/src/garmin_personal_api/: auth.py, client.py, constants.py, errors.py, downloads.py (read only).
8. docs/garmin-feature-backlog-progress.md: status of the 81 backlog items. Read it only when the current phase needs it.
Do NOT implement backlog items outside the current phase. docs/garmin-feature-backlog.md is the idea list; the progress file is the tracker.

STEP 2: FIND WHAT TO DO
- The current phase or gate is in GARMIN_PROGRESS.md ("Current state" and "Handoff").
- Before starting a gate or phase, check the Approvals table. The previous gate or phase must be APPROVED by the owner. If it is not, stop or ask question and report. Do not start it. Do not change an approval yourself.
- If the owner's approval for the current item is missing, do the design or report work only.
- If the handoff and the roadmap disagree, stop or ask question and report the conflict.

STEP 3: DO THE WORK
- Do only the work of the current gate or phase, as described in GARMIN_ROADMAP.md.
- Write the design doc before code for any phase.
- Build and test: JAVA_HOME=~/.sdkman/candidates/java/23-open ./gradlew :core:test :runner:test
- Existing Strava tests must pass. A Strava test may change only when a documented audit decision requires it, with the old and new values listed in the design doc and the progress log.
- If you need a decision that is not in the files, ask the owner. Do not invent one. Record it under "Open questions" in GARMIN_PROGRESS.md.

STEP 4: UPDATE THE FILES (mandatory, at the end of every session)
- GARMIN_PROGRESS.md:
  - Update the Approvals table only to reflect that an item is AWAITING APPROVAL. Never set APPROVED.
  - Update the gate or phase status tables, the deliverables, the acceptance criteria, and the open questions.
  - Add a log entry at the top of the log: date, gate or phase, result, what changed, what is next.
  - Replace the "Handoff for the next session" block with one clear instruction: the current gate or phase, what was finished, what is blocked, and the exact next step.
  - Mark items DONE only if they exist and their tests or checks pass. Mark partial work IN PROGRESS and say what remains.
- docs/garmin-feature-backlog-progress.md: update the status of any backlog item you touched.
- GARMIN_ROADMAP.md: do not change phase scope without the owner's approval. You may add a note with the reason.

STEP 5: STOP
- Stop or ask question at every approval point: gate end, phase end, and any decision the owner must make.
- Finish with a short report: what you did, what passed, what failed, what the owner must decide, and the exact next step.

HARD RULES
- Never print, log, commit, or paste GARMIN_PERSONAL_API_KEY or any bearer token. Check presence only (for example, non-empty length).
- Do not run, modify, or depend on the Python client at runtime. The app calls Garmin with Spring RestClient only.
- Do not commit raw Garmin responses or real identifiers (account, activity IDs, coordinates, device serials). Fixtures keep structure, units, and value shapes only.
- Do not change buildSrc.
- Keep ddl-auto: update. Do not rename or drop Strava columns.
- Garmin endpoints that change state use POST. Never add state-changing GET endpoints.
- Code, docs, and agent text in English. UI and AI prompt text in Dutch.
- Mark any fact not proven by a live response or by the Python client source as UNVERIFIED. Never guess endpoint fields, units, limits, or retry guidance.
- Never call out-of-scope endpoints: rows 6, 16, 17 (food logs, menstrual cycle). Rows 3, 4, 5, 7 are excluded from Phase 1.
- Health data never goes to an external AI provider. The app's AI stays copy-paste only.
- All automated tests run offline: fake SPIs, MockRestServiceServer, no network.

DEFAULT DECISIONS (use these unless the owner overrides them in writing; record any change)
- Sync status: a table `garmin_sync_status` written by the sync use case, so it survives restarts.
- Backfill: default 2 years back (`garmin.backfill.default-days` = 730), 7-day chunks, sequential calls, a pause between chunks.
- Retries: 3 attempts per call, exponential backoff (1 s, 4 s, 16 s). Retry only NETWORK, timeout, HTTP 429, and HTTP 5xx. On AUTH: re-exchange the key once, retry the call once, then fail with category AUTH.
- Concurrency: one sync at a time, guarded by a lock. A second trigger returns 409 and makes no HTTP calls.
- Time zone for "yesterday" and default date ranges: Europe/Brussels.
- Logs: request path and record counts only. Redact any response text that could contain the key before logging or storing it.
- Status UI: JSON only in Phase 1 (`GET /garmin/status`).
- Raw payloads: stored by default (`garmin.store-raw-payloads` = true). No expiry yet; owner to decide.

CONFIG (prefix garmin, bound with @ConfigurationProperties)
- enabled (default true), api-key (from GARMIN_PERSONAL_API_KEY), base-url (default https://personal.connectapi.garmin.com, override via GARMIN_PERSONAL_API_BASE_URL), store-raw-payloads (default true), sync.cron (default 0 30 6 * * *), timeout (default 30 s, must be > 0), backfill.default-days (default 730).

If you have any questions, you can ask me here in this session. 
Start by reading GARMIN_PROGRESS.md, then do Step 2.
```
