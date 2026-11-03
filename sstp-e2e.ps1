# ONE real VPN login + ssh to the real cluster through the userspace SSTP tunnel (read-only helper `status`).
# Credentials only via docker --env-file; the ssh key is mounted read-only at /key.
param(
    [string]$EnvFile = "C:\Users\giuse\AppData\Local\Temp\claude\d--Codici-Workspace\6d4c3be6-f987-4c90-98d9-f45a715f6d1c\scratchpad\vpn.env",
    [Parameter(Mandatory)][string]$KeyFile
)
$ErrorActionPreference = "Stop"
$proj = $PSScriptRoot
$key = (Resolve-Path $KeyFile).Path
$script = @"
set -e
rsync -a --delete --exclude build --exclude .gradle --exclude .kotlin --exclude dist --exclude .git /src/ /work/
cd /work
gradle --no-daemon -q --console=plain :sstp:e2e
"@
$script = $script -replace "`r", ""
docker run --rm --env-file $EnvFile -v "${proj}:/src:ro" -v "${key}:/key:ro" -v cc-gradle:/gradle-cache -v cc-work-sstp:/work cc-android-build bash -c $script
if ($LASTEXITCODE -ne 0) { throw "e2e failed (exit $LASTEXITCODE)" }
