#Requires -Version 5.1
<#
.SYNOPSIS
    Stop de Running Coach op deze Windows-pc.

.DESCRIPTION
    Standaard: containers stoppen en de publieke https-link uitzetten, zodat er
    niets meer bereikbaar is. De database (named volume pgdata) blijft bewaard,
    dus na start.ps1 staat alles er weer.

      -KeepFunnel   laat de Funnel-link staan (geeft dan 502 zolang de app stopt)
      -Down         containers ook verwijderen (docker compose down, volume blijft)

    Dit script verwijdert NOOIT het database-volume. Gebruik nooit "down -v".

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\deploy\stop.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\deploy\stop.ps1 -Down
#>
[CmdletBinding()]
param(
    [switch]$Down,
    [switch]$KeepFunnel
)

$ErrorActionPreference = 'Stop'
$deployDir = $PSScriptRoot
$repoDir = Split-Path -Parent $deployDir
$composeFile = Join-Path $deployDir 'docker-compose.yml'
$tailscaleExe = Join-Path $env:ProgramFiles 'Tailscale\tailscale.exe'

function Say([string]$Message) { Write-Host "`n==> $Message" -ForegroundColor Cyan }
function Warn([string]$Message) { Write-Host "LET OP: $Message" -ForegroundColor Yellow }

function Test-Docker {
    try { docker info --format '{{.ServerVersion}}' 2>$null | Out-Null; return $LASTEXITCODE -eq 0 }
    catch { return $false }
}

function Invoke-Compose {
    Push-Location $repoDir
    try { & docker compose -f $composeFile @args }
    finally { Pop-Location }
}

# ---------------------------------------------------------------------------
# 1. Publieke link eerst uitzetten (anders geeft de link een 502)
# ---------------------------------------------------------------------------
if (-not $KeepFunnel) {
    if (Test-Path $tailscaleExe) {
        Say "Publieke link uitzetten"
        & $tailscaleExe funnel --https=443 off 2>&1 | Out-Null
        & $tailscaleExe funnel status 2>&1
    } else {
        Warn "Tailscale niet gevonden; publieke link kan niet uitgezet worden."
    }
}

# ---------------------------------------------------------------------------
# 2. Containers stoppen
# ---------------------------------------------------------------------------
if (-not (Test-Docker)) {
    Warn "Docker reageert niet. De containers draaien dus al niet meer."
    exit 0
}

if ($Down) {
    Say "Stack verwijderen (database-volume pgdata blijft bewaard)"
    Invoke-Compose down
} else {
    Say "Stack stoppen (containers blijven bestaan, database blijft bewaard)"
    Invoke-Compose stop
}

Say @"
Gestopt. De database staat nog in het volume pgdata.
  Weer starten:  powershell -ExecutionPolicy Bypass -File .\deploy\start.ps1
  Status:        powershell -ExecutionPolicy Bypass -File .\deploy\status.ps1
"@
exit 0
