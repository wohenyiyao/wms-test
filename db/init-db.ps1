# =====================================================================
# WMS database initializer
# Creates the database (if missing) and executes db/seed.sql
# (truncates all business tables, then loads the formal seed data).
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File db/init-db.ps1
#   powershell -ExecutionPolicy Bypass -File db/init-db.ps1 -User root -Password root -Database wms-test
#
# Notes:
#   - ASCII-only so it parses under both Windows PowerShell 5.1 and 7.
#   - mysql.exe is located on PATH, or falls back to C:\mysql57\bin\mysql.exe.
#   - Run with the repo root as the working directory (or use -SeedFile).
# =====================================================================
param(
    [string]$DbHost = '127.0.0.1',
    [string]$User = 'root',
    [string]$Password = 'root',
    [string]$Database = 'wms-test',
    [string]$SeedFile = ''
)

$ErrorActionPreference = 'Stop'

# --- resolve seed file (default: <script-dir>/seed.sql) -----------------
if (-not $SeedFile) {
    $SeedFile = Join-Path $PSScriptRoot 'seed.sql'
}
if (-not (Test-Path $SeedFile)) {
    Write-Host "[ERROR] seed file not found: $SeedFile" -ForegroundColor Red
    exit 1
}

# --- locate mysql.exe ----------------------------------------------------
$mysql = (Get-Command mysql.exe -ErrorAction SilentlyContinue)
if ($mysql) {
    $mysqlPath = $mysql.Source
}
else {
    $fallback = 'C:\mysql57\bin\mysql.exe'
    if (Test-Path $fallback) { $mysqlPath = $fallback }
    else {
        Write-Host '[ERROR] mysql.exe not found on PATH nor at C:\mysql57\bin\mysql.exe' -ForegroundColor Red
        exit 1
    }
}
Write-Host "mysql: $mysqlPath"

# --- create database if missing ------------------------------------------
# Note: use MYSQL_PWD instead of --password to avoid the "Using a password
# on the command line" warning on stderr, which Windows PowerShell 5.1
# turns into a terminating NativeCommandError under $ErrorActionPreference=Stop.
$createSql = "CREATE DATABASE IF NOT EXISTS ``$Database`` DEFAULT CHARACTER SET utf8mb4"
$env:MYSQL_PWD = $Password
& $mysqlPath --host=$DbHost --user=$User -e $createSql 2>&1 | Out-Null
$createExit = $LASTEXITCODE
Remove-Item Env:MYSQL_PWD
if ($createExit -ne 0) {
    Write-Host "[ERROR] failed to create database '$Database'" -ForegroundColor Red
    exit 1
}
Write-Host "database ready: $Database"

# --- execute seed.sql -----------------------------------------------------
$seedAbs = (Resolve-Path $SeedFile).Path
$sourceCmd = "source $seedAbs"
$env:MYSQL_PWD = $Password
& $mysqlPath --default-character-set=utf8mb4 --host=$DbHost --user=$User --database=$Database -e $sourceCmd 2>&1 | Out-Null
$seedExit = $LASTEXITCODE
Remove-Item Env:MYSQL_PWD
if ($seedExit -ne 0) {
    Write-Host "[ERROR] failed to execute $seedAbs" -ForegroundColor Red
    exit 1
}

# --- verify counts ---------------------------------------------------------
$countSql = "SELECT (SELECT COUNT(*) FROM products) AS products, (SELECT COUNT(*) FROM warehouses) AS warehouses, (SELECT COUNT(*) FROM locations) AS locations, (SELECT COUNT(*) FROM inventory) AS inventory_rows, (SELECT COUNT(*) FROM inbound_orders) AS inbound_orders, (SELECT COUNT(*) FROM outbound_orders) AS outbound_orders;"
$env:MYSQL_PWD = $Password
& $mysqlPath --default-character-set=utf8mb4 --host=$DbHost --user=$User --database=$Database -e $countSql 2>&1
$countExit = $LASTEXITCODE
Remove-Item Env:MYSQL_PWD
if ($countExit -ne 0) {
    Write-Host "[ERROR] failed to verify counts" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "Seed data loaded OK." -ForegroundColor Green
exit 0
