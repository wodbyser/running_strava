#!/usr/bin/env bash
# One-time setup of an always-on machine (Ubuntu/Debian) for the Running Coach app.
# Safe to run twice. Run as your normal user (it asks for sudo when needed):
#   bash deploy/setup.sh
set -euo pipefail

cd "$(dirname "$0")"

say() { printf '\n==> %s\n' "$*"; }
warn() { printf 'LET OP: %s\n' "$*" >&2; }

# --- Docker -----------------------------------------------------------------
if ! command -v docker >/dev/null 2>&1; then
    say "Docker installeren"
    curl -fsSL https://get.docker.com | sudo sh
    sudo usermod -aG docker "$USER"
    warn "Je bent net toegevoegd aan de docker-groep; dit script gebruikt voorlopig sudo."
fi
if ! docker info >/dev/null 2>&1; then
    dc() { sudo docker compose "$@"; }
else
    dc() { docker compose "$@"; }
fi

# --- Tailscale --------------------------------------------------------------
if ! command -v tailscale >/dev/null 2>&1; then
    say "Tailscale installeren"
    curl -fsSL https://tailscale.com/install.sh | sh
fi
sudo systemctl enable --now tailscaled
if ! sudo tailscale status >/dev/null 2>&1; then
    say "Tailscale koppelen: open de link die nu verschijnt en log in"
    sudo tailscale up
fi

# --- Never sleep ------------------------------------------------------------
say "Slaapstand uitzetten (de app moet altijd bereikbaar zijn)"
sudo systemctl mask sleep.target suspend.target hibernate.target hybrid-sleep.target >/dev/null 2>&1 || true
sudo mkdir -p /etc/systemd/logind.conf.d
printf '[Login]\nHandleLidSwitch=ignore\nHandleLidSwitchExternalPower=ignore\n' |
    sudo tee /etc/systemd/logind.conf.d/99-running-coach.conf >/dev/null
sudo systemctl restart systemd-logind || true

# --- Configuration ----------------------------------------------------------
if [ ! -f .env ]; then
    say "deploy/.env aanmaken uit het voorbeeld"
    cp .env.example .env
    chmod 600 .env
    sed -i "s|^DB_PASSWORD=$|DB_PASSWORD=$(openssl rand -hex 24)|" .env
    sed -i "s|^APP_REMEMBER_ME_KEY=$|APP_REMEMBER_ME_KEY=$(openssl rand -hex 32)|" .env
    warn "Vul in deploy/.env nog in: APP_PASSWORD, STRAVA_CLIENT_ID, STRAVA_CLIENT_SECRET,"
    warn "STRAVA_REDIRECT_URI en eventueel GARMIN_PERSONAL_API_KEY. Daarna opnieuw: bash deploy/setup.sh"
    exit 0
fi
chmod 600 .env
for key in APP_PASSWORD STRAVA_CLIENT_ID STRAVA_CLIENT_SECRET STRAVA_REDIRECT_URI; do
    if ! grep -qE "^${key}=.+" .env; then
        warn "$key is nog leeg in deploy/.env; de app start niet zonder."
        exit 1
    fi
done

# --- Start ------------------------------------------------------------------
say "App bouwen en starten (eerste keer duurt dit enkele minuten)"
dc up -d --build
dc ps

say "Wachten tot de app antwoordt op http://127.0.0.1:8080/login"
for i in $(seq 1 60); do
    if curl -fsS -o /dev/null http://127.0.0.1:8080/login; then
        say "De app draait."
        break
    fi
    sleep 5
    if [ "$i" = 60 ]; then
        warn "De app antwoordt niet. Bekijk de logs met: docker compose -f deploy/docker-compose.yml logs -f app"
        exit 1
    fi
done

say "Nu de publieke link aanzetten (eenmalig, pas daarna verder):"
echo "    sudo tailscale funnel --bg 8080"
echo "Daarna toont 'sudo tailscale funnel status' je https-adres."
