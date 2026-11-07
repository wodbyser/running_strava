# Running AI Coach - Strava Data Sync & Analysis

## Architecture

Multi-module Kotlin + Spring Boot 3.4 (Java 23, Kotlin 2.1.20)

- **`core/`** — domain models, use case interfaces & implementations, SPI ports. No Spring; only Jackson annotations.
- **`runner/`** — Spring Boot app with RestClient, JPA, Thymeleaf controllers, config.
- Adapters in `runner` implement SPI interfaces from `core`. Use cases wired via `@Configuration` (`AppConfig.kt`).
- Module build files follow `{module}.gradle.kts` convention (`core.gradle.kts`, `runner.gradle.kts`).

## Java Setup

Project targets **Java 23**. SDKMAN users: `.sdkmanrc` auto-switches on `cd`. Enable with:
```
sdk config  # set sdkman_auto_env=true
```
Or manually: `sdk use java 23-open`. IntelliJ: set Gradle JVM to JDK 23 in Settings → Build Tools → Gradle.

## Build & Run

```bash
# Default (PostgreSQL) — .env auto-loaded by convention plugin
docker compose up -d
STRAVA_CLIENT_ID=xxx STRAVA_CLIENT_SECRET=xxx ./gradlew :runner:bootRun

# Dev (H2)
./gradlew :runner:bootRun -Dspring.profiles.active=dev
```

- `bootRun` defaults to PostgreSQL (no active profile), see `spring-boot-module.gradle.kts:18`.
- `.env` from project root is loaded automatically at boot (same convention plugin).
- PostgreSQL container name: `running-db`, port 5432, volume `pgdata`.

## Key Endpoints

| URL | Purpose |
|---|---|
| `POST /fetch-all` | Fetch all historical activities + details + streams |
| `POST /sync` | Sync only new activities since last sync |
| `GET /` | Dashboard (Thymeleaf) |
| `GET /activities` | Activity list |
| `GET /activity/{id}` | Activity detail |
| `GET /recovery`, `/performance`, `/load`, `/load/response` | Garmin recovery, performance, training load and "reaction to training" pages (read-only, computed on demand; `/load/data` is the JSON) |
| `GET /race`, `/race/history` | Race prediction, goals, pacing plan, race readiness, and the look back at past races (read-only, computed on demand; `/race/data` is the JSON). Goals: `POST /race/goal`, `POST /race/goal/{id}/delete`; `POST /race/strava-recheck` re-reads every Strava summary once |
| `GET /ai` | AI prompt page (copy-paste for external chatbots, no API key needed) |
| `GET /auth/strava` | Strava OAuth initiation |
| `GET /h2-console` | H2 console (dev only) |

Scheduled sync: daily at 06:00 (`strava.sync.cron`), guarded by token presence.

## Testing & Linting

- Run on Java 23: `JAVA_HOME=~/.sdkman/candidates/java/23-open ./gradlew :core:test :runner:test`
  (Java 21 gives a misleading "Inconsistent JVM target" error; don't "fix" buildSrc).
- `core/src/test`: golden lap dataset (`GoldenLaps`), classifier/structure, best efforts, units/aggregates, models (EF, Riegel, HR zones).
- `runner/src/test`: service tests with an in-memory `FakeActivityRepository` (no Spring context).
- Pure calculation logic lives in `core` (`com.running.strava.analysis`, `LapClassifier`, `WorkoutStructure`); keep it Spring-free.
- `ktlint` dependency present in `buildSrc` but **not configured** in any module.

## Frontend

Thymeleaf templates in `runner/src/main/resources/templates/`. No JS build tool, no frontend framework. Layout via `layout.html`.
Shared pieces: `fragments.html` (`help(term)` popover, `windowForm(keys, anchor)` period form, `recoveryHero`), Chart.js from CDN with defaults in `layout.html` (`window.RC`).
UI rules (owner feedback, binding): `docs/ui-guidelines.md`: conclusion first, charts over tables, plain Dutch, explain only hard terms, Dutch dates via `PerfDates`, every period adjustable, check pages in a browser before reporting.

## AI Extension

`AiAnalysisService.buildTrainingContext()` returns a formatted training history string. Feed it to any AI provider (LangChain4j, OpenAI, Gemini, etc.).

## Database

- **prod**: PostgreSQL 17 via `docker-compose.yml` (Hibernate `update` DDL)
- **dev**: H2 file `data/running.mv.db` with PostgreSQL compatibility mode (`MODE=PostgreSQL`)
- Reset: `docker compose down -v`

## Deployment

Online/always-on hosting lives in `deploy/` (`DEPLOY.md` is the full runbook, in Dutch; target is a Windows 11
machine with Docker Desktop + Tailscale Funnel).

- **Linux (Ubuntu/Debian) laptops/servers: `deploy/LINUX.md`**, with `deploy/setup.sh`, `deploy/start.sh`,
  `deploy/stop.sh`, `deploy/status.sh`, `deploy/migrate.sh` and `deploy/install-systemd.sh` (systemd units for
  boot + a nightly backup timer). Same compose file, same image, same database; only the host tooling differs.
  Note: `deploy/docker-compose.yml` uses project `running`, so it shares the volume `running_pgdata` and the
  container names with the root dev compose — run dev and deploy one at a time.

- **Day-to-day on the server: `deploy/START-STOP.md`** (Dutch, start there): `deploy/start.ps1`,
  `deploy/stop.ps1`, `deploy/status.ps1`. `start` = Docker up + wait for `/login` + Funnel on; `stop` = Funnel
  off + containers stopped (DB volume kept); `status` exits 0 when healthy. Live at
  <https://desktop-ekbqgir.tailfd47be.ts.net> on Windows host `C:\Users\Gebruiker\running`.
- `deploy/docker-compose.yml` — app + Postgres, app on `127.0.0.1:8080`, one named volume `pgdata`, no bind mounts.
- `deploy/setup.ps1` (Windows) / `deploy/setup.sh` (Linux) — installs Docker + Tailscale, disables sleep, creates
  `.env`, starts the stack.
- Never `docker compose down -v`: that drops the `pgdata` volume, i.e. the whole database.
- `docker compose` in PowerShell can exit 1 because progress goes to stderr; trust `docker compose ps` /
  `status.ps1`, not the exit code. Shell scripts need LF: `.gitattributes` pins `gradlew` and `*.sh` to `eol=lf`
  (CRLF made the Docker build fail with `./gradlew: not found`).
- `deploy/migrate.ps1` / `migrate.sh` — `dump` / `restore` / `backup`, only via `docker compose exec|cp`.
- `Dockerfile` — multi-stage build with an AppCDS archive (faster start-up); `application-prod.yml` sets cache,
  INFO logging, `PORT`/`forward-headers-strategy` and requires `APP_PASSWORD`.
- Login: `SecurityConfig` + `AppSecurityProperties` (`app.security.*`). Blank `APP_PASSWORD` = open app (local).
  CSRF is disabled on purpose (plain Thymeleaf forms); keep the session cookie `SameSite=Lax`.
- Public URL: only `STRAVA_REDIRECT_URI` (Strava authorize URL, path `/callback`). No other base-URL setting.
- Schema: Hibernate `ddl-auto: update`, no Flyway/seeds. All state (incl. Strava tokens, Garmin raw payloads) is in
  Postgres; there are no uploads.
- A 512 MB / 0.1 CPU cloud host was measured and is too small (4 min start-up, `/load` OOM). Do not deploy there.

