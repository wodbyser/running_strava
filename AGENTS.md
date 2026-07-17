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
# Dev (H2) — .env auto-loaded by convention plugin
STRAVA_CLIENT_ID=xxx STRAVA_CLIENT_SECRET=xxx ./gradlew :runner:bootRun

# Prod (PostgreSQL)
docker compose up -d
./gradlew :runner:bootRun -Dspring.profiles.active=prod
```

- `bootRun` defaults to `dev` profile (see `spring-boot-module.gradle.kts:18`).
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
| `GET /auth/strava` | Strava OAuth initiation |
| `GET /h2-console` | H2 console (dev only) |

Scheduled sync: daily at 06:00 (`strava.sync.cron`), guarded by token presence.

## Testing & Linting

- **No tests exist** in either module (no `src/test` files found).
- `ktlint` dependency present in `buildSrc` but **not configured** in any module.

## Frontend

Thymeleaf templates in `runner/src/main/resources/templates/`. No JS build tool, no frontend framework. Layout via `layout.html`.

## AI Extension

`AiAnalysisService.buildTrainingContext()` returns a formatted training history string. Feed it to any AI provider (LangChain4j, OpenAI, Gemini, etc.).

## Database

- **prod**: PostgreSQL 17 via `docker-compose.yml` (Hibernate `update` DDL)
- **dev**: H2 file `data/running.mv.db` with PostgreSQL compatibility mode (`MODE=PostgreSQL`)
- Reset: `docker compose down -v`
