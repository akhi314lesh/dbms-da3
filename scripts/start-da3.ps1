# start-da3.ps1 - Launch complete DA3 Polyglot Stack (InfluxDB + Spring Boot + React Frontend)
$ErrorActionPreference = "Continue"

$projectRoot = Split-Path -Parent $PSScriptRoot

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  Launching DA3: Polyglot InfluxDB + Oracle Smart Home    " -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. Check InfluxDB on port 8086
Write-Host "[1/3] Checking InfluxDB on port 8086..." -ForegroundColor Yellow
$influxHealthy = $false
try {
    $res = Invoke-WebRequest -Uri "http://localhost:8086/health" -UseBasicParsing -TimeoutSec 2 -ErrorAction SilentlyContinue
    if ($res.StatusCode -eq 200) {
        $influxHealthy = $true
        Write-Host "  [+] InfluxDB is already running on http://localhost:8086" -ForegroundColor Green
    }
} catch {
    $influxHealthy = $false
}

if (-not $influxHealthy) {
    Write-Host "  [*] Starting local InfluxDB 2.x engine on port 8086..." -ForegroundColor Cyan
    Start-Process python -ArgumentList "`"$projectRoot\da3-influxdb-migration\influx_engine.py`"" -WindowStyle Hidden
    Start-Sleep -Seconds 2
    Write-Host "  [+] InfluxDB 2.x engine active on http://localhost:8086" -ForegroundColor Green
}

# 2. Start Spring Boot in a new window
Write-Host "[2/3] Launching Spring Boot Backend on :8080..." -ForegroundColor Yellow
$backendCmd = "cd `"$projectRoot\backend`"; `$env:DB_PASSWORD='SmartHomeDev123'; `$env:ADMIN_GOOGLE_EMAIL='akhilesh07vaidya@gmail.com'; `$env:GOOGLE_APPLICATION_CREDENTIALS='C:\Users\akhil\OneDrive\Desktop\firebase-secrets\smart-home-service-account.json'; .\mvnw.cmd spring-boot:run"
Start-Process powershell.exe -ArgumentList "-NoExit", "-Command", $backendCmd

# 3. Start Vite Frontend in a new window
Write-Host "[3/3] Launching React / Vite Frontend on :5173..." -ForegroundColor Yellow
$frontendCmd = "cd `"$projectRoot\frontend`"; npm run dev"
Start-Process powershell.exe -ArgumentList "-NoExit", "-Command", $frontendCmd

Write-Host ""
Write-Host "==========================================================" -ForegroundColor Green
Write-Host "  ALL DA3 SERVICES LAUNCHED LOCALLY!                     " -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
Write-Host "  1. React UI & DA3 Hub:  http://localhost:5173" -ForegroundColor Cyan
Write-Host "  2. Backend REST API:    http://localhost:8080/api/influx/health" -ForegroundColor Cyan
Write-Host "  3. InfluxDB Engine:     http://localhost:8086" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Green
