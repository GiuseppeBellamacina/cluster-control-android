# Run any Gradle task inside the build container (no local installs).
#   .\gradle.ps1 :core:test
#   .\gradle.ps1 :app:assembleDebug
# Sources are copied into a named volume (/work) because Gradle is very slow on Windows bind mounts.
# After :app:assemble* the APK is copied to dist\clustercontrol-debug.apk.
param([Parameter(ValueFromRemainingArguments)][string[]]$GradleArgs)

$ErrorActionPreference = "Stop"
$image = "cc-android-build"
$proj = $PSScriptRoot
New-Item -ItemType Directory -Force (Join-Path $proj "dist") | Out-Null

docker image inspect $image *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Host "building the build image (first time only, a few minutes)..." -ForegroundColor Cyan
    docker build -f (Join-Path $proj "Dockerfile.build") -t $image $proj
    if ($LASTEXITCODE -ne 0) { throw "docker build failed" }
}

$gradleCmd = "gradle --no-daemon --console=plain " + ($GradleArgs -join " ")
$script = @"
set -e
rsync -a --delete --exclude build --exclude .gradle --exclude .kotlin --exclude dist --exclude .git /src/ /work/
cd /work
$gradleCmd
if [ -f app/build/outputs/apk/debug/app-debug.apk ]; then cp app/build/outputs/apk/debug/app-debug.apk /dist/clustercontrol-debug.apk; fi
"@
$script = $script -replace "`r", ""

docker run --rm `
    -v "${proj}:/src:ro" `
    -v "${proj}\dist:/dist" `
    -v cc-gradle:/gradle-cache `
    -v cc-work:/work `
    $image bash -c $script
if ($LASTEXITCODE -ne 0) { throw "gradle failed (exit $LASTEXITCODE)" }
