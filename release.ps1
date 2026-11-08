# Builds the release apk and optionally publishes it as a GitHub release (the in-app updater downloads that asset).
#   .\release.ps1                          # build only -> dist\ClusterControl-<version>.apk
#   .\release.ps1 -Publish -Notes "..."    # + git tag v<versionName> + GitHub release
# The apk carries no credentials (they are typed in the app). It is signed with the debug key kept in the
# cc-android-home Docker volume: every build has the same signature, which is what lets Android install a
# release over the installed app.
param(
    [switch]$Publish,
    [string]$Notes = ""
)
$ErrorActionPreference = "Stop"
$proj = $PSScriptRoot
$ver = [regex]::Match((Get-Content "$proj\app\build.gradle.kts" -Raw), 'versionName = "([^"]+)"').Groups[1].Value
$apk = Join-Path $proj "dist\ClusterControl-$ver.apk"

& "$proj\gradle.ps1" :app:assembleDebug
Copy-Item "$proj\dist\clustercontrol-debug.apk" $apk -Force

# a release must never be debuggable (that would give adb / run-as access to the app's data)
$dir = Split-Path $apk
$leaf = Split-Path $apk -Leaf
$debuggable = docker run --rm -v "${dir}:/d:ro" cc-android-build bash -c "/opt/android-sdk/build-tools/35.0.0/aapt2 dump badging /d/$leaf | grep -c application-debuggable"
if ([int]("$debuggable".Trim()) -ne 0) { throw "the apk is debuggable: not publishing" }
Write-Host "not debuggable: ok" -ForegroundColor Green
Write-Host "APK: $apk" -ForegroundColor Green

if ($Publish) {
    $tag = "v$ver"
    git tag -a $tag -m "ClusterControl $ver"
    git push origin $tag
    gh release create $tag $apk --title "ClusterControl $ver" --notes $Notes
}
