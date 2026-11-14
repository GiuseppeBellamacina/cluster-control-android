# Builds the release apk and optionally publishes it as a GitHub release (the in-app updater downloads that asset).
#   .\release.ps1                          # build only -> dist\ClusterControl-<version>.apk
#   .\release.ps1 -Publish -Notes "..."    # + git tag v<versionName> + GitHub release
# The apk carries no credentials (they are typed in the app). It is signed with the debug key kept in the
# cc-android-home Docker volume: every build has the same signature, which is what lets Android install a
# release over the installed app.
# Publishing refuses an apk signed with another key than the last release: Android would not install the update.
# Keep a copy of the key: %USERPROFILE%\.clustercontrol\debug.keystore (taken from the cc-android-home volume).
param(
    [switch]$Publish,
    [string]$Notes = "",
    [switch]$NewKey   # publish even if the signing key differs from the last release (everyone must reinstall)
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

# An apk signed with another key than the installed one is refused by Android ("app not installed") and the in-app
# updater cannot work. The key lives in the cc-android-home Docker volume: if the volume was recreated, the key is new.
function Get-SignerDigest([string]$file) {
    $d = Split-Path $file
    $l = Split-Path $file -Leaf
    $out = docker run --rm -v "${d}:/d:ro" cc-android-build bash -c "/opt/android-sdk/build-tools/35.0.0/apksigner verify --print-certs /d/$l | grep 'SHA-256'"
    return ("$out".Trim() -split ':')[-1].Trim()
}

if ($Publish -and -not $NewKey) {
    $prev = Join-Path ([IO.Path]::GetTempPath()) "cc-prev-release"
    Remove-Item $prev -Recurse -Force -ErrorAction SilentlyContinue
    gh release download --pattern "*.apk" --dir $prev 2>$null
    $old = Get-ChildItem $prev -Filter *.apk -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($old) {
        $was = Get-SignerDigest $old.FullName
        $now = Get-SignerDigest $apk
        if ($was -ne $now) {
            throw "the apk is signed with a different key than the last release ($($old.Name)): Android would refuse to update the installed app. Restore the old keystore into the cc-android-home volume (a copy should be in %USERPROFILE%\.clustercontrol\debug.keystore), or pass -NewKey to publish anyway (everyone must reinstall)."
        }
        Write-Host "same signing key as $($old.Name): ok" -ForegroundColor Green
    }
}

if ($Publish) {
    $tag = "v$ver"
    git tag -a $tag -m "ClusterControl $ver"
    git push origin $tag
    gh release create $tag $apk --title "ClusterControl $ver" --notes $Notes
}
