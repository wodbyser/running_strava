# Multi-user Progress

Single source of truth for where this feature stands. **Read this first, update it last.**
Full design and rationale: `docs/multi-user-design.md` (self-contained, no session context needed).
Prompt to start a fresh agent on the next phase: `MULTI_USER_PROMPT.md` (same prompt every time).

**Last updated:** 2026-11-17 by: review session (design corrected, no code yet).

## Next session starts here

- **Next action:** start **Phase 0** (safety net + baseline measurements, no code change). All owner decisions are
  taken (D1–D14, D8 revised); there are no open questions. Do not start Phase 1 before the Phase 0 gate is green.
- **Nothing is implemented yet.** `main` is unchanged apart from documentation.
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
| 0 | Backup/restore drill, row counts, schema snapshot, page-timing baseline, backup-all script (no code change) | NOT STARTED | |
| 1 | `running_control`, `app_user`/`invite_code`/`auth_audit`, DB login, owner bootstrap | NOT STARTED | |
| 2 | Routing datasource, fail-closed filter, Hibernate start-up, `TenantSchemaUpdater`, tenant-aware schedulers/warm-ups, provisioner CLI | NOT STARTED | |
| 3 | Per-tenant caches, Garmin token provider, coordinator, `SecretCipher`/`CredentialResolver`, Strava rate budget | NOT STARTED | |
| 4 | `app_settings` + `activities.source`, `/register`, `/setup`, Strava OAuth `state`, Strava-only users, reset | NOT STARTED | |
| 5 | GARMIN-only projection, mode guard, capability matrix + UI panels | NOT STARTED | |
| 6 | `/admin/users`, backup-all nightly, deploy docs | NOT STARTED | |
| 7 | Hardening, session invalidation, delete-user, remove feature flag | NOT STARTED | |

## Phase 0 checklist

- [ ] Fresh dump via `deploy/migrate.sh backup` (do **not** use the old untracked `deploy/running.dump`)
- [ ] Restore into scratch DB `running_restore_test`; boot the current image against it
- [ ] Record row counts of all 18 tables (live and restored must match)
- [ ] Save `pg_dump --schema-only` of the live DB under `docs/multi-user-baseline/` (no data)
- [ ] Record median timings of `/`, `/activities`, `/load`, `/race` and start-up time in "Measurements log"
- [ ] `deploy/backup.sh` + `deploy/backup.ps1`: dump every `running*` DB + manifest; `migrate.sh backup` unchanged
- [ ] One manual backup-all run restored successfully before wiring it into the nightly timer
- [ ] No file under `runner/` or `core/` changed

## Phase 1 checklist

- [ ] `MultiUserProperties` (`app.multi-user.enabled` default `false`, `control-url`, `tenant-pause`)
- [ ] `ControlDb` (own Hikari + `JdbcTemplate`, **not** a `DataSource` bean), only with the flag on
- [ ] `running_control` auto-create (autocommit) + `CREATE TABLE IF NOT EXISTS` initializer
- [ ] `AppUserPrincipal` (Serializable), `ControlUserDetailsService`
- [ ] `SecurityConfig`: `InMemoryUserDetailsManager` when off (`matchIfMissing = true`), control-plane when on
- [ ] `OwnerBootstrap`: create on first boot, `encoder.matches` before re-hash, refuse a 2nd OWNER
- [ ] Refuse start-up with flag on + blank `APP_PASSWORD`, or flag on + H2 URL
- [ ] Tests: `OwnerBootstrapTest`, `FlagOffContextTest`
- [ ] Flag-off regression: existing suites pass, no connection to `running_control` at boot
- [ ] Flag on against restored copy: remember-me survives a restart

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
| | | |

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

## Guardrail checklist before any commit

- [ ] No secret in a log line, template or exception message
- [ ] No route that can touch the owner tenant while the caller is another tenant
- [ ] No `DROP`/`TRUNCATE`/`DELETE`/`RENAME DATABASE` without a tenant guard (`running_u_` prefix, never the owner DB)
- [ ] No new `DataSource` bean, no Flyway, no `ddl-auto` change on the flag-off path
- [ ] Existing owner pages unchanged (numbers and timings)
- [ ] `docs/multi-user-design.md` updated if a decision changed, and the decision logged above
