# Opdracht voor de agent op de Windows-machine

Kopieer alles onder de streep als prompt. Vervang eerst de `<<<...>>>`-plaatsaanduidingen.

---

## Doel

De Running Coach (Kotlin/Spring Boot) draaien op deze Windows 11-machine, met een publieke https-link via
Tailscale Funnel. De app draait in Docker; jij voert de stappen uit en controleert ze. Stop alleen bij de
gemarkeerde momenten (UAC, herstart, Tailscale-console, Strava-console) en vraag dan de gebruiker iets te doen.

## Vaste feiten — niet opnieuw uitzoeken

| Onderwerp | Waarde |
|---|---|
| Repo | `C:\Users\Gebruiker\running` (staat er al) |
| Machine | Windows 11 Pro 25H2, Ryzen 7 PRO 4750U, 32 GB RAM, wifi-only |
| Aanwezig | Node.js, winget. **Niet**: Docker, WSL2, Git, Python, OpenSSH, Tailscale, psql |
| Admin | Alleen via UAC; jij draait als `Gebruiker`, niet verhoogd |
| App | `deploy\docker-compose.yml` (app + Postgres), image wordt lokaal gebouwd |
| Poort | app alleen op `127.0.0.1:8080` |
| Login | verplicht online via `APP_PASSWORD` (zonder wachtwoord start de app niet in prod) |
| Strava-callbackpad | **`/callback`** (start: `/auth/strava`) |
| Database | naam `running`, gebruiker `postgres`, wachtwoord = `DB_PASSWORD` |
| Schema | Hibernate `ddl-auto: update`; **geen** migraties of seeds |
| Data | `deploy\running.dump` (custom format, 47 MB, 591 activiteiten) — vraag het bestand als het ontbreekt |
| Volume | alleen de named volume `pgdata`; back-ups zijn bestanden in `deploy\backups\` |

## Stap 1 — Repo controleren

```powershell
Set-Location C:\Users\Gebruiker\running
Test-Path .\deploy\docker-compose.yml, .\deploy\setup.ps1, .\deploy\migrate.ps1, .\Dockerfile
git log --oneline -3
```

Alles moet `True` zijn en de bovenste commit moet over deployment gaan. Zo niet: stop en meld het.

## Stap 2 — Installeren (UAC)

```powershell
powershell -ExecutionPolicy Bypass -File .\deploy\setup.ps1
```

- Er verschijnt één UAC-vraag: die moet de gebruiker goedkeuren.
- Exitcode 10 = Windows moet opnieuw opstarten (WSL2/Docker). Vraag de gebruiker te rebooten en draai daarna
  hetzelfde commando opnieuw.
- Verschijnt het Docker Desktop-welkomstscherm, vraag de gebruiker het door te klikken (licentie accepteren).
- Werkt `docker info` daarna niet: de gebruiker moet één keer uit- en weer inloggen (groep `docker-users`) en
  daarna opnieuw `setup.ps1` draaien.

## Stap 3 — `deploy\.env` schrijven

Schrijf het bestand `C:\Users\Gebruiker\running\deploy\.env` als **UTF-8 zonder BOM**, met deze inhoud:

```dotenv
DB_PASSWORD=<<<DB_PASSWORD>>>
APP_USERNAME=wout
APP_PASSWORD=<<<APP_PASSWORD>>>
APP_REMEMBER_ME_KEY=<<<APP_REMEMBER_ME_KEY>>>
STRAVA_CLIENT_ID=<<<STRAVA_CLIENT_ID>>>
STRAVA_CLIENT_SECRET=<<<STRAVA_CLIENT_SECRET>>>
STRAVA_REDIRECT_URI=https://PLACEHOLDER.ts.net/callback
GARMIN_PERSONAL_API_KEY=<<<GARMIN_PERSONAL_API_KEY>>>
TZ=Europe/Brussels
```

`STRAVA_REDIRECT_URI` is tijdelijk een placeholder; die vul je in stap 6. Zet het bestand niet in git.

## Stap 4 — Starten

```powershell
powershell -ExecutionPolicy Bypass -File .\deploy\setup.ps1
docker compose -f .\deploy\docker-compose.yml ps
docker compose -f .\deploy\docker-compose.yml logs --tail 50 app
```

Verwacht: `Started ApplicationKt in ... seconds` en
`(Invoke-WebRequest http://127.0.0.1:8080/login -UseBasicParsing).StatusCode` = `200`.

## Stap 5 — Bestaande gegevens terugzetten

```powershell
powershell -ExecutionPolicy Bypass -File .\deploy\migrate.ps1 restore -File .\deploy\running.dump
docker compose -f .\deploy\docker-compose.yml restart app
docker compose -f .\deploy\docker-compose.yml exec -T postgres psql -U postgres -d running -c "select count(*) from activities;"
```

Verwacht: **591**. (`pg_restore` mag waarschuwingen geven over objecten die nog niet bestaan; dat is normaal.)

## Stap 6 — Publieke link (Tailscale Funnel)

1. `& "$env:ProgramFiles\Tailscale\tailscale.exe" up` → de gebruiker logt in via de browser.
2. Naam ophalen: `& "$env:ProgramFiles\Tailscale\tailscale.exe" status --json` → veld `DNSName`
   (bijvoorbeeld `desktop-ekbqgir.tail1234.ts.net`).
3. **Stop hier en vraag de gebruiker** in de Tailscale-console: *DNS → HTTPS Certificates* aanzetten, en in
   *Access controls* het `funnel`-attribuut toevoegen:

```json
{ "nodeAttrs": [ { "target": ["*"], "attr": ["funnel"] } ] }
```

4. Daarna: `tailscale.exe funnel --bg 8080` en `tailscale.exe funnel status`. De URL is
   `https://<DNSName>`.
5. Controleer van buiten (gsm op 4G): de loginpagina moet verschijnen.

## Stap 7 — Strava koppelen

1. Zet in `deploy\.env`: `STRAVA_REDIRECT_URI=https://<DNSName>/callback`.
2. `docker compose -f .\deploy\docker-compose.yml up -d`.
3. **Vraag de gebruiker** in [strava.com/settings/api](https://www.strava.com/settings/api) het
   *Authorization Callback Domain* op `<DNSName>` te zetten (zonder `https://`, zonder `/callback`).
4. Open `https://<DNSName>/auth/strava`, laat de gebruiker goedkeuren; verwacht "Strava gekoppeld!".
5. Test de knop **Nieuwe trainingen syncen** op het dashboard.

## Stap 8 — Overleeft een herstart

1. Vraag toestemming en zet automatisch aanmelden aan: `control userpasswords2` (vinkje "Users must enter a user
   name and password" uit). Zeg erbij dat BitLocker of een BIOS-wachtwoord verstandig is.
2. Controleer in Docker Desktop: *Settings → General → Start Docker Desktop when you sign in* staat aan.
3. Geplande back-up (elke nacht 03:00):

```powershell
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument '-NoProfile -ExecutionPolicy Bypass -File "C:\Users\Gebruiker\running\deploy\migrate.ps1" backup'
Register-ScheduledTask -TaskName 'Running Coach back-up' -Action $action -Trigger (New-ScheduledTaskTrigger -Daily -At 3am) -RunLevel Highest
```

## Stap 9 — Eindcontrole

- `docker compose -f .\deploy\docker-compose.yml ps` → beide containers `running`/`healthy`.
- Publieke URL toont de login; na inloggen het dashboard met historiek.
- `/recovery`, `/performance`, `/load` laden zonder fout.
- Herstart de app één keer (`restart app`) en controleer dat je ingelogd blijft (remember-me).

## Niet doen

- Geen poorten openzetten, geen port-forwarding, geen SSH of tunnels naar deze machine.
- `network_mode: host` of bind-mounts toevoegen aan de compose-file.
- Secrets in git zetten of in de chat/logs plakken.
- Dumps maken met `>`-redirect; gebruik `migrate.ps1` (die gebruikt `docker compose cp`, anders beschadigt
  PowerShell 5.1 het binaire bestand).

## Terugkoppelen

Meld: publieke URL, of login werkt, het aantal activiteiten na het terugzetten, de status van de geplande taak,
en alles wat je moest afwijken van deze stappen.
