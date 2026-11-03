# ONE real VPN login + ssh to the real cluster through the userspace SSTP tunnel (read-only helper `status`).
# -EnvFile: a file with VPN_HOST, VPN_USER, VPN_PASSWORD, T2G_SSH_HOST, T2G_SSH_USER (passed only via docker --env-file)
# Credentials only via docker --env-file; the ssh key is mounted read-only at /key.
param(
    [Parameter(Mandatory)][string]$EnvFile,
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
