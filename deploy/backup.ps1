#Requires -Version 5.1
<#
.SYNOPSIS
    Back-up van ALLE databases van de Running Coach (Windows; op Linux: deploy/backup.sh).

.DESCRIPTION
    Dumpt `running` en elke `running_*` database (niet de scratch-databases `*_restore_test`) naar
    deploy\backups\all-<datum>\, met een manifest met het aantal rijen per tabel. Alleen lezen op de
    databases. `migrate.ps1 backup` blijft ongewijzigd werken.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\deploy\backup.ps1
#>
$ErrorActionPreference = 'Stop'
$deployDir = $PSScriptRoot
$repoDir = Split-Path -Parent $deployDir
$composeFile = Join-Path $deployDir 'docker-compose.yml'
$dbService = 'postgres'
$dbUser = 'postgres'

function Say([string]$Message) { Write-Host "`n==> $Message" -ForegroundColor Cyan }
function Fail([string]$Message) { Write-Host "FOUT: $Message" -ForegroundColor Red; exit 1 }

function Invoke-Compose {
    Push-Location $repoDir
    try { & docker compose -f $composeFile @args }
    finally { Pop-Location }
}

function Invoke-Sql([string]$Db, [string]$Sql) {
    $out = Invoke-Compose exec -T $dbService psql -U $dbUser -d $Db -At -c $Sql
    if ($LASTEXITCODE -ne 0) { Fail "psql is mislukt op database '$Db'." }
    $out
}

$ready = $false
for ($i = 1; $i -le 30; $i++) {
    Invoke-Compose exec -T $dbService pg_isready -U $dbUser -d running *> $null
    if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    Start-Sleep -Seconds 2
}
if (-not $ready) { Fail "De database-container draait niet." }

$backupRoot = Join-Path $deployDir 'backups'
$dir = Join-Path $backupRoot ("all-{0}" -f (Get-Date -Format 'yyyyMMdd-HHmm'))
New-Item -ItemType Directory -Path $dir -Force | Out-Null
$manifest = Join-Path $dir 'manifest.txt'
"Back-up $(Get-Date -Format 'o')" | Set-Content $manifest

$names = @(Invoke-Sql 'postgres' "SELECT datname FROM pg_database WHERE (datname = 'running' OR datname LIKE 'running\_%') AND datname NOT LIKE '%\_restore\_test' ORDER BY datname")
if ($names.Count -eq 0) { Fail "Geen running-database gevonden." }

foreach ($name in $names) {
    Say "Dump maken van database '$name'"
    $tmp = "/tmp/$name.dump"
    $target = Join-Path $dir "$name.dump"
    Invoke-Compose exec -T $dbService pg_dump -U $dbUser -d $name --no-owner --no-privileges -Fc -f $tmp
    if ($LASTEXITCODE -ne 0) { Fail "pg_dump van '$name' is mislukt." }
    Invoke-Compose cp "${dbService}:$tmp" $target
    if ($LASTEXITCODE -ne 0) { Fail "Kopiëren van de dump van '$name' is mislukt." }
    Invoke-Compose exec -T $dbService rm -f $tmp *> $null
    if (-not (Test-Path $target) -or (Get-Item $target).Length -eq 0) { Fail "Dump van '$name' is leeg." }
    "`n[$name] $((Get-Item $target).Length) bytes" | Add-Content $manifest
    Invoke-Sql $name "SELECT table_name || ' ' || (xpath('/row/c/text()', query_to_xml(format('SELECT count(*) AS c FROM %I.%I', table_schema, table_name), false, true, '')))[1]::text FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE' ORDER BY 1" | Add-Content $manifest
}

Get-ChildItem $backupRoot -Directory -Filter 'all-*' |
    Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-14) } |
    ForEach-Object { Remove-Item $_.FullName -Recurse -Force; Write-Host "Oude back-up verwijderd: $($_.Name)" }
Get-ChildItem $dir | Select-Object Name, Length | Format-Table
