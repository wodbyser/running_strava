#!/usr/bin/env bash
# Eenmalige installatie van deze Linux-pc (Ubuntu/Debian) als altijd-aan server
# voor de Running Coach: Docker, Tailscale, .env, stack en systemd-units.
# Veilig om twee keer te draaien. Draai als je eigen gebruiker (vraagt sudo waar nodig):
#
#   bash deploy/setup.sh
#
# Opties:
#   --always-on    slaapstand uitzetten en de klep negeren. Alleen voor een echte
#                  server; op je dagelijkse laptop laat je dit weg.
#   --no-funnel    de publieke Tailscale-link niet aanzetten (alleen lokaal/tailnet).
#   --no-systemd   geen systemd-units installeren; dan start niets vanzelf na een herstart.
set -euo pipefail
cd "$(dirname "$0")"

always_on=0
funnel=1
systemd=1
for arg in "$@"; do
    case "$arg" in
    --always-on) always_on=1 ;;
    --no-funnel) funnel=0 ;;
    --no-systemd) systemd=0 ;;
    -h | --help)
        sed -n '2,15p' "$0"
        exit 0
        ;;
    *) printf 'Onbekende optie: %s (zie --help)\n' "$arg" >&2; exit 1 ;;
    esac
done

source ./common.sh

# --- Docker -----------------------------------------------------------------
if ! command -v docker >/dev/null 2>&1; then
    say "Docker installeren"
    curl -fsSL https://get.docker.com | $SUDO sh
    $SUDO usermod -aG docker "$USER"
    warn "Je bent net toegevoegd aan de docker-groep; dit script gebruikt voorlopig sudo."
fi
$SUDO systemctl enable --now docker >/dev/null 2>&1 || true
docker_ok || fail "Docker antwoordt niet. Controleer: systemctl status docker"

# --- Tailscale --------------------------------------------------------------
# De standaardinstallatie doet `apt-get update` over alle repo's. Staat er op deze
# machine een externe repo met een ontbrekende GPG-sleutel, dan stopt hij daarop.
# Daarom een fallback die alleen de Tailscale-repo zelf bijwerkt.
install_tailscale() {
    if curl -fsSL https://tailscale.com/install.sh | $SUDO sh; then
        return 0
    fi
    warn "De standaardinstallatie van Tailscale mislukte. Meestal komt dat door een"
    warn "andere apt-repo op deze machine met een ontbrekende GPG-sleutel."
    say "Tailscale rechtstreeks uit de eigen repo installeren"
    codename="$(. /etc/os-release && printf '%s' "$VERSION_CODENAME")"
    curl -fsSL "https://pkgs.tailscale.com/stable/ubuntu/$codename.noarmor.gpg" |
        $SUDO tee /usr/share/keyrings/tailscale-archive-keyring.gpg >/dev/null
    printf 'deb [signed-by=/usr/share/keyrings/tailscale-archive-keyring.gpg] https://pkgs.tailscale.com/stable/ubuntu %s main\n' "$codename" |
        $SUDO tee /etc/apt/sources.list.d/tailscale.list >/dev/null
    $SUDO apt-get update -o Dir::Etc::sourcelist=sources.list.d/tailscale.list \
        -o Dir::Etc::sourceparts=- -o APT::Get::List-Cleanup=0
    $SUDO apt-get install -y tailscale
}

if ! have_tailscale; then
    say "Tailscale installeren"
    install_tailscale
    have_tailscale || fail "Tailscale installeren lukte niet. Zie deploy/LINUX.md."
fi
$SUDO systemctl enable --now tailscaled
if ! tailscale_ok; then
    say "Tailscale koppelen: open de link die nu verschijnt en log in"
    $SUDO tailscale up
fi

# --- Slaapstand (alleen met --always-on) ------------------------------------
if [ "$always_on" = 1 ]; then
    say "Slaapstand uitzetten (de app moet altijd bereikbaar zijn)"
    $SUDO systemctl mask sleep.target suspend.target hibernate.target hybrid-sleep.target >/dev/null 2>&1 || true
    $SUDO mkdir -p /etc/systemd/logind.conf.d
    printf '[Login]\nHandleLidSwitch=ignore\nHandleLidSwitchExternalPower=ignore\n' |
        $SUDO tee /etc/systemd/logind.conf.d/99-running-coach.conf >/dev/null
    $SUDO systemctl restart systemd-logind || true
else
    say "Slaapstand blijft aan (dit is een gewone laptop)"
    printf '    Voor een altijd-aan machine: bash deploy/setup.sh --always-on\n'
    printf '    Terugdraaien kan met: sudo systemctl unmask sleep.target suspend.target hibernate.target hybrid-sleep.target\n'
fi

# --- Configuratie -----------------------------------------------------------
if [ ! -f .env ]; then
    say "deploy/.env aanmaken uit het voorbeeld"
    cp .env.example .env
    chmod 600 .env
    sed -i "s|^DB_PASSWORD=$|DB_PASSWORD=$(openssl rand -hex 24)|" .env
    sed -i "s|^APP_REMEMBER_ME_KEY=$|APP_REMEMBER_ME_KEY=$(openssl rand -hex 32)|" .env
    if tailscale_ok; then
        dns="$(public_dns)"
        if [ -n "$dns" ]; then
            sed -i "s|^STRAVA_REDIRECT_URI=.*|STRAVA_REDIRECT_URI=https://$dns/callback|" .env
        fi
    fi
    warn "Vul in deploy/.env nog in: APP_PASSWORD, STRAVA_CLIENT_ID, STRAVA_CLIENT_SECRET"
    warn "en eventueel GARMIN_PERSONAL_API_KEY. Daarna opnieuw: bash deploy/setup.sh"
    exit 0
fi
chmod 600 .env
for key in APP_PASSWORD STRAVA_CLIENT_ID STRAVA_CLIENT_SECRET STRAVA_REDIRECT_URI; do
    if ! grep -qE "^${key}=.+" .env; then
        warn "$key is nog leeg in deploy/.env; de app start niet zonder."
        exit 1
    fi
done
if grep -qE '^STRAVA_REDIRECT_URI=https://YOUR-MACHINE' .env && tailscale_ok; then
    dns="$(public_dns)"
    if [ -n "$dns" ]; then
        say "STRAVA_REDIRECT_URI invullen op basis van de Tailscale-naam"
        sed -i "s|^STRAVA_REDIRECT_URI=.*|STRAVA_REDIRECT_URI=https://$dns/callback|" .env
    fi
fi

# --- Start ------------------------------------------------------------------
if [ "$funnel" = 1 ]; then
    bash ./start.sh --build
else
    bash ./start.sh --build --no-funnel
fi

# --- Automatisch starten na een herstart ------------------------------------
if [ "$systemd" = 1 ]; then
    bash ./install-systemd.sh
else
    warn "Geen systemd-units geïnstalleerd: na een herstart start niets vanzelf."
fi

# --- Afronden ---------------------------------------------------------------
say "Installatie klaar."
if [ "$funnel" = 1 ] && tailscale_ok; then
    dns="$(public_dns)"
    if [ -n "$dns" ]; then
        printf '    Lokaal:  http://127.0.0.1:8080\n'
        printf '    Publiek: https://%s  (login nodig)\n' "$dns"
        printf '\n    Werkt de publieke link niet, zet dan in de Tailscale-console HTTPS-certificaten\n'
        printf '    en het funnel-attribuut aan (zie deploy/LINUX.md). Zet ook het\n'
        printf '    Authorization Callback Domain bij Strava op %s\n' "$dns"
    fi
fi
