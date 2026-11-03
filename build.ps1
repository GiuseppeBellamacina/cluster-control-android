# Build the debug APK in Docker -> dist\clustercontrol-debug.apk
$ErrorActionPreference = "Stop"
& "$PSScriptRoot\gradle.ps1" :app:assembleDebug
$apk = Join-Path $PSScriptRoot "dist\clustercontrol-debug.apk"
if (-not (Test-Path $apk)) { throw "APK not produced" }
Write-Host ("APK: {0} ({1:N1} MB)" -f $apk, ((Get-Item $apk).Length / 1MB)) -ForegroundColor Green
