# Runs the SSTP lab CLI in the build container (no local installs). Credentials only via docker --env-file.
#   .\sstp-lab.ps1 -LabArgs "--no-auth"          # stops before sending credentials
#   .\sstp-lab.ps1 -LabArgs "--target 151.97.253.110:22"   # ONE real authentication attempt
param(
    [string]$EnvFile = "C:\Users\giuse\AppData\Local\Temp\claude\d--Codici-Workspace\6d4c3be6-f987-4c90-98d9-f45a715f6d1c\scratchpad\vpn.env",
    [string]$LabArgs = ""
)
$ErrorActionPreference = "Stop"
$proj = $PSScriptRoot
$script = @"
set -e
rsync -a --delete --exclude build --exclude .gradle --exclude .kotlin --exclude dist --exclude .git /src/ /work/
cd /work
gradle --no-daemon -q --console=plain :sstp:run --args='$LabArgs'
"@
$script = $script -replace "`r", ""
docker run --rm --env-file $EnvFile -v "${proj}:/src:ro" -v cc-gradle:/gradle-cache -v cc-work-sstp:/work cc-android-build bash -c $script
if ($LASTEXITCODE -ne 0) { throw "lab run failed (exit $LASTEXITCODE)" }
