# Public release: builds the apk WITHOUT credentials, checks that none leaked into it, optionally publishes it as a
# GitHub release (the in-app updater downloads that asset), then rebuilds YOUR apk (with credentials) for the phone.
#   .\release.ps1                          # build + leak check only
#   .\release.ps1 -Publish -Notes "..."    # + git tag v<versionName> + GitHub release
# The apk is signed with the debug key kept in the cc-android-home Docker volume: every build (yours and the release)
# has the same signature, which is what lets Android install a release over your app.
param(
    [switch]$Publish,
    [string]$Notes = ""
)
$ErrorActionPreference = "Stop"
$proj = $PSScriptRoot
$ver = [regex]::Match((Get-Content "$proj\app\build.gradle.kts" -Raw), 'versionName = "([^"]+)"').Groups[1].Value
$apk = Join-Path $proj "dist\ClusterControl-$ver.apk"

& "$proj\gradle.ps1" :app:assembleDebug -PnoSeed
Copy-Item "$proj\dist\clustercontrol-debug.apk" $apk -Force

# no credential may be inside the public apk (values come from secrets.properties, base64; never printed)
$secrets = Join-Path $proj "secrets.properties"
if (Test-Path $secrets) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $needles = foreach ($line in Get-Content $secrets) {
        $text = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($line.Split('=', 2)[1]))
        ($text -split "`r?`n" | Sort-Object Length -Descending | Select-Object -First 1)  # longest line: a key body, a password...
    }
    $zip = [IO.Compression.ZipFile]::OpenRead($apk)
    try {
        foreach ($entry in $zip.Entries | Where-Object { $_.Name -like "*.dex" }) {
            $ms = New-Object IO.MemoryStream
            $s = $entry.Open(); $s.CopyTo($ms); $s.Dispose()
            $content = [Text.Encoding]::Latin1.GetString($ms.ToArray())
            foreach ($n in $needles | Where-Object { $_ }) {
                if ($content.Contains($n)) { throw "LEAK: a credential is inside $apk ($($entry.Name)); not publishing" }
            }
        }
    }
    finally { $zip.Dispose() }
    Write-Host "leak check passed: no credential in the release apk" -ForegroundColor Green
}

if ($Publish) {
    $tag = "v$ver"
    git tag $tag
    git push origin $tag
    gh release create $tag $apk --title "ClusterControl $ver" --notes $Notes
}

& "$proj\build.ps1"   # your own apk, with credentials, for the phone
