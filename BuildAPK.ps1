<# 
.SYNOPSIS
VR Hand Tracker - Automated APK Builder
Builds APK via GitHub Actions automatically, downloads when ready.

.REQUIREMENTS
- Windows 10/11
- Git (winget install Git.Git)
- GitHub CLI (winget install GitHub.cli) -> run 'gh auth login' once
- Internet connection

.USAGE
.\BuildAPK.ps1
# Or right-click -> "Run with PowerShell"
#>

param(
    [string]$GitHubUsername = "",
    [string]$RepoName = "VRHandTracker",
    [switch]$AutoInstallTools,
    [switch]$NoWait
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

Write-Host "==================================================" -ForegroundColor Cyan
Write-Host "   VR HAND TRACKER - AUTOMATED APK BUILDER" -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan
Write-Host ""

# ============================================================
# STEP 0: Check/Install Prerequisites
# ============================================================
function Check-Prerequisites {
    Write-Host "[0/5] Checking prerequisites..." -ForegroundColor Yellow
    
    $missing = @()
    
    if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
        $missing += "Git"
    }
    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
        $missing += "GitHub CLI (gh)"
    }
    
    if ($missing.Count -gt 0) {
        Write-Host "Missing: $($missing -join ', ')" -ForegroundColor Red
        if ($AutoInstallTools) {
            Write-Host "Installing via winget..." -ForegroundColor Yellow
            if ($missing -contains "Git") { winget install --id Git.Git -e --accept-source-agreements --accept-package-agreements }
            if ($missing -contains "GitHub CLI (gh)") { winget install --id GitHub.cli -e --accept-source-agreements --accept-package-agreements }
            $env:PATH = [System.Environment]::GetEnvironmentVariable("PATH","Machine") + ";" + [System.Environment]::GetEnvironmentVariable("PATH","User")
            Write-Host "Restart PowerShell and run again" -ForegroundColor Red
            exit 1
        } else {
            Write-Host "Run with -AutoInstallTools or install manually:" -ForegroundColor Yellow
            Write-Host "  winget install Git.Git GitHub.cli" -ForegroundColor Gray
            Write-Host "  Then run: gh auth login" -ForegroundColor Gray
            exit 1
        }
    }
    
    # Check gh auth
    $auth = gh auth status 2>&1
    if ($auth -notmatch "Logged in") {
        Write-Host "GitHub CLI not authenticated. Run: gh auth login" -ForegroundColor Red
        exit 1
    }
    
    Write-Host "Prerequisites OK" -ForegroundColor Green
}

# ============================================================
# STEP 1: Prepare Project
# ============================================================
function Prepare-Project {
    Write-Host "[1/5] Preparing project..." -ForegroundColor Yellow
    
    $projectDir = "C:\VRHandTrackerBuild"
    if (Test-Path $projectDir) {
        Remove-Item $projectDir -Recurse -Force
    }
    
    # Extract from embedded ZIP (or copy from temp)
    $zipPath = "C:\tmp\VRHandTrackerNative.zip"
    if (-not (Test-Path $zipPath)) {
        Write-Host "Project ZIP not found at $zipPath" -ForegroundColor Red
        Write-Host "Run the setup script first to create it" -ForegroundColor Yellow
        exit 1
    }
    
    Expand-Archive $zipPath -DestinationPath $projectDir -Force
    Set-Location $projectDir
    
    Write-Host "Project ready at $projectDir" -ForegroundColor Green
    return $projectDir
}

# ============================================================
# STEP 2: Create/Configure GitHub Repo
# ============================================================
function Setup-GitHubRepo {
    param($projectDir, $repoName)
    Write-Host "[2/5] Setting up GitHub repository..." -ForegroundColor Yellow
    
    Set-Location $projectDir
    
    # Initialize git
    git init
    git config user.email "builder@vrhandtracker.local"
    git config user.name "VRHandTracker Builder"
    git add .
    git commit -m "VR Hand Tracker - MediaPipe + Cardboard"
    
    # Get GitHub username if not provided
    if (-not $GitHubUsername) {
        $GitHubUsername = (gh api user --jq .login)
    }
    
    Write-Host "GitHub user: $GitHubUsername" -ForegroundColor Cyan
    
    # Check if repo exists
    $repoExists = gh repo view "$GitHubUsername/$repoName" 2>$null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "Repo exists, pushing updates..." -ForegroundColor Yellow
        git remote add origin "https://github.com/$GitHubUsername/$repoName.git" 2>$null
        git push -u origin main --force
    } else {
        Write-Host "Creating new repository..." -ForegroundColor Yellow
        gh repo create "$repoName" --public --source=. --push --description "VR Hand Tracker for Samsung + Cardboard using MediaPipe"
    }
    
    $repoUrl = "https://github.com/$GitHubUsername/$repoName"
    Write-Host "Repository: $repoUrl" -ForegroundColor Green
    return @{ Url = $repoUrl; Owner = $GitHubUsername; Name = $repoName }
}

# ============================================================
# STEP 3: Trigger Build
# ============================================================
function Trigger-Build {
    param($repoInfo)
    Write-Host "[3/5] Triggering GitHub Actions build..." -ForegroundColor Yellow
    
    # Trigger workflow_dispatch
    gh workflow run "build.yml" --repo "$($repoInfo.Owner)/$($repoInfo.Name)"
    
    Write-Host "Build triggered! Watch at: $($repoInfo.Url)/actions" -ForegroundColor Cyan
    
    if ($NoWait) {
        Write-Host "Skipping wait (use -NoWait). Check Actions tab manually." -ForegroundColor Yellow
        return $null
    }
    
    return $repoInfo
}

# ============================================================
# STEP 4: Wait for Build & Download
# ============================================================
function Wait-And-Download {
    param($repoInfo)
    Write-Host "[4/5] Waiting for build to complete..." -ForegroundColor Yellow
    
    $maxWait = 300  # 5 minutes
    $interval = 15
    $elapsed = 0
    $runId = $null
    
    while ($elapsed -lt $maxWait) {
        Start-Sleep -Seconds $interval
        $elapsed += $interval
        
        # Get latest workflow run
        $runs = gh run list --repo "$($repoInfo.Owner)/$($repoInfo.Name)" --workflow=build.yml --limit=1 --json databaseId,status,conclusion,url
        if ($runs) {
            $run = $runs | ConvertFrom-Json
            $runId = $run.databaseId
            $status = $run.status
            $conclusion = $run.conclusion
            
            Write-Host "  Status: $status" -NoNewline
            if ($conclusion) { Write-Host " ($conclusion)" -NoNewline }
            Write-Host ""
            
            if ($status -eq "completed") {
                if ($conclusion -eq "success") {
                    Write-Host "Build succeeded!" -ForegroundColor Green
                    break
                } else {
                    Write-Host "Build failed: $conclusion" -ForegroundColor Red
                    Write-Host "View logs: $($repoInfo.Url)/actions/runs/$runId" -ForegroundColor Yellow
                    exit 1
                }
            }
        }
    }
    
    if ($elapsed -ge $maxWait) {
        Write-Host "Timeout waiting for build" -ForegroundColor Red
        exit 1
    }
    
    # Download artifact
    Write-Host "[5/5] Downloading APK..." -ForegroundColor Yellow
    
    $artifactDir = "$env:USERPROFILE\Downloads\VRHandTracker_APK_$(Get-Date -Format 'yyyyMMdd_HHmmss')"
    New-Item -ItemType Directory -Path $artifactDir | Out-Null
    
    gh run download $runId --repo "$($repoInfo.Owner)/$($repoInfo.Name)" --dir $artifactDir
    
    $apkPath = Get-ChildItem $artifactDir -Recurse -Filter "*.apk" | Select-Object -First 1
    if ($apkPath) {
        Write-Host "" -ForegroundColor Green
        Write-Host "==================================================" -ForegroundColor Green
        Write-Host "  BUILD COMPLETE - APK READY!" -ForegroundColor Green
        Write-Host "==================================================" -ForegroundColor Green
        Write-Host "APK location: $($apkPath.FullName)" -ForegroundColor Cyan
        Write-Host "Size: $([math]::Round($apkPath.Length/1MB, 1)) MB" -ForegroundColor Cyan
        Write-Host ""
        Write-Host "To install on Samsung:" -ForegroundColor Yellow
        Write-Host "  adb install `"$($apkPath.FullName)`"" -ForegroundColor Gray
        Write-Host ""
        Write-Host "Or copy to phone and tap to install" -ForegroundColor Gray
        
        # Open folder
        explorer $artifactDir
    } else {
        Write-Host "APK not found in artifacts" -ForegroundColor Red
        exit 1
    }
}

# ============================================================
# MAIN
# ============================================================
try {
    Check-Prerequisites
    $projectDir = Prepare-Project
    $repoInfo = Setup-GitHubRepo -projectDir $projectDir -repoName $RepoName
    $repoInfo = Trigger-Build -repoInfo $repoInfo
    if ($repoInfo) { Wait-And-Download -repoInfo $repoInfo }
}
catch {
    Write-Host "ERROR: $_" -ForegroundColor Red
    Write-Host $_.ScriptStackTrace -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "Done!" -ForegroundColor Green