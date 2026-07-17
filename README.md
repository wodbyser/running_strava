# 🏃 Running Coach

Personal running analytics tool. Syncs your Strava activities to a local database and shows everything in a clean dashboard.

## Quick Start

### 1. Strava App aanmaken (eenmalig)

1. Ga naar https://www.strava.com/settings/api
2. Maak een app aan, noteer **Client ID** en **Client Secret**
3. Zet `Authorization Callback Domain` op `localhost`

### 2. Database starten

```bash
# PostgreSQL via Docker (aanbevolen)
docker compose up -d

# Of gebruik H2 (dev mode, geen Docker nodig)
# Dan start je met het 'dev' profiel (zie stap 3)
```

### 3. Start de app

```bash
STRAVA_CLIENT_ID=xxx STRAVA_CLIENT_SECRET=xxx ./gradlew :runner:bootRun

# Voor H2 (zonder PostgreSQL):
STRAVA_CLIENT_ID=xxx STRAVA_CLIENT_SECRET=xxx ./gradlew :runner:bootRun --args='--spring.profiles.active=dev'
```

### 4. Authoriseer Strava

Open http://localhost:8080 in je browser en klik op de link om in te loggen op Strava.

### 5. Data ophalen

Na authorisatie klik je op **"Alle data ophalen"** op de dashboard pagina.
De app haalt dan al je historische activiteiten, details en GPS-streams op.

## Wat je ziet

| Pagina | Wat |
|---|---|
| **Dashboard** | Totaaloverzicht, laatste 10 trainingen, PBs |
| **Trainingen** | Alle trainingen met tempo, HR, cadence, elevatie |
| **Detail** | Per training alle beschikbare data |
| **PBs** | Beste tijden op 1km, 5km, 10km, HM, marathon |

## Endpoints

| URL | Actie |
|---|---|
| `GET /` | Dashboard |
| `GET /activities` | Alle trainingen |
| `GET /activity/{id}` | Detail van training |
| `GET /sync` | Nieuwe trainingen syncen |
| `GET /fetch-all` | Alle data opnieuw ophalen |
| `GET /auth/strava` | Strava OAuth login |

De geplande sync draait elke dag om 06:00 (aanpasbaar via `strava.sync.cron`).

## Database

```bash
# PostgreSQL via Docker
docker compose up -d  # start PostgreSQL op poort 5432

# Data blijft bewaard in Docker volume 'pgdata'
# Om te resetten: docker compose down -v
```

De app gebruikt PostgreSQL als primaire opslag (alles lokaal).
In dev mode wordt H2 gebruikt (bestand `data/running.mv.db`).

## Uitbreiden met AI

De `AiAnalysisService.buildTrainingContext()` genereert een complete context-string
met al je trainingsdata. Stuur die naar een AI-model (bijv. via LangChain4j, OpenAI, Gemini)
en vraag om analyse, planning, voorspellingen.

Voorbeeldvragen die de AI kan beantwoorden:
- Ben ik aan het overtrainen?
- Hoe evolueert mijn lactaatdrempel?
- Wat is mijn ideale trainingsweek?
- Voorspelling voor halve marathon / marathon
- Blessurerisico op basis van trainingsbelasting

## Technologieën

- **Backend:** Kotlin + Spring Boot 3.4
- **Database:** PostgreSQL 17 (prod) / H2 (dev)
- **Frontend:** Thymeleaf + HTML/CSS
- **API:** Strava v3 API (activiteiten, streams, details)
- **Build:** Gradle (multi-module: core + runner)
