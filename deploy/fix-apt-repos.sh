#!/usr/bin/env bash
# Repareert drie externe apt-repo's op deze machine waarvan de GPG-sleutel kwijt is
# (HashiCorp, Google Chrome, Google Cloud SDK). Daardoor faalt `sudo apt-get update`
# met NO_PUBKEY / "not signed", en dus ook elke installatie die daarop verder bouwt.
#
#   sudo bash deploy/fix-apt-repos.sh
#
# De bestaande bronbestanden worden eerst als *.bak bewaard. Niet app-specifiek:
# dit is machineonderhoud.
set -euo pipefail

[ "$(id -u)" -eq 0 ] || {
    printf 'Draai dit met sudo: sudo bash %s\n' "$0" >&2
    exit 1
}

say() { printf '\n==> %s\n' "$*"; }
changed=0

# --- HashiCorp --------------------------------------------------------------
if [ -f /etc/apt/sources.list.d/hashicorp.list ] || [ -f /etc/apt/sources.list.d/hashicorp.sources ]; then
    say "HashiCorp-sleutel opnieuw ophalen"
    curl -fsSL https://apt.releases.hashicorp.com/gpg |
        gpg --dearmor --yes -o /usr/share/keyrings/hashicorp-archive-keyring.gpg
    chmod 0644 /usr/share/keyrings/hashicorp-archive-keyring.gpg
    changed=1
fi

# --- Google Chrome ----------------------------------------------------------
# Er staan soms twee definities (een klassieke .list en een deb822 .sources).
# De .list verwijst vaak niet naar de sleutel; daar komt de NO_PUBKEY vandaan.
chrome_files=()
for candidate in /etc/apt/sources.list.d/google-chrome.list /etc/apt/sources.list.d/google-chrome.sources; do
    [ -f "$candidate" ] && chrome_files+=("$candidate")
done
if [ "${#chrome_files[@]}" -gt 0 ]; then
    say "Chrome-repo's controleren"
    if [ ! -f /usr/share/keyrings/google-chrome.gpg ]; then
        curl -fsSL https://dl.google.com/linux/linux_signing_key.pub |
            gpg --dearmor --yes -o /usr/share/keyrings/google-chrome.gpg
        chmod 0644 /usr/share/keyrings/google-chrome.gpg
    fi
    for chrome_file in "${chrome_files[@]}"; do
        [ "${chrome_file##*.}" = "list" ] || continue
        # Een eerdere, kapotte poging herstellen: signed-by hoort binnen de blokhaken.
        if grep -qE '^deb \[[^]]*\] signed-by=' "$chrome_file" && [ -f "$chrome_file.bak" ]; then
            cp -a "$chrome_file.bak" "$chrome_file"
            printf '    kapotte regel hersteld uit %s.bak\n' "$chrome_file"
        fi
        if ! grep -q 'signed-by=' "$chrome_file"; then
            cp -a "$chrome_file" "$chrome_file.bak"
            sed -E -i 's|^deb \[([^]]*)\] (https://dl\.google\.com.*)$|deb [\1 signed-by=/usr/share/keyrings/google-chrome.gpg] \2|' \
                "$chrome_file"
            printf '    signed-by toegevoegd aan %s (bewaard als %s.bak)\n' "$chrome_file" "$chrome_file"
        fi
    done
    changed=1
fi

# --- Google Cloud SDK -------------------------------------------------------
cloud_sources=""
for candidate in /etc/apt/sources.list.d/google-cloud-sdk.list /etc/apt/sources.list.d/google-cloud-sdk.sources; do
    [ -f "$candidate" ] && cloud_sources="$candidate"
done
if [ -n "$cloud_sources" ]; then
    say "Cloud SDK-sleutel opnieuw ophalen"
    curl -fsSL https://packages.cloud.google.com/apt/doc/apt-key.gpg |
        gpg --dearmor --yes -o /usr/share/keyrings/cloud.google.gpg
    chmod 0644 /usr/share/keyrings/cloud.google.gpg
    if ! grep -q 'signed-by=/usr/share/keyrings/cloud.google.gpg' "$cloud_sources"; then
        printf '    LET OP: %s verwijst niet naar /usr/share/keyrings/cloud.google.gpg\n' "$cloud_sources" >&2
    fi
    changed=1
fi

if [ "$changed" = 0 ]; then
    printf 'Geen van de drie repo'"'"'s gevonden; niets te doen.\n'
    exit 0
fi

say "apt-indexen bijwerken"
if apt-get update; then
    say "Klaar: apt-get update slaagt weer."
else
    warn "apt-get update faalt nog. Zet een bewaard bronbestand terug met:"
    warn "  cp /etc/apt/sources.list.d/<naam>.bak /etc/apt/sources.list.d/<naam>"
    exit 1
fi
