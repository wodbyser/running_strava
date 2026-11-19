# Multi-user Progress

Single source of truth for where this feature stands. **Read this first, update it last.**
Full design and rationale: `docs/multi-user-design.md` (self-contained, no session context needed).
Prompt to start a fresh agent on the next phase: `MULTI_USER_PROMPT.md` (same prompt every time).

**Last updated:** 2026-11-19 by: Phase 3 session (per-tenant Garmin key/token/coordinator, SecretCipher, CredentialResolver, Strava rate budget). Phase 3 is APPROVED (owner, 2026-11-19) and committed.

## Next session starts here

- **Next action:** Phase 3 is APPROVED (2026-11-19). Start **Phase 4**, but first check the owner preconditions (below) with the owner. Before it ships `app_settings` + `activities.source` to the owner's DB: verified backup first (design §11.1).
- Phase 3 facts for the next agent: `CredentialResolver` (flag off: env key; flag on: OWNER env key, other tenants a stored
  key decrypted by `SecretCipher`, no tenant = no key) is in `tenant/CredentialResolver.kt`; `TenantSettings` is the port to the
  future `app_settings` table (Phase 4 replaces `NoTenantSettings` in `MultiUserConfig`; key name `CredentialResolver.GARMIN_KEY`
  = `garmin.api_key`, store it with `SecretCipher.encrypt`). `StravaRateBudget` (fed by `StravaApiClientImpl`) and `BackfillGate`
  are beans but nothing calls `tenantBackfillAllowed()`/`runIfFree` yet: the Phase 4 per-tenant backfill must. The "key verlopen"
  banner (`GarminKeyBanner`, layout.html) has no `/settings` link yet (route is Phase 4). `evict(tenant)` exists on the four
  caches and on `GarminTokenProvider`; the Phase 4 reset must call all five.
- Phase 2 facts for the next agent: everything is in `runner/.../com/running/tenant/` (`TenantContext`+`TenantKey`,
  `TenantRegistry`, `TenantRoutingDataSource`, `TenantContextFilter`, `TenantSchemaUpdater`+`SchemaGuard`+
  `UniqueConstraintChurn`, `TenantJobRunner`, `TenantProvisioner`, `MultiUserEnvironmentPostProcessor`; flag-on wiring in
  `MultiUserConfig`). Schedulers/warm-ups reach the runner through `ObjectProvider`/an optional field, so with the flag off
  the code path is the old one. Pulled forward from Phase 3 (needed for the Phase 2 acceptance): `TenantKey` cache keys in the
  four services, and an interim guard in `GarminSyncCoordinator` (only the OWNER tenant may use the one env key).
- Provision a tenant without UI (flag on, own port, exits when done):
  `APP_MULTI_USER_ENABLED=true java -jar runner/build/libs/app.jar --provision-user=<name>` (same env as the app). The user
  gets an unusable password; a failed run leaves a PENDING row and can simply be repeated. Nothing is ever dropped.
- Test infrastructure: Testcontainers **2.0.5** (`testcontainers-postgresql`, test only). 1.x cannot talk to the current Docker engine.
  `MultiUserIT` is the base class for flag-on tests (needs Docker; image `postgres:17`).
- Owner preconditions before Phase 4: Strava subscription and Strava athlete capacity 1 → 10 (owner does these, design §19).
  `APP_SIGNUP_CODE` and `APP_TENANT_SECRET_KEY` were appended to `deploy/.env` on 2026-11-19 with the owner's explicit permission
  (random values, never printed; old file in `deploy/backups/env-before-multiuser-20261119`). Do not edit or print them.

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
| 2 | Routing datasource, fail-closed filter, Hibernate start-up, `TenantSchemaUpdater`, tenant-aware schedulers/warm-ups, provisioner CLI | APPROVED | 434 core + 220 runner tests green; zero schema statements on restored owner copy; timings equal within noise, see log |
| 3 | Per-tenant caches, Garmin token provider, coordinator, `SecretCipher`/`CredentialResolver`, Strava rate budget | APPROVED | 434 core + 241 runner tests green; flag off/on manual run on restored copy, see log |
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

- [x] Boot with flag on, list every start-up connection; apply design §5.7 (`allow_jdbc_metadata_access=false`,
      `ddl-auto: none` **only on the flag-on path**)
- [x] `TenantContext` (ThreadLocal, nested-safe)
- [x] `TenantRoutingDataSource` wrapping the auto-configured pool via `BeanPostProcessor` (owner pool size stays 2)
- [x] `TenantContextFilter` after `AuthorizationFilter`, not double-registered as a servlet filter
- [x] `TenantRegistry` (in memory, owner first)
- [x] `TenantSchemaUpdater` with dry run + statement guard; **zero statements** on the restored owner copy
- [x] `TenantJobRunner`; Strava cron, Garmin cron, race-log cron, load warm-up, race warm-up through it
- [x] Garmin executor captures the tenant at submit
- [x] `TenantProvisioner` + `--provision-user=<name>` CLI; owner assertion at boot
- [x] Tests: `TenantContextTest`, `TenantRoutingDataSourceTest`, `TenantContextFilterTest`, `RememberMeTenantTest`,
      `OwnerPoolTest`, `SchemaUpdaterTest`, `OwnerTenantAssertionTest`, `IsolationIT`
      (OwnerPoolTest = `MultiUserBootIT`, RememberMeTenantTest and TenantContextFilterTest = `IsolationIT`)
- [x] Flag on, owner only: timings within 5% of Phase 0 (record numbers here)

## Phase 3 checklist

- [x] Tenant-scoped cache keys in `BestEffortService`, `PerformanceService`, `LoadService`, `RaceService` + `evict(tenant)`
- [x] Per-tenant `GarminTokenProvider` + `GarminSyncCoordinator` (+ per-tenant `isRunning`, `GarminSyncView`)
- [x] `SecretCipher` + `APP_TENANT_SECRET_KEY` (refuse to start if missing while enabled)
- [x] `CredentialResolver` (tenant setting, env key for OWNER only); `GarminProperties.configured` replaced
- [x] Garmin `AUTH` error per tenant surfaced as the "key verlopen" banner (D14); nothing deleted
- [x] Strava rate-budget counter (response headers), global backfill semaphore, tenant pause
- [x] Tests: `TenantCacheTest`, `GarminTenantKeyTest`, `GarminTenantGateTest` (per-tenant busy), `SecretCipherTest`, `StravaRateBudgetTest`, `GarminKeyBannerTest`;
      TenantJobRunnerTest and the one-tenant-fails test = `TenantRegistryAndJobsTest` (Phase 2)

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
| 2026-11-19 | Phase 2, flag ON, restored copy `running_restore_test`, local jar, owner only: start-up | 7.8-9.1 s "Started"; `Schema update 'wout': 0 statement(s)` on every boot (3 boots) |
| 2026-11-19 | Phase 2 timings, same jar, same copy, curl with session, flag ON vs OFF, 70 warm requests each, median | `/activities` on 0.173 / 0.179 s vs off 0.177 / 0.180 s; `/` on 0.078 / 0.078 vs off 0.080 / 0.078 (differences 0-2%). Heavy pages are noisy between identical runs (`/load` 0.55-0.69, `/race` 0.76-1.02 s for BOTH flag values over 20-sample runs): on 0.55-0.68 / 0.76-0.89, off 0.58-0.69 / 0.83-1.02. A first run looked 10-20% slower on `/activities`; it was the per-connection `ObjectProvider` lookup in the router (now resolved once) plus run-to-run noise. `allow_jdbc_metadata_access=false` alone changes nothing (checked with the flag off) |
| 2026-11-19 | Phase 2, provisioned empty tenant `testtenant` (scratch, local Postgres), flag ON | 20 schema statements, 18 tables; tenant `/activities` 0 rows, pages `/ /load /race /recovery /performance` 200; owner `/activities` still 606 rows; second boot `Schema update 'testtenant': 0 statement(s)`; both warm-ups logged `starting for 2 tenants`; scratch DBs `running_u_testtenant` and `running_control` dropped afterwards (not the owner's DB) |
| 2026-11-19 | Phase 3, same jar and restored copy `running_restore_test`, 6 pages, flag ON (with secret key) vs OFF, one cold request each | all 200 and same order of magnitude (`/` 2.1 vs 2.3 s, `/activities` 0.75 vs 0.58, `/load` 6.0 vs 7.1, `/race` 1.7 vs 1.3 s; cold, warm-ups running, not a timing gate). `/garmin/status` state `OK` with the env key under the flag ON (owner key still used). Flag ON without `APP_TENANT_SECRET_KEY`: refuses to start with a clear message, no secret printed. Start-up 8.5 s both. `Schema update 'wout': 0 statement(s)` |
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

- **2026-11-19** — Phase 2. Baseline suite green before starting. Built, in `runner/.../tenant/`: `TenantContext`/`TenantKey`,
  `TenantRegistry`, `TenantRoutingDataSource` (wraps the auto-configured owner pool through a static `BeanPostProcessor`, tenant
  pools 2/0/idle 2 min, fail closed), `TenantContextFilter` (after `AuthorizationFilter`, registration disabled so it is not added
  twice), `TenantSchemaUpdater` (+ guard, + unique-constraint churn filter, runs owner first in a `SmartInitializingSingleton`,
  owner refusal stops start-up, a broken tenant is skipped), `TenantJobRunner` (strava sync, garmin sync, race log, load/race
  warm-ups; flag off = job runs once like before), `TenantProvisioner` + `--provision-user=` CLI, `MultiUserEnvironmentPostProcessor`.
  Tests: 8 new classes (unit: context, routing, registry/jobs, schema guard, owner assertion + provisioner validation, Garmin gate;
  Testcontainers: `MultiUserBootIT`, `IsolationIT` with login, remember-me-only cookie, disabled tenant, same activity id in two
  tenants). Suite on Java 23: core 434, runner 220, 0 failures. Findings that changed the design (design updated, §5.5, §5.7,
  §8.2, Phase 2 text): (1) Hibernate 6.6 has no `SchemaUpdate` in core, the migrator SPI is used; (2) Hibernate re-creates every
  unique constraint on every run (also today's `ddl-auto: update`), so a literal "zero statements" needed
  `UniqueConstraintChurn`; `SKIP` loses constraints on new tables; (3) `spring.config.activate.on-property` checks presence, not
  value, and would have set `ddl-auto: none` with the flag OFF: replaced by an `EnvironmentPostProcessor` (37 tests caught the first
  attempt); (4) provisioning never drops on failure (hard rule 1 over the old §5.5 cleanup); (5) two Phase 3 items pulled forward so
  the Phase 2 acceptance is true: `TenantKey` cache keys and an interim OWNER-only guard on the Garmin coordinator. Flag-off check:
  `FlagOffContextTest` (no multi-user bean, `ddl-auto` still `update`, owner pool size 2) + manual run of the same jar with the flag
  off against the restored copy: login, `/ /activities /load /race` 200, no control DB created, no multi-user log lines. Manual,
  flag ON: see Measurements log. Never connected to the owner's database `running`: no backup needed (nothing ran against it); all
  runs used `running_restore_test` in the local container (stopped again). No secret in any log (grep 0). Open / left for later:
  `tenant-pause` default is still `0s` (Phase 3); `OwnerBootstrap`'s `InitializingBean` was replaced by an `initMethod` bean so the
  registry really starts after it (ordering bug found by the first IT run); `TenantContextFilter` answers 403 for a disabled tenant
  but does not end the session (Phase 7). Questions asked: none. Owner approved Phase 2 and asked for a commit (2026-11-19).

- **2026-11-19** — Phase 3. Baseline suite green. Built: `SecretCipher` (AES-256-GCM, `enc:v1:`, null on bad/foreign value),
  `CredentialResolver` + `TenantSettings` port, per-tenant `GarminTokenProvider` (state per tenant, key rotation drops the token,
  `evict`, `secrets()` = every known key/token + the env key, so redaction also holds for non-owner tenants), per-tenant
  `GarminSyncCoordinator` flags (the single executor still serialises tenants on purpose) with the "usable key" check coming from the
  resolver, `GarminSyncView`/`GarminSyncController` through the resolver, `GarminKeyBanner` (non-owner tenants with a stored AUTH
  error only; false without a DB read for the owner/flag off), `evict(tenant)` on the four caches, `StravaRateBudget` (read headers,
  70% of the 15-min or daily budget, 429 block, stale windows ignored) + `BackfillGate`, `app.multi-user.tenant-pause` now `5s`,
  `app.multi-user.secret-key` (`APP_TENANT_SECRET_KEY`) required when the flag is on. Decisions/deviations (design updated):
  (1) the OWNER's Garmin key is only the env key, never read from his DB (design said "setting, else env"; the owner has no
  `app_settings` table before Phase 4 and invariant 5 forbids the extra query); (2) no `TenantSettings` store yet: tenants have no key
  until Phase 4, so they get `NOT_CONFIGURED` and zero Garmin calls (tested); (3) `StravaApiClientImpl` GET calls now use `toEntity`
  to read headers: same requests, same bodies (the only change on the owner's Strava path; `StravaExternalIdTest` and the suite pass);
  (4) the banner has no link, `/settings` does not exist before Phase 4. Not built, belongs later: per-tenant backfill queue that uses
  the budget/gate and the progress page (Phase 4), `/settings`. Verified: core 434 + runner 241 tests, 0 failures; manual
  runs flag off and on against `running_restore_test` (see Measurements log), grep for the fake key/password/remember-me key/master
  key in both logs = 0. Never connected to the owner's `running`: no backup needed. I created and dropped the scratch DB
  `running_control` in the local container; `running-db` stopped again. No questions asked.

## Guardrail checklist before any commit

- [ ] No secret in a log line, template or exception message
- [ ] No route that can touch the owner tenant while the caller is another tenant
- [ ] No `DROP`/`TRUNCATE`/`DELETE`/`RENAME DATABASE` without a tenant guard (`running_u_` prefix, never the owner DB)
- [ ] No new `DataSource` bean, no Flyway, no `ddl-auto` change on the flag-off path
- [ ] Existing owner pages unchanged (numbers and timings)
- [ ] `docs/multi-user-design.md` updated if a decision changed, and the decision logged above
