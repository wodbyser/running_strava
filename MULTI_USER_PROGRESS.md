# Multi-user Progress

Single source of truth for where this feature stands. **Read this first, update it last.**
Full design and rationale: `docs/multi-user-design.md` (self-contained, no session context needed).
Prompt to start a fresh agent on the next phase: `MULTI_USER_PROMPT.md` (same prompt every time).

**Last updated:** 2026-11-19 by: Phase 1 session (control plane + DB-backed login behind the flag).

## Next session starts here

- **Next action:** Phase 1 is APPROVED (2026-11-19). Start **Phase 2** (routing datasource, §5.7 boot with the flag on first). Phase 0 is APPROVED (owner deploys on Linux;
  `backup.ps1` is irrelevant and was not run). Wiring `backup.sh` into the systemd timer stays a Phase 6 item.
- **Phase 1 is DONE** (flag off by default). New package `runner/.../com/running/tenant/`; `SecurityConfig` has one
  `@ConditionalOnProperty` on `userDetailsService`; `application.yml` got `app.multi-user.*`.
- Phase 2 reminders: control DB test infrastructure so far is H2 in PostgreSQL mode (§15.3 decided by the owner
  2026-11-19: use Testcontainers PostgreSQL, adding it as a test-only dependency in Phase 2). The owner-DB assertion of §5.6 is
  already in `OwnerBootstrap` (refuses start if OWNER row's `tenant_db` differs from the URL database); Phase 2 only
  needs to add the pool wrapping.
- Owner preconditions before Phase 4 (not code, owner does these): Strava subscription, Strava athlete capacity
  1 → 10, `APP_SIGNUP_CODE` and `APP_TENANT_SECRET_KEY` in `deploy/.env` (see design §19).

## Hard rules for this feature (never violate)

1. The owner's database is named `running` and is never renamed, copied over, reset or dropped. The app never
   drops any database; reset/delete rename `running_u_*` databases to an archive name.
2. No code path may fall back to the owner's database when the tenant is unknown. The router throws.
3. No `user_id` column, no shared-table filtering. Isolation is database-level on purpose.
4. No reset/delete route may ever act on the OWNER tenant (server-side guard, not just hidden UI).
5. `docker compose down -v` stays forbidden (root dev compose and deploy compose share `running_pgdata`).
6. `app.multi-user.enabled` stays `false` in production until the Phase 4 gate (Strava users) or Phase 5 gate
   (Garmin users) is green. Before that it is only switched on against a restored copy. With it off the app must
   behave exactly as before: every multi-user bean is `@ConditionalOnProperty`, Spring Boot's own Hikari pool,
   `InMemoryUserDetailsManager`, `ddl-auto: update`.
7. No Flyway, no change to `ddl-auto` on the flag-off path (D8). Schema for tenant DBs comes from the same entities
   via `TenantSchemaUpdater` (design §8).
8. Never declare a `DataSource` bean (it disables Spring Boot's auto-configured owner pool). See design §5.2.
9. Never log a key, JWT, token, password or invite code.
10. Every phase ends with: `JAVA_HOME=~/.sdkman/candidates/java/23-open ./gradlew :core:test :runner:test` green,
    a verified backup, and this file updated.

## Owner decisions (2026-11-17, binding)

| # | Decision | Choice |
|---|---|---|
| D1 | Isolation | One PostgreSQL database per user, one app instance |
| D2 | Registration | Invite code only (`APP_SIGNUP_CODE`) |
| D3 | Strava | Owner's Strava app + OAuth per user (per-user client id/secret is non-compliant with Strava policy: dropped) |
| D4 | Garmin-only gap | Accepted, with honest UI notices |
| D5 | Scale | 4–10 users (Strava: max 10 athletes incl. owner without Strava review) |
| D6 | Owner login | Unchanged, `APP_PASSWORD` stays authoritative |
| D7 | User store | New database `running_control` |
| D8 | Schema | **Hibernate update per database** (owner keeps `ddl-auto: update`; no Flyway). Revised by the owner 2026-11-17 |
| D9 | Rollout | Feature flag `app.multi-user.enabled`, default false; multi-user beans absent when off |
| D10 | Garmin key at rest | AES-256-GCM in the tenant DB, master key from env |
| D11 | Switching source | Only via a full guarded reset (archive DB by rename → fresh DB → re-onboard), never for the OWNER |
| D12 | Language | Dutch only |
| D13 | Owner's view of other users | Counts + last sync only, never content |
| D14 | Expired Garmin key | Banner + link to `/settings`, stored data stays, nothing deleted |

## Phase status

Status values: `NOT STARTED`, `IN PROGRESS`, `BLOCKED`, `DONE`, `APPROVED` (owner only).

| Phase | Scope | Status | Gate result |
|---|---|---|---|
| 0 | Backup/restore drill, row counts, schema snapshot, page-timing baseline, backup-all script (no code change) | APPROVED | Tests green, restore drill counts match, see log |
| 1 | `running_control`, `app_user`/`invite_code`/`auth_audit`, DB login, owner bootstrap | APPROVED | 186 runner + 434 core tests green; flag-on manual run on restored copy, see log |
| 2 | Routing datasource, fail-closed filter, Hibernate start-up, `TenantSchemaUpdater`, tenant-aware schedulers/warm-ups, provisioner CLI | NOT STARTED | |
| 3 | Per-tenant caches, Garmin token provider, coordinator, `SecretCipher`/`CredentialResolver`, Strava rate budget | NOT STARTED | |
| 4 | `app_settings` + `activities.source`, `/register`, `/setup`, Strava OAuth `state`, Strava-only users, reset | NOT STARTED | |
| 5 | GARMIN-only projection, mode guard, capability matrix + UI panels | NOT STARTED | |
| 6 | `/admin/users`, backup-all nightly, deploy docs | NOT STARTED | |
| 7 | Hardening, session invalidation, delete-user, remove feature flag | NOT STARTED | |

## Phase 0 checklist

- [x] Fresh dump via `deploy/migrate.sh backup` (do **not** use the old untracked `deploy/running.dump`)
- [x] Restore into scratch DB `running_restore_test`; boot the current image against it
- [x] Record row counts of all tables (22 here, not 18; live and restored match)
- [x] Save `pg_dump --schema-only` of the live DB under `docs/multi-user-baseline/` (no data)
- [x] Record median timings of `/`, `/activities`, `/load`, `/race` and start-up time in "Measurements log"
- [x] `deploy/backup.sh` + `deploy/backup.ps1`: dump every `running*` DB + manifest; `migrate.sh backup` unchanged
- [x] One manual backup-all run restored (nightly wiring deliberately left to Phase 6 / owner; not wired) successfully before wiring it into the nightly timer
- [x] No file under `runner/` or `core/` changed

## Phase 1 checklist

- [x] `MultiUserProperties` (`app.multi-user.enabled` default `false`, `control-url`, `tenant-pause`)
- [x] `ControlDb` (own Hikari + `JdbcTemplate`, **not** a `DataSource` bean), only with the flag on
- [x] `running_control` auto-create (autocommit) + `CREATE TABLE IF NOT EXISTS` initializer
- [x] `AppUserPrincipal` (Serializable), `ControlUserDetailsService`
- [x] `SecurityConfig`: `InMemoryUserDetailsManager` when off (`matchIfMissing = true`), control-plane when on
- [x] `OwnerBootstrap`: create on first boot, `encoder.matches` before re-hash, refuse a 2nd OWNER
- [x] Refuse start-up with flag on + blank `APP_PASSWORD`, or flag on + H2 URL
- [x] Tests: `OwnerBootstrapTest`, `FlagOffContextTest`
- [x] Flag-off regression: existing suites pass, no connection to `running_control` at boot
- [x] Flag on against restored copy: remember-me survives a restart

## Phase 2 checklist

- [ ] Boot with flag on, list every start-up connection; apply design §5.7 (`allow_jdbc_metadata_access=false`,
      `ddl-auto: none` **only on the flag-on path**)
- [ ] `TenantContext` (ThreadLocal, nested-safe)
- [ ] `TenantRoutingDataSource` wrapping the auto-configured pool via `BeanPostProcessor` (owner pool size stays 2)
- [ ] `TenantContextFilter` after `AuthorizationFilter`, not double-registered as a servlet filter
- [ ] `TenantRegistry` (in memory, owner first)
- [ ] `TenantSchemaUpdater` with dry run + statement guard; **zero statements** on the restored owner copy
- [ ] `TenantJobRunner`; Strava cron, Garmin cron, race-log cron, load warm-up, race warm-up through it
- [ ] Garmin executor captures the tenant at submit
- [ ] `TenantProvisioner` + `--provision-user=<name>` CLI; owner assertion at boot
- [ ] Tests: `TenantContextTest`, `TenantRoutingDataSourceTest`, `TenantContextFilterTest`, `RememberMeTenantTest`,
      `OwnerPoolTest`, `SchemaUpdaterTest`, `OwnerTenantAssertionTest`, `IsolationIT`
- [ ] Flag on, owner only: timings within 5% of Phase 0 (record numbers here)

## Phase 3 checklist

- [ ] Tenant-scoped cache keys in `BestEffortService`, `PerformanceService`, `LoadService`, `RaceService` + `evict(tenant)`
- [ ] Per-tenant `GarminTokenProvider` + `GarminSyncCoordinator` (+ per-tenant `isRunning`, `GarminSyncView`)
- [ ] `SecretCipher` + `APP_TENANT_SECRET_KEY` (refuse to start if missing while enabled)
- [ ] `CredentialResolver` (tenant setting, env key for OWNER only); `GarminProperties.configured` replaced
- [ ] Garmin `AUTH` error per tenant surfaced as the "key verlopen" banner (D14); nothing deleted
- [ ] Strava rate-budget counter (response headers), global backfill semaphore, tenant pause
- [ ] Tests: `TenantCacheTest`, `TenantJobRunnerTest`, `GarminTenantKeyTest`, `SecretCipherTest`, one-tenant-fails test

## Phase 4 checklist

- [ ] Owner preconditions done (Strava subscription, capacity 10, env secrets)
- [ ] Verified backup, then ship `AppSettingEntity` + nullable `activities.source` (null = STRAVA)
- [ ] `/register` with invite code (env code reusable), constant-time compare, rate limit 5/15min per IP;
      login rate limit per (IP, username); verify forwarded client IP behind Funnel
- [ ] `/setup` wizard (Strava button, Garmin key + Test), hidden after onboarding; never for the OWNER
- [ ] Strava OAuth `state` (HMAC: tenant + session + expiry); replay rejected; Dutch message on "athlete limit"
- [ ] `DataMode` derivation + persistence; OWNER hard-coded `BOTH`
- [ ] `DataModeGuard` for STRAVA mode; OAuth 409 once data exists
- [ ] `/settings` (rotate same-source credentials) + `POST /settings/reset` (archive by rename), OWNER refused + test
- [ ] First Strava backfill on the per-tenant queue with a progress page
- [ ] Tests: `InviteCodeTest`, `DataModeTest`, `OwnerModeTest`, `ModeChangeGuardTest`, `OwnerSafetyTest`
- [ ] Manual: one Strava-only user end-to-end; owner pages unchanged

## Phase 5 checklist

- [ ] `GarminActivityProjector` with collision guard; matcher test (projected activity matches its own ref)
- [ ] `DataModeGuard` in Strava sync/fetch/backfill/OAuth and Garmin sync
- [ ] Throwing-stub tests: GARMIN mode makes zero Strava calls, STRAVA mode zero Garmin calls
- [ ] `TenantCapabilities` + one reusable "not available" panel (Dutch copy)
- [ ] `docs/multi-user-capabilities.md` filled by opening every page in every mode
- [ ] `AiAnalysisService.buildTrainingContext` respects the mode; owner decides on `/ai` for Strava tenants (design R14)
- [ ] Manual: one Garmin-only user end-to-end

## Phase 6 checklist

- [ ] `/admin/users` (OWNER only): list, last login, masked key, counts, disable, single-use invite codes
- [ ] Backup-all wired into the nightly timer; `status.ps1`/`status.sh` show tenant count
- [ ] Docs updated: `AGENTS.md`, `deploy/DEPLOY.md`, `deploy/LINUX.md`, `deploy/START-STOP.md`, `deploy/AGENT-PROMPT.md`
- [ ] New env vars documented in `deploy/.env.example`: `APP_SIGNUP_CODE`, `APP_TENANT_SECRET_KEY`, `APP_CONTROL_DB_URL`

## Phase 7 checklist

- [ ] Remove `app.multi-user.enabled=false` path and `InMemoryUserDetailsManager`
- [ ] Session invalidation on disable
- [ ] Delete-user flow: Strava `oauth/revoke`, archive by rename, owner drops archive within 30 days after a dump; OWNER forbidden
- [ ] Log-redaction audit across all tenants
- [ ] Final performance comparison vs the Phase 0 baseline
- [ ] Owner sign-off

## Measurements log

| Date | What | Result |
|---|---|---|
| 2026-11-19 | Local stack (`running-db`, copy of owner data, Postgres 17), dump `deploy/backups/running-20261119-1323.dump` | 49,352,978 bytes, 85 TOC entries |
| 2026-11-19 | Row counts live vs restored (`running_restore_test`) | 22 tables identical (activities 606, activity_laps 6062, activity_streams 586, garmin_body_battery_sample 81129, garmin_raw_payload 2464, others smaller) |
| 2026-11-19 | Start-up of `running-coach:latest` (built 2026-11-18 22:02) against the restored copy, flag n/a | 6.4 s Spring "Started", /login reachable after 8 s |
| 2026-11-19 | Warm median of 5 requests via curl, session cookie, app in Docker with 2 GB, restored copy | `/` 0.117 s, `/activities` 0.096 s, `/load` 0.745 s, `/race` 1.018 s (first/cold: 0.48, 0.35, 2.78, 1.43 s). Sub-100 ms pages are noisy (range 0.06-0.15 s); judge ±5% on `/load` and `/race` |
| 2026-11-19 | `deploy/backup.sh` run; `running.dump` of it restored into a scratch DB | counts identical to live; scratch DB dropped |

## Session log

Newest last. One entry per session: what was done, what was verified, what is next.

- **2026-11-17** — Analysis session. Read the whole `runner` module, the 18 entities, auth, Strava/Garmin
  adapters, schedulers, deploy compose and the in-memory caches. Found the singleton landmines (five caches keyed by
  activity id, one Garmin token cache, one sync "busy" flag, global `configured` check, one-shot warm-ups).
  Wrote `docs/multi-user-design.md`. No code changed.
- **2026-11-17** — Owner answered the four open points in session: O1 full guarded reset only, O2 Dutch only,
  O3 counts + last sync in `/admin/users`, O4 "key verlopen" banner with data kept. Recorded as D11–D14 in
  `docs/multi-user-design.md` §3 and in §19. Checklists updated. Still no code changed.
- **2026-11-17** — Review session. Checked the design against the code and the current Strava/Garmin terms; 15
  corrections, listed in design §20. Main ones: owner chose Hibernate update per DB instead of Flyway (D8); no
  custom `DataSource` bean; filter after `AuthorizationFilter` (remember-me); start-up without tenant (§5.7);
  `encoder.matches` for the owner password; OWNER fixed to `BOTH`; reset = archive by rename; phases reordered so
  per-tenant state exists before signup; Strava subscription + 10-athlete cap. Removed the stale
  `review-package.md`. Still no code changed.

- **2026-11-19** — Phase 0. Baseline tests green on Java 23 before starting. Used the local deploy stack
  (`running-db`, was stopped, started and stopped again; the owner's `running` DB only read via `pg_dump`/`SELECT`).
  Fresh dump, restore into `running_restore_test`, booted the current image against it (own container on port 8099,
  no Strava/Garmin credentials, removed afterwards), row counts match, schema-only dump saved in
  `docs/multi-user-baseline/running-schema.sql`, timings in the Measurements log. Added `deploy/backup.sh`
  (tested: dumps `running` and `running_p3`, manifest with counts, restore verified) and `deploy/backup.ps1`
  (**not run**, no Windows here; same logic, please run once on the server). Findings: the DB has 22 tables, 4 of
  them legacy upper-case ones no entity maps (design corrected); there is an extra DB `running_p3` (35 MB, copy of
  an earlier experiment; the backup-all picks it up as `running_*`); `running_restore_test` is left in the local
  volume (scratch, owner may drop it). `deploy/backups/` and `running.dump` are git-ignored. Not done on purpose:
  wiring `backup.sh` into the systemd timer (Phase 6 checklist; changes the owner's nightly job). No questions asked.

- **2026-11-19** — Phase 1 (owner: Phase 0 APPROVED, Linux deploy only). Plan: `MultiUserProperties`, `ControlDb`,
  control schema, principal/user-details, `OwnerBootstrap`, conditional `SecurityConfig`; files in `runner/.../tenant/`
  (`MultiUserConfig.kt` holds all flag-on beans and `MultiUserGuards`). Tests (H2 in PostgreSQL mode, no new
  dependency): `OwnerBootstrapTest` (create, same hash on unchanged password, re-hash on change, blank keeps hash,
  changed username/2nd owner/other owner DB refused, case-insensitive lookup, disabled user, guards for blank password
  and H2 URL, URL helper) and `FlagOffContextTest` (no `ControlDb`/`MultiUserProperties`/`ownerBootstrap` bean,
  `InMemoryUserDetailsManager`, owner pool `maximumPoolSize == 2`). Full suite on Java 23: core 434, runner 186, 0
  failures. Manual, flag ON against `running_restore_test` (local `running-db`, reached via container IP, port not
  published; jar run with Strava/Garmin env blanked, throwaway passwords): `running_control` auto-created, OWNER row
  `wout`/`running_restore_test`; login 302 to `/`, wrong password to `/login?error`, `/activities` `/load` `/race`
  200; restart with same password: hash md5 unchanged and the remember-me cookie alone still gives 200 on `/`; changed
  password: hash changed, new password works, old fails; blank `APP_PASSWORD` refuses to start with the clear message;
  no password/remember-me key in any of the 4 logs (grep 0). Flag OFF against the same copy: login, `/`
  `/activities` `/load` `/race` 200, no `running_control` database created, no "control" in the log. The owner's DB
  `running` was never connected to. Decisions/deviations: owner tenant id = lowercased `APP_USERNAME`, which must match
  `^[a-z][a-z0-9_]{2,30}$` when the flag is on (start-up refuses otherwise); `last_login_at` is written by
  `LastLoginRecorder` on form login only (remember-me re-auth does not write); `auth_audit` gets
  `OWNER_CREATED`/`OWNER_PASSWORD_UPDATED` rows; one-owner rule is enforced in code, not by a partial index (H2).
  Left over: I dropped the scratch `running_control` I created in the local Postgres after the flag-on run (it is not
  the owner's DB); `running-db` was started for the test and stopped again; no timings compared (Phase 2 gate).
  Phase 1 does not change `ddl-auto` or add a `DataSource`. Owner answers (2026-11-19): Phase 1 approved; APP_USERNAME fits the regex; Testcontainers approved for Phase 2; commit requested. No backup needed: nothing ran against
  the owner's database `running`.

## Guardrail checklist before any commit

- [ ] No secret in a log line, template or exception message
- [ ] No route that can touch the owner tenant while the caller is another tenant
- [ ] No `DROP`/`TRUNCATE`/`DELETE`/`RENAME DATABASE` without a tenant guard (`running_u_` prefix, never the owner DB)
- [ ] No new `DataSource` bean, no Flyway, no `ddl-auto` change on the flag-off path
- [ ] Existing owner pages unchanged (numbers and timings)
- [ ] `docs/multi-user-design.md` updated if a decision changed, and the decision logged above
