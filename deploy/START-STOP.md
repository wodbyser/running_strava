# Starten en stoppen op deze pc

Deze pc is de altijd-aan server. De app draait in Docker (twee containers) en is bereikbaar via
Tailscale Funnel. Dit bestand is de korte bedieningshandleiding; de volledige installatie staat in
`deploy/DEPLOY.md`.

| | |
|---|---|
| Lokaal | <http://127.0.0.1:8080> |
| Publiek | <https://desktop-ekbqgir.tailfd47be.ts.net> (login nodig) |
| Repo | `C:\Users\Gebruiker\running`, branch `main` |
| Login | gebruikersnaam en wachtwoord staan in `deploy\.env` (`APP_USERNAME` / `APP_PASSWORD`) |
| Containers | `running-app` (de app) en `running-db` (Postgres 17) |
| Database | named volume `running_pgdata` — nooit verwijderen |
| Sleutels | `deploy\.env` (niet in git) |

## Voor een agent zonder context

Kopieer dit als opdracht; de rest vindt de agent in dit bestand.

```
De Running Coach draait op deze Windows-laptop in Docker, publiek op
https://desktop-ekbqgir.tailfd47be.ts.net. Repo: C:\Users\Gebruiker\running (branch main).

Lees eerst deploy\START-STOP.md. Beheer gaat met drie scripts:
  start:  powershell -ExecutionPolicy Bypass -File .\deploy\start.ps1
  stop:   powershell -ExecutionPolicy Bypass -File .\deploy\stop.ps1
  status: powershell -ExecutionPolicy Bypass -File .\deploy\status.ps1   (exitcode 0 = gezond)

Regels: nooit "docker compose down -v" (dat wist de database). "docker compose" kan in
PowerShell exitcode 1 geven door stderr; kijk naar status.ps1 of "docker compose ps".
Logs: docker compose -f .\deploy\docker-compose.yml logs --tail 50 app
Sleutels staan in deploy\.env (niet in git, nooit doorsturen).
```

## De drie commando's

Vanuit `C:\Users\Gebruiker\running`:

```powershell
# starten (of: weer online brengen na stop)
powershell -ExecutionPolicy Bypass -File .\deploy\start.ps1

# stoppen (alles offline)
powershell -ExecutionPolicy Bypass -File .\deploy\stop.ps1

# kijken of alles goed staat (exitcode 0 = in orde)
powershell -ExecutionPolicy Bypass -File .\deploy\status.ps1
```

`start.ps1` is veilig om te draaien als alles al loopt: het doet dan niets stuk. `status.ps1` is de
eerste controle bij elke klacht.

## Wat de scripts doen

| Script | Wat het doet |
|---|---|
| `start.ps1` | Start Docker Desktop als de engine niet antwoordt, doet `docker compose up -d`, wacht tot `/login` 200 geeft en zet `tailscale funnel --bg 8080` aan. |
| `start.ps1 -Build` | Zoals hierboven, maar bouwt eerst de image opnieuw. Nodig na een codewijziging; duurt enkele minuten. |
| `start.ps1 -NoFunnel` | Alleen lokaal starten, publieke link blijft uit. |
| `stop.ps1` | Zet de publieke link uit en stopt de containers. De database blijft bewaard. |
| `stop.ps1 -Down` | Idem, maar verwijdert de containers ook (`docker compose down`). Het volume `running_pgdata` blijft staan. |
| `stop.ps1 -KeepFunnel` | Laat de publieke link staan (geeft dan 502 zolang de app stopt). |
| `status.ps1` | Docker, beide containers, aantal activiteiten in de database, lokale loginpagina, Tailscale, Funnel en de publieke loginpagina. |

## Na een herstart van de pc

De twee containers hebben `restart: unless-stopped` en Docker Desktop staat ingesteld om bij het
aanmelden te starten. Na een herstart komt de app dus vanzelf terug — maar pas nadat iemand op deze
pc is ingelogd: Docker Desktop is geen Windows-service en start niet op het inlogscherm. Tailscale
start wel meteen (dat is een Windows-service), dus de publieke link geeft tot die tijd geen antwoord.

Twee dingen die daardoor misgaan:

| Situatie | Gevolg |
|---|---|
| Er is niemand ingelogd na een herstart of stroompanne | De app blijft offline tot iemand op de pc inlogt. Zet `AutoAdminLogon` aan als dat automatisch moet. |
| De laptop staat op accu | Na 3 minuten stilte gaat hij in slaapstand en is de app offline. Op netstroom gebeurt dat niet, ook niet met de klep dicht. |

Stond de stack met `stop.ps1` uit, dan blijft hij na een herstart uit. Dat is de bedoeling: start dan
weer met `start.ps1`.

Controle na een herstart:

```powershell
powershell -ExecutionPolicy Bypass -File .\deploy\status.ps1
```

## Logs bekijken

```powershell
docker compose -f .\deploy\docker-compose.yml logs --tail 50 app      # laatste 50 regels app
docker compose -f .\deploy\docker-compose.yml logs -f app             # live meekijken, Ctrl+C stopt
docker compose -f .\deploy\docker-compose.yml logs --tail 50 postgres # database
```

Bij een gezonde start staat er `Started ApplicationKt in ... seconds`.

## Code bijwerken

```powershell
git pull
powershell -ExecutionPolicy Bypass -File .\deploy\start.ps1 -Build
```

Database en `.env` blijven bewaard; alleen `running-app` wordt vervangen. De app is tijdens de build
even niet bereikbaar (enkele minuten).

## Database: back-up en terugzetten

De nachtelijke taak **Running Coach back-up** draait om 03:00 en bewaart 14 dagen in `deploy\backups\`.

```powershell
# handmatige back-up
powershell -ExecutionPolicy Bypass -File .\deploy\migrate.ps1 backup

# dump van de huidige database
powershell -ExecutionPolicy Bypass -File .\deploy\migrate.ps1 dump

# dump terugzetten (overschrijft de database volledig), daarna de app herstarten
powershell -ExecutionPolicy Bypass -File .\deploy\migrate.ps1 restore -File .\deploy\backups\running-20261101-0300.dump
docker compose -f .\deploy\docker-compose.yml restart app
```

Volgorde is belangrijk: eerst `restore`, dan `restart app`. De app leest de database bij het opstarten.

## Publieke link aan of uit

De Funnel-link hoort bij `start.ps1` en `stop.ps1`; los kan ook:

```powershell
$ts = "$env:ProgramFiles\Tailscale\tailscale.exe"
& $ts funnel status            # moet de https-link tonen
& $ts funnel --bg 8080         # aanzetten
& $ts funnel --https=443 off   # uitzetten
```

Funnel werkt alleen als in de Tailscale-console HTTPS-certificaten én het `funnel`-attribuut aan staan
(zie `deploy/DEPLOY.md` stap 6). Zonder die twee meldt Tailscale `Funnel is not enabled on your tailnet`
en blijft de publieke link dood.

## Als er iets misgaat

| Symptoom | Oorzaak en oplossing |
|---|---|
| `docker compose` eindigt met exitcode 1, maar de containers lopen | PowerShell maakt van de voortgang op stderr een fout. Kijk naar `docker compose ps` / `status.ps1`, niet naar de exitcode. |
| Publieke link geeft 502 | De app luistert niet op 8080. `status.ps1`, daarna `docker compose -f .\deploy\docker-compose.yml logs --tail 50 app`. |
| Publieke link onbereikbaar, lokaal wel goed | Funnel staat uit of is niet toegestaan. `& $ts funnel status` en `start.ps1`. |
| `running-app` herstart steeds | Database niet bereikbaar of verkeerd wachtwoord. `logs postgres` en `logs app`, en controleer `DB_PASSWORD` in `deploy\.env`. |
| Database leeg na een restore | Verkeerd bestand of mislukte restore. `status.ps1` toont het aantal activiteiten (hoort 591 te zijn op deze dataset). |
| Docker reageert niet | Docker Desktop is niet gestart of de gebruiker is niet ingelogd. `start.ps1` start het en wacht. |
| Na een herstart is de app weg | Docker Desktop is niet automatisch gestart of er is niemand ingelogd. Zie `deploy/DEPLOY.md` stap 5. |

## Wat je nooit moet doen

- `docker compose down -v` — dat verwijdert het volume `running_pgdata` en daarmee de hele database.
- `deploy\.env` committen of doorsturen: daar staan het app-wachtwoord, het databasewachtwoord, de
  Strava-sleutels en de Garmin-sleutel in.
- `deploy\backups\` of een dump in git zetten: dat is een volledige kopie van de gezondheidsdata.
- De map `runner\data\` gebruiken op deze machine: die is voor de lokale H2-ontwikkelmodus, niet voor
  de Docker-deploy (die gebruikt Postgres).
