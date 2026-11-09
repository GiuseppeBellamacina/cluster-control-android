# Integration tests of core against a throwaway sshd + the REAL cluster_helper.sh / chain_tick.sh (fake SLURM).
# Never touches the real cluster. Usage: .\integration.ps1 -ProjectRepo <path to a checkout of the project>
param([Parameter(Mandatory)][string]$ProjectRepo)

$ErrorActionPreference = "Stop"
$proj = $PSScriptRoot
$t2g = (Resolve-Path $ProjectRepo).Path

docker image inspect cc-android-build *> $null
if ($LASTEXITCODE -ne 0) { & "$proj\gradle.ps1" --version | Out-Null }   # builds the base image
docker build -q -f "$proj\Dockerfile.it" -t cc-android-it $proj | Out-Null
if ($LASTEXITCODE -ne 0) { throw "docker build failed" }

docker run --rm `
    -v "${proj}:/src:ro" `
    -v "${t2g}:/t2g:ro" `
    -v cc-gradle:/gradle-cache `
    -v cc-work-it:/work `
    cc-android-it bash /entry.sh
if ($LASTEXITCODE -ne 0) { throw "integration tests failed (exit $LASTEXITCODE)" }
