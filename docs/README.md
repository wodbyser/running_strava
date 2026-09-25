# Running AI Coach: Documentation

This folder holds the project's documentation that isn't code: architecture, data pipeline, metric definitions and audit reports.

| Document | Content |
|---|---|
| [audit/phase-1-report.md](audit/phase-1-report.md) | Phase 1 correctness/trust audit: issues, trust table, decisions |

## Modules

| Module | Responsibility | Depends on |
|---|---|---|
| `core/` | Domain models (`Activity`, `Lap`, `ActivityStream`, `BestEffort`), analysis logic (`LapClassifier`, `WorkoutStructure`), use cases (sync, fetch, backfill, OAuth exchange), SPI ports (`ActivityRepository`, `StravaApiClient`, `StravaTokenRepository`). **No Spring.** | Jackson annotations only |
| `runner/` | Spring Boot app: Strava REST client, JPA adapters, analysis services (`CoachService`, `PeriodComparisonService`, `AiAnalysisService`), Thymeleaf controllers and templates, scheduler. | `core` |
| `buildSrc/` | Convention plugins (`kotlin-module`, `spring-boot-module`; `.env` loading, default profile). Do not change the JVM target: build on Java 23. | – |

Rule: pure calculation logic belongs in `core` so it can be unit-tested without Spring.

## Data pipeline

```
Strava API ──► StravaApiClientImpl (JSON → domain)
          ──► use cases (core) ──► ActivityRepositoryImpl (JPA entities, PostgreSQL/H2)
          ──► services / controllers (runner) ──► Thymeleaf + Chart.js
                                              └─► AiAnalysisService (copy-paste prompts)
```

### Units and conventions

| Quantity | Stored as | Notes |
|---|---|---|
| Distance | m (Float) | |
| Time | s (Int), moving and elapsed | |
| Speed | m/s (Float) | pace = 1000 / speed s/km |
| Heart rate | bpm (Float/Int) | |
| Cadence | Strava value **per foot** | real running cadence = ×2 (spm) |
| Start time | `TIMESTAMP WITH TIME ZONE`, read back as UTC | the activity's local zone is in the `timezone` string, e.g. `(GMT+01:00) Europe/Brussels` |
| Streams | JSON text per stream (time, distance, heartrate, cadence, …) | |
| Best efforts / splits | **not persisted** (see audit 0.4) | |

## Metric classes

Every output shown to the user or put in a prompt belongs to one of these classes. The audit uses them to set the standard of correctness.

| Class | Meaning | Standard |
|---|---|---|
| A | Direct measurement (Strava) | shown faithfully, correct units |
| B | Deterministic derivation (pace, totals) | mathematically exact |
| C | Established model (Riegel, Karvonen) | correctly implemented + limitations shown |
| D | App heuristic (lap classification, max-HR estimate, profile labels, verdicts) | tested, thresholds marked UNVALIDATED, uncertainty visible ("schatting", "automatisch herkend") |
| E | AI prompt text | complete data with units and definitions; heuristics labelled as app estimates |

## Build and test

```bash
JAVA_HOME=~/.sdkman/candidates/java/23-open ./gradlew :core:test :runner:compileKotlin
```

See the root `AGENTS.md` for running the app, its endpoints and database setup.
