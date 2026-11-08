# Running Coach op een altijd-aan Linux-pc (Ubuntu/Debian)

De app draait in Docker (app + Postgres) en is wereldwijd bereikbaar via **Tailscale Funnel**:
gratis, geen creditcard, geen routerinstellingen, geen open poorten.

```
browser (overal)  ->  https://<machine>.<tailnet>.ts.net  ->  Tailscale Funnel
                                                          ->  127.0.0.1:8080  (app, met login)
                                                          ->  Postgres (Docker-volume pgdata)
```

De Windows-variant staat in [`DEPLOY.md`](DEPLOY.md) en [`START-STOP.md`](START-STOP.md). Deze
handleiding is de Linux-tegenhanger; de compose-file, de image en de database zijn identiek.

| | |
|---|---|
| Repo | deze map (bijv. `/home/<gebruiker>/Documents/running`) |
| Lokaal | <http://127.0.0.1:8080> |
| Publiek | `https://<machine>.<tailnet>.ts.net` (login nodig) |
| Containers | `running-app` (de app) en `running-db` (Postgres 17) |
| Database | named volume `running_pgdata` — nooit verwijderen |
| Sleutels | `deploy/.env` (niet in git, modus 600) |
| Back-ups | `deploy/backups/` (14 dagen, nachtelijke timer) |

---

## Voor een agent zonder context

Kopieer dit als opdracht; de rest vindt de agent in dit bestand.

```
De Running Coach draait op deze Linux-laptop in Docker, publiek via Tailscale Funnel.
Repo: <pad naar de repo> (branch main). Lees eerst deploy/LINUX.md.

Beheer gaat met vier scripts (geen sudo nodig als je in de docker-groep zit):
  start:   bash deploy/start.sh            (--build na een codewijziging, --no-funnel voor alleen lokaal)
  stop:    bash deploy/stop.sh             (--down verwijdert de containers, --keep-funnel laat de link staan)
  status:  bash deploy/status.sh           (exitcode 0 = gezond)
  back-up: bash deploy/migrate.sh backup

Dezelfde dingen via systemd: systemctl status|start|stop running-coach.
Regels: nooit "docker compose down -v" (dat wist de database). Logs:
  docker compose -f deploy/docker-compose.yml logs --tail 50 app
Sleutels staan in deploy/.env (niet in git, nooit doorsturen).
```

---

## Eenmalige installatie

### 0. Wat je nodig hebt

| | |
|---|---|
| Machine | Een gewone laptop of pc met Ubuntu/Debian. De app gebruikt ongeveer 0,5 GB. |
| Rechten | Eenmalig sudo (Docker, Tailscale, systemd). |
| Account | Gratis [Tailscale](https://login.tailscale.com/start)-account. |
| Netwerk | Wifi is prima. Geen open poort, geen SSH nodig. |

### 1. Repo op de machine

Zorg dat `deploy/`, `runner/`, `core/`, `buildSrc/`, `gradle/`, `gradlew`, `Dockerfile` en
`settings.gradle.kts` in dezelfde map staan (`git clone` of een zip uitpakken). De app bouwt zichzelf
in de image; er hoeft niets vooraf gecompileerd te worden.

Controle: `test -f deploy/docker-compose.yml && echo ok`.

### 2. Installeren

```bash
bash deploy/setup.sh
```

Het script doet, veilig om te herhalen:

1. Docker installeren als het ontbreekt, `docker.service` aanzetten, je aan de `docker`-groep toevoegen.
2. Tailscale installeren, `tailscaled` aanzetten en je eenmalig laten inloggen (`tailscale up`).
3. `deploy/.env` aanmaken met willekeurige `DB_PASSWORD` en `APP_REMEMBER_ME_KEY`, en
   `STRAVA_REDIRECT_URI` invullen op basis van de Tailscale-naam.
4. **Stoppen** met de melding welke waarden nog ontbreken.

Slaapstand blijft standaard **aan**: dit is een gewone laptop. Voor een machine die altijd bereikbaar
moet zijn gebruik je `bash deploy/setup.sh --always-on`; dat zet `sleep`/`suspend`/`hibernate` uit en
negeert de klep. Terugdraaien:

```bash
sudo systemctl unmask sleep.target suspend.target hibernate.target hybrid-sleep.target
sudo rm /etc/systemd/logind.conf.d/99-running-coach.conf
```

> Ben je net toegevoegd aan de `docker`-groep, log dan één keer uit en weer in. Tot dan werkt alles
> via sudo, dat vraagt dan om je wachtwoord.

### 3. `deploy/.env` invullen

```bash
nano deploy/.env     # of: code deploy/.env
```

| Variabele | Waarde |
|---|---|
| `APP_PASSWORD` | Je loginwachtwoord voor de app. Kies iets sterks. |
| `STRAVA_CLIENT_ID`, `STRAVA_CLIENT_SECRET` | Uit [strava.com/settings/api](https://www.strava.com/settings/api). |
| `STRAVA_REDIRECT_URI` | `https://<machine>.<tailnet>.ts.net/callback` (staat er meestal al). |
| `GARMIN_PERSONAL_API_KEY` | Optioneel. |
| `DB_PASSWORD`, `APP_REMEMBER_ME_KEY` | Al automatisch ingevuld. Niet wijzigen, behalve `DB_PASSWORD` als het database-volume al bestaat (zie hieronder). |

### 4. Starten en controleren

```bash
bash deploy/setup.sh            # opnieuw: bouwt de image, start de stack, wacht op /login
bash deploy/status.sh           # exitcode 0 = alles in orde
```

Verwacht in de logs: `Started ApplicationKt in ... seconds`. De app luistert alleen op `127.0.0.1`;
je netwerk ziet hem niet. Dat is bewust.

### 5. Publieke https-link aanzetten (Tailscale Funnel)

`setup.sh` en `start.sh` zetten de Funnel automatisch aan. Tailscale moet dat wel toestaan; eenmalig
in de Tailscale-beheerconsole:

1. **DNS** → *HTTPS Certificates* aanzetten, en MagicDNS staat aan.
2. **Access controls** → het `funnel`-attribuut toevoegen:

```json
{
  "nodeAttrs": [
    { "target": ["*"], "attr": ["funnel"] }
  ]
}
```

Daarna (nog eens `bash deploy/start.sh` of direct):

```bash
sudo tailscale funnel --bg 8080
sudo tailscale funnel status      # toont je https-adres
```

`start.sh` draait dat commando zelf en vraagt daarbij om je sudo-wachtwoord (dat blijft onzichtbaar
tijdens het typen; de prompt blijft staan tot je Tailscale in de console toestaat). Wil je die
vraag helemaal weg, maak je gebruiker dan eenmalig Tailscale-operator:

```bash
sudo tailscale set --operator="$USER"
```

Vanaf dan werken `start.sh` en `stop.sh` zonder sudo-prompt.

Test het van buiten (gsm op 4G): je hoort de loginpagina van Running Coach te zien.

### 6. Strava koppelen aan de nieuwe link

1. `STRAVA_REDIRECT_URI=https://<machine>.<tailnet>.ts.net/callback` staat in `deploy/.env`.
2. In [strava.com/settings/api](https://strava.com/settings/api): *Authorization Callback Domain* =
   `<machine>.<tailnet>.ts.net` (zonder `https://` en zonder `/callback`).
3. Herstart: `bash deploy/start.sh`.
4. Open `https://<machine>.<tailnet>.ts.net/auth/strava`, log in en keur goed. Je ziet
   "Strava gekoppeld!".
5. Test de knop **Nieuwe trainingen syncen** op het dashboard.

> **Let op bij twee machines.** Strava staat maar één *Authorization Callback Domain* per API-app toe.
> Zet je deze laptop naast de Windows-pc online, dan kun je maar op één van de twee opnieuw
> autoriseren. De token-uitwisseling zelf stuurt geen `redirect_uri` mee, dus een bestaand token
> (zoals in `deploy/running.dump`) blijft werken; alleen opnieuw inloggen bij Strava vraagt om het
> juiste domein. Wil je beide machines onafhankelijk kunnen koppelen, maak dan een tweede Strava
> API-app aan en gebruik die sleutels hier.

### 7. Je bestaande gegevens overzetten

`deploy/running.dump` (custom format, ±48 MB, 591 activiteiten) staat klaar in de repo:

```bash
bash deploy/migrate.sh restore running.dump
docker compose -f deploy/docker-compose.yml restart app
```

Volgorde is belangrijk: eerst `restore`, dan `restart app`. Daarna staan alle activiteiten,
Garmin-gegevens en de Strava-tokens erin; niets opnieuw ophalen nodig. `deploy/status.sh` toont het
aantal activiteiten — dat hoort 591 te zijn voor deze dataset.

De dump bevat ook vier oude hoofdletter-tabellen (`ACTIVITIES`, `ACTIVITY_STREAMS`, `STRAVA_TOKENS`,
`SYNC_STATUS`) met dezelfde inhoud. Die zijn ongebruikt: de app leest de kleine-letter-versies. Je mag
ze negeren of later opruimen.

> **Staat er al een database?** Dan sla je deze stap over. `deploy/status.sh` toont het aantal
> activiteiten; staat daar een getal boven 0, dan is de database al gevuld en zou een restore die
> nieuwere gegevens overschrijven.

### Gedeelde database met de dev-stack

`deploy/docker-compose.yml` gebruikt projectnaam `running`, net als de `docker-compose.yml` in de root
van de repo (de dev-database). Daardoor delen ze het volume `running_pgdata` en de container­namen
`running-db` / `running-app`. Dat is bewust: één dataset, geen tweede kopie van je gezondheidsdata.

Gevolgen:

- Draai niet tegelijk de dev-stack en de deploy-stack. `docker compose up -d` in de root vervangt de
  container `running-db` door de dev-variant (met poort 5432 open op de host); de app blijft werken,
  want het volume en het netwerk zijn hetzelfde.
- Bestond het volume al met een ander wachtwoord, dan kan de app niet verbinden
  (`password authentication failed`). Zet dan in `deploy/.env` hetzelfde wachtwoord als waarmee het
  volume ooit is aangemaakt — voor de dev-database is dat `admin`.
- Op een verse machine bestaat het volume nog niet en vult Postgres het wachtwoord uit `deploy/.env`
  in bij de eerste start.

### 8. Automatisch starten en back-ups

`setup.sh` installeert twee systemd-units (tenzij je `--no-systemd` meegaf):

| Unit | Wat het doet |
|---|---|
| `running-coach.service` | Start bij het opstarten de stack én de publieke link. `ExecStop` is `stop.sh`. |
| `running-coach-backup.timer` | Elke nacht om 03:00 een dump in `deploy/backups/`, 14 dagen bewaard. |

```bash
systemctl status running-coach
systemctl list-timers 'running-coach*'
sudo systemctl start running-coach-backup.service   # nu meteen een back-up
```

Handmatig installeren of opnieuw renderen (bijv. na het verplaatsen van de repo):

```bash
bash deploy/install-systemd.sh
```

De units bevatten het absolute pad naar deze repo. Verplaats je de map, draai dan
`install-systemd.sh` opnieuw.

---

## Dagelijks gebruik

```bash
bash deploy/start.sh              # starten (of weer online brengen na stop)
bash deploy/stop.sh               # alles offline, database blijft bewaard
bash deploy/status.sh             # controle, exitcode 0 = gezond
```

`start.sh` is veilig om te draaien als alles al loopt: het doet dan niets stuk. `status.sh` is de
eerste controle bij elke klacht.

| Script | Wat het doet |
|---|---|
| `start.sh` | Start `docker.service` als de engine niet antwoordt, doet `docker compose up -d`, wacht tot `/login` 200 geeft en zet `tailscale funnel --bg 8080` aan. |
| `start.sh --build` | Zoals hierboven, maar bouwt eerst de image opnieuw. Nodig na een codewijziging; duurt enkele minuten. |
| `start.sh --no-funnel` | Alleen lokaal/tailnet starten, publieke link blijft uit. |
| `stop.sh` | Zet de publieke link uit en stopt de containers. De database blijft bewaard. |
| `stop.sh --down` | Idem, maar verwijdert de containers ook (`docker compose down`). Het volume `running_pgdata` blijft staan. |
| `stop.sh --keep-funnel` | Laat de publieke link staan (geeft dan 502 zolang de app stopt). |
| `status.sh` | Docker, beide containers, aantal activiteiten in de database, lokale loginpagina, Tailscale, Funnel en de publieke loginpagina. |

Via systemd (zelfde effect):

```bash
sudo systemctl start running-coach
sudo systemctl stop running-coach
systemctl status running-coach
```

### Na een herstart van de pc

`docker.service` is enabled en de containers hebben `restart: unless-stopped`, dus de app komt vanzelf
terug. `running-coach.service` wacht daarna op `/login` en zet de Funnel aan. Je hoeft niets te doen —
behalve als je de stack met `stop.sh` had gestopt: dan blijft hij uit tot je weer `start.sh` draait.

Staat de laptop op accu en valt hij in slaapstand, dan is de app offline. Op netstroom gebeurt dat niet.
Voor een machine die altijd aan moet staan: `bash deploy/setup.sh --always-on`.

Controle na een herstart:

```bash
bash deploy/status.sh
```

### Logs bekijken

```bash
docker compose -f deploy/docker-compose.yml logs --tail 50 app       # laatste 50 regels app
docker compose -f deploy/docker-compose.yml logs -f app              # live meekijken, Ctrl+C stopt
docker compose -f deploy/docker-compose.yml logs --tail 50 postgres  # database
journalctl -u running-coach -n 50                                    # wat systemd deed
```

Bij een gezonde start staat er `Started ApplicationKt in ... seconds`.

### Code bijwerken

```bash
git pull
bash deploy/start.sh --build
```

Database en `.env` blijven bewaard; alleen `running-app` wordt vervangen. De app is tijdens de build
even niet bereikbaar (enkele minuten).

### Database: back-up en terugzetten

De nachtelijke timer draait om 03:00 en bewaart 14 dagen in `deploy/backups/`.

```bash
bash deploy/migrate.sh backup                                     # handmatige back-up
bash deploy/migrate.sh dump                                       # dump van de huidige database
bash deploy/migrate.sh restore deploy/backups/running-20261107-0300.dump
docker compose -f deploy/docker-compose.yml restart app           # daarna de app herstarten
```

De timer draait als root en chown't de back-ups daarna naar jouw gebruiker, dus je kunt ze gewoon
openen en opruimen. Zet ze af en toe ook op een andere schijf of in de cloud.

### Publieke link aan of uit

De Funnel-link hoort bij `start.sh` en `stop.sh`; los kan ook:

```bash
sudo tailscale funnel status          # moet de https-link tonen
sudo tailscale funnel --bg 8080       # aanzetten
sudo tailscale funnel --https=443 off # uitzetten
```

Funnel werkt alleen als in de Tailscale-console HTTPS-certificaten én het `funnel`-attribuut aan staan
(stap 5). Zonder die twee meldt Tailscale `Funnel is not enabled on your tailnet` en blijft de publieke
link dood.

### De dagelijkse sync

De app synchroniseert zelf: Strava om 06:00 en Garmin om 06:30 (`Europe/Brussels`, via `TZ` in de
compose-file). Handmatig syncen kan altijd met de knoppen op het dashboard.

---

## Verschillen met de Windows-pc

| | Windows | Linux (dit document) |
|---|---|---|
| Installatie | `deploy/setup.ps1` | `deploy/setup.sh` |
| Starten/stoppen/status | `start.ps1` / `stop.ps1` / `status.ps1` | `start.sh` / `stop.sh` / `status.sh` |
| Database | `deploy/migrate.ps1` | `deploy/migrate.sh` |
| Docker | Docker Desktop, start pas na inloggen | `docker.service`, start bij het booten |
| Automatisch starten | automatisch aanmelden + Docker Desktop | `running-coach.service` (echt een service) |
| Nooit in slaap vallen | `powercfg` + klepinstelling | `setup.sh --always-on` |
| Back-up | geplande taak in Windows | `running-coach-backup.timer` |
| Tailscale | `tailscale.exe` in `Program Files` | `tailscale` + `tailscaled` |

Eén ding blijft gelijk: het zijn **twee aparte databases**. Zet dezelfde gegevens alleen op de machine
die de baas is en houd de andere lokaal, of kies bewust één van de twee als altijd-aan server.

---

## Als er iets misgaat

| Symptoom | Oorzaak en oplossing |
|---|---|
| `docker` reageert niet | `sudo systemctl start docker` of `bash deploy/start.sh`; controleer `systemctl status docker`. |
| `permission denied` op de docker-socket | Je zit (nog) niet in de `docker`-groep: `sudo usermod -aG docker $USER` en één keer opnieuw inloggen. |
| `apt-get update` faalt met `NO_PUBKEY` of `not signed` | Een andere externe apt-repo (HashiCorp, Google Chrome, cloud-sdk, ...) heeft een ontbrekende sleutel. `setup.sh` valt voor Tailscale terug op de eigen repo en werkt gewoon door; los de andere repo op met `sudo apt-key ...` of door de sleutel opnieuw te downloaden. `apt install` blijft intussen werken met de bestaande indexen. |
| Publieke link geeft 502 | De app luistert niet op 8080. `bash deploy/status.sh`, daarna `docker compose -f deploy/docker-compose.yml logs --tail 50 app`. |
| Publieke link onbereikbaar, lokaal wel goed | Funnel staat uit of is niet toegestaan. `sudo tailscale funnel status`, dan `bash deploy/start.sh`. |
| Funnel: `not enabled on your tailnet` | Stap 5: HTTPS-certificaten en het `funnel`-attribuut aanzetten. |
| `start.sh` vraagt om een sudo-wachtwoord en blijft daarna hangen | Opgelost in `deploy/common.sh` (`timeout --foreground`, nodig zodat sudo bij de terminal kan). Op een oudere checkout: Ctrl-C, script bijwerken, opnieuw draaien. Tijdens het typen blijft het wachtwoord onzichtbaar. |
| `running-app` herstart steeds | Database niet bereikbaar of verkeerd wachtwoord. `logs postgres` / `logs app`, controleer `DB_PASSWORD` in `deploy/.env`. |
| App stopt met `Could not resolve placeholder 'APP_PASSWORD'` | `APP_PASSWORD` ontbreekt in `deploy/.env`. |
| Je wordt steeds uitgelogd | `APP_REMEMBER_ME_KEY` wijzigt; zet er één vaste waarde in. |
| Database leeg na een restore | Verkeerd bestand of mislukte restore. `status.sh` toont het aantal activiteiten (591 voor deze dataset). |
| Na een herstart is de app weg | `systemctl status running-coach`; units ontbreken dan (opnieuw `bash deploy/install-systemd.sh`) of de stack was met `stop.sh` gestopt. |
| Eerste pagina traag na herstart | Normaal: de app doet bij het opstarten rekenwerk. Daarna is alles snel. |
| Schijf vol | `docker system prune -af` en oude bestanden in `deploy/backups/` verwijderen. |

---

## Wat je nooit moet doen

- `docker compose down -v` — dat verwijdert het volume `running_pgdata` en daarmee de hele database.
- `deploy/.env` committen of doorsturen: daar staan het app-wachtwoord, het databasewachtwoord, de
  Strava-sleutels en de Garmin-sleutel in.
- `deploy/backups/` of een dump in git zetten: dat is een volledige kopie van de gezondheidsdata.
- De map `runner/data/` gebruiken op deze machine: die is voor de lokale H2-ontwikkelmodus (poort 8080
  is dan al bezet door de container).
