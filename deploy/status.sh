#!/usr/bin/env bash
# Toon of de Running Coach op deze Linux-pc goed draait.
# Exitcode 0 = alles in orde, 1 = er is iets mis (handig voor een agent).
#
# Controleert: Docker, beide containers, de database met het aantal activiteiten,
# de lokale loginpagina, Tailscale, de Funnel en de publieke loginpagina.
set -uo pipefail
source "$(dirname "$0")/common.sh"

problems=0
url=""
ok() { printf '  [ok]    %s\n' "$*"; }
bad() {
    printf '  [fout]  %s\n' "$*"
    problems=$((problems + 1))
}
info() { printf '  [info]  %s\n' "$*"; }

printf '\nRunning Coach - status %s\n' "$(date '+%Y-%m-%d %H:%M')"

# --- Docker -----------------------------------------------------------------
if docker_ok; then
    ok "Docker antwoordt"
else
    bad "Docker reageert niet. Start docker.service of draai deploy/start.sh."
    printf '\nKlaar met fouten.\n\n'
    exit 1
fi

# --- Containers -------------------------------------------------------------
states="$(dc ps --format '{{.Name}}|{{.Status}}' 2>/dev/null || true)"
for name in running-db running-app; do
    line="$(printf '%s\n' "$states" | grep "^$name|" || true)"
    if [ -z "$line" ]; then
        bad "$name draait niet"
    elif printf '%s' "$line" | grep -qi 'up'; then
        ok "$name : ${line#*|}"
    else
        bad "$name : ${line#*|}"
    fi
done

# --- Database ---------------------------------------------------------------
if printf '%s\n' "$states" | grep -q '^running-db|.*[Uu]p'; then
    count="$(dc exec -T postgres psql -U postgres -d running -tAc 'select count(*) from activities;' 2>/dev/null | tr -d '[:space:]')"
    if printf '%s' "$count" | grep -qE '^[0-9]+$'; then
        if [ "$count" -gt 0 ]; then
            ok "Database bereikbaar, $count activiteiten"
        else
            bad "Database bereikbaar maar leeg (0 activiteiten)"
        fi
    else
        bad "Database niet bereikbaar (psql gaf geen getal)"
    fi
fi

# --- Lokale app -------------------------------------------------------------
code="$(curl -o /dev/null -sS -w '%{http_code}' --max-time 15 "$APP_URL" || true)"
if [ "$code" = 200 ]; then
    ok "Lokaal $APP_URL : 200"
else
    bad "Lokaal $APP_URL : ${code:-geen antwoord}"
fi

# --- Tailscale + publieke link ----------------------------------------------
if ! have_tailscale; then
    info "Tailscale niet geïnstalleerd; alleen lokaal gebruik."
else
    url=""
    if tailscale_ok; then
        dns="$(public_dns)"
        ok "Tailscale: ${dns:-onbekend} (Running)"
        if [ -n "$dns" ]; then url="https://$dns"; fi
    else
        bad "Tailscale antwoordt niet of is niet ingelogd"
    fi

    if funnel_on; then
        ok "Funnel staat aan"
    else
        bad "Funnel staat uit (publieke link werkt niet). Aanzetten: bash deploy/start.sh"
    fi

    if [ -n "$url" ] && funnel_on; then
        pcode="$(curl -o /dev/null -sS -w '%{http_code}' --max-time 30 "$url/login" || true)"
        if [ "$pcode" = 200 ]; then
            ok "Publiek $url/login : 200"
        else
            bad "Publiek $url/login : ${pcode:-geen antwoord}"
        fi
    fi
fi

# --- Samenvatting -----------------------------------------------------------
printf '\n'
if [ "$problems" -eq 0 ]; then
    printf 'Alles in orde. Lokaal: http://127.0.0.1:8080%s\n\n' "${url:+  Publiek: $url}"
    exit 0
fi
printf '%s probleem(en):\n' "$problems"
printf 'Oplossingen staan in deploy/LINUX.md.\n\n'
exit 1
