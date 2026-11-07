#!/usr/bin/env bash
# Installeert de systemd-units van de Running Coach op deze machine:
#   running-coach.service         start de stack + publieke link bij het opstarten
#   running-coach-backup.timer    nachtelijke back-up om 03:00 (bewaart 14 dagen)
#
#   bash deploy/install-systemd.sh
#
# Eenmalig; opnieuw draaien werkt ook (units worden overschreven). setup.sh roept
# dit automatisch aan, tenzij je --no-systemd meegeeft.
set -euo pipefail
source "$(dirname "$0")/common.sh"

UNIT_DIR=/etc/systemd/system
TEMPLATE_DIR="$DEPLOY_DIR/systemd"

command -v systemctl >/dev/null 2>&1 ||
    fail "systemd niet gevonden; installeer de units dan niet en gebruik deploy/start.sh."
[ -d "$TEMPLATE_DIR" ] || fail "Map $TEMPLATE_DIR ontbreekt."

owner="$(stat -c %U "$REPO_DIR")"
group="$(stat -c %G "$REPO_DIR")"

say "Units installeren in $UNIT_DIR (repo: $REPO_DIR)"
for template in "$TEMPLATE_DIR"/*.in; do
    name="$(basename "${template%.in}")"
    sed -e "s|@REPO_DIR@|$REPO_DIR|g" \
        -e "s|@OWNER@|$owner|g" \
        -e "s|@GROUP@|$group|g" \
        "$template" | $SUDO tee "$UNIT_DIR/$name" >/dev/null
    $SUDO chmod 644 "$UNIT_DIR/$name"
    printf '    %s\n' "$UNIT_DIR/$name"
done

$SUDO systemctl daemon-reload

# De app-unit niet meteen starten: die draait al (setup.sh heeft net gestart).
$SUDO systemctl enable running-coach.service >/dev/null
$SUDO systemctl enable --now running-coach-backup.timer >/dev/null

say "Klaar. Vanaf de volgende herstart komt de app vanzelf terug:
  systemctl status running-coach          # toestand van de stack + publieke link
  systemctl stop running-coach            # zelfde als deploy/stop.sh
  systemctl start running-coach           # zelfde als deploy/start.sh
  systemctl list-timers running-coach*    # wanneer de volgende back-up loopt"
