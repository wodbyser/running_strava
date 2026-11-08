#!/usr/bin/env bash
# Start de Running Coach op deze Linux-pc: Docker-stack + publieke Funnel-link.
#   bash deploy/start.sh              starten (of weer online brengen na stop)
#   bash deploy/start.sh --build      na een codewijziging: image opnieuw bouwen
#   bash deploy/start.sh --no-funnel  alleen lokaal/tailnet, publieke link blijft uit
#
# Veilig om vaker te draaien: wat al loopt blijft staan. Stoppen met deploy/stop.sh,
# de toestand bekijken met deploy/status.sh.
set -euo pipefail
source "$(dirname "$0")/common.sh"

build=0
funnel=1
for arg in "$@"; do
    case "$arg" in
    -b | --build) build=1 ;;
    --no-funnel) funnel=0 ;;
    -h | --help)
        sed -n '2,7p' "$0"
        exit 0
        ;;
    *) fail "Onbekende optie: $arg (gebruik --build of --no-funnel)" ;;
    esac
done

# --- 1. Docker moet draaien -------------------------------------------------
if ! docker_ok; then
    if command -v systemctl >/dev/null 2>&1; then
        say "Docker antwoordt niet; docker.service starten"
        $SUDO systemctl start docker || true
        for _ in $(seq 1 24); do
            if docker_ok; then break; fi
            sleep 5
        done
    fi
fi
docker_ok || fail "Docker reageert niet. Controleer: systemctl status docker,
  en of je gebruiker in de docker-groep zit (na toevoegen een keer uit- en inloggen)."

# --- 2. Stack starten -------------------------------------------------------
if [ "$build" = 1 ]; then
    say "Image bouwen en stack starten (de eerste keer duurt dit enkele minuten)"
    dc up -d --build
else
    say "Stack starten"
    dc up -d
fi

# --- 3. Wachten tot de app antwoordt ---------------------------------------
say "Wachten tot de app antwoordt op $APP_URL"
up=0
for _ in $(seq 1 60); do
    if app_ok; then
        up=1
        break
    fi
    sleep 5
done
if [ "$up" = 0 ]; then
    warn "De app antwoordt niet. Laatste regels uit het log:"
    dc logs --tail 30 app || true
    fail "Starten mislukt. Zie deploy/LINUX.md."
fi

# --- 4. Publieke https-link (Tailscale Funnel) ------------------------------
if [ "$funnel" = 1 ]; then
    if ! have_tailscale; then
        warn "Tailscale is niet geïnstalleerd; de publieke link blijft uit."
    elif ! tailscale_ok; then
        warn "Tailscale is niet verbonden; de publieke link blijft uit. Doe: sudo tailscale up"
    elif funnel_on; then
        say "Publieke link stond al aan"
    else
        say "Publieke link aanzetten (tailscale funnel --bg 8080)"
        # Staat Funnel nog niet aan op de tailnet, dan blijft `tailscale funnel` wachten
        # tot je dat in de console doet. Daarom een limiet: met een terminal krijg je
        # drie minuten de tijd, zonder terminal stoppen we snel (bijv. bij het opstarten).
        if [ -t 0 ]; then
            printf '    Staat Funnel nog niet aan? Open de link die Tailscale hieronder toont.\n'
            if [ -n "$SUDO" ] && ! $SUDO -n true 2>/dev/null; then
                printf '    Tailscale heeft root nodig: zo meteen je sudo-wachtwoord (blijft onzichtbaar tijdens het typen).\n'
            fi
            limit=180
        else
            limit=15
        fi
        if ts_timeout "$limit" funnel --bg 8080; then
            say "Publieke link staat aan"
        else
            warn "Funnel is niet toegestaan op deze tailnet. Open de link die Tailscale"
            warn "hierboven toont (login.tailscale.com/f/funnel?node=...) en zet Funnel aan,"
            warn "of zet in de console HTTPS-certificaten + het funnel-attribuut aan."
            warn "Draai daarna opnieuw: bash deploy/start.sh"
        fi
    fi
fi

# --- Samenvatting -----------------------------------------------------------
dc ps

url=""
if tailscale_ok; then
    dns="$(public_dns)"
    if [ -n "$dns" ]; then url="https://$dns"; fi
fi

say "De app draait.
  Lokaal:    http://127.0.0.1:8080
  Publiek:   ${url:-onbekend - Tailscale niet verbonden}${url:+  (login nodig)}
  Login:     gebruiker uit APP_USERNAME, wachtwoord uit deploy/.env
  Stoppen:   bash deploy/stop.sh
  Status:    bash deploy/status.sh"
