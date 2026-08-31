@echo off
chcp 65001 >nul
echo ==========================================
echo VR Hand Tracker - Upload to GitHub for Auto APK Build
echo ==========================================
echo.
echo This script will:
echo 1. Initialize git repository
echo 2. Commit all files
echo 3. Create GitHub repo (requires GitHub CLI: gh auth login)
echo 4. Push code - GitHub Actions will build APK automatically
echo.
echo Prerequisites:
echo - Install GitHub CLI: winget install GitHub.cli
echo - Run: gh auth login
echo.
pause

echo Initializing git...
git init
git add .
git commit -m "Initial commit: VR Hand Tracker with MediaPipe + Cardboard"

echo Creating GitHub repository...
gh repo create VRHandTracker --public --source=. --push --description "VR Hand Tracker for Samsung + Cardboard using MediaPipe"

echo.
echo ==========================================
echo DONE! 
echo ==========================================
echo.
echo 1. Go to: https://github.com/%USERNAME%/VRHandTracker/actions
echo 2. Wait for "Build Debug APK" workflow to complete (2-3 min)
echo 3. Click the workflow run -> Download "VRHandTracker-APK" artifact
echo 4. Extract zip -> app-debug.apk
echo 5. Install on Samsung: adb install app-debug.apk
echo.
pause