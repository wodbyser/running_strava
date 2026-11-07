#!/usr/bin/env bash
# Stop de Running Coach op deze Linux-pc (alles offline, database blijft bewaard).
#   bash deploy/stop.sh               publieke link uit + containers stoppen
#   bash deploy/stop.sh --down        idem, maar verwijdert de containers ook
#   bash deploy/stop.sh --keep-funnel publieke link laten staan (geeft dan 502)
set -euo pipefail
source "$(dirname "$0")/common.sh"

down=0
funnel=1
for arg in "$@"; do
    case "$arg" in
    --down) down=1 ;;
    --keep-funnel) funnel=0 ;;
    -h | --help)
        sed -n '2,5p' "$0"
        exit 0
        ;;
    *) fail "Onbekende optie: $arg (gebruik --down of --keep-funnel)" ;;
    esac
done

if [ "$funnel" = 1 ]; then
    if tailscale_ok && funnel_on; then
        say "Publieke link uitzetten"
        ts_timeout 30 funnel --https=443 off ||
            warn "Funnel uitzetten lukte niet; controleer: sudo tailscale funnel status"
    else
        say "Publieke link stond al uit"
    fi
fi

if [ "$down" = 1 ]; then
    say "Containers verwijderen (het volume running_pgdata blijft staan)"
    dc down
else
    say "Containers stoppen (database blijft bewaard)"
    dc stop
fi

say "Klaar. Weer starten met: bash deploy/start.sh"
