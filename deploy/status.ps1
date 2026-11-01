#Requires -Version 5.1
<#
.SYNOPSIS
    Toon of de Running Coach op deze Windows-pc goed draait.

.DESCRIPTION
    Controleert in een keer: Docker, de twee containers, de database met het aantal
    activiteiten, de lokale loginpagina, de publieke Funnel-link en de Tailscale-status.
    Exitcode 0 = alles in orde, 1 = er is iets mis (handig voor een agent).

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\deploy\status.ps1
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Continue'
$deployDir = $PSScriptRoot
$repoDir = Split-Path -Parent $deployDir
$composeFile = Join-Path $deployDir 'docker-compose.yml'
$tailscaleExe = Join-Path $env:ProgramFiles 'Tailscale\tailscale.exe'
$appUrl = 'http://127.0.0.1:8080/login'

$problems = New-Object System.Collections.Generic.List[string]
function Ok([string]$Message) { Write-Host "  [ok]    $Message" -ForegroundColor Green }
function Bad([string]$Message) { Write-Host "  [fout]  $Message" -ForegroundColor Red; $problems.Add($Message) }
function Info([string]$Message) { Write-Host "  [info]  $Message" }

function Test-Docker {
    try { docker info --format '{{.ServerVersion}}' 2>$null | Out-Null; return $LASTEXITCODE -eq 0 }
    catch { return $false }
}

Write-Host "`nRunning Coach - status $(Get-Date -Format 'yyyy-MM-dd HH:mm')" -ForegroundColor Cyan

# --- Docker ---
if (-not (Test-Docker)) {
    Bad "Docker reageert niet. Start Docker Desktop of draai deploy\start.ps1."
    Write-Host "`nKlaar met fouten.`n" -ForegroundColor Red
    exit 1
}
Ok "Docker antwoordt ($(docker info --format '{{.ServerVersion}}'))"

# --- Containers ---
Push-Location $repoDir
try { $ps = (& docker compose -f $composeFile ps --format '{{.Name}}|{{.State}}|{{.Status}}' 2>$null) } finally { Pop-Location }
$states = @{}
foreach ($line in $ps) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    $parts = $line.Split('|')
    if ($parts.Count -ge 3) { $states[$parts[0]] = $parts[2] }
}
foreach ($name in @('running-db', 'running-app')) {
    if ($states.ContainsKey($name)) {
        if ($states[$name] -match 'Up') { Ok "$name : $($states[$name])" } else { Bad "$name : $($states[$name])" }
    } else {
        Bad "$name draait niet"
    }
}

# --- Database ---
if ($states.ContainsKey('running-db') -and $states['running-db'] -match 'Up') {
    $count = (& docker compose -f $composeFile exec -T postgres psql -U postgres -d running -tAc "select count(*) from activities;" 2>$null)
    $count = ("$count").Trim()
    if ($count -match '^\d+$') {
        if ([int]$count -gt 0) { Ok "Database bereikbaar, $count activiteiten" } else { Bad "Database bereikbaar maar leeg (0 activiteiten)" }
    } else {
        Bad "Database niet bereikbaar (psql gaf geen getal)"
    }
}

# --- Lokale app ---
try {
    $r = Invoke-WebRequest $appUrl -UseBasicParsing -TimeoutSec 15
    if ($r.StatusCode -eq 200) { Ok "Lokaal $appUrl : 200" } else { Bad "Lokaal $appUrl : $($r.StatusCode)" }
} catch {
    Bad "Lokaal $appUrl antwoordt niet"
}

# --- Tailscale + publieke link ---
$publicUrl = ''
if (Test-Path $tailscaleExe) {
    $json = & $tailscaleExe status --json 2>$null
    if ($LASTEXITCODE -eq 0) {
        $node = $json | ConvertFrom-Json
        if ($node.BackendState -eq 'Running') { Ok "Tailscale: $($node.Self.DNSName.TrimEnd('.')) (Running)" } else { Bad "Tailscale: $($node.BackendState)" }
        if ($node.Self.DNSName) { $publicUrl = "https://$($node.Self.DNSName.TrimEnd('.'))" }
    } else {
        Bad "Tailscale antwoordt niet"
    }

    $funnel = (& $tailscaleExe funnel status 2>&1) -join "`n"
    if ($funnel -match 'No serve config') {
        Bad "Funnel staat uit (publieke link werkt niet). Aanzetten: deploy\start.ps1"
    } else {
        Ok "Funnel staat aan"
    }

    if ($publicUrl -and $funnel -notmatch 'No serve config') {
        try {
            $p = Invoke-WebRequest "$publicUrl/login" -UseBasicParsing -TimeoutSec 30
            if ($p.StatusCode -eq 200) { Ok "Publiek $publicUrl/login : 200" } else { Bad "Publiek $publicUrl/login : $($p.StatusCode)" }
        } catch {
            Bad "Publiek $publicUrl/login antwoordt niet"
        }
    }
} else {
    Info "Tailscale niet geinstalleerd; alleen lokaal gebruik."
}

# --- Samenvatting ---
Write-Host ""
if ($problems.Count -eq 0) {
    Write-Host "Alles in orde. Lokaal: http://127.0.0.1:8080  $(if ($publicUrl) { " Publiek: $publicUrl" })" -ForegroundColor Green
    exit 0
} else {
    Write-Host "$($problems.Count) probleem(en):" -ForegroundColor Red
    $problems | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    Write-Host "Oplossingen staan in deploy\START-STOP.md." -ForegroundColor Yellow
    exit 1
}
