@echo off
chcp 65001 >nul
title VR Hand Tracker - APK Builder

echo ============================================
echo  VR HAND TRACKER - AUTOMATED APK BUILDER
echo ============================================
echo.

REM Check if running as admin (not required but helpful)
net session >nul 2>&1
if %errorLevel% == 0 (
    echo Running with Administrator privileges
) else (
    echo Running as standard user (OK)
)
echo.

REM Check for Git and GitHub CLI
where git >nul 2>&1
if %errorLevel% neq 0 (
    echo [ERROR] Git not found in PATH
    echo Install: winget install Git.Git
    pause
    exit /b 1
)

where gh >nul 2>&1
if %errorLevel% neq 0 (
    echo [ERROR] GitHub CLI (gh) not found in PATH
    echo Install: winget install GitHub.cli
    echo Then run: gh auth login
    pause
    exit /b 1
)

echo Prerequisites OK
echo.

REM Run PowerShell script
powershell -ExecutionPolicy Bypass -File "%~dp0BuildAPK.ps1" %*

echo.
pause