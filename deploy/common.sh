#!/usr/bin/env bash
# Gedeelde helpers voor de Linux-scripts in deploy/. Niet los uitvoeren: start.sh,
# stop.sh, status.sh en install-systemd.sh sourcen dit bestand.
#
# De scripts werken met en zonder sudo: zolang je gebruiker (nog) niet in de
# docker-groep zit, gaat docker via sudo. Draaien ze als root (systemd), dan
# gaat alles rechtstreeks.

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(dirname "$DEPLOY_DIR")"
COMPOSE_FILE="$DEPLOY_DIR/docker-compose.yml"
ENV_FILE="$DEPLOY_DIR/.env"

# APP_PORT = host-poort van de app (standaard 8080). Volgorde: omgeving, deploy/.env, 8080.
if [ -z "${APP_PORT:-}" ] && [ -f "$ENV_FILE" ]; then
    APP_PORT="$(sed -n 's/^APP_PORT=\([0-9][0-9]*\).*/\1/p' "$ENV_FILE" | tail -1)"
fi
APP_PORT="${APP_PORT:-8080}"
export APP_PORT
APP_URL="http://127.0.0.1:$APP_PORT/login"

say() { printf '\n==> %s\n' "$*"; }
warn() { printf 'LET OP: %s\n' "$*" >&2; }
fail() {
    printf 'FOUT: %s\n' "$*" >&2
    exit 1
}

if [ "$(id -u)" -eq 0 ]; then
    SUDO=""
else
    SUDO="sudo"
fi

# --- docker compose ---------------------------------------------------------
if docker info >/dev/null 2>&1; then
    dc() { docker compose -f "$COMPOSE_FILE" "$@"; }
else
    dc() { $SUDO docker compose -f "$COMPOSE_FILE" "$@"; }
fi

docker_ok() { docker info >/dev/null 2>&1 || $SUDO docker info >/dev/null 2>&1; }

# --- tailscale --------------------------------------------------------------
have_tailscale() { command -v tailscale >/dev/null 2>&1; }

# ts = tailscale, zonder sudo als dat lukt (status/ip mogen meestal), anders met sudo.
# Commando's die root eisen (funnel, up) vallen vanzelf terug op sudo. Gebruik deze
# variant alleen waar een wachtwoordprompt zichtbaar mag zijn: niet in een $( ).
ts() {
    if tailscale "$@" 2>/dev/null; then
        return 0
    fi
    if [ -z "$SUDO" ]; then
        return 1
    fi
    # Alleen om een wachtwoord vragen als er een terminal is; anders faalt het meteen
    # in plaats van te blijven hangen.
    if [ -t 0 ]; then
        $SUDO tailscale "$@"
    else
        $SUDO -n tailscale "$@"
    fi
}

# ts_ro = alleen lezen, nooit om een wachtwoord vragen (sudo -n). Faalt stil.
ts_ro() {
    if tailscale "$@" 2>/dev/null; then
        return 0
    fi
    if [ -n "$SUDO" ]; then
        $SUDO -n tailscale "$@" 2>/dev/null
    else
        return 1
    fi
}

# timeout met --foreground: zonder die optie zet GNU timeout het commando in een
# eigen procesgroep, en dan kan sudo niet bij de terminal. Gevolg: de vraag om het
# wachtwoord blijft hangen, terwijl je typt en je wachtwoord gewoon in beeld komt
# (sudo krijgt SIGTTIN/SIGTTOU en stopt). Valt terug op gewone timeout (busybox).
if timeout --foreground 0 true >/dev/null 2>&1; then
    TMO=(timeout --foreground)
else
    TMO=(timeout)
fi

# ts_timeout SECONDEN ... : als ts, maar met een tijdslimiet. Nodig omdat
# `tailscale funnel --bg` blijft wachten zolang Funnel niet op de tailnet is
# toegestaan; zonder limiet hangt het script dan voor altijd.
ts_timeout() {
    local limit="$1"
    local quick=10
    shift
    [ "$limit" -lt "$quick" ] && quick="$limit"
    # Eerste poging kort houden: hangt alleen als Funnel nog niet is toegestaan.
    if "${TMO[@]}" "$quick" tailscale "$@" 2>/dev/null; then
        return 0
    fi
    if [ -z "$SUDO" ]; then
        return 1
    fi
    if [ -t 0 ]; then
        "${TMO[@]}" "$limit" $SUDO tailscale "$@"
    else
        "${TMO[@]}" "$limit" $SUDO -n tailscale "$@"
    fi
}

# tailscale_ok: tailscaled antwoordt en het toestel is ingelogd.
tailscale_ok() {
    have_tailscale && ts_ro status >/dev/null 2>&1
}

# funnel_on: er staat een Funnel/serve-configuratie klaar.
funnel_on() {
    local out rc=0
    out="$(ts_ro funnel status 2>&1)" || rc=$?
    [ "$rc" -eq 0 ] || return 1
    printf '%s' "$out" | grep -q 'No serve config' && return 1
    printf '%s' "$out" | grep -qi 'not enabled' && return 1
    printf '%s' "$out" | grep -q 'https://' || return 1
    return 0
}

# public_dns: de MagicDNS-naam van deze machine, zonder punt op het einde.
public_dns() {
    ts_ro status --json 2>/dev/null |
        sed -n 's/.*"DNSName"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' |
        head -1 | sed 's/\.$//'
}

# --- app --------------------------------------------------------------------
app_ok() { curl -fsS -o /dev/null --max-time 10 "$APP_URL"; }

# port_busy PORT: luistert er iets op deze poort (ook andere containers met host-netwerk)?
port_busy() {
    if command -v ss >/dev/null 2>&1; then
        [ -n "$(ss -H -ltn "sport = :$1" 2>/dev/null)" ]
    else
        (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null
    fi
}

# resolve_app_port: staat de poort in gebruik door iets anders dan onze eigen app, kies
# dan de eerstvolgende vrije poort en bewaar die als APP_PORT in deploy/.env, zodat
# compose, status en Funnel dezelfde poort gebruiken.
resolve_app_port() {
    if dc ps --format '{{.Name}}|{{.Status}}' 2>/dev/null | grep -qi '^running-app|.*up'; then
        return 0
    fi
    port_busy "$APP_PORT" || return 0
    local old="$APP_PORT" p
    for p in $(seq $((old + 1)) $((old + 50))); do
        if ! port_busy "$p"; then
            warn "Poort $old is al in gebruik door een ander programma; de app gebruikt voortaan poort $p."
            if [ -f "$ENV_FILE" ]; then
                if grep -q '^APP_PORT=' "$ENV_FILE"; then
                    sed -i "s|^APP_PORT=.*|APP_PORT=$p|" "$ENV_FILE"
                else
                    printf 'APP_PORT=%s\n' "$p" >>"$ENV_FILE"
                fi
            fi
            APP_PORT="$p"
            export APP_PORT
            APP_URL="http://127.0.0.1:$APP_PORT/login"
            return 0
        fi
    done
    fail "Geen vrije poort gevonden tussen $old en $((old + 50))."
}
