#Requires -Version 5.1
<#
.SYNOPSIS
    Database verplaatsen of back-uppen voor de Running Coach (Windows).

.DESCRIPTION
    Werkt uitsluitend via `docker compose exec` / `docker compose cp`, dus het maakt
    niet uit of de host Windows, Linux of macOS is. Er wordt niets naar de host
    gestreamd (geen binaire redirect), dus de dump blijft intact.

    dump      schrijft deploy\running.dump van de database in de container
    restore   zet een dump terug (overschrijft de database volledig)
    backup    schrijft deploy\backups\running-<datum>.dump en bewaart 14 dagen

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\deploy\migrate.ps1 dump

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\deploy\migrate.ps1 restore -File .\deploy\running.dump
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0, Mandatory = $true)]
    [ValidateSet('dump', 'restore', 'backup')]
    [string]$Action,

    [string]$File = 'running.dump'
)

$ErrorActionPreference = 'Stop'
$deployDir = $PSScriptRoot
$repoDir = Split-Path -Parent $deployDir
$composeFile = Join-Path $deployDir 'docker-compose.yml'
$dbService = 'postgres'
$dbName = 'running'
$dbUser = 'postgres'
$inContainer = '/tmp/running.dump'

function Say([string]$Message) { Write-Host "`n==> $Message" -ForegroundColor Cyan }
function Fail([string]$Message) { Write-Host "FOUT: $Message" -ForegroundColor Red; exit 1 }

function Invoke-Compose {
    Push-Location $repoDir
    try { & docker compose -f $composeFile @args }
    finally { Pop-Location }
}

function Assert-DbUp {
    $ready = $false
    for ($i = 1; $i -le 30; $i++) {
        Push-Location $repoDir
        try {
            docker compose -f $composeFile exec -T $dbService pg_isready -U $dbUser -d $dbName *> $null
            if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        }
        finally { Pop-Location }
        Start-Sleep -Seconds 2
    }
    if (-not $ready) { Fail "De database-container draait niet. Start eerst: docker compose -f deploy\docker-compose.yml up -d" }
}

function Export-Dump([string]$Target) {
    Assert-DbUp
    Say "Dump maken van database '$dbName'"
    Invoke-Compose exec -T $dbService pg_dump -U $dbUser -d $dbName --no-owner --no-privileges -Fc -f $inContainer
    if ($LASTEXITCODE -ne 0) { Fail "pg_dump is mislukt." }
    Invoke-Compose cp "${dbService}:$inContainer" $Target
    if ($LASTEXITCODE -ne 0) { Fail "Kopiëren van de dump is mislukt." }
    Invoke-Compose exec -T $dbService rm -f $inContainer *> $null
    Get-Item $Target | Select-Object FullName, Length, LastWriteTime | Format-List
}

switch ($Action) {
    'dump' {
        Export-Dump (Join-Path $deployDir 'running.dump')
        Say "Klaar. Zet dit bestand op de doelmachine en herstel het daar met:"
        Write-Host "    .\deploy\migrate.ps1 restore -File .\deploy\running.dump"
    }

    'restore' {
        if (-not (Test-Path $File)) { Fail "Bestand '$File' niet gevonden." }
        $absolute = (Resolve-Path $File).Path
        Assert-DbUp
        Say "Database '$dbName' overschrijven met $absolute"
        Invoke-Compose cp $absolute "${dbService}:$inContainer"
        if ($LASTEXITCODE -ne 0) { Fail "Kopiëren naar de container is mislukt." }
        Invoke-Compose exec -T $dbService pg_restore -U $dbUser -d $dbName --clean --if-exists --no-owner --no-privileges $inContainer
        # pg_restore meldt warnings bij het opruimen van objecten die nog niet bestaan; dat is normaal.
        Invoke-Compose exec -T $dbService rm -f $inContainer *> $null
        Say "Hersteld. De app herstarten: docker compose -f deploy\docker-compose.yml restart app"
    }

    'backup' {
        $backupDir = Join-Path $deployDir 'backups'
        if (-not (Test-Path $backupDir)) { New-Item -ItemType Directory -Path $backupDir | Out-Null }
        $target = Join-Path $backupDir ("running-{0}.dump" -f (Get-Date -Format 'yyyyMMdd-HHmm'))
        Export-Dump $target
        Get-ChildItem $backupDir -Filter 'running-*.dump' |
            Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-14) } |
            ForEach-Object { Remove-Item $_.FullName -Force; Write-Host "Oude back-up verwijderd: $($_.Name)" }
    }
}
