# Running Coach op een altijd-aan Windows-pc

De app draait in Docker (app + Postgres) op een Windows 11-machine die altijd aan staat. De publieke
https-link komt van **Tailscale Funnel**: gratis, geen creditcard, geen routerinstellingen, geen open poorten.

```
browser (overal)  ->  https://<machine>.<tailnet>.ts.net  ->  Tailscale Funnel
                                                          ->  127.0.0.1:8080  (app, met login)
                                                          ->  Postgres (Docker-volume pgdata)
```

Waarom niet in de cloud: de gratis cloudservers zonder creditcard geven 512 MB en 0,1 CPU. Gemeten op deze app:
opstarten 4 minuten, dashboard 3 minuten, `/load` crasht door geheugengebrek. Een gewone laptop is ruim 10x sneller.

---

## Dagelijks starten en stoppen

Deze handleiding is eenmalig (installatie). Voor het dagelijkse gebruik staat alles in
[`START-STOP.md`](START-STOP.md): `start.ps1`, `stop.ps1` en `status.ps1`.

---

## Feiten over deze app (voor de agent op de doelmachine)

| Vraag | Antwoord |
|---|---|
| **1. Wat moet er in de repo staan** | De volledige repo, inclusief `deploy/` en `Dockerfile`. De app bouwt zichzelf in de image; er is niets vooraf gecompileerd nodig. |
| **2. `.env`-velden** | Zie `deploy/.env.example`, hieronder volledig. Verplicht: `DB_PASSWORD`, `APP_PASSWORD`, `STRAVA_CLIENT_ID`, `STRAVA_CLIENT_SECRET`, `STRAVA_REDIRECT_URI`. Optioneel: `GARMIN_PERSONAL_API_KEY`, `APP_USERNAME`, `APP_REMEMBER_ME_KEY`, `TZ`. |
| **3. Strava-callbackpad** | **`/callback`** (niet `/auth/strava/callback`). `GET /auth/strava` start de autorisatie, `GET /callback` wisselt de code in. De volledige redirect-URI is dus `https://<machine>.<tailnet>.ts.net/callback`; het *Authorization Callback Domain* bij Strava is `<machine>.<tailnet>.ts.net`. |
| **4. Publieke URL** | Eén env-var: **`STRAVA_REDIRECT_URI`**. Die wordt alleen gebruikt om de Strava-autorisatie-URL te bouwen. De token-uitwisseling stuurt géén `redirect_uri` mee. De app kent verder geen eigen basis-URL: alle links in de templates zijn relatief. `PORT` bepaalt de listen-poort (standaard 8080); `server.forward-headers-strategy=framework` (prod-profiel) zorgt dat redirects het `X-Forwarded-Proto`/`Host` van Funnel volgen. |
| **5. Database** | Database **`running`**, gebruiker **`postgres`**, wachtwoord uit `DB_PASSWORD` (lokaal: `admin`). Dump: `deploy/running.dump`, custom format (`pg_dump -Fc`), 47 MB, 591 activiteiten, inclusief Strava-tokens en Garmin-gegevens. |
| **6. Migraties/seeds** | **Geen.** Geen Flyway, geen Liquibase, geen `data.sql`. Hibernate `ddl-auto: update` maakt het schema bij de eerste start zelf aan; de tabellen komen leeg op. Geen uploads: alle toestand zit in Postgres, inclusief `garmin_raw_payload` en `strava_tokens`. Het enige volume is de **named volume `pgdata`**; back-ups zijn gewone bestanden op de host (`deploy\backups\`). |

### `deploy/.env.example` (volledig)

```dotenv
# Copy to .env and fill in. Never commit this file.
# Generate secrets with:  openssl rand -hex 32
# (On Windows, deploy/setup.ps1 fills DB_PASSWORD and APP_REMEMBER_ME_KEY automatically.)

# Database password (internal only, pick something random)
DB_PASSWORD=

# Login for the app itself
APP_USERNAME=wout
APP_PASSWORD=
# Keep this stable, otherwise everyone is logged out after a restart
APP_REMEMBER_ME_KEY=

# Strava API app (https://www.strava.com/settings/api)
STRAVA_CLIENT_ID=
STRAVA_CLIENT_SECRET=
# Must match the Authorization Callback Domain in Strava, with /callback
STRAVA_REDIRECT_URI=https://YOUR-MACHINE.YOUR-TAILNET.ts.net/callback

# Optional: Garmin personal API key
GARMIN_PERSONAL_API_KEY=

# Timezone for the 06:00 and 06:30 sync jobs
TZ=Europe/Brussels
```

---

## Wat je nodig hebt

| | |
|---|---|
| Machine | Deze laptop: Ryzen 7 PRO 4750U, 32 GB RAM, 427 GB vrij. Ruim voldoende (de app gebruikt ongeveer 0,5 GB). |
| Rechten | Eenmalig een admin-sessie (UAC) voor WSL2, Docker Desktop, Tailscale en `powercfg`. |
| Account | Gratis [Tailscale](https://login.tailscale.com/start)-account. |
| Netwerk | Wifi is prima. Er hoeft geen poort open en er is geen SSH nodig. |

De bestaande toestand is geen probleem: Docker, WSL2, Tailscale en OpenSSH ontbreken allemaal, en `setup.ps1`
installeert wat nodig is. Git en Python zijn niet nodig. Node.js is niet nodig.

---

## Stap 1 — Repo op de machine

Zet de repo in `C:\Users\Gebruiker\running` (pad maakt verder niet uit). Twee mogelijkheden:

- **Zip:** pak het archief uit zodat `deploy\`, `runner\`, `core\`, `buildSrc\`, `gradle\`, `gradlew`,
  `Dockerfile` en `settings.gradle.kts` in dezelfde map staan.
- **Git:** `winget install -e --id Git.Git`, daarna `git clone <private-remote> C:\Users\Gebruiker\running`.

Controle: `Test-Path .\deploy\docker-compose.yml` moet `True` geven.

## Stap 2 — Installeren (WSL2, Docker Desktop, Tailscale, energie)

Open PowerShell **als Gebruiker** (niet als Administrator) in de repo-map:

```powershell
powershell -ExecutionPolicy Bypass -File .\deploy\setup.ps1
```

Het script vraagt één keer om verhoging (UAC) en doet dan:

1. WSL2 aanzetten (`dism` + `wsl --install --no-distribution`; er is géén Linux-distributie nodig).
2. Docker Desktop en Tailscale installeren via winget, en je toevoegen aan `docker-users`.
3. Slaapstand, sluimerstand en schijf-timeout op netstroom uitzetten; klep dicht = niets doen;
   netwerkprofiel van Openbaar naar Privé; firewall-regel voor Tailscale.
4. `deploy\.env` aanmaken met willekeurige `DB_PASSWORD` en `APP_REMEMBER_ME_KEY`.
5. Daarna stoppen met de melding welke waarden nog ontbreken.

**Belangrijk:** als het script meldt dat er een herstart nodig is (eerste keer WSL2/Docker), start Windows opnieuw
op en draai hetzelfde commando nog eens. Na de installatie moet je één keer uit- en weer inloggen zodat de
groep `docker-users` actief wordt; als Docker daarna nog niet reageert, is dat de oorzaak.

## Stap 3 — `deploy\.env` invullen

```powershell
notepad .\deploy\.env
```

| Variabele | Waarde |
|---|---|
| `APP_PASSWORD` | Je loginwachtwoord voor de app. Kies iets sterks. |
| `STRAVA_CLIENT_ID`, `STRAVA_CLIENT_SECRET` | Uit [strava.com/settings/api](https://www.strava.com/settings/api). |
| `STRAVA_REDIRECT_URI` | `https://<machine>.<tailnet>.ts.net/callback` (de naam komt uit stap 6; mag later ingevuld worden). |
| `GARMIN_PERSONAL_API_KEY` | Optioneel. |
| `DB_PASSWORD`, `APP_REMEMBER_ME_KEY` | Al automatisch ingevuld. Niet wijzigen. |

## Stap 4 — Starten en controleren

```powershell
powershell -ExecutionPolicy Bypass -File .\deploy\setup.ps1
docker compose -f .\deploy\docker-compose.yml ps
docker compose -f .\deploy\docker-compose.yml logs -f app     # Ctrl+C stopt met kijken
```

Verwacht in de logs: `Started ApplicationKt in ... seconds`. Controle op de machine zelf:

```powershell
(Invoke-WebRequest http://127.0.0.1:8080/login -UseBasicParsing).StatusCode   # 200
```

De app luistert alleen op `127.0.0.1`; je netwerk ziet hem niet. Dat is bewust.

## Stap 5 — Automatisch starten na een herstart

De containers hebben `restart: unless-stopped`, maar Docker Desktop is geen Windows-service: het start pas
wanneer jouw gebruiker is ingelogd. Zet daarom automatisch aanmelden aan, anders staat de app na een
stroompanne of herstart stil:

```powershell
control userpasswords2        # vink "Users must enter a user name and password" uit
```

Overweeg daarbij BitLocker (of minstens een BIOS-wachtwoord), want de laptop start dan zonder wachtwoord op.

## Stap 6 — Publieke https-link aanzetten (Tailscale Funnel)

Eenmalig in de Tailscale-beheerconsole:

1. **DNS** → *HTTPS Certificates* aanzetten, en MagicDNS staat aan.
2. **Access controls** → voeg de Funnel-toestemming toe in het policy-bestand:

```json
{
  "nodeAttrs": [
    { "target": ["*"], "attr": ["funnel"] }
  ]
}
```

Op de laptop:

```bash
sudo tailscale funnel --bg 8080
sudo tailscale funnel status
```

De uitvoer toont je adres, bijvoorbeeld `https://running-laptop.tail1234.ts.net`.
Test het van buiten (gsm op 4G): je hoort de loginpagina van Running Coach te zien.

Op Windows zijn de commando's (zonder `sudo`, wel in PowerShell):

```powershell
& "$env:ProgramFiles\Tailscale\tailscale.exe" up          # eenmalig, opent de browser
& "$env:ProgramFiles\Tailscale\tailscale.exe" funnel --bg 8080
& "$env:ProgramFiles\Tailscale\tailscale.exe" funnel status
```

## Stap 7 — Strava koppelen aan de nieuwe link

1. Zet in `deploy\.env`: `STRAVA_REDIRECT_URI=https://<machine>.<tailnet>.ts.net/callback`.
2. Herstart: `docker compose -f .\deploy\docker-compose.yml up -d`.
3. In [strava.com/settings/api](https://www.strava.com/settings/api): *Authorization Callback Domain* =
   `<machine>.<tailnet>.ts.net` (zonder `https://` en zonder `/callback`).
4. Open `https://<machine>.<tailnet>.ts.net/auth/strava`, log in en keur goed. Je ziet "Strava gekoppeld!".
5. Test de knop **Nieuwe trainingen syncen** op het dashboard.

## Stap 8 — Je bestaande gegevens overzetten

De dump staat klaar als `deploy\running.dump` (custom format, 47 MB, 591 activiteiten). Zet dat bestand in
`deploy\` op deze machine en dan:

```powershell
powershell -ExecutionPolicy Bypass -File .\deploy\migrate.ps1 restore -File .\deploy\running.dump
docker compose -f .\deploy\docker-compose.yml restart app
```

Daarna staan alle activiteiten, Garmin-gegevens en de Strava-tokens erin; niets opnieuw ophalen nodig.
Controle: open de link en kijk of je historiek op het dashboard staat.

De dump bevat ook vier oude hoofdletter-tabellen (`ACTIVITIES`, `ACTIVITY_STREAMS`, `STRAVA_TOKENS`,
`SYNC_STATUS`) met dezelfde inhoud. Die zijn ongebruikt: de app leest de kleine-letter-versies. Je mag ze negeren
of later opruimen.

## Stap 9 — Automatische back-ups

Een geplande taak in Windows (elke nacht om 03:00, bewaart 14 dagen):

```powershell
$action = New-ScheduledTaskAction -Execute 'powershell.exe' `
  -Argument '-NoProfile -ExecutionPolicy Bypass -File "C:\Users\Gebruiker\running\deploy\migrate.ps1" backup'
$trigger = New-ScheduledTaskTrigger -Daily -At 3am
Register-ScheduledTask -TaskName 'Running Coach back-up' -Action $action -Trigger $trigger -RunLevel Highest
```

De back-ups komen in `deploy\backups\`. Zet ze af en toe ook op een andere schijf of in OneDrive.

## Stap 10 — Updaten na een codewijziging

Vervang de bestanden in de repo-map (zip of `git pull`) en dan:

```powershell
docker compose -f .\deploy\docker-compose.yml up -d --build
```

Database en `.env` blijven bewaard; alleen de app-container wordt vervangen. Duurt enkele minuten, de app is
in die tijd even niet bereikbaar.

---

## De dagelijkse sync

De app synchroniseert zelf: Strava om 06:00 en Garmin om 06:30 (`Europe/Brussels`, via `TZ` in de compose-file).
Omdat de machine altijd aan staat, werkt dat zonder extra hulp. Handmatig syncen kan altijd met de knoppen op het
dashboard.

## Wat als er iets misgaat

| Symptoom | Oorzaak en oplossing |
|---|---|
| `docker` bestaat niet / reageert niet | Docker Desktop draait niet of je bent niet (opnieuw) ingelogd na toevoeging aan `docker-users`. Start Docker Desktop en probeer opnieuw. |
| Docker Desktop start niet | Virtualisatie (SVM Mode) staat uit in de BIOS/UEFI, of WSL2 is nog niet actief na de herstart. |
| Funnel-link geeft 502 | De app luistert niet op 8080. `docker compose -f .\deploy\docker-compose.yml ps` en `logs app`. |
| App stopt met "Could not resolve placeholder 'APP_PASSWORD'" | `APP_PASSWORD` ontbreekt in `deploy\.env`. |
| App herstart steeds | Database niet bereikbaar of verkeerd wachtwoord. `logs postgres` en `logs app`. |
| Je wordt steeds uitgelogd | `APP_REMEMBER_ME_KEY` wijzigt; zet er een vaste waarde in (het script doet dat). |
| Funnel: "not enabled" | Stap 6.2 (ACL met `funnel`) en HTTPS-certificaten aanzetten. |
| Laptop gaat toch in slaap | `powercfg /change standby-timeout-ac 0`, klep-instelling controleren, en "Modern Standby" in de energie-instellingen. |
| Na een herstart is de app weg | Automatisch aanmelden aanzetten (stap 5) en in Docker Desktop "Start Docker Desktop when you sign in" aanvinken. |
| Schijf vol | `docker system prune -af` en oude bestanden in `deploy\backups\` verwijderen. |
| Eerste pagina traag na herstart | Normaal: de app doet bij het opstarten rekenwerk. Daarna is alles snel. |

Nuttige commando's:

```powershell
docker compose -f .\deploy\docker-compose.yml ps
docker compose -f .\deploy\docker-compose.yml logs --tail 100 app
docker compose -f .\deploy\docker-compose.yml restart app
& "$env:ProgramFiles\Tailscale\tailscale.exe" funnel status
```

## Beveiliging in één alinea

De link is publiek bereikbaar, dus de login staat aan: zonder `APP_PASSWORD` start de app online niet eens.
De app-poort is alleen lokaal open, de database helemaal niet. `deploy\.env` bevat je sleutels: nooit in git,
niet doorsturen, en de map `deploy\backups\` bevat een volledige kopie van je gezondheidsdata.
Wil je de app liever privé houden, laat dan stap 6 weg en gebruik Tailscale op je eigen toestellen:
`http://<machine>:8080` werkt dan binnen je tailnet.

## Bijlage — Linux/macOS

Op Linux of macOS werkt dezelfde compose-file; gebruik `deploy/setup.sh` en `deploy/migrate.sh` in plaats van de
PowerShell-scripts. De compose-file heeft geen Linux-aannames: geen `network_mode: host`, geen absolute paden,
geen bind-mounts met vaste uid/gid, één named volume (`pgdata`) en een healthcheck op Postgres.

Voor een volledige Linux-installatie (start/stop/status-scripts, systemd-units, nachtelijke back-up) staat er een
aparte runbook: [`LINUX.md`](LINUX.md).
