#Requires -Version 5.1
<#
.SYNOPSIS
    Start de Running Coach op deze Windows-pc (Docker-stack + publieke Funnel-link).

.DESCRIPTION
    Doet in volgorde:
      1. Docker Desktop starten als de engine nog niet antwoordt.
      2. De stack starten: docker compose up -d (met -Build ook opnieuw bouwen).
      3. Wachten tot de app antwoordt op http://127.0.0.1:8080/login.
      4. De publieke https-link aanzetten: tailscale funnel --bg 8080.

    Veilig om vaker te draaien: wat al loopt blijft staan. Stoppen doet u met
    deploy\stop.ps1, de toestand bekijken met deploy\status.ps1.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\deploy\start.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\deploy\start.ps1 -Build
    (na een codewijziging: image opnieuw bouwen, duurt enkele minuten)

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\deploy\start.ps1 -NoFunnel
    (alleen lokaal starten, geen publieke link)
#>
[CmdletBinding()]
param(
    [switch]$Build,
    [switch]$NoFunnel
)

$ErrorActionPreference = 'Stop'
$deployDir = $PSScriptRoot
$repoDir = Split-Path -Parent $deployDir
$composeFile = Join-Path $deployDir 'docker-compose.yml'
$tailscaleExe = Join-Path $env:ProgramFiles 'Tailscale\tailscale.exe'
$appUrl = 'http://127.0.0.1:8080/login'

function Say([string]$Message) { Write-Host "`n==> $Message" -ForegroundColor Cyan }
function Warn([string]$Message) { Write-Host "LET OP: $Message" -ForegroundColor Yellow }
function Fail([string]$Message) { Write-Host "FOUT: $Message" -ForegroundColor Red; exit 1 }

function Test-Docker {
    try { docker info --format '{{.ServerVersion}}' 2>$null | Out-Null; return $LASTEXITCODE -eq 0 }
    catch { return $false }
}

function Test-App {
    try { return (Invoke-WebRequest $appUrl -UseBasicParsing -TimeoutSec 10).StatusCode -eq 200 }
    catch { return $false }
}

function Invoke-Compose {
    Push-Location $repoDir
    try { & docker compose -f $composeFile @args }
    finally { Pop-Location }
}

# ---------------------------------------------------------------------------
# 1. Docker Desktop moet draaien (het is geen Windows-service)
# ---------------------------------------------------------------------------
if (-not (Test-Docker)) {
    $desktop = Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'
    if (-not (Test-Path $desktop)) { Fail "Docker Desktop is niet geinstalleerd. Draai eenmalig deploy\setup.ps1." }
    Say "Docker Desktop starten (kan een minuut duren)"
    Start-Process -FilePath $desktop | Out-Null
    for ($i = 1; $i -le 96; $i++) {
        if (Test-Docker) { break }
        Start-Sleep -Seconds 5
        if ($i -eq 24) { Warn "Docker Desktop start traag. Accepteer het welkomstscherm als dat verschijnt." }
    }
}
if (-not (Test-Docker)) {
    Fail @"
Docker reageert niet. Controleer:
  - Docker Desktop is gestart en je bent ingelogd op deze Windows-sessie;
  - je bent lid van de groep docker-users (na toevoegen een keer uit- en inloggen);
  - virtualisatie (SVM Mode) staat aan in de BIOS/UEFI.
"@
}

# ---------------------------------------------------------------------------
# 2. Stack starten
# ---------------------------------------------------------------------------
if ($Build) {
    Say "Image bouwen en stack starten (de eerste keer duurt dit enkele minuten)"
    Invoke-Compose up -d --build
} else {
    Say "Stack starten"
    Invoke-Compose up -d
}
# docker compose schrijft voortgang naar stderr; PowerShell maakt daar soms een
# niet-terminating error van. De containerstatus hieronder is de echte controle.

# ---------------------------------------------------------------------------
# 3. Wachten tot de app antwoordt
# ---------------------------------------------------------------------------
Say "Wachten tot de app antwoordt op $appUrl"
$up = $false
for ($i = 1; $i -le 60; $i++) {
    if (Test-App) { $up = $true; break }
    Start-Sleep -Seconds 5
}
if (-not $up) {
    Warn "De app antwoordt niet. Laatste regels uit het log:"
    Invoke-Compose logs --tail 30 app
    Fail "Starten mislukt. Zie deploy\START-STOP.md."
}

# ---------------------------------------------------------------------------
# 4. Publieke https-link (Tailscale Funnel)
# ---------------------------------------------------------------------------
$publicUrl = ''
if (Test-Path $tailscaleExe) {
    $json = & $tailscaleExe status --json 2>$null
    if ($LASTEXITCODE -eq 0) {
        $dns = ($json | ConvertFrom-Json).Self.DNSName
        if ($dns) { $publicUrl = "https://$($dns.TrimEnd('.'))" }
    }
}

if (-not $NoFunnel) {
    if (-not (Test-Path $tailscaleExe)) {
        Warn "Tailscale is niet geinstalleerd; de publieke link blijft uit."
    } else {
        $status = (& $tailscaleExe funnel status 2>&1) -join "`n"
        if ($status -match 'No serve config') {
            Say "Publieke link aanzetten (tailscale funnel --bg 8080)"
            $out = (& $tailscaleExe funnel --bg 8080 2>&1) -join "`n"
            Write-Host $out
            if ($out -match 'not enabled') {
                Warn "Funnel is niet toegestaan op deze tailnet. Zet in de Tailscale-console"
                Warn "HTTPS-certificaten en het funnel-attribuut aan (zie deploy\DEPLOY.md stap 6)."
            }
        } else {
            Say "Publieke link stond al aan"
        }
    }
}

# ---------------------------------------------------------------------------
# Samenvatting
# ---------------------------------------------------------------------------
Invoke-Compose ps
Say @"
De app draait.
  Lokaal:    http://127.0.0.1:8080
  Publiek:   $(if ($publicUrl) { "$publicUrl  (login nodig)" } else { 'onbekend - Tailscale niet gevonden' })
  Login:     gebruiker uit APP_USERNAME, wachtwoord uit deploy\.env
  Stoppen:   powershell -ExecutionPolicy Bypass -File .\deploy\stop.ps1
  Status:    powershell -ExecutionPolicy Bypass -File .\deploy\status.ps1
"@
exit 0
