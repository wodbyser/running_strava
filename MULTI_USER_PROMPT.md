# Multi-user: herbruikbare prompt voor een nieuwe agent

**Voor Wout:** kopieer alles onder de streep hieronder en plak het in een nieuwe agent-sessie in deze repo. Het is
elke keer dezelfde prompt, ongeacht hoe ver het werk staat: de agent leest zelf in `MULTI_USER_PROGRESS.md` welke
fase aan de beurt is, doet precies één fase (of maakt een onderbroken fase af) en stopt dan met een verslag.
Wil je dat hij alleen een specifieke fase doet of alleen iets controleert, zet dan één zin onder de prompt,
bijvoorbeeld "Doe alleen de Phase 2-checklist tot en met de schema-updater".

---

You are implementing the multi-user feature of this repository (Kotlin + Spring Boot 3.4 running-analysis app,
repo root = current working directory). You have no memory of earlier sessions. Everything you need is in the
repository. Work carefully: the owner's existing data and experience must never be harmed. That is more important
than finishing.

## 1. Read first, in this order (do not skip, do not skim)

1. `AGENTS.md` — project rules, build/test commands, deploy notes.
2. `MULTI_USER_PROGRESS.md` — current phase status, checklists, hard rules, session log. **This is the source of
   truth for where the work stands.**
3. `docs/multi-user-design.md` — full design. Read §0–§3 and §11 completely, then every section the current phase
   refers to. §20 lists earlier review corrections: do not reintroduce any of them.
4. `docs/ui-guidelines.md` — binding UI rules (only if the phase touches templates).
5. The source files the current phase touches (see design §16), before you change them. Verify every claim in the
   design against the code; if the code differs, the code wins and you correct the design document.

## 2. Determine what to do

From the "Phase status" table in `MULTI_USER_PROGRESS.md`:

- A phase is `IN PROGRESS` → continue that phase. Check which checklist items are really done (look at the code,
  run the tests) instead of trusting the ticks.
- A phase is `BLOCKED` → read why in the session log. If the blocker is an owner action that is not done yet,
  stop and tell the owner exactly what is needed. Do not work around it.
- Otherwise → start the lowest phase that is `NOT STARTED`, but only if every lower phase is `DONE` or `APPROVED`.
- All phases `DONE`/`APPROVED` → do not invent work; report that and stop.
- If the owner added an extra instruction under this prompt, it takes precedence over this section, but never over
  the hard rules.

Do **one phase per session**. When its gate is green, stop. Do not start the next phase.

## 3. Before changing anything

1. `git status` and `git log --oneline -5`. Uncommitted changes you did not make are the owner's work: do not
   overwrite, revert or delete them; ask if they are in your way.
2. Confirm Java 23: `JAVA_HOME=~/.sdkman/candidates/java/23-open ./gradlew :core:test :runner:test` must be green
   **before** you start. If it is red before your changes, stop and report.
3. Restate in 3–6 lines (to yourself, in the progress file once you start): which phase, which checklist items,
   which files you expect to touch.

## 4. Hard rules (never violate; the full list is in `MULTI_USER_PROGRESS.md`)

- Never run anything destructive against the owner's database `running`: no `DROP`, `TRUNCATE`, `DELETE`,
  `RENAME`, `pg_restore --clean` into `running`, no `docker compose down -v` (it deletes the production volume).
- Work against a **restored copy** (e.g. `running_restore_test`) or a throwaway database, never against production.
  If you need production (a dump, row counts, timings) and it is not reachable from this machine, give the owner
  the exact commands to run and wait for the output.
- With `app.multi-user.enabled=false` (the default) the app must behave exactly as before: all multi-user beans
  `@ConditionalOnProperty`, no `DataSource` bean of your own, no Flyway, no `ddl-auto` change on that path.
- Never switch the flag on in production, never deploy, never edit `deploy/.env`. That is the owner's call.
- Never log or print a key, JWT, token, password or invite code (also not in test output or your report).
- No code path may fall back to the owner's database when the tenant is unknown.
- Stay inside the current phase. If you find something that belongs to a later phase, write it down in the
  progress file instead of building it.
- Keep diffs small and in the style of the surrounding code; `core` stays Spring-free; match the comment density.

## 5. When to stop and ask the owner (use the question tool, in Dutch, short)

- An owner precondition is missing (Strava subscription / athlete capacity, env secrets; design §19).
- The design is wrong, ambiguous or contradicts the code in a way that changes behaviour for the owner.
- Anything would change the owner's data, schema, login, URL, timings or cron behaviour.
- A gate cannot be met (e.g. timings outside ±5%, schema updater produces statements on the owner copy).
- A new dependency is needed (e.g. Testcontainers; design §15.3 says decide once).

Do not guess on these. Record the question and the answer in the progress file.

## 6. Doing the phase

- Follow the phase's checklist in `MULTI_USER_PROGRESS.md` and the matching steps and acceptance in design §14.
- Write the tests named for the phase (design §15.2) next to the code, not afterwards.
- Run `JAVA_HOME=~/.sdkman/candidates/java/23-open ./gradlew :core:test :runner:test` after every meaningful step.
- For UI work: open the pages in a browser and check them (design `docs/ui-guidelines.md`), with the flag off
  **and** on, before calling it done.
- Measurements (timings, row counts, connection counts) go into "Measurements log" with date and method.

## 7. Gate: the phase is only DONE when all of this is true

1. Every checklist item of the phase is ticked and really done (code + test or recorded evidence).
2. The acceptance criteria of the phase in design §14 are met, with evidence written in the progress file.
3. Full test suite green on Java 23.
4. Flag-off regression: the app with the default config behaves as before (existing tests + a manual look at `/`,
   `/activities`, `/load`, `/race`).
5. No secret in any log line, template, exception message or report.
6. A verified backup exists if the phase touched anything that runs against the owner's database.

If not all true: leave the phase `IN PROGRESS` (or `BLOCKED` with the reason) and say exactly what is left.

## 8. Before you end the session (always, also when you stop early)

Update `MULTI_USER_PROGRESS.md`:

- "Last updated" line, phase status table, ticked checklist items.
- "Next session starts here": the concrete next action for a fresh agent.
- One session-log entry: what was done, what was verified (with numbers), what is open, questions asked + answers.
- If a decision or a fact in the design changed: update `docs/multi-user-design.md` in the same session and say
  so in the log. Never let the two documents disagree.

Do not commit unless the owner asks; leave the changes for review.

## 9. Final report to the owner (in Dutch, plain language, short)

1. Welke fase, en of die af is (DONE / IN PROGRESS / BLOCKED).
2. Wat er veranderd is, in gewone woorden, met de belangrijkste bestanden.
3. Bewijs dat er voor hem niets veranderd is (tests, metingen, flag-off check).
4. Wat hij zelf moet doen of beslissen, als dat er is.
5. Wat de volgende sessie gaat doen.
