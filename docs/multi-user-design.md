# Multi-user design: one app, one database per user

**Status:** proposed, not started. Owner decisions are in §3 and are binding.
**Written:** 2026-11-17. **Reviewed and corrected:** 2026-11-17 (second session, see §20 for what changed and why).
**Audience:** the implementing agent. This document is self-contained: it assumes no memory of the session that produced it.
**Progress tracker:** `MULTI_USER_PROGRESS.md` (read that first, update it last).

---

## 0. Samenvatting voor de eigenaar (Wout)

Wat er komt:

- Op het loginscherm komt een knop **"Nieuw account"**. Die werkt alleen met een invite-code die jij deelt.
- Elke nieuwe gebruiker krijgt **een eigen, volledig lege PostgreSQL-database**. Jouw database `running` wordt niet
  gekopieerd, niet omgebouwd en niet hergebruikt: die blijft exact zoals hij is.
- Die gebruiker plakt in een setup-wizard zijn **Garmin personal API key** en/of klikt **"Koppel Strava"**.
  Strava heeft geen "key om te plakken": een gebruiker koppelt zijn account via de Strava-login (OAuth) op jouw
  Strava-app. Iemand zijn eigen Strava client-id/secret laten plakken is volgens het Strava API-beleid (2026)
  uitdrukkelijk niet toegelaten, dus dat bouwen we niet.
- Heeft hij alleen Strava → alles komt uit Strava. Heeft hij alleen Garmin → alles komt uit Garmin en er wordt
  **geen enkele Strava-aanroep** gedaan. Heeft hij beide → precies zoals de app vandaag voor jou werkt.
- Jij blijft inloggen met dezelfde gebruikersnaam, hetzelfde wachtwoord, dezelfde URL en dezelfde database.
  Aan jouw datapad verandert niets: geen `user_id`-kolom, geen filter dat vergeten kan worden, geen migratie van
  jouw rijen, geen Flyway, `ddl-auto: update` blijft zoals vandaag.
- **Zolang de feature-vlag uit staat, draait voor jou exact de huidige code.** De multi-user-onderdelen worden dan
  zelfs niet aangemaakt (geen extra databaseverbinding, geen extra filter).

De belangrijkste veiligheidsregel in dit ontwerp: **als de app niet 100% zeker weet bij welke gebruiker een verzoek
hoort, weigert hij het verzoek.** Er is geen enkele codeflow die terugvalt op jouw database. Dat is afdwingbaar en
wordt met tests bewezen (zie §11 en §15).

De enige wijziging aan jouw database in het hele plan: er komen twee dingen bij, een nieuwe tabel `app_settings`
en een kolom `activities.source`. Beide zijn toevoegingen (geen wijziging of verwijdering) en ze komen er op
dezelfde manier als elke nieuwe tabel vandaag: via Hibernate `ddl-auto: update`, pas nadat er een geverifieerde
back-up is gemaakt.

Wat jij zelf moet regelen voor andere Strava-gebruikers (zie §19): een **Strava-abonnement** op jouw account (sinds
juni 2026 verplicht voor API-ontwikkelaars) en in de Strava API-instellingen de **capaciteit van 1 naar 10
atleten** zetten (zonder review mogelijk). Meer dan 9 andere Strava-gebruikers kan niet zonder review door Strava.

---

## 1. Goal and non-goals

### Goal

1. A second person can create an account and use the app with their own Strava and/or Garmin credentials.
2. That person's data is invisible to everyone else, and vice versa, by construction (separate database).
3. Data-source modes: `STRAVA`, `GARMIN`, `BOTH`. `GARMIN` must not read or call Strava at all.
4. The owner's current experience, data, credentials, URL and performance stay exactly the same.
5. An agent with no context can implement this from this document plus `MULTI_USER_PROGRESS.md`.

### Non-goals

- Sharing data between users, leaderboards, coaching other users, family accounts.
- An admin UI for anything other than user/invite management.
- Multi-tenant analytics across users.
- Moving the owner's existing data into a new schema or database.
- OAuth for Garmin (the personal API uses a static API key exchanged for a JWT; that stays).

---

## 2. Verified current state (do not trust memory, these are checked facts)

### 2.1 Runtime and layout

| Item | Reality |
|---|---|
| Modules | `core` (Spring-free domain + use cases + SPI), `runner` (Spring Boot 3.4, JPA, Thymeleaf) |
| Java / Kotlin | Java 23, Kotlin 2.1.20 |
| Database | PostgreSQL 17, one database `running`, Hibernate `ddl-auto: update`, **no Flyway, no Liquibase** |
| DataSource | **No custom `DataSource` bean**: Spring Boot auto-configures one Hikari pool from `spring.datasource.*`; `spring.datasource.hikari.maximum-pool-size: 2` (all profiles), `minimum-idle: 0` (prod) |
| Hibernate | Spring Boot 3.4 → Hibernate 6.6. Explicit dialect set in every profile. `spring.jpa.open-in-view` not set (default `true`) |
| Id generation | Natural ids (Strava/Garmin ids, dates) or `GenerationType.IDENTITY` (`race_goal`, `race_prediction_log`, `garmin_raw_payload`). **No `SEQUENCE` generators, no second-level cache** — both matter for one EntityManagerFactory over many databases (§5.7) |
| Dev profile | H2 file `data/running.mv.db` in PostgreSQL mode (`application-dev.yml`, own `ddl-auto: update`) |
| Docker image | `Dockerfile` runs an AppCDS training boot against in-memory H2 with default properties; it must keep booting with the flag at its default (`false`) |
| Deploy | `deploy/docker-compose.yml`, project `running`, volume `running_pgdata`, app on `127.0.0.1:${APP_PORT:-8080}`, `mem_limit: 2g`, Postgres `mem_limit: 1g`, DB user `postgres` (superuser, so `CREATE DATABASE` works) |
| Backup today | `deploy/migrate.sh backup` / `migrate.ps1 backup` dumps **only** the `running` database (14-day retention), via `docker compose exec` into the Postgres container. **The app container has no `pg_dump`.** |
| `deploy/running.dump` | Exists on disk but is **not tracked in git** and dates from 2026-11-01: not a current backup. Always make a fresh dump. |
| Session | cookie `SameSite=Lax`, remember-me 90 days, CSRF disabled on purpose |
| App threads | Tomcat `max: 20` (prod) |

### 2.2 Authentication (the thing being replaced)

- `runner/src/main/kotlin/com/running/config/SecurityConfig.kt`: one in-memory user built from
  `app.security.username` / `app.security.password` (`APP_USERNAME`, `APP_PASSWORD`). Roles: `OWNER`.
- Blank `APP_PASSWORD` ⇒ `loginEnabled == false` ⇒ **the app is open** (local use only). This must keep working.
- `AppSecurityProperties` (`app.security.*`): `username`, `password`, `rememberMeKey`.
- `LoginController` → template `login.html`. Login form posts to `/login`.
- Strava OAuth: `StravaOAuthController` (`/auth/strava`, `/callback`) stores the token via
  `ExchangeStravaCode` → `StravaTokenRepositoryImpl`, which **always writes row id = 1**. `/callback` takes only
  `code`; there is **no `state` parameter today** (Phase 4 adds one).
- Remember-me is `TokenBasedRememberMeServices`: its cookie signature includes the user's *encoded* password. A
  stable hash in the control DB (§6.3) therefore keeps cookies valid across restarts; re-hashing on every boot
  would silently log everybody out.

### 2.3 Persistence: 18 entity tables (22 in the dev copy), all implicitly single-tenant

`@Table` entities (all in `runner/src/main/kotlin/.../db/`):

```
activities              activity_streams        activity_laps          sync_status
strava_tokens           race_goal               race_prediction_log
garmin_daily_hrv        garmin_daily_sleep      garmin_daily_resting_hr
garmin_daily_body_battery  garmin_body_battery_sample  garmin_daily_stress
garmin_daily_steps      garmin_activity_ref     garmin_hr_zones
garmin_raw_payload      garmin_sync_status
```

Singleton rows that assume "there is exactly one user":

- `strava_tokens.id = 1`
- `sync_status.id = 1`
- `garmin_sync_status.id = 1`

`activities.id` is the **Strava activity id** (no generator). `garmin_activity_ref.activity_id` is the Garmin
activity id. `ActivityRepository` has no tenant parameter anywhere — that is exactly why "database per user" is the
cheap option: every repository keeps working unchanged, it just talks to a different database.

### 2.4 Credentials

- Strava: `strava.client-id` / `client-secret` / `redirect-uri` from env, one app for everything.
  Tokens per athlete live in `strava_tokens` (one row).
- Garmin: `garmin.api-key` (`GARMIN_PERSONAL_API_KEY`) from env. `GarminTokenProvider` exchanges it for a JWT and
  keeps the JWT **in memory only**. `GarminProperties.configured` is a global check.
  Garmin personal API keys are created by each user on Garmin Connect **web** (Settings → Personal API Key), shown
  once, and **expire after 30, 90, 180 or 365 days**; access tokens live 24 h, no refresh token. Expiry is therefore
  a normal event, not an edge case (D14).
- `GarminRedactor { tokens.secrets() }` scrubs key + JWT from log payloads.

### 2.4a External constraints (checked 2026-11-17, re-check before Phase 4)

| Provider | Constraint | Consequence |
|---|---|---|
| Strava | Since 2026-06-01 the developer (owner) needs a **Strava subscription** for a Standard-tier app | Precondition for Phase 4, owner action |
| Strava | New apps have athlete capacity **1** ("Single Player Mode"); self-service upgrade to **10** without review, more needs Strava's review | Max 9 extra Strava users. D5 (4–10 users) fits only after the owner upgrades in the Strava API settings |
| Strava | Rate limits are **per app**, shared by all athletes: 200/15 min & 2,000/day overall, 100/15 min & 1,000/day read (capacity 1); 400 & 4,000 overall, 200 & 2,000 read (capacity 10) | §10.4 arithmetic uses the *read* limits |
| Strava | API policy: entering your own client id/secret into someone else's platform is non-compliant; show each athlete only their own data; no Strava data in AI context windows; delete within 30 days after deauthorization | Per-user client id/secret is **dropped** (not "later"). `/ai` and delete-user need care (§17 R13–R14) |
| Garmin | Personal API is "for personal use … not intended for commercial applications serving multiple end users" | Fine for a small non-commercial group where each person creates their own key; never charge, never advertise (R15) |

### 2.5 Scheduling and background work (all global today)

| Job | Where | Cron |
|---|---|---|
| Strava sync | `StravaSyncScheduler.syncNewActivities` | `strava.sync.cron`, default `0 0 6 * * *` |
| Garmin sync | `GarminSyncScheduler.syncGarmin` | `garmin.sync.cron`, default `0 30 6 * * *` |
| Race prediction log | `RaceService` (`@Scheduled`) | `garmin.race.log-cron`, default `0 0 7 * * *` |
| Load warm-up | `LoadService.warmUpInBackground` (`ApplicationReadyEvent`) | once at boot |
| Race warm-up | `RaceService.warmUpInBackground` (`ApplicationReadyEvent`) | once at boot |

### 2.6 In-memory state that is shared across the JVM (the landmines)

These are the reason a naive "just add sessions" change would corrupt results. All of them are per-JVM singletons
and would be **shared between users**:

| Location | State | Why it breaks with 2 users |
|---|---|---|
| `BestEffortService` | `streamCache: ConcurrentHashMap<Long, Map<Double, Double>>` | keyed by activity id only |
| `BestEffortService` | `usableStreamCache: ConcurrentHashMap<Long, Boolean>` | same |
| `PerformanceService` | `cache: ConcurrentHashMap<Triple<Long, Int?, String>, RunAnalysis>` | activity id in key |
| `LoadService` | `cache: ConcurrentHashMap<Triple<Long, String, String>, Heavy>` | activity id in key |
| `RaceService` | `halvesCache: ConcurrentHashMap<Long, Halves>` | activity id in key |
| `GarminTokenProvider` | `token`, `expiresAt` | one cached JWT for one key |
| `GarminSyncCoordinator` | `AtomicBoolean running` + one single-thread executor | user B gets `BUSY` while user A syncs |
| `GarminProperties.configured` | global key check | one key for the whole app |
| `LoadService` / `RaceService` warm-up | fire once at `ApplicationReadyEvent` on raw `Thread`s | only the default tenant warms up; with routing on they would run with **no tenant bound** |
| Hibernate / Spring start-up | EntityManagerFactory bootstrap opens a connection (JDBC metadata, `ddl-auto: update`) on the main thread | with routing on and no tenant bound, **start-up fails** (§5.7) |
| `FrontendController` `/sync`, `/fetch-all`, `/fetch-remaining`, `/backfill-*` | run synchronously on the request thread | fine for routing (tenant bound by the filter), but a first Strava backfill blocks a Tomcat thread for a long time (§10.4) |

**Rule:** every item in this table must become tenant-scoped or tenant-aware (Phases 2 and 3). Nothing else in
`core` holds mutable state: the domain and analysis code is pure. Verified by grep: the only mutable singletons in
`runner` are the five `ConcurrentHashMap` caches, the `AtomicBoolean` in the coordinator and the two `var`s in the
token provider.

### 2.7 Front end

- `layout.html` sets `data-garmin-sync` from a model attribute; `FrontendController` reads
  `GarminSyncCoordinator.isRunning` globally.
- Pages: `/`, `/activities`, `/activity/{id}`, `/recovery`, `/performance`, `/load`, `/load/response`, `/race`,
  `/race/history`, `/ai`.
- UI rules are binding: `docs/ui-guidelines.md` (conclusion first, charts over tables, plain Dutch, every period
  adjustable, check in a browser before reporting).

---

## 3. Decisions (binding, taken with the owner on 2026-11-17)

| # | Decision | Choice | Why |
|---|---|---|---|
| D1 | Isolation model | **One PostgreSQL database per user**, one app instance | A forgotten `WHERE` cannot leak another user's data because the connection itself points elsewhere. Owner's DB is not touched. |
| D2 | Who can register | **Invite code only** (`APP_SIGNUP_CODE`) | The app is publicly reachable through Tailscale Funnel. No open signup. |
| D3 | Strava credentials | **Owner's Strava app + OAuth per user** | Users only click "Koppel Strava"; the owner keeps one client id/secret. Per-user own client id/secret is **not allowed** by the Strava API policy (§2.4a) and is dropped, not postponed. |
| D4 | Garmin capability gap | **Accepted**, with honest UI notices | The Garmin personal API used here has no streams/laps. Garmin-only users get everything that can be computed from summary + wellness data. |
| D5 | Expected scale | **4–10 users** | Sequential nightly sync, small pools (2 connections per tenant), no queue/worker infrastructure. |
| D6 | Owner's login | Unchanged username/password/URL. `APP_USERNAME`/`APP_PASSWORD` remain the source of truth on every boot (§6.3) | Changing `APP_PASSWORD` in `.env` must keep working. |
| D7 | Source of the user list | New database `running_control` (control plane), separate from every tenant database | Login must work before a tenant is known. Keeps tenant DBs free of auth data. |
| D8 | Schema ownership | **Hibernate schema update per database** (same mechanism as today). Owner's DB keeps `ddl-auto: update` untouched; no Flyway. Changed 2026-11-17 by the owner (was: Flyway + `ddl-auto: none`) | Flyway would change the owner's app from Phase 0 on (even with the flag off), add `flyway_schema_history` to his DB and force a hand-written migration for every future entity. Hibernate update gives every tenant DB exactly the schema the owner's DB gets, additively. |
| D9 | Feature flag | `app.multi-user.enabled`, default **false** | With the flag off the owner runs the old code path exactly: no router, no filter, no extra pool, no control DB connection. All multi-user beans are `@ConditionalOnProperty`. |
| D10 | Garmin key storage | Encrypted (AES-256-GCM) in the tenant DB, master key from env `APP_TENANT_SECRET_KEY` | A tenant DB dump alone is useless. The owner's env key keeps working as an override. |
| D11 | Switching source later | **Only via a full guarded reset** (archive the tenant DB, provision a fresh one). Never for the OWNER | No mixed/duplicate activities, no merge logic, no analysis drift. |
| D12 | Language | **Dutch only** | No second code path for templates or explainer panels. |
| D13 | Owner's view of other users | Counts + last sync only (created, last login, last sync, activity count, key present masked, status) | Enough to help someone; never another user's training content. |
| D14 | Expired/revoked Garmin key | Banner "key verlopen of ingetrokken" + link to `/settings`, all pages keep working on stored data, **nothing deleted**, sync stops with an AUTH error | Honest, no data loss, no mail infrastructure needed. |

---

## 4. Architecture

```
                     ┌──────────────────────────────────────────────┐
   browser ───────►  │ Spring Boot app (one JVM, one port)          │
                     │                                              │
                     │  SecurityFilterChain                         │
                     │    ├─ login (control DB, bcrypt)             │
                     │    └─ TenantContextFilter  ── ThreadLocal ──┐│
                     │                                             ││
                     │  TenantRoutingDataSource  ◄─────────────────┘│
                     │    ├─ tenant "wout"  → running        (owner)│
                     │    ├─ tenant "anna"  → running_u_anna        │
                     │    └─ tenant "piet"  → running_u_piet        │
                     │            │                                 │
                     │  JPA / repositories (unchanged code)         │
                     └────────────┼─────────────────────────────────┘
                                  │
                     ┌────────────▼─────────────────────────────────┐
                     │ PostgreSQL 17 (one instance)                  │
                     │  running_control  : users, invites, audit     │
                     │  running          : owner data (untouched)    │
                     │  running_u_<slug> : one per extra user        │
                     └──────────────────────────────────────────────┘
```

Why this shape:

- **No `user_id` anywhere.** 18 tables, ~40 repositories/queries and the whole `core` analysis layer stay as they
  are. Zero risk of a missing filter, zero migration of owner rows, zero query-plan change for the owner.
- **Physical isolation.** Even a SQL injection or a bug in a future feature can only reach the database the
  connection points at.
- **Per-tenant reset/deletion is trivial and loss-free**: rename the database to an archive name (§9.3); the
  owner drops archives by hand after a dump.
- **Cost:** one extra connection pool per tenant (2 connections) and one ThreadLocal read per request. Measured
  budget in §13.

What it is not: it is *not* a second code path. Same controllers, same services, same templates. Only *which
database* and *which credentials* differ, plus a capability flag for the UI.

---

## 5. Tenant routing (the core mechanism)

### 5.1 Control plane schema (`running_control`)

New package `runner/src/main/kotlin/com/running/tenant/db/`.

```sql
CREATE TABLE app_user (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(64) NOT NULL UNIQUE,      -- lowercase, ^[a-z0-9][a-z0-9_.-]{2,31}$
    password_hash   VARCHAR(255) NOT NULL,            -- {bcrypt}...
    role            VARCHAR(16) NOT NULL,             -- OWNER | USER
    tenant_id       VARCHAR(40) NOT NULL UNIQUE,      -- "wout", "anna"
    tenant_db       VARCHAR(63) NOT NULL,             -- "running", "running_u_anna"
    status          VARCHAR(16) NOT NULL,             -- PENDING | ACTIVE | DISABLED
    created_at      TIMESTAMPTZ NOT NULL,
    last_login_at   TIMESTAMPTZ NULL
);

CREATE TABLE invite_code (
    code_hash       VARCHAR(255) PRIMARY KEY,         -- SHA-256 of the code, never the code itself
    created_at      TIMESTAMPTZ NOT NULL,
    expires_at      TIMESTAMPTZ NULL,
    used_by         BIGINT NULL REFERENCES app_user(id),
    used_at         TIMESTAMPTZ NULL
);

CREATE TABLE auth_audit (
    id              BIGSERIAL PRIMARY KEY,
    at              TIMESTAMPTZ NOT NULL,
    username        VARCHAR(64) NULL,
    action          VARCHAR(40) NOT NULL,             -- SIGNUP, LOGIN_FAIL, KEY_SET, KEY_CLEARED, ...
    detail          VARCHAR(300) NULL,                -- never a secret
    ip              VARCHAR(64) NULL
);
```

`tenant_id` is the routing key (in-memory), `tenant_db` is the physical database name. Both are immutable after
creation; changing them is not supported (an admin action would need a separate, explicit migration).

### 5.2 Datasources: leave Spring Boot's pool alone, wrap it only when the flag is on

Two bugs to avoid (both present in the first draft of this document):

- Declaring **any** bean of type `DataSource` (e.g. a `controlDataSource`) makes Spring Boot's
  `DataSourceAutoConfiguration` back off (`@ConditionalOnMissingBean(DataSource::class)`). The owner's pool would
  then be whatever the custom code builds, even with the flag off.
- `DataSourceProperties.initializeDataSourceBuilder().build()` does **not** bind `spring.datasource.hikari.*`.
  The owner's pool would silently grow from 2 to Hikari's default 10 connections and `minimum-idle: 0` would be lost.

Therefore:

```kotlin
/** Not a DataSource bean on purpose: auto-configuration must keep building the owner's pool. */
class ControlDb(val jdbc: JdbcTemplate, private val pool: HikariDataSource) : AutoCloseable {
    override fun close() = pool.close()
}

@Configuration
@ConditionalOnProperty("app.multi-user.enabled", havingValue = "true")
class MultiUserDataConfig {
    @Bean(destroyMethod = "close")
    fun controlDb(props: MultiUserProperties): ControlDb { /* Hikari, url = props.controlUrl, max 2, min idle 0 */ }

    /** Wraps the auto-configured owner pool; the pool itself (and its hikari settings) is untouched. */
    @Bean
    fun tenantRoutingPostProcessor(...) = object : BeanPostProcessor {
        override fun postProcessAfterInitialization(bean: Any, name: String): Any =
            if (name == "dataSource" && bean is HikariDataSource) TenantRoutingDataSource(ownerTenantId, bean, tenantPools)
            else bean
    }
}
```

Hard requirements:

1. With `app.multi-user.enabled=false` **no multi-user bean exists** (`@ConditionalOnProperty` on every
   multi-user `@Configuration`, `@Component` and filter). The primary `DataSource` is Spring Boot's own auto-configured
   Hikari pool, i.e. **today's behaviour, not a re-implementation of it**. This is the owner's escape hatch and must
   stay until Phase 7.
2. The control DB is never a `DataSource` bean, never `@Primary`, never used by JPA. Plain `JdbcTemplate`.
3. With the flag on, the owner's pool is still the auto-configured one (same `spring.datasource.*`,
   same `hikari.*`); it is only wrapped. A test asserts `maximumPoolSize == 2` on the wrapped owner pool.
4. Control DB URL: new property `app.multi-user.control-url` (env `APP_CONTROL_DB_URL`), defaulting to the owner's
   URL with the database name replaced by `running_control`. Same username/password as `spring.datasource.*`.

### 5.3 Router and tenant context

```kotlin
object TenantContext {
    private val holder = ThreadLocal<String>()
    fun current(): String? = holder.get()
    fun currentOrFail(): String = holder.get() ?: throw TenantNotResolvedException()
    fun <T> asTenant(id: String, block: () -> T): T {
        val previous = holder.get()
        holder.set(id)
        return try { block() } finally { if (previous == null) holder.remove() else holder.set(previous) }
    }
}

class TenantRoutingDataSource(
    private val ownerTenantId: String,
    private val ownerDataSource: DataSource,
    private val pools: (tenantId: String) -> DataSource,     // resolves tenant_db from the registry
) : AbstractDataSource() {
    private val tenantPools = ConcurrentHashMap<String, DataSource>()
    override fun getConnection() = dataSource().connection
    override fun getConnection(u: String?, p: String?) = dataSource().connection
    private fun dataSource(): DataSource {
        val id = TenantContext.currentOrFail()               // fail closed, never null
        return if (id == ownerTenantId) ownerDataSource
        else tenantPools.computeIfAbsent(id) { pools(it) }
    }
    fun closeTenant(id: String) { (tenantPools.remove(id) as? HikariDataSource)?.close() }
}
```

`TenantNotResolvedException` maps to **HTTP 500** with a generic page. It must never fall back.

Tenant pools: `HikariDataSource` named `tenant-<id>`, `maximumPoolSize = 2`, `minimumIdle = 0`,
`idleTimeout = 2 min`, same username/password as the owner, URL = owner URL with the database name replaced by the
validated `tenant_db`. The `tenant_db` lookup comes from the in-memory `TenantRegistry` (loaded at boot and
updated on signup/disable), never from a per-request SQL query.

### 5.4 Resolution: servlet filter inside the security chain

```kotlin
class TenantContextFilter(private val registry: TenantRegistry) : OncePerRequestFilter() {
    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? AppUserPrincipal
        if (principal == null) { chain.doFilter(req, res); return }     // /login, /register, /css
        registry.requireActive(principal.tenantId)                       // throws if disabled
        TenantContext.asTenant(principal.tenantId) { chain.doFilter(req, res) }
    }
}
```

Registered with `http.addFilterAfter(tenantContextFilter, AuthorizationFilter::class.java)`, i.e. as the **last**
filter of the security chain and before `DispatcherServlet`. Not after `SecurityContextHolderFilter`: at that point a
request that is authenticated by the remember-me cookie (the first request after every restart, and the normal case
for the owner with a 90-day cookie) has no principal yet, so the filter would skip binding and the request would
fail with `TenantNotResolvedException`. Nothing may touch a tenant database before this filter.
Do not also register the filter as a plain servlet `@Component` (Spring Boot would add it a second time, outside
the security chain); expose it with `FilterRegistrationBean(...).isEnabled = false` or don't make it a bean.

Rules:

- Unauthenticated paths (`/login`, `/register`, `/css/**`, `/js/**`, `/img/**`, `/favicon.ico`, `/error`) never
  open a tenant connection. If they accidentally do, they hit the fail-closed exception → that is a bug, and a test
  must catch it (see §15). (`/actuator/**` does not exist in this app; do not add it.)
- Login itself (`UsernamePasswordAuthenticationFilter`, `RememberMeAuthenticationFilter`) only reads the control DB
  through `ControlUserDetailsService`, which uses `ControlDb`, never the routed `DataSource`. Writing
  `last_login_at` also goes to the control DB.
- The tenant id comes from the **principal** (`AppUserPrincipal`, a `UserDetails` implementation), not from a path
  variable, header, cookie or form field. No user-controlled value may select a database.
- `TenantContext` must also be set around: `@Scheduled` methods, `@Async` tasks, `ApplicationReadyEvent` jobs, the
  raw warm-up `Thread`s and the Garmin executor (§10). ThreadLocal does not propagate by itself; capture
  `TenantContext.currentOrFail()` on the submitting thread and wrap the `Runnable` explicitly.

### 5.5 Provisioning a tenant

`TenantProvisioner.provision(username)`:

1. Validate `username` → `tenantId` = sanitized slug, `tenantDb` = `running_u_` + slug, both matched against
   `^[a-z][a-z0-9_]{2,30}$`. Reject anything else. **Never** interpolate unvalidated text into SQL/DDL. Reject the
   names `running`, `running_control`, `postgres`, `template0`, `template1` explicitly.
2. Insert the `app_user` row with `status = PENDING` in the control DB (the `UNIQUE` constraints make a second
   concurrent signup for the same name fail here, before any database exists).
3. `CREATE DATABASE "<tenantDb>"` on a maintenance connection to the `postgres` database with **autocommit on**
   (`CREATE DATABASE` cannot run inside a transaction).
4. `<tenantDb>` is empty → `TenantSchemaUpdater` creates the full schema inside `TenantContext.asTenant(tenantId)`
   (§8).
5. Seed tenant settings: `data_mode = UNSET`, `onboarding_complete = false`.
6. Set `status = ACTIVE`, add the tenant to `TenantRegistry`. If any step fails: remove it from the registry, close the
   tenant pool and report. **Nothing is dropped** (corrected in Phase 2: hard rule "the app never drops a database"
   wins over the earlier cleanup idea): the `PENDING` row and the half-made database stay, and running the
   provisioner again for the same name resumes (database exists → schema update → `ACTIVE`).
7. Log an `auth_audit` row. Never log the password or any key.

Idempotency: running the provisioner twice for the same user must be a no-op or a clean failure, never a second
database. At boot, `PENDING` rows older than 10 minutes are logged as a warning, never auto-dropped.

### 5.6 Database naming and the owner

| User | tenant_id | tenant_db |
|---|---|---|
| wout (OWNER) | `wout` | **`running`** (the existing DB, never renamed, never recreated) |
| anna | `anna` | `running_u_anna` |

On boot with multi-user enabled, the app parses the database name out of `spring.datasource.url` and asserts it
equals the `tenant_db` of the OWNER row. If not → **refuse to start**. This makes a misconfigured deploy loud
instead of silently pointing the owner at the wrong database.

### 5.7 Start-up and Hibernate with one EntityManagerFactory over many databases

With routing on, the first `getConnection()` happens while Spring builds the EntityManagerFactory on the main
thread, **before** any request or tenant exists. A fail-closed router makes start-up fail there. Do not solve this
with a "default tenant". Instead, only when the flag is on:

1. `spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false` (Hibernate ≥ 6.5; the dialect is
   already explicit in every profile), so Hibernate does not open a connection during bootstrap. Also
   `spring.sql.init.mode=never` (the SQL initializer would otherwise open a connection).
2. `spring.jpa.hibernate.ddl-auto=none` **for the flag-on path only**. Implemented as `MultiUserEnvironmentPostProcessor`
   (`META-INF/spring.factories`). Do **not** use a multi-document `spring.config.activate.on-property`: it only tests
   that the property *exists* (not its value) and, because `application.yml` always defines `app.multi-user.enabled`,
   it would also switch `ddl-auto` to `none` with the flag off (this happened in the first attempt and 37 tests caught it).
   Measured on the restored owner copy: no other start-up connection needed fixing, and the SQL/timings are the same
   as with metadata access on. Schema update is then done explicitly by
   `TenantSchemaUpdater` (§8), owner first, inside `TenantContext.asTenant(...)`, before `ApplicationReadyEvent`.
   The owner's schema result is identical to today's `ddl-auto: update`.
3. Boot once with the flag on as the **first** step of Phase 2 and fix every other start-up connection the same
   way (bind the owner explicitly in that specific component, never in the router).

One EntityManagerFactory serving several databases is safe here only because of facts verified in §2.1. Keep them
true (a test or a review rule):

- No `GenerationType.SEQUENCE`/`AUTO` with `allocationSize > 1`: the pooled optimizer keeps id blocks in memory per
  EMF and would hand tenant B ids fetched from tenant A's sequence.
- No Hibernate second-level or query cache (they are keyed by entity id, not by database).
- Open-session-in-view is fine: the `EntityManager` is opened inside `DispatcherServlet`, after the filter.

---

## 6. Authentication, registration, onboarding

### 6.1 Components

- `AppUserPrincipal` (`UserDetails`): username, password hash, role, `tenantId`, `enabled`.
- `ControlUserDetailsService` on `controlDataSource` (plain JDBC or a second `EntityManagerFactory`; **recommend
  plain `JdbcTemplate`** to avoid two JPA units).
- `SecurityConfig` keeps the same shape: form login, `loginPage("/login")`, `defaultSuccessUrl("/", false)`,
  remember-me (`key` from `APP_REMEMBER_ME_KEY`), logout. CSRF stays disabled; cookie stays `SameSite=Lax`.
- Open mode (blank `APP_PASSWORD`, multi-user disabled) keeps the current `permitAll` branch. With multi-user
  enabled and a blank `APP_PASSWORD` → refuse to start **in every profile** (without login there is no principal,
  so every page would fail closed anyway).
- Multi-user requires PostgreSQL. With the flag on and an H2 URL (dev profile) → refuse to start with a clear
  message. The dev profile stays single-user.

### 6.2 Request paths

| Path | Auth | Notes |
|---|---|---|
| `GET /login` | open | adds "Nieuw account" link only when `APP_SIGNUP_CODE` is set |
| `GET /register` | open | 404 when signup is off |
| `POST /register` | open | invite code + username + password; rate limited |
| `GET /setup` | authenticated | wizard; only reachable while the tenant has no data/credentials |
| `POST /setup/garmin` | authenticated | paste key, "Test" runs one harmless API call |
| `POST /setup/garmin/clear` | authenticated | clears the key (tenant only) |
| `GET /auth/strava` | authenticated | adds a signed `state` bound to the tenant |
| `GET /callback` | authenticated | validates `state`, exchanges the code into the **current tenant DB** |
| `GET /admin/users` | OWNER only | list, disable, invite codes, per-user sync status |
| `POST /admin/users/{id}/disable` | OWNER only | blocks login and closes the pool |
| `GET /settings` | authenticated | replace Strava/Garmin credentials, see mode |
| `POST /settings/reset` | non-OWNER only | D11: archive this tenant's DB, provision a fresh one, back to `/setup` |

`/setup` is hidden once `onboarding_complete = true` (requirement: "enkel voor lege data"). Credential rotation
stays possible from `/settings` but **changing the data-source mode while data exists is blocked** (§9.3).

### 6.3 Owner bootstrap (must not break `APP_PASSWORD` changes)

At boot, with multi-user enabled:

1. If no `app_user` with role `OWNER` exists → create it from `APP_USERNAME` / `APP_PASSWORD`, `tenant_id=wout`,
   `tenant_db=<db from DB_URL>`, role `OWNER`.
2. If it exists and `APP_PASSWORD` is non-blank → `if (!encoder.matches(APP_PASSWORD, storedHash))` store a new
   hash. **Never compare hashes as strings**: BCrypt salts differ every time, so a string compare would re-hash on
   every boot and invalidate every remember-me cookie (§2.2). This preserves today's behaviour: editing `.env`
   changes the owner's password.
3. If `APP_PASSWORD` is blank → leave the hash alone (do not lock the owner out) and log a warning.
4. (Implemented in Phase 1: the owner's `tenant_id` is the lowercased `APP_USERNAME`, which must match `^[a-z][a-z0-9_]{2,30}$` with the flag on; the OWNER row's `tenant_db` must equal the database of `spring.datasource.url`, else start-up refuses.)
5. `APP_USERNAME` change is **not** supported automatically (it would rename the owner row); refuse to start with a
   clear message instead of silently creating a second OWNER.

### 6.4 Signup

Requirements:

- `APP_SIGNUP_CODE` non-blank and length ≥ 16, otherwise signup is off (link hidden, `/register` → 404).
- Codes are compared in constant time (`MessageDigest.isEqual` on SHA-256 digests). The env code is **reusable**
  until the owner changes or blanks it in `.env` (needed in Phase 4, before `/admin/users` exists). From Phase 6 on,
  the owner can also generate **single-use** codes on `/admin/users`; those are stored only as SHA-256 in
  `invite_code`.
- Rate limit: max 5 attempts per IP per 15 minutes, in-memory (no new dependency). Log failures to `auth_audit`.
  Behind Tailscale Funnel the client IP comes from `X-Forwarded-For` (prod has `forward-headers-strategy:
  framework`); verify in Phase 4 that `request.remoteAddr` is the real client and not the proxy, otherwise one
  person can lock out everyone. Rate-limit `POST /login` failures per (IP, username) pair, never per username alone
  (that would let a stranger lock the owner out).
- Password: minimum 10 characters, BCrypt via `DelegatingPasswordEncoder` (same encoder as today).
- On success: provision tenant → auto-login → redirect `/setup`.

---

## 7. Secrets

### 7.1 What is stored where

| Secret | Location | Protection |
|---|---|---|
| Owner's Garmin key | `.env` (unchanged) | env only, never in DB |
| Other users' Garmin key | tenant DB `app_settings` (`garmin.api_key`) | AES-256-GCM, `enc:v1:` prefix, master key `APP_TENANT_SECRET_KEY` |
| Strava tokens (all users) | tenant DB `strava_tokens` | DB only; tokens already stored in clear today (unchanged for the owner) |
| Strava client id/secret | env (one app) | unchanged |
| User passwords | control DB, `{bcrypt}` | unchanged encoder |

`APP_TENANT_SECRET_KEY` = base64 of exactly 32 random bytes. If it is missing or malformed and multi-user is
enabled, the app **refuses to start** (a silent fallback to plaintext storage is forbidden).

### 7.2 Rules

- A key may be shown in the UI **once** in masked form (last 4 characters only). Never re-readable in full.
- Never log: key, JWT, access/refresh token, password, invite code. Extend the existing redaction
  (`GarminRedactor`) and add the tenant key to `secrets()` for **every** tenant, not just the default one.
- The `GarminTokenProvider.secrets()` list is built per tenant; a test asserts a log line containing a fake key is
  redacted for a non-owner tenant too.
- `/admin/users` shows only presence + last 4.

### 7.3 Credential resolution

```kotlin
interface CredentialResolver {
    fun garminKey(tenant: TenantId): String?   // tenant setting, else env if tenant is OWNER, else null
    fun stravaConfigured(tenant: TenantId): Boolean  // strava_tokens row exists in the tenant DB
}
```

`GarminProperties.apiKey` stops being the source of truth in multi-user mode. Keep the property and the env var for
the owner (zero change for him) and for local dev.

Strava tokens are read through `StravaTokenRepository` (row id 1) inside the tenant DB, so the Strava use cases
need no credential change at all: the routed connection already selects the right athlete's token.

---

## 8. Schema management (D8: Hibernate update per database, no Flyway)

### 8.1 Principle

Every database (owner and tenants) gets its schema from the **same entity classes through the same Hibernate
schema-update mechanism** the owner's app uses today. No migration files, no `flyway_schema_history`, no change to
the owner's workflow: adding a field or entity keeps working exactly as now.

| Flag | Owner DB `running` | Tenant DBs |
|---|---|---|
| off (default) | `ddl-auto: update` by Spring Boot, **unchanged** | do not exist |
| on | `TenantSchemaUpdater`, owner first, at boot | `TenantSchemaUpdater` at boot (each active tenant) and at provisioning |

The flag-on path switches Hibernate's own boot-time `ddl-auto` to `none` (§5.7) only because there is no tenant
bound on the boot thread; the updater then does the same `update` per database explicitly.

### 8.2 `TenantSchemaUpdater` (flag on only)

1. Capture Hibernate's `Metadata` and service registry during bootstrap with an `org.hibernate.integrator.spi.Integrator`
   registered through a `HibernatePropertiesCustomizer` (`hibernate.integrator_provider`). No second EntityManagerFactory.
2. For each database, inside `TenantContext.asTenant(id)`:
   1. **Dry run:** Hibernate 6.6 has no `SchemaUpdate` class in `hibernate-core`; use the SPI instead:
      `SchemaManagementTool.getSchemaMigrator(settings).doMigration(metadata, options, ContributableMatcher.ALL, target)`
      with a script-only `TargetDescriptor` that collects the statements (nothing is executed). Exceptions halt the dry run.
      **Unique-constraint churn:** Hibernate's default (`DROP_RECREATE_QUIETLY`) emits `alter table … drop constraint if
      exists X` + `add constraint X unique …` for every unique constraint on every run (also today's `ddl-auto: update`
      does this on every owner boot). `SKIP` is not usable (a new table would then lack its unique constraints), so
      `UniqueConstraintChurn` removes the quiet drops and the add of a constraint that already exists. Verified: zero
      statements on the restored owner copy and on an up-to-date tenant.
   2. **Guard:** every statement must start with `create table`, `create index`, `create unique index`,
      `alter table … add column` or `alter table … add constraint`. Anything else (`drop`, `alter column`,
      `rename`, `truncate`, `delete`) → do **not** execute, log the script, and for the owner **refuse to start**
      (for a tenant: mark it unhealthy, skip it, keep the owner running).
   3. Log the statements (count + text; no data involved). For the owner this is the same DDL `ddl-auto: update`
      would have run today (minus the unique-constraint churn).
   4. Execute exactly the vetted statements one by one over a plain connection of the routed DataSource (so what was
      guarded is what runs).
3. A new, empty tenant DB therefore receives the complete schema; an existing DB only receives additions.

Phase 2 must prove on a **restored copy** of the owner's DB that the dry run against the current schema produces
**zero statements** (same result as today's `ddl-auto: update` on an up-to-date DB). If it does not, stop and
report: it means the updater differs from Spring Boot's behaviour and the owner would see unexpected DDL.

### 8.3 Owner-safety rules for schema changes

- Hibernate update never drops or renames. Renaming/removing a column or table stays a separate, owner-approved,
  hand-run step with its own backup (same as today).
- New non-null columns on existing tables must be **nullable in the database** (Kotlin type nullable, or a
  `columnDefinition` with a constant default). Hibernate update cannot add a `NOT NULL` column without default to a
  table with rows, and that would break start-up.
- `docker compose down -v` stays forbidden (it destroys `pgdata`).

### 8.4 The two additions this feature makes

```kotlin
@Entity @Table(name = "app_settings")
class AppSettingEntity(
    @Id @Column(name = "setting_key", length = 64) var key: String = "",
    @Column(name = "setting_value", columnDefinition = "TEXT") var value: String = "",
    @Column(name = "updated_at") var updatedAt: Instant = Instant.EPOCH,
)

// ActivityEntity: nullable on purpose; null means STRAVA. No backfill of the owner's rows.
@Column(name = "source", length = 16) var source: String? = null
```

`app_settings` holds `data_mode`, `onboarding_complete` and the encrypted Garmin key. `activities.source` is
provenance (`null`/`STRAVA` / `GARMIN`) and the collision guard for the Garmin projection (§9.4). Both are
additive. They reach the owner's DB the normal way (`ddl-auto: update`) in the first release that contains the
entities (Phase 4), after a verified backup. The owner's existing rows are not rewritten.

---

## 9. Data-source modes

### 9.1 The enum

```kotlin
enum class DataMode { UNSET, STRAVA, GARMIN, BOTH }
```

- `STRAVA` — Strava activities are the backbone; no Garmin API calls, no Garmin pages with data (they show their
  existing empty state).
- `GARMIN` — Garmin is the backbone. **No Strava call is made and `strava_tokens` is never read.**
- `BOTH` — exactly today's behaviour: Strava activities + Garmin enrichment.
- `UNSET` — onboarding not finished; only `/setup` is reachable (all other pages redirect there).

Derivation: tokens present && key present → `BOTH`; tokens only → `STRAVA`; key only → `GARMIN`; neither →
`UNSET`. The computed value is persisted **once**, at the end of onboarding (D11). After that it is read-only for
the tenant; only `/settings/reset` can change it (§9.3).

**The OWNER is always `BOTH`, hard-coded** from his role, never read from `app_settings` and never `UNSET`. His
database has no `data_mode` row and must not need one; otherwise the `UNSET` redirect would send him to `/setup`.
For other tenants the mode is cached in `TenantRegistry` (in memory), so no request does an extra query for it.

### 9.2 Enforcement (not just UI)

`DataModeGuard` (or a `TenantCapabilities` bean) is injected where it matters:

| Component | `STRAVA` | `GARMIN` | `BOTH` |
|---|---|---|---|
| `SyncStravaData` / `FetchAllHistoricalData` / backfills | allowed | **no-op, throws `ModeMismatch` or returns empty — must not call HTTP** | allowed |
| `GarminSyncCoordinator` / `GarminSyncData` | not configured → no-op, no HTTP | allowed | allowed |
| `StravaOAuthController` | allowed | hidden + 409 | allowed |
| `GarminActivityProjector` | off | **on**, after every Garmin sync | off (Strava is the backbone) |
| `/recovery`, `/performance`, `/load`, `/race` | today's behaviour with no Garmin rows | today's behaviour with no streams/laps | today |

A test must stub `StravaApiClient` with an implementation that throws on any call, run the whole app in `GARMIN`
mode, and assert no exception is observed. Same for `GarminApiClient` in `STRAVA` mode.

### 9.3 Mode changes are guarded (D11: reset only)

- `DataMode` is written **once**, during onboarding, while the tenant has no activities.
- Adding a second credential later would change the mode, so it is **blocked** once the tenant has data: the
  "koppel Strava" button on `/settings` returns 409 with an explanation pointing at `/settings/reset`. Replacing a
  key with another key of the **same** source is always allowed (that is credential rotation, not a mode change).
- `/settings/reset` (non-OWNER only) is the only route that changes the source. The app container has no
  `pg_dump`, so the reset never deletes rows; it **archives** the database instead:
  (a) close the tenant's Hikari pool and evict its caches/token provider, (b) on the maintenance connection
  `SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '<tenantDb>'`, (c)
  `ALTER DATABASE "<tenantDb>" RENAME TO "<tenantDb>_old_<yyyyMMddHHmm>"` (name validated, ≤ 63 chars),
  (d) provision a fresh empty `<tenantDb>` (§5.5 steps 3–5), (e) redirect to `/setup`.
  The archived DB is kept until the owner drops it by hand (or after 30 days by a documented manual step); it is
  part of the all-databases backup (§12).
- Server-side guard, in the service and not only in the controller: `if (tenantDb == ownerDb || role == OWNER) throw`.
  Test it. There is no UI path to reset the owner.

### 9.4 Garmin → `activities` projection (GARMIN mode only)

`GarminActivityProjector` (runner) runs after each successful Garmin sync, inside the tenant context:

| `Activity` field | Source |
|---|---|
| `id` | `garmin_activity_ref.activity_id` |
| `name` | type label + local date ("Hardlopen 17 nov") |
| `distance` | `distance_m` |
| `movingTime` | `moving_s` ?: `duration_s` |
| `elapsedTime` | `elapsed_s` ?: `duration_s` |
| `totalElevationGain` | `0f` (not available — documented limitation) |
| `type` / `sportType` | `typeKey`: contains `running` → `Run`; `walking` → `Walk`; `hiking` → `Hike`; `cycling` → `Ride`; else `Other` |
| `startDate` | `start_time_gmt` at `Europe/Brussels` |
| `timezone` | `Europe/Brussels` |
| `averageSpeed` | `distance / movingTime` (0 if unknown) |
| `averageHeartrate` / `maxHeartrate` | `avg_hr` / `max_hr` |
| `hasHeartrate` | `avg_hr != null` |
| `isManual` | `is_manual` |
| `externalId` | Garmin activity id (string) |
| `deviceName` | `manufacturer` |
| `source` | `GARMIN` (new column) |
| everything else | `null` / `false` |

Idempotency and collision guard:

```kotlin
val existing = activityRepository.findById(id)
if (existing != null && existing.source != "GARMIN") { log.warn(...); return }   // null = STRAVA: never overwrite
activityRepository.saveAll(projected)
```

Also store training load / TE nowhere new: `garmin_activity_ref` already holds them and `LoadService` reads them
directly. Do not duplicate them into `activities`.

`LoadService` joins Garmin load to activities through `GarminActivityMatcher` (core, matched by start time). A
projected activity must match its own `garmin_activity_ref` row; add a test for that, otherwise Garmin-only load
silently drops to zero.

### 9.5 Honest capability matrix (Phase 5 deliverable)

Write `docs/multi-user-capabilities.md` with one row per page in `docs/ui-guidelines.md` and columns
`STRAVA | GARMIN | BOTH`, values `FULL | PARTIAL | NOT AVAILABLE`, and a short Dutch sentence for the UI. Known
starting points (verify each during Phase 5, do not assume):

| Page | STRAVA-only | GARMIN-only |
|---|---|---|
| `/` dashboard | full | full (activity summary) |
| `/activities`, `/activity/{id}` | full | partial (no laps, no streams, no best efforts) |
| `/recovery` | not available (no Garmin) | full |
| `/performance` | full | partial: steady/drift/durability/EF need streams → not available |
| `/load`, `/load/response` | partial (TRIMP from Strava HR) | full (Garmin TE + training load) |
| `/race`, `/race/history` | full | partial: prediction works from race times; pacing/specificity need streams |
| `/ai` | full | partial (context string reflects available data) |

UI rule: a `NOT AVAILABLE` page shows one consistent panel — "Deze analyse heeft Strava-data nodig (seconde-data
per training)." / "Deze analyse heeft Garmin-data nodig." — not an empty chart, not an error.

---

## 10. Per-tenant background work (the landmines from §2.6)

### 10.1 `TenantJobRunner`

```kotlin
@Component
class TenantJobRunner(private val registry: TenantRegistry) {
    fun runAll(name: String, block: () -> Unit) {
        log.info("Job '{}' starting for {} tenants", name, registry.activeCount())
        for (tenant in registry.activeOrdered()) {          // OWNER first, then by created_at
            try {
                TenantContext.asTenant(tenant.id) { block() }
            } catch (e: Exception) {
                log.warn("Job '{}' failed for tenant '{}': {}", name, tenant.id, e.javaClass.simpleName)
            }
            pauseBetweenTenants()                            // see 10.4
        }
    }
}
```

Rules:

- A failing tenant must never skip, delay or alter the owner's job. The owner runs **first**.
- Log lines always include the tenant id; never the username's data.
- No `@Async` fan-out: strictly sequential, so the owner's job is never competing for CPU, DB connections or the
  Strava budget (see §13).

### 10.2 What must become per-tenant

| Component | Change |
|---|---|
| `GarminTokenProvider` | one instance per tenant (map keyed by tenant id), same in-memory JWT caching |
| `GarminSyncCoordinator` | per-tenant `running` flag and per-tenant queue; `isRunning` must be queried per tenant (feeds `data-garmin-sync`) |
| `GarminProperties.configured` | replace with `CredentialResolver.garminKey(tenant) != null` |
| `BestEffortService`, `PerformanceService`, `LoadService`, `RaceService` caches | prepend the tenant id to every cache key (`data class TenantKey(val tenant: String, ...)`), and add `evict(tenant)` |
| Load / Race warm-up | run for the OWNER first at `ApplicationReadyEvent` (unchanged timing), then tenant-by-tenant on a single daemon thread |
| `RaceService.recordToday` (`@Scheduled`) | `TenantJobRunner.runAll("race-log")` |
| `StravaSyncScheduler` | `TenantJobRunner.runAll("strava-sync")`; inside the block keep the existing "no token → skip" check |
| `GarminSyncScheduler` | same for Garmin |
| `FrontendController` `garminSyncRunning` | per tenant |

### 10.3 Cache keys

Today: `ConcurrentHashMap<Long, …>`. After: `ConcurrentHashMap<TenantKey<Long>, …>`. Cheapest correct fix. A
tenant id is 3–32 characters; the extra memory is negligible. Also add a size cap or a periodic
`evict(tenant)` on data reset so a deleted tenant cannot leak memory forever.

### 10.4 Rate limits and pacing

Strava limits are **per application**, shared by all athletes (§2.4a). The binding numbers are the *read* limits:
100 requests / 15 min and 1,000 / day at capacity 1, 200 / 15 min and 2,000 / day after the owner's self-service
upgrade to capacity 10. With an incremental daily sync (~2–3 calls per user) 10 users is ~30 calls: safe. A
first-time backfill is one list call per 200 activities plus **two calls per activity** (streams + laps), so a
runner with 1,000 activities needs ~2,000 calls: that alone is a full day of the app's read budget, shared with the
owner's own sync. It **must not run for several tenants at once** and must never starve the owner. Rules:

- New setting `app.multi-user.tenant-pause` between tenants inside a job (design default `5s`; `application.yml` still has `0s`: set it in Phase 3 together with the rate budget; it only applies between tenants, never before the owner).
- First-time historical backfills are triggered per user from `/setup`, run on a per-tenant queue (not on the
  request thread, unlike today's `/fetch-all`), and are **serialized across tenants** by one global semaphore.
- Tenant backfills stop for the day when the app has used ~70% of the 15-minute or daily read budget (read
  `X-ReadRateLimit-Usage` / `X-RateLimit-Usage` response headers), and resume the next day. The owner's 06:00 sync
  must always have budget left. On HTTP 429: stop, never retry in a loop.
- Show a progress page ("x van y trainingen opgehaald, de rest volgt morgen"); do not auto-start a second tenant's
  backfill.
- No rate-limit library: a small counter fed by the response headers is enough.

---

## 11. Owner-safety invariants (non-negotiable; each one needs a test)

1. **No fallback.** `TenantRoutingDataSource` throws when no tenant is bound. There is no "default tenant".
2. **The owner's `tenant_db` is `running` and never changes.** A startup assertion refuses to boot if
   `DB_URL`'s database name differs from the OWNER row's `tenant_db`.
3. **No reset/delete for the OWNER.** Enforced server-side, not only by hiding UI.
4. **Nothing destructive is ever issued against the owner's database.** The only DDL is Hibernate's additive
   update (guarded, §8.2), after a verified backup, verified first on a restored copy. `DROP`/`RENAME DATABASE`
   code paths accept only names starting with `running_u_` and refuse the owner's `tenant_db` explicitly.
5. **The owner's request path has no extra DB round trips.** Tenant resolution comes from the `UserDetails` object
   already loaded by Spring Security (session or remember-me), not from a `SELECT` per request.
6. **Cache keys include the tenant.** No shared mutable state keyed by a bare activity id.
7. **Secrets never appear in logs, error pages or `/admin`.** Redaction is per tenant and tested.
8. **`docker compose down -v` stays forbidden.** Multi-user increases the blast radius; the warning stays in
   `AGENTS.md`, `deploy/DEPLOY.md`, `deploy/LINUX.md` and the new runbook. Note that the root dev
   `docker-compose.yml` and `deploy/docker-compose.yml` share the volume `running_pgdata` on the same machine.
9. **Flag off = no multi-user code runs.** Every multi-user bean is `@ConditionalOnProperty`; with the flag off the
   bean graph is today's graph plus nothing. A test boots the context with the flag off and asserts that no
   `TenantRoutingDataSource`, `ControlDb` or `TenantContextFilter` bean exists.
10. **The OWNER is always mode `BOTH`** and is never redirected to `/setup` (§9.1).

### 11.1 The one write to the owner's DB

Hibernate update adds `app_settings` and the nullable column `activities.source` (§8.4), in the release that ships
Phase 4. Pre-flight, mandatory:

```bash
bash deploy/migrate.sh backup            # writes deploy/backups/running-<ts>.dump
# verify the dump before deploying (the file is on the host; list its contents inside the container):
docker compose -f deploy/docker-compose.yml cp deploy/backups/running-<ts>.dump postgres:/tmp/check.dump
docker compose -f deploy/docker-compose.yml exec -T postgres pg_restore -l /tmp/check.dump | wc -l   # compare with the previous dump
# restore drill into a scratch DB (running_restore_test), boot the new image against it, run the acceptance script
```

Rollback if the owner's app misbehaves: redeploy the previous image (one extra table and one extra nullable column
do not break it; Hibernate ignores unknown columns). Restore the dump only if data itself is wrong.

---

## 12. Backup and restore (this is the "data never lost" requirement)

1. New `deploy/backup.sh` / `deploy/backup.ps1`: dump **every** database (`running_control` + `running` + every
   `running_u_*`), one file per database plus a manifest with row counts. Retention 14 days (same as today).
2. Existing `migrate.sh backup` stays working (it must not change behaviour for the owner).
3. Nightly: the existing systemd timer calls the new all-databases script.
4. Restore drill, documented and executed at least once per phase gate:
   `restore <file>` into a scratch database, boot the image with `DB_URL` pointed at it, log in, open `/`,
   `/activities`, `/load`.
5. Before Phase 0 and before every release that changes entities: a fresh dump, size-checked against the previous one.
6. A tenant is never deleted by the app: reset and delete-user **rename** the database to `<db>_old_<ts>` (§9.3).
   Dropping an archived database is a manual owner step, only after its dump is written and non-empty.
7. All dumps run in the Postgres container (`docker compose exec postgres pg_dump …`), as today; the app container
   has no `pg_dump` and must not get one.

---

## 13. Performance budget for the owner (must stay within)

| Cost | Budget |
|---|---|
| Extra JVM heap | one Hikari pool per tenant (≈ 50–100 KB idle) + tenant map; < 1 MB per tenant |
| Extra DB connections | 2 per tenant (pool max 2, `minimum-idle: 0`) + 2 control → 10 tenants ≤ 24 connections total, Postgres default 100 |
| Extra latency per request | one ThreadLocal read + one map lookup; **no extra SQL** |
| Owner's pages | identical queries, identical indexes, identical pool |
| Nightly jobs | owner first and alone; tenants afterwards, sequential, configurable pause |
| Startup | owner's warm-up unchanged; tenant warm-ups after, off the boot thread |
| Tomcat threads | unchanged (`max: 20`); tenant jobs use their own threads, never request threads |

If a measurement ever exceeds these, stop and report instead of raising limits silently.

---

## 14. Implementation phases

Each phase is independently shippable, has its own gate, and leaves the app working. Do not start a phase before
the previous gate is green. Update `MULTI_USER_PROGRESS.md` after every phase.

**Why this order** (corrected in review): with the flag on, the 06:00 schedulers, the start-up warm-ups and the
Garmin executor run **without a tenant** and would fail closed, and the global `GarminTokenProvider` would sync the
owner's Garmin data into whichever tenant DB is bound. So schedulers/warm-ups must be tenant-aware in the same
phase as routing (Phase 2), and all per-tenant in-memory state must exist (Phase 3) **before** the first non-owner
user can sign up (Phase 4).

**Production rule:** the flag may be switched on in production only after the Phase 4 gate (Strava users) or the
Phase 5 gate (Garmin users). Before that it is switched on only against a restored copy.

### Phase 0 — Safety net and baseline (no code change on the owner's path)

Goal: prove the backup/restore path and record the numbers every later phase is compared with.

Steps:

1. Fresh dump of the owner's DB (`deploy/migrate.sh backup`); restore it into a scratch DB `running_restore_test`
   inside the same Postgres container; boot the current image against it (`DB_URL=…/running_restore_test`).
2. Record row counts of all tables (18 entity tables; the local copy also has 4 legacy upper-case H2-era tables `ACTIVITIES`, `ACTIVITY_STREAMS`, `STRAVA_TOKENS`, `SYNC_STATUS` that no entity maps) and `pg_dump --schema-only` of the live DB (store under
   `docs/multi-user-baseline/`, no data).
3. Record page timings for `/`, `/activities`, `/load`, `/race` (5 warm requests each, median) and the start-up
   time. These are the ±5% reference.
4. Add `deploy/backup.sh`/`.ps1` that dumps every `running*` database (only `running` exists now) + a manifest with
   row counts, keeping `migrate.sh backup` unchanged. Wire it into the nightly timer only after one manual run is
   restored successfully.

Acceptance:

- Restore drill works and is documented step by step (append results to `MULTI_USER_PROGRESS.md`).
- No change to any file under `runner/` or `core/`.

Rollback: nothing to roll back.

### Phase 1 — Control plane and DB-backed login (flag off by default)

Steps:

1. `MultiUserProperties` (`app.multi-user.enabled=false` default, `control-url`, `tenant-pause`).
2. `running_control` bootstrap (auto-create the database if missing, `CREATE DATABASE` with autocommit).
3. `app_user`, `invite_code`, `auth_audit` tables (created by a small `CREATE TABLE IF NOT EXISTS` control-plane
   initializer on `ControlDb`, not by JPA).
4. `AppUserPrincipal` (Serializable), `ControlUserDetailsService`, owner bootstrap (§6.3).
5. `SecurityConfig`: the existing `InMemoryUserDetailsManager` bean becomes `@ConditionalOnProperty(... havingValue
   = "false", matchIfMissing = true)`; the control-plane one exists only with the flag on. The filter chain code is
   shared.
6. Refuse to start with the flag on and (blank `APP_PASSWORD` or an H2 URL).

Acceptance:

- Flag off: identical bean graph and behaviour; existing tests pass unchanged; no connection to `running_control`
  at boot (test).
- Flag on (against a restored copy): owner logs in with `APP_USERNAME`/`APP_PASSWORD`; remember-me survives a
  restart; changing `APP_PASSWORD` and restarting changes the password; blank `APP_PASSWORD` refuses to start; a
  second OWNER cannot be created.

### Phase 2 — Tenant routing, schema updater, tenant-aware jobs (flag off by default)

Steps:

1. Boot once with the flag on and list every start-up connection; then apply §5.7
   (`allow_jdbc_metadata_access=false`, `ddl-auto: none` for the flag-on path only).
2. `TenantContext`, `TenantRoutingDataSource` (via `BeanPostProcessor`, §5.2), `TenantContextFilter` (after
   `AuthorizationFilter`, §5.4), `TenantRegistry`.
3. `TenantSchemaUpdater` (§8.2) with dry run + guard; prove zero statements on the restored owner copy.
4. `TenantJobRunner`; route the three `@Scheduled` methods and both `ApplicationReadyEvent` warm-ups through it
   (with only the owner registered, behaviour equals today). The Garmin executor captures the tenant on submit.
5. `TenantProvisioner` + a CLI entry point (`--provision-user=<name>`, no login, no credentials) so a tenant can be
   created without the UI. Owner assertion at boot (§5.6).
6. Isolation and fail-closed tests (§15.2).

Two things from Phase 3 had to be pulled forward, because the acceptance below (same activity id in two tenants, no
cross-visibility; a provisioned tenant must never use the owner's Garmin key) is otherwise not true:
the four per-activity caches already use `TenantKey` (tenant id + key, tenant null with the flag off), and
`GarminSyncCoordinator` only starts a run when the current tenant is the OWNER (interim guard until
`CredentialResolver` exists; all other tenants get `NOT_CONFIGURED` and no HTTP call). Phase 3 keeps `evict(tenant)`,
per-tenant token provider/running flag and the real credential resolution.

Acceptance:

- Flag on, owner only: same pages, same numbers, same cron log lines, timings within 5% of the Phase 0 baseline.
- Flag on + a provisioned empty tenant (no credentials): it sees an empty app; the owner's numbers are unchanged.
- Two tenants with the same activity id inserted by hand: no cross-visibility on any page.
- A request or job with no tenant bound never reaches the owner DB (counting datasource proxy).
- Flag off: still identical to today.

### Phase 3 — Per-tenant in-memory state and credentials (still no signup)

Steps:

1. Tenant-scoped cache keys in `BestEffortService`, `PerformanceService`, `LoadService`, `RaceService` +
   `evict(tenant)`.
2. `GarminTokenProvider` per tenant (map keyed by tenant id); `GarminSyncCoordinator` with per-tenant running flag;
   `garminSyncRunning` per tenant in `FrontendController`/`GarminSyncView`.
3. `SecretCipher` (AES-256-GCM, `APP_TENANT_SECRET_KEY`) + `CredentialResolver`; `GarminProperties.configured`
   replaced by `CredentialResolver.garminKey(tenant) != null`. OWNER keeps the env key.
4. Garmin `AUTH` error per tenant stored and shown as the "key verlopen" banner (D14); nothing deleted.
5. Strava rate-budget counter + global backfill semaphore + tenant pause (§10.4).

Acceptance:

- Owner's cron times and order unchanged (log evidence); owner's Garmin sync still uses the env key.
- A tenant without a key gets `NOT_CONFIGURED` and **zero** Garmin HTTP calls (throwing stub).
- Cache test: identical activity id under two tenants yields two distinct cached results.
- With 3 tenants: 3 sequential job runs; forcing tenant B to throw does not touch the owner's result or timing.
- The `data-garmin-sync` attribute is per tenant.

### Phase 4 — Registration, onboarding, Strava-only users

Steps:

1. `AppSettingEntity` + nullable `activities.source` (§8.4), shipped after a verified backup (§11.1).
2. `/register` + invite code + rate limit; auto-login; `/setup` wizard.
3. Strava OAuth per tenant: signed `state` (HMAC over tenant id + session id + expiry) validated on `/callback`;
   tokens written into the current tenant DB.
4. `POST /setup/garmin` with a "Test" call (token exchange only); masked display. Garmin-only onboarding may be
   completed, but Garmin-only *pages* are Phase 5.
5. `DataMode` derivation + persistence; OWNER hard-coded `BOTH`; `/setup` unreachable once `onboarding_complete`.
6. `DataModeGuard` for `STRAVA` mode (no Garmin calls) and the OAuth 409 once data exists.
7. `/settings` (rotate same-source credentials) + `POST /settings/reset` (archive-by-rename, §9.3), OWNER refused.
8. First Strava backfill on the per-tenant queue with a progress page (not on the request thread).

Acceptance:

- A brand-new Strava-only user completes signup → OAuth → backfill → sees their own dashboard.
- The owner's pages, numbers and timings are unchanged; the owner never sees `/setup`.
- Keys are not in logs, not in HTML; a test greps captured log output for a fake key.
- Replaying a `state` or using another tenant's `state` fails.
- `/settings/reset` as OWNER → 403, and the owner DB name is refused in the service layer too.

### Phase 5 — GARMIN-only mode

Steps:

1. `GarminActivityProjector` + collision guard (§9.4) + matcher test (projected activity matches its own ref).
2. `DataModeGuard` in all Strava sync/fetch/backfill/OAuth paths; prove no HTTP call in `GARMIN` mode.
3. `TenantCapabilities` + reusable "not available" panel; wire it into every page from §9.5.
4. Write `docs/multi-user-capabilities.md` by actually opening each page for each mode (browser check, per
   `docs/ui-guidelines.md`).
5. Bind the mode to the AI context string (`AiAnalysisService.buildTrainingContext`) so it never mentions absent
   data.

Acceptance:

- Garmin-only tenant: `/`, `/activities`, `/activity/{id}`, `/recovery`, `/load`, `/load/response`, `/race` all
  render without errors; each unavailable analysis shows the panel.
- No Strava HTTP call and no `strava_tokens` read (stub throws; test asserts zero calls).
- Strava-only tenant: `/recovery` shows the Garmin panel; no Garmin HTTP call.
- `BOTH` tenant: output identical to the owner's for the same data (spot-check three pages).

### Phase 6 — Admin, backups, deploy, docs

Steps:

1. `/admin/users` (OWNER only): list, last login, key presence (masked), counts, disable, invite codes.
2. `deploy/backup.sh`/`.ps1` cover all databases; `status.sh`/`status.ps1` report tenant count.
3. Update `AGENTS.md`, `deploy/DEPLOY.md`, `deploy/LINUX.md`, `deploy/START-STOP.md`, `deploy/AGENT-PROMPT.md`.
4. Document `APP_SIGNUP_CODE`, `APP_TENANT_SECRET_KEY`, `running_control` in `.env.example`-style docs.

Acceptance: nightly backup contains all databases and the manifest; a restore of one tenant + the owner works;
docs complete and Dutch where the reader is the owner.

### Phase 7 — Hardening and flag removal (only after the owner signs off)

Steps: remove the `app.multi-user.enabled=false` path, session invalidation on disable, delete-user flow
(Strava `oauth/revoke` first, then archive-by-rename, owner drops the archive by hand after its dump; OWNER
refused), log-redaction audit, dependency/licence check, final performance comparison, delete `InMemoryUserDetailsManager`.

Acceptance: full suite green, security checklist signed off, owner confirms nothing changed for him.

---

## 15. Test plan

### 15.1 Existing suites must stay green

`JAVA_HOME=~/.sdkman/candidates/java/23-open ./gradlew :core:test :runner:test` (Java 21 gives a misleading JVM
target error; do not "fix" buildSrc).

### 15.2 New tests (minimum set)

| Test | Asserts |
|---|---|
| `TenantContextTest` | nested `asTenant` restores correctly; `currentOrFail` throws |
| `TenantRoutingDataSourceTest` | unknown tenant → exception, **zero** owner connections; owner id → owner pool; two tenants → two pools |
| `TenantContextFilterTest` | unauthenticated paths never bind a tenant; disabled user is rejected |
| `OwnerTenantAssertionTest` | wrong `DB_URL` database vs OWNER row → startup fails |
| `IsolationIT` | two tenants, same activity ids in both, no cross-visibility on any page endpoint |
| `OwnerBootstrapTest` | fresh DB creates owner; unchanged `APP_PASSWORD` keeps the **same** hash (remember-me survives); changed one re-hashes; blank refuses start-up with the flag on; second owner refused |
| `InviteCodeTest` | wrong/used/expired code rejected; constant-time compare; rate limit trips |
| `SecretCipherTest` | round-trip; wrong master key fails; ciphertext has no plaintext; missing key → startup refused |
| `DataModeTest` | derivation matrix; `GARMIN` never constructs a Strava call (throwing stub) |
| `GarminProjectorTest` | mapping table §9.4; idempotent upsert; refuses to overwrite a `STRAVA` row |
| `TenantCacheTest` | same activity id, two tenants → two entries; `evict` clears one only |
| `TenantJobRunnerTest` | owner first; one failing tenant does not stop the rest; pause applied |
| `OwnerSafetyTest` | reset/delete routes return 403/404 for OWNER |
| `ModeChangeGuardTest` | with data present, adding a second source returns 409; same-source key rotation still works; `/settings/reset` archives only that tenant's DB (renamed, not dropped) and refuses the owner DB name |
| `FlagOffContextTest` | flag off → no `TenantRoutingDataSource`, `ControlDb`, `TenantContextFilter` bean; owner pool `maximumPoolSize == 2` |
| `OwnerPoolTest` | flag on → the wrapped owner pool is Spring Boot's auto-configured one with the same `hikari.*` values |
| `RememberMeTenantTest` | a request authenticated only by the remember-me cookie gets the tenant bound (filter order) |
| `SchemaUpdaterTest` | empty DB → full schema; up-to-date DB → zero statements; a `drop`/`alter column` statement in the script → not executed |
| `OwnerModeTest` | OWNER is `BOTH` without any `app_settings` row and is never redirected to `/setup` |
| `GarminTenantKeyTest` | tenant without key → `NOT_CONFIGURED`, zero HTTP; owner still uses the env key; two tenants never share a JWT |

### 15.3 Test infrastructure

Two databases are needed, so H2 is not enough. Preferred: Testcontainers PostgreSQL (add
`org.testcontainers:postgresql` + `junit-jupiter` as `testImplementation`, `@Testcontainers`). If the owner objects
to a new test dependency, fall back to a `@SpringBootTest` guarded by an env var that points at a local Postgres
and creates `running_test_*` databases, skipped when absent. Decide once, document it, do not mix. **Decided 2026-11-19 by the owner: Testcontainers.**

### 15.4 Manual acceptance script (repeat every phase)

1. `deploy/migrate.sh backup`, verify the dump.
2. Boot with the flag off → owner's five main pages, compare against the previous screenshots/notes.
3. Boot with the flag on → same five pages, same numbers, same timings (±5%).
4. (From Phase 4) Signup a fresh Strava-only user, sync, open all pages.
5. (From Phase 5) Signup a fresh Garmin-only user, sync, open all pages.
6. Try to reach user B's activity id while logged in as user A → 404, never B's data.
7. Check `docker compose logs` for any key/token/password fragment.

---

## 16. File map for the implementer

New (indicative, adapt to repo style):

```
runner/src/main/kotlin/com/running/tenant/
    TenantContext.kt                 TenantRoutingDataSource.kt   TenantContextFilter.kt
    TenantRegistry.kt                TenantProvisioner.kt         TenantJobRunner.kt
    MultiUserProperties.kt           SecretCipher.kt              CredentialResolver.kt
    AppUserPrincipal.kt              ControlUserDetailsService.kt OwnerBootstrap.kt
    db/ControlSchema.kt              db/AppUserRow.kt             db/InviteCodeRow.kt
    web/RegisterController.kt        web/SetupController.kt       web/AdminUsersController.kt
    ControlDb.kt                     MultiUserDataConfig.kt       DataModeGuard.kt
    schema/TenantSchemaUpdater.kt    schema/MetadataCapturingIntegrator.kt
runner/src/main/kotlin/com/running/settings/db/AppSettingEntity.kt
runner/src/main/resources/templates/register.html
runner/src/main/resources/templates/setup.html
runner/src/main/resources/templates/settings.html
runner/src/main/resources/templates/admin-users.html
docs/multi-user-design.md  docs/multi-user-capabilities.md  MULTI_USER_PROGRESS.md
deploy/backup.sh  deploy/backup.ps1
```

Touched (surgical, keep diffs small):

```
runner/runner.gradle.kts                        Testcontainers (test only, if approved); no Flyway
runner/src/main/resources/application*.yml      new app.multi-user.* properties; flag-on-only Hibernate settings (§5.7)
.../config/SecurityConfig.kt                    two auth paths behind the flag; filter registration
.../config/AppConfig.kt                         per-tenant wiring (no DataSource bean, §5.2)
.../frontend/LoginController.kt                 add the signup link flag
.../frontend/FrontendController.kt              per-tenant garminSyncRunning; capability attributes
.../strava/adapter/StravaOAuthController.kt     state validation; mode guard
.../strava/scheduler/StravaSyncScheduler.kt      TenantJobRunner
.../garmin/scheduler/GarminSyncScheduler.kt      TenantJobRunner
.../garmin/adapter/GarminTokenProvider.kt        per tenant
.../garmin/adapter/GarminSyncCoordinator.kt      per tenant
.../garmin/web/GarminSyncView.kt, GarminSyncController.kt   per-tenant running state and AUTH banner
.../config/GarminConfig.kt, GarminProperties.kt   credential resolver instead of global key; executor wrapping
.../analysis/{LoadService,PerformanceService,RaceService,BestEffortService}.kt   tenant cache keys, warm-ups
.../strava/db/ActivityEntity.kt                  + source
core/.../strava/domain/Activity.kt               + `source: String? = null` (defaulted, no constructor churn)
templates/login.html, layout.html                signup link, per-tenant sync badge, capability panel
docs/ui-guidelines.md                            add the "not available" panel rule
AGENTS.md, deploy/*                              new env vars, backup-all, runbook
```

Do **not** touch: `core` analysis maths, `LapClassifier`, `WorkoutStructure`, best-effort algorithms, the Garmin
data-provenance model, or any existing table's columns other than the additive `activities.source`.

---

## 17. Risk register

| # | Risk | Impact | Mitigation |
|---|---|---|---|
| R1 | A request runs without a tenant and hits the owner's DB | Data leak **and** possible writes to owner data | Fail-closed router, no default tenant, filter last in the security chain, `OwnerSafetyTest` |
| R2 | Schema updater behaves differently from today's `ddl-auto: update` on the owner's DB | Unexpected DDL or start-up failure | Dry run + statement guard (§8.2); prove zero statements on a restored copy; flag off = Spring Boot's own update path |
| R3 | Owner's `APP_PASSWORD` change stops working, or remember-me breaks on every restart | Owner locked out / logged out | `encoder.matches` before re-hashing (§6.3) + test |
| R4 | Shared caches mix two tenants' results | Wrong analysis shown | Tenant-scoped keys + `TenantCacheTest` |
| R5 | Garmin token cache shared | One user's calls use another's key; owner's Garmin data written into a tenant DB | Per-tenant provider **before** signup exists (Phase 3); test |
| R6 | Strava app rate limit (per app) during backfills | Sync failures for everyone, incl. the owner | Sequential jobs, global backfill semaphore, 70% budget stop, owner's sync first (§10.4) |
| R7 | Postgres connection exhaustion | App-wide outage | Pool 2 + `minimum-idle: 0`; tenant cap alert in `/admin`; `max_connections` check in the runbook |
| R8 | Secrets in logs | Account takeover | Per-tenant redaction + log grep test |
| R9 | Open registration abuse | Spam tenants, DB spam | Invite code + rate limit + owner-only tenant delete |
| R10 | Garmin-only expectations exceed API capability | Disappointed user / "broken" pages | §9.5 matrix + one consistent panel + honest Dutch copy |
| R11 | Owner's performance regresses | The deal-breaker | Feature flag off by default, multi-user beans absent when off; ±5% acceptance per phase; no extra SQL on his path |
| R12 | Accidentally `DROP DATABASE running` | Total loss | App never drops databases (archive-by-rename), `running_u_` prefix check, OWNER guard, startup assertion, no `-v` |
| R13 | Strava capacity/subscription not in place | Second Strava user cannot connect ("limit of connected athletes exceeded") | Owner precondition before Phase 4 (§2.4a); `/setup` shows a clear Dutch message on that OAuth error |
| R14 | Strava API policy: no Strava data in AI context windows; delete within 30 days after deauthorization | Policy breach, app revoked (would also hit the owner) | For non-owner tenants with Strava data, hide `/ai` or reduce it to non-Strava data (owner decides in Phase 5); delete-user calls `oauth/revoke` and the archive is dropped within 30 days |
| R15 | Garmin Personal API is meant for personal, non-commercial use | Key or access revoked | Small invited group, each user creates and owns their own key, never paid, never public signup |
| R16 | Flag switched on in production before Phase 3/4 is done | Owner's 06:00 sync fails closed, or Garmin data crosses tenants | Production rule in §14; `MULTI_USER_PROGRESS.md` hard rule 6 |

---

## 18. Effort estimate

| Phase | Estimate (focused agent) |
|---|---|
| 0 — safety net + baseline | 0.5 day |
| 1 — control plane + login | 1–1.5 days |
| 2 — routing + schema updater + tenant-aware jobs + isolation | 2–2.5 days |
| 3 — per-tenant state + credentials + rate budget | 1.5–2 days |
| 4 — signup + setup + Strava-only | 2 days |
| 5 — Garmin-only + capability matrix | 2 days |
| 6 — admin, backups, docs | 1 day |
| 7 — hardening + flag removal | 1 day |
| **Total** | **~11–13 working days**, excluding the owner's review time per gate |

Phases 0–3 deliver the isolation guarantee and can ship without any user-visible change (flag off). If time is
short, stop after Phase 4 (extra Strava-only users) and postpone Phase 5 (Garmin-only).

---

## 19. Assumptions and open points

Assumptions (verify, then correct this document):

1. Each new user can create their own **Garmin personal API key** on Garmin Connect web (verified 2026-11-17: self
   service, no approval, expiry 30/90/180/365 days). The wizard explains where to get it, recommends 365 days and
   offers a "Test" button.
2. The owner's Strava app may serve multiple athletes via OAuth (verified: yes, up to 10 athletes self-service,
   with a Strava subscription; §2.4a). Per-user client id/secret is **not** a fallback: it is non-compliant.
3. The deployment host has enough RAM for one JVM (already true) and Postgres can host ~10 small databases
   (true at this data size).
4. The Postgres role in `.env` has `CREATEDB` (the `postgres` superuser does; verified in `deploy/docker-compose.yml`).
5. `deploy/running.dump` is **not** a current backup (untracked, 2026-11-01). Phase 0 makes a fresh one.

Owner preconditions before Phase 4 (not code):

- Strava subscription on the account that owns the Strava app.
- Strava API settings: athlete capacity raised from 1 to 10.
- `APP_SIGNUP_CODE` and `APP_TENANT_SECRET_KEY` generated and stored in `deploy/.env` (and in the owner's password
  manager: losing `APP_TENANT_SECRET_KEY` means every tenant must paste their Garmin key again; no data is lost).

Open points for the owner: **none. All four were answered on 2026-11-17** and are binding decisions D11–D14 in §3.
D8 was changed by the owner during the review session (Hibernate update instead of Flyway).

### Answers recorded (2026-11-17)

| # | Question | Owner's answer | Consequence in the design |
|---|---|---|---|
| O1 | Switch source later? | **Only via a full guarded reset** | §9.3 is the final word: archive the tenant DB → fresh DB → re-onboard. No merge, no de-duplication, no `BOTH`-after-the-fact. Server-side OWNER guard. |
| O2 | Language? | **Dutch only** | No i18n layer. All new templates (`register`, `setup`, `settings`, `admin-users`) in plain Dutch, matching `docs/ui-guidelines.md`. |
| O3 | Owner's view of other users? | **Counts + last sync only** | `/admin/users` columns: username, created, last login, last sync, activity count, key present (last 4), status. No activity lists, no names of trainings, no data of others. |
| O4 | Expired Garmin key? | **Banner + link, data stays** | A persistent banner on every page for that tenant: "Je Garmin-key is verlopen of ingetrokken. Werk hem bij in Instellingen." Sync records an AUTH error category; nothing is deleted; pages keep rendering stored data. |
| O5 | Schema mechanism? (review session) | **Hibernate update per database** | §8 rewritten: no Flyway, owner keeps `ddl-auto: update`, tenants get the same schema via `TenantSchemaUpdater`. |

Because of O1, `DataMode` is written **once** at onboarding. `POST /settings/data-mode` does not exist; the only
route that changes a source is `POST /settings/reset` (D11). Because of O4, `GarminErrorCategory.AUTH` must surface
in the tenant UI, not only in the log (Phase 3 checklist).

---

## 20. Review log (2026-11-17, second session)

The first draft was checked line by line against the code and the current Strava/Garmin terms. Corrections:

| # | Finding | Fix |
|---|---|---|
| 1 | Flyway + `ddl-auto: none` changed the owner's app already in Phase 0, even with the flag off | D8 changed by the owner: Hibernate update per DB (§8) |
| 2 | A custom `DataSource` bean disables Spring Boot's auto-configuration, and `initializeDataSourceBuilder()` ignores `hikari.*` (owner pool would grow from 2 to 10) | Keep the auto-configured pool, wrap it with a `BeanPostProcessor` only when the flag is on; control DB is not a `DataSource` bean (§5.2) |
| 3 | Hibernate bootstrap opens a connection with no tenant bound → start-up fails with a fail-closed router | §5.7 |
| 4 | Filter after `SecurityContextHolderFilter` runs before remember-me authentication → owner's first request after restart fails | Filter after `AuthorizationFilter` (§5.4) |
| 5 | Owner bootstrap "re-hash if it differs" would re-hash every boot (BCrypt salt) and kill remember-me | `encoder.matches` (§6.3) |
| 6 | Owner had no `data_mode` → would be `UNSET` → redirected to `/setup` | OWNER hard-coded `BOTH` (§9.1) |
| 7 | Reset "dumps to `deploy/backups/`" — the app container has no `pg_dump` and no access to the host folder | Archive-by-rename (§9.3, §12) |
| 8 | Phase order let a non-owner sign up (old Phase 3) before per-tenant Garmin token/caches/schedulers (old Phase 4): the owner's env Garmin key would sync into the new user's DB, and the 06:00 jobs would fail closed | Phases reordered (§14) |
| 9 | Strava: subscription required, capacity 1 by default (10 self-service), limits 200/2000 & read 100/1000; per-user client id/secret is non-compliant; AI and deletion rules | §2.4a, §10.4, D3, R13–R14 |
| 10 | Garmin personal keys expire (30–365 days) and are for personal, non-commercial use | §2.4, R15 |
| 11 | `activities.source NOT NULL DEFAULT` cannot be added by Hibernate update; and would rewrite meaning of owner rows | Nullable, `null` = STRAVA (§8.4) |
| 12 | `deploy/running.dump` described as "in the repo, current" | It is untracked and from 2026-11-01 (§2.1, §19) |
| 13 | Env invite code "single-use" conflicted with signup before `/admin` exists | Env code reusable, single-use codes from Phase 6 (§6.4) |
| 14 | Login/signup rate limit could lock out the owner or be keyed on the proxy IP | Per (IP, username); verify forwarded IP (§6.4) |
| 15 | Small text errors ("twee dingen" listing three, "six caches" — there are five, "0.1 CPU headroom") | Fixed |
