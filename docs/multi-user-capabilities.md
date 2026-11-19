# Wat werkt in welke modus (Phase 5)

Bron van de waarheid in code: `CapabilityMatrix` in `runner/.../tenant/web/Capabilities.kt`. Dit document en die tabel moeten gelijk blijven.
De eigenaar is altijd `BOTH` (alles volledig, geen enkele melding). Een account kiest één keer een bron (D11).

| Pagina | Alleen Strava | Alleen Garmin | Strava + Garmin |
|---|---|---|---|
| `/` dashboard | volledig | volledig (samenvatting per training, geen Strava-knoppen) | volledig |
| `/activities`, `/activity/{id}` | volledig | gedeeltelijk: geen rondes, geen seconde-data, hoogtemeters onbekend (`-`), geen Strava-link | volledig |
| `/pbs` | volledig | gedeeltelijk: alleen hele trainingen | volledig |
| `/recovery` | **niet beschikbaar** (paneel: "Deze analyse heeft Garmin-data nodig.") | volledig | volledig |
| `/performance`, `/performance/run/{id}` | volledig | **niet beschikbaar** (paneel: "Deze analyse heeft Strava-data nodig (seconde-data per training).") | volledig |
| `/load`, `/load/response` | gedeeltelijk: belasting uit hartslag, zonder Garmin-trainingseffect | volledig | volledig |
| `/race`, `/race/history` | volledig | gedeeltelijk: voorspelling uit wedstrijdtijden, tempoplan en specificiteit missen seconde-data | volledig |
| `/ai` | volledig | gedeeltelijk: prompt zegt expliciet dat rondes en hoogtemeters ontbreken | volledig |
| `/next-training`, `/rate-training`, `/compare`, `/compare-runs`, `/coach` | volledig | gedeeltelijk (zelfde reden als hierboven) | volledig |

Gedeeltelijke pagina's tonen bovenaan één gele regel (`capabilityNote`, layout). Niet-beschikbare pagina's sturen naar `/unavailable`
(één vast paneel, `unavailable.html`), nooit een lege grafiek of een foutpagina.

## Wat er verder per modus verandert

- **Alleen Garmin:** na elke Garmin-sync schrijft `GarminActivityProjector` de Garmin-activiteiten naar `activities` (`source = GARMIN`, hoogtemeters 0 = onbekend). Een bestaande rij met een andere bron wordt nooit overschreven.
  Elke Strava-route (`/sync`, `/fetch-all`, `/fetch-remaining`, `/backfill-*`, `/coach/sync`, `/race/strava-recheck`, `/auth/strava`) doet niets of geeft 409; de Strava-client zelf weigert in de service-laag (`ModeGuardedStravaClient`); `strava_tokens` wordt niet gelezen.
- **Alleen Strava:** de Garmin-sleutel wordt nooit gebruikt (`CredentialResolver`), elke Garmin-start geeft `NOT_CONFIGURED`, geen HTTP-call.
- **Beide:** de projectie staat uit, Strava blijft de basis.

## Hoe dit is nagekeken

`ModeIsolationIT` (Testcontainers, vlag aan): per modus elke pagina uit de tabel geeft 200 (of de paneelpagina, ook 200), nul interacties op de Strava-client in
GARMIN-modus en op de Garmin-client in STRAVA-modus, projectie alleen in een GARMIN-tenant, eigenaar ongewijzigd. Een echte Garmin-account met echte data is nog niet getest (pre-launch test, zie `MULTI_USER_PROGRESS.md`).
