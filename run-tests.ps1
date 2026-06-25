#!/usr/bin/env pwsh
# run-tests.ps1 — Windows-версия run-tests.sh для локальной разработки и CI

param (
    [Parameter(Mandatory = $false)]
    [ValidateSet("all", "unit", "it")]
    [string]$Mode = "all"
)

Write-Host "Running tests in mode: $Mode" -ForegroundColor Cyan

switch ($Mode) {
    "all" {
        Write-Host "Running UNIT + INTEGRATION tests (mvn verify)..."
        ./mvnw -B verify
    }
    "unit" {
        Write-Host "Running UNIT tests only (mvn test)..."
        ./mvnw -B test
    }
    "it" {
        Write-Host "Running INTEGRATION tests only (mvn failsafe:integration-test)..."
        & ./mvnw -B -DskipTests=true failsafe:integration-test failsafe:verify
    }
}

if ($LASTEXITCODE -ne 0) {
    Write-Error "Tests failed in mode: $Mode" -ErrorAction Stop
}

Write-Host "Tests finished successfully in mode: $Mode" -ForegroundColor Green