#!/usr/bin/env bash
# Database verplaatsen of back-uppen (Linux/macOS; op Windows: deploy/migrate.ps1).
# Werkt uitsluitend via `docker compose exec` en `docker compose cp`, dus geen aannames over het host-OS
# en geen binaire redirects die de dump kunnen beschadigen.
#
#   bash deploy/migrate.sh dump
#   bash deploy/migrate.sh restore [bestand]
#   bash deploy/migrate.sh backup
set -euo pipefail

cd "$(dirname "$0")"
COMPOSE_FILE="docker-compose.yml"
DB=postgres
NAME=running
USER=postgres
TMP=/tmp/running.dump

say() { printf '\n==> %s\n' "$*"; }
dc() { docker compose -f "$COMPOSE_FILE" "$@"; }

db_up() {
    dc up -d "$DB"
    for _ in $(seq 1 30); do
        if dc exec -T "$DB" pg_isready -U "$USER" -d "$NAME" >/dev/null 2>&1; then return 0; fi
        sleep 2
    done
    echo "De database-container draait niet." >&2
    exit 1
}

dump_to() {
    db_up
    say "Dump maken van database '$NAME'"
    dc exec -T "$DB" pg_dump -U "$USER" -d "$NAME" --no-owner --no-privileges -Fc -f "$TMP"
    dc cp "$DB:$TMP" "$1"
    dc exec -T "$DB" rm -f "$TMP"
    ls -lh "$1"
}

case "${1:-}" in
dump)
    dump_to running.dump
    say "Klaar. Zet dit bestand op de doelmachine en herstel het daar."
    ;;
restore)
    file="${2:-running.dump}"
    [ -f "$file" ] || { echo "Bestand $file niet gevonden" >&2; exit 1; }
    db_up
    say "Database '$NAME' overschrijven met $file"
    dc cp "$file" "$DB:$TMP"
    dc exec -T "$DB" pg_restore -U "$USER" -d "$NAME" --clean --if-exists --no-owner --no-privileges "$TMP"
    dc exec -T "$DB" rm -f "$TMP"
    say "Hersteld. Herstart de app: docker compose -f deploy/docker-compose.yml restart app"
    ;;
backup)
    mkdir -p backups
    dump_to "backups/running-$(date +%Y%m%d-%H%M).dump"
    find backups -name 'running-*.dump' -mtime +14 -delete
    ;;
*)
    sed -n '2,9p' "$0"
    exit 1
    ;;
esac
