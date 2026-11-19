#!/usr/bin/env bash
# Back-up van ALLE databases van de Running Coach (Linux/macOS; op Windows: deploy/backup.ps1).
# Dumpt `running` en elke `running_*` database (niet de scratch-databases `*_restore_test`) naar
# deploy/backups/all-<datum>/, met een manifest met het aantal rijen per tabel. Alleen lezen op de databases.
# `migrate.sh backup` blijft ongewijzigd werken.
#
#   bash deploy/backup.sh
set -euo pipefail

cd "$(dirname "$0")"
COMPOSE_FILE="docker-compose.yml"
DB=postgres
USER=postgres

say() { printf '\n==> %s\n' "$*"; }
dc() { docker compose -f "$COMPOSE_FILE" "$@"; }
psql_q() { dc exec -T "$DB" psql -U "$USER" -d "$1" -At -c "$2"; }

for _ in $(seq 1 30); do
    if dc exec -T "$DB" pg_isready -U "$USER" -d running >/dev/null 2>&1; then ready=1; break; fi
    sleep 2
done
[ "${ready:-0}" = 1 ] || { echo "De database-container draait niet." >&2; exit 1; }

dir="backups/all-$(date +%Y%m%d-%H%M)"
mkdir -p "$dir"
manifest="$dir/manifest.txt"
printf 'Back-up %s\n' "$(date -Iseconds)" >"$manifest"

mapfile -t dbs < <(psql_q postgres "SELECT datname FROM pg_database WHERE (datname = 'running' OR datname LIKE 'running\_%') AND datname NOT LIKE '%\_restore\_test' ORDER BY datname")
[ "${#dbs[@]}" -gt 0 ] || { echo "Geen running-database gevonden." >&2; exit 1; }

for name in "${dbs[@]}"; do
    say "Dump maken van database '$name'"
    tmp="/tmp/$name.dump"
    dc exec -T "$DB" pg_dump -U "$USER" -d "$name" --no-owner --no-privileges -Fc -f "$tmp"
    dc cp "$DB:$tmp" "$dir/$name.dump"
    dc exec -T "$DB" rm -f "$tmp"
    [ -s "$dir/$name.dump" ] || { echo "Dump van $name is leeg." >&2; exit 1; }
    {
        printf '\n[%s] %s bytes\n' "$name" "$(stat -c %s "$dir/$name.dump")"
        psql_q "$name" "SELECT table_name || ' ' || (xpath('/row/c/text()', query_to_xml(format('SELECT count(*) AS c FROM %I.%I', table_schema, table_name), false, true, '')))[1]::text FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE' ORDER BY 1"
    } >>"$manifest"
done

find backups -maxdepth 1 -type d -name 'all-*' -mtime +14 -exec rm -rf {} +
ls -lh "$dir"
