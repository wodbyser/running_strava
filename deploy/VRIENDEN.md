# Vrienden laten meedoen (Linux)

Elke vriend krijgt een eigen account met een **eigen database**. Jij blijft de beheerder en ziet van hen alleen
aantallen en laatste sync, nooit hun trainingen. Standaard staat dit **uit**; je app werkt dan precies als voorheen.

## 1. Eenmalig voorbereiden

- [ ] Strava-app: *Authorization Callback Domain* = jouw `…ts.net`-domein (zie `LINUX.md` §6). Max. 10 atleten, jij meegeteld.
- [ ] Nachtelijke back-up van alle databases: `bash deploy/install-systemd.sh` (opnieuw draaien, veilig).
- [ ] Test eerst zelf (zie stap 3), pas daarna vrienden uitnodigen.

## 2. Aanzetten

```bash
bash deploy/backup.sh                    # 1. back-up, check dat deploy/backups/all-<datum>/ gevuld is
openssl rand -base64 32                  # 2. maak een sleutel
```

Zet in `deploy/.env`:

```
APP_MULTI_USER_ENABLED=true
APP_TENANT_SECRET_KEY=<de sleutel>
APP_SIGNUP_CODE=<minstens 16 willekeurige tekens>
```

```bash
docker compose -f deploy/docker-compose.yml up -d    # 3. herstart
bash deploy/status.sh                                # 4. toont "Gebruikers: 1 actief van 1"
```

Bewaar `APP_TENANT_SECRET_KEY` ook in je wachtwoordmanager. Zonder die sleutel zijn Garmin-sleutels van vrienden
in een back-up onleesbaar. Wijzig hem nooit meer.

Terug naar het oude gedrag: zet `APP_MULTI_USER_ENABLED=false` en herstart. Er wordt niets gewist.
**Nooit** `docker compose down -v`.

## 3. Test zelf, vóór je iemand uitnodigt

1. Open `https://<jouw-link>/admin/users`, klik **Nieuwe code maken** en kopieer die (je ziet hem maar één keer).
2. Maak in een privévenster of op je telefoon (mobiel internet) een testaccount via `/register`.
3. Koppel Strava of voer een Garmin-sleutel in en kijk of de gegevens binnenkomen (`/backfill` toont de voortgang).
4. Kijk op `/admin/users` of het account verschijnt. Probeer ook **Uitschakelen** en **Weer inschakelen**.
5. Ga na of je eigen pagina's (`/`, `/activities`, `/load`, `/race`) ongewijzigd zijn.
6. Controleer het IP in de log: `select action, username, ip from auth_audit order by id desc limit 5;` in de
   database `running_control`. Het IP moet het publieke adres van je telefoon zijn, niet `127.0.0.1` of `172.x`.

## 4. Een vriend uitnodigen

1. `/admin/users` → **Nieuwe code maken** (geldt 7 dagen, één keer). Stuur die privé door.
2. De vriend gaat naar `https://<jouw-link>/register`, kiest een gebruikersnaam (kleine letters, cijfers, `_`)
   en een wachtwoord naar keuze.
3. De vriend kiest **Strava** (knop "Koppel Strava") of **Garmin** (eigen API-sleutel). De keuze staat daarna vast;
   wisselen kan alleen via "opnieuw beginnen" in `/settings` (de oude gegevens worden gearchiveerd, niet gewist).
4. Strava-gegevens worden rustig in de achtergrond opgehaald. Dat kan even duren.

Wat een Garmin-only vriend mist (geen Strava-data) staat op de pagina's zelf uitgelegd.

## 5. Beheer (`/admin/users`)

| Wil je… | Doe |
|---|---|
| iemand tijdelijk blokkeren | **Uitschakelen** (data blijft, direct uitgelogd) |
| weer toelaten | **Weer inschakelen** |
| iemand definitief weghalen | **Verwijderen** (Strava-toegang wordt ingetrokken, database gearchiveerd) |
| een code intrekken | **Intrekken** bij de code |

Na **Verwijderen** staat de database nog als `running_u_<naam>_old_<datum>`. Maak eerst een back-up
(`bash deploy/backup.sh`) en verwijder dat archief zelf binnen 30 dagen (Strava-regel).

## 6. Goed om te weten

- Back-up van alles: `bash deploy/backup.sh` (draait ook nachtelijks na `install-systemd.sh`).
- Updaten: `git pull && bash deploy/start.sh --build`. Eerst een back-up.
- Garmin-sleutel verlopen? De vriend ziet een melding en past hem aan in `/settings`; zijn data blijft staan.
- Deel `APP_SIGNUP_CODE` nooit; gebruik de codes voor één gebruik.
- Meer details: `deploy/LINUX.md` (§ Meerdere gebruikers), `docs/multi-user-design.md`.
