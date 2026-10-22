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
Write-Host "[2/5] Launching Spring Boot Backend on :8080..." -ForegroundColor Yellow
$backendCmd = "cd `"$projectRoot\backend`"; `$env:DB_PASSWORD='SmartHomeDev123'; `$env:ADMIN_GOOGLE_EMAIL='akhilesh07vaidya@gmail.com'; `$env:GOOGLE_APPLICATION_CREDENTIALS='C:\Users\akhil\OneDrive\Desktop\firebase-secrets\smart-home-service-account.json'; .\mvnw.cmd spring-boot:run"
Start-Process powershell.exe -ArgumentList "-NoExit", "-Command", $backendCmd

# 3. Start Website 1: Oracle Smart Home Frontend (:5173)
Write-Host "[3/5] Launching Website 1 (Oracle Smart Home) on :5173..." -ForegroundColor Yellow
$frontendCmd = "cd `"$projectRoot\frontend`"; npm run dev"
Start-Process powershell.exe -ArgumentList "-NoExit", "-Command", $frontendCmd

# 4. Start Website 2: InfluxDB Smart Home Frontend (:5174)
Write-Host "[4/5] Launching Website 2 (InfluxDB Smart Home) on :5174..." -ForegroundColor Yellow
$frontendInfluxCmd = "cd `"$projectRoot\frontend-influx`"; npm run dev"
Start-Process powershell.exe -ArgumentList "-NoExit", "-Command", $frontendInfluxCmd

# 5. Start Website 3: Oracle vs InfluxDB Benchmark Frontend (:5175)
Write-Host "[5/5] Launching Website 3 (Oracle vs InfluxDB Benchmark) on :5175..." -ForegroundColor Yellow
$frontendCompareCmd = "cd `"$projectRoot\frontend-compare`"; npm run dev"
Start-Process powershell.exe -ArgumentList "-NoExit", "-Command", $frontendCompareCmd

Write-Host ""
Write-Host "==========================================================" -ForegroundColor Green
Write-Host "  ALL DA3 SERVICES LAUNCHED LOCALLY!                     " -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
Write-Host "  1. Oracle Smart Home:       http://localhost:5173" -ForegroundColor Cyan
Write-Host "  2. InfluxDB Smart Home:     http://localhost:5174" -ForegroundColor Cyan
Write-Host "  3. Comparison & Benchmark:  http://localhost:5175" -ForegroundColor Cyan
Write-Host "  4. Backend REST API:        http://localhost:8080/api/influx/health" -ForegroundColor Cyan
Write-Host "  5. InfluxDB Engine:         http://localhost:8086" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Green
