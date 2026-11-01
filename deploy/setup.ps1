#Requires -Version 5.1
<#
.SYNOPSIS
    Eenmalige installatie van de Running Coach op een altijd-aan Windows 11-machine.

.DESCRIPTION
    Doet in volgorde:
      1. WSL2 aanzetten (nodig voor Docker Desktop)           - verhoogd (UAC)
      2. Docker Desktop en Tailscale installeren via winget   - verhoogd (UAC)
      3. Slaapstand, klep-dicht en netwerkprofiel aanpassen    - verhoogd (UAC)
      4. deploy\.env aanmaken met willekeurige wachtwoorden
      5. docker compose up -d --build en wachten tot de app antwoordt

    Veilig om twee keer te draaien. De tweede keer slaat het over wat al klaar is.
    Draai dit als je eigen gebruiker (Gebruiker), NIET als Administrator: het script
    vraagt zelf één keer om verhoging voor stap 1 tot 3.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\deploy\setup.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\deploy\setup.ps1 -SkipInstall
    (alleen .env controleren en de app starten)
#>
[CmdletBinding()]
param(
    [switch]$SkipInstall,
    [switch]$SkipStart
)

$ErrorActionPreference = 'Stop'
$deployDir = $PSScriptRoot
$repoDir = Split-Path -Parent $deployDir
$composeFile = Join-Path $deployDir 'docker-compose.yml'
$envFile = Join-Path $deployDir '.env'
$appUrl = 'http://127.0.0.1:8080/login'

function Say([string]$Message) { Write-Host "`n==> $Message" -ForegroundColor Cyan }
function Warn([string]$Message) { Write-Host "LET OP: $Message" -ForegroundColor Yellow }
function Fail([string]$Message) { Write-Host "FOUT: $Message" -ForegroundColor Red; exit 1 }

function New-Hex([int]$Bytes) {
    $buffer = New-Object byte[] $Bytes
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($buffer)
    $rng.Dispose()
    ($buffer | ForEach-Object { $_.ToString('x2') }) -join ''
}

function Test-Docker {
    try { docker info --format '{{.ServerVersion}}' 2>$null | Out-Null; return $LASTEXITCODE -eq 0 }
    catch { return $false }
}

function Get-EnvValue([string]$Key) {
    if (-not (Test-Path $envFile)) { return '' }
    $match = Select-String -Path $envFile -Pattern "^$([regex]::Escape($Key))=(.*)$" | Select-Object -First 1
    if ($match) { return $match.Matches[0].Groups[1].Value.Trim() }
    return ''
}

function Set-EnvValue([string]$Key, [string]$Value) {
    $lines = Get-Content $envFile
    $found = $false
    $out = foreach ($line in $lines) {
        if ($line -match "^$([regex]::Escape($Key))=") { $found = $true; "$Key=$Value" } else { $line }
    }
    if (-not $found) { $out = @($out) + "$Key=$Value" }
    Set-Content -Path $envFile -Value $out -Encoding ASCII
}

# ---------------------------------------------------------------------------
# 1-3. Verhoogde stappen (één UAC-vraag)
# ---------------------------------------------------------------------------
if (-not $SkipInstall) {
    $privileged = Join-Path $env:TEMP 'running-coach-privileged.ps1'
    @'
$ErrorActionPreference = 'Continue'
$rebootNeeded = $false

Write-Host "`n==> WSL2 controleren"
$wslOk = $false
try { wsl.exe --status *> $null; $wslOk = ($LASTEXITCODE -eq 0) } catch { $wslOk = $false }
if (-not $wslOk) {
    Write-Host "WSL2 installeren (zonder Linux-distributie)"
    dism.exe /online /enable-feature /featurename:Microsoft-Windows-Subsystem-Linux /all /norestart | Out-Null
    dism.exe /online /enable-feature /featurename:VirtualMachinePlatform /all /norestart | Out-Null
    wsl.exe --install --no-distribution
    $rebootNeeded = $true
}

Write-Host "`n==> Docker Desktop controleren"
$dockerExe = Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'
if (-not (Test-Path $dockerExe)) {
    Write-Host "Docker Desktop installeren via winget"
    winget install --exact --id Docker.DockerDesktop --accept-package-agreements --accept-source-agreements --silent
    if (-not (Test-Path $dockerExe)) { Write-Host "winget-installatie van Docker Desktop mislukte"; exit 2 }
    $rebootNeeded = $true
}
$group = Get-LocalGroup -Name 'docker-users' -ErrorAction SilentlyContinue
if ($group) {
    try { Add-LocalGroupMember -Group 'docker-users' -Member $env:USERNAME -ErrorAction Stop; Write-Host "Gebruiker toegevoegd aan docker-users" } catch { }
}

Write-Host "`n==> Tailscale controleren"
$tailscaleExe = Join-Path $env:ProgramFiles 'Tailscale\tailscale.exe'
if (-not (Test-Path $tailscaleExe)) {
    Write-Host "Tailscale installeren via winget"
    winget install --exact --id tailscale.tailscale --accept-package-agreements --accept-source-agreements --silent
    if (-not (Test-Path $tailscaleExe)) { Write-Host "winget-installatie van Tailscale mislukte"; exit 3 }
}

Write-Host "`n==> Slaapstand en klep-dicht uitzetten (op netstroom)"
powercfg.exe /change standby-timeout-ac 0
powercfg.exe /change hibernate-timeout-ac 0
powercfg.exe /change disk-timeout-ac 0
powercfg.exe /change monitor-timeout-ac 10
# SUB_BUTTONS / LIDACTION = 0 (niets doen)
powercfg.exe /setacvalueindex SCHEME_CURRENT 4f971e89-eebd-4455-a8de-9e59040e7347 5ca83367-6e45-459f-a27b-476b1d01c936 0
powercfg.exe /setactive SCHEME_CURRENT

Write-Host "`n==> Netwerkprofiel op Privé zetten (Docker en Tailscale hebben dat nodig)"
Get-NetConnectionProfile | Where-Object { $_.NetworkCategory -eq 'Public' } |
    ForEach-Object { Set-NetConnectionProfile -InterfaceIndex $_.InterfaceIndex -NetworkCategory Private }

Write-Host "`n==> Firewall-regel voor Tailscale"
if (-not (Get-NetFirewallRule -DisplayName 'Tailscale (Running Coach)' -ErrorAction SilentlyContinue)) {
    $exe = Join-Path $env:ProgramFiles 'Tailscale\tailscale-ipn.exe'
    if (Test-Path $exe) {
        New-NetFirewallRule -DisplayName 'Tailscale (Running Coach)' -Direction Inbound -Action Allow -Program $exe -Profile Any | Out-Null
    }
}

if ($rebootNeeded) {
    Write-Host "`nREBOOT NODIG: start Windows opnieuw op en draai dit script daarna nog eens." -ForegroundColor Yellow
    exit 10
}
exit 0
'@ | Set-Content -Path $privileged -Encoding UTF8

    Say "Installatie starten (je krijgt één keer een UAC-vraag)"
    $proc = Start-Process -FilePath 'powershell.exe' -Verb RunAs -Wait -PassThru -ArgumentList @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$privileged`""
    )
    Remove-Item $privileged -ErrorAction SilentlyContinue

    switch ($proc.ExitCode) {
        0 { }
        10 {
            Warn "Windows moet opnieuw opstarten voordat Docker werkt."
            Warn "Start opnieuw op en draai daarna: powershell -ExecutionPolicy Bypass -File .\deploy\setup.ps1"
            exit 0
        }
        2 { Fail "Docker Desktop kon niet geïnstalleerd worden. Installeer het met de hand via https://www.docker.com/products/docker-desktop/ en draai dit script opnieuw met -SkipInstall." }
        3 { Fail "Tailscale kon niet geïnstalleerd worden. Installeer het met de hand via https://tailscale.com/download/windows en draai dit script opnieuw met -SkipInstall." }
        default { Fail "De verhoogde installatiestap stopte met code $($proc.ExitCode)." }
    }
}

# ---------------------------------------------------------------------------
# Docker Desktop moet draaien (het is geen Windows-service)
# ---------------------------------------------------------------------------
if (-not (Test-Docker)) {
    $desktop = Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'
    if (Test-Path $desktop) {
        Say "Docker Desktop starten"
        Start-Process -FilePath $desktop | Out-Null
        for ($i = 1; $i -le 96; $i++) {
            if (Test-Docker) { break }
            Start-Sleep -Seconds 5
            if ($i -eq 24) { Warn "Docker Desktop start traag op. Accepteer het welkomstscherm/licentie als dat verschijnt." }
        }
    }
}
if (-not (Test-Docker)) {
    Fail @"
Docker reageert niet. Controleer:
  - Docker Desktop is gestart en je bent ingelogd op deze Windows-sessie;
  - je bent lid van de groep docker-users (log één keer uit en weer in na de installatie);
  - virtualisatie staat aan in de BIOS/UEFI (SVM Mode).
Daarna: powershell -ExecutionPolicy Bypass -File .\deploy\setup.ps1 -SkipInstall
"@
}

# ---------------------------------------------------------------------------
# 4. .env
# ---------------------------------------------------------------------------
if (-not (Test-Path $envFile)) {
    Say "deploy\.env aanmaken"
    Copy-Item (Join-Path $deployDir '.env.example') $envFile
    Set-EnvValue 'DB_PASSWORD' (New-Hex 24)
    Set-EnvValue 'APP_REMEMBER_ME_KEY' (New-Hex 32)
    Warn "Vul in deploy\.env nog in: APP_PASSWORD, STRAVA_CLIENT_ID, STRAVA_CLIENT_SECRET en"
    Warn "STRAVA_REDIRECT_URI (https://<machine>.<tailnet>.ts.net/callback), eventueel GARMIN_PERSONAL_API_KEY."
    Warn "Draai daarna opnieuw: powershell -ExecutionPolicy Bypass -File .\deploy\setup.ps1"
    exit 0
}

$required = @('APP_PASSWORD', 'STRAVA_CLIENT_ID', 'STRAVA_CLIENT_SECRET', 'STRAVA_REDIRECT_URI')
$missing = $required | Where-Object { [string]::IsNullOrWhiteSpace((Get-EnvValue $_)) }
if ($missing) {
    Warn "Deze waarden zijn nog leeg in deploy\.env: $($missing -join ', ')"
    Warn "Vul ze in en draai dit script opnieuw."
    exit 1
}
if ((Get-EnvValue 'STRAVA_REDIRECT_URI') -match 'YOUR-MACHINE|YOUR-TAILNET') {
    Warn "STRAVA_REDIRECT_URI staat nog op de voorbeeldwaarde. Zet er je eigen link in:"
    Warn "  https://<machine>.<tailnet>.ts.net/callback  (zie DEPLOY.md stap 6)"
    exit 1
}

# ---------------------------------------------------------------------------
# 5. Starten
# ---------------------------------------------------------------------------
if (-not $SkipStart) {
    Say "App bouwen en starten (de eerste keer duurt dit enkele minuten)"
    Push-Location $repoDir
    try {
        docker compose -f $composeFile up -d --build
        if ($LASTEXITCODE -ne 0) { Fail "docker compose up is mislukt." }
    }
    finally { Pop-Location }

    Say "Wachten tot de app antwoordt op $appUrl"
    $up = $false
    for ($i = 1; $i -le 60; $i++) {
        try {
            $response = Invoke-WebRequest -Uri $appUrl -UseBasicParsing -TimeoutSec 10
            if ($response.StatusCode -eq 200) { $up = $true; break }
        }
        catch { }
        Start-Sleep -Seconds 5
    }
    if (-not $up) {
        Fail "De app antwoordt niet. Bekijk de logs: docker compose -f deploy\docker-compose.yml logs -f app"
    }
    Say "De app draait lokaal op http://127.0.0.1:8080"
}

docker compose -f $composeFile ps
Say @"
Volgende stap: de publieke https-link aanzetten (eenmalig).
  1. Tailscale-console: DNS -> HTTPS-certificaten aan; Access controls -> funnel-attribuut (zie DEPLOY.md stap 6).
  2. Koppelen:   & "$env:ProgramFiles\Tailscale\tailscale.exe" up
  3. Funnel:     & "$env:ProgramFiles\Tailscale\tailscale.exe" funnel --bg 8080
  4. Controle:   & "$env:ProgramFiles\Tailscale\tailscale.exe" funnel status
"@
