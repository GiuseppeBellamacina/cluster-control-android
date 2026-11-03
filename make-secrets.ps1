# Writes secrets.properties (gitignored) with the credentials to bake into YOUR personal APK.
# Values are base64 in the file and are never printed. The APK then contains them in clear: do not share it.
param(
    [string]$EnvFile = "D:\Codici\Workspace\render-multi-service-manager\.env",
    [string]$KeyFile
)
$ErrorActionPreference = "Stop"
$envs = @{}
foreach ($line in Get-Content $EnvFile) {
    $t = $line.Trim()
    if ($t -and -not $t.StartsWith("#")) {
        $k, $v = $t -split '=', 2
        if ($k -and $null -ne $v) { $envs[$k.Trim()] = $v.Trim().Trim('"') }
    }
}
if (-not $KeyFile) { $KeyFile = $envs["T2G_SSH_KEY_FILE"] }
function B64($s) { [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($s)) }

$map = [ordered]@{
    vpnHost     = $envs["VPN_HOST"]
    vpnUser     = $envs["VPN_USER"]
    vpnPassword = $envs["VPN_PASSWORD"]
    sshHost     = $envs["T2G_SSH_HOST"]
    sshUser     = $envs["T2G_SSH_USER"]
    sshKey      = (Get-Content $KeyFile -Raw)
}
$missing = $map.Keys | Where-Object { -not $map[$_] }
if ($missing) { throw "missing values: $($missing -join ', ')" }
$out = ($map.Keys | ForEach-Object { "$_=$(B64 $map[$_])" }) -join "`n"
Set-Content -Path (Join-Path $PSScriptRoot "secrets.properties") -Value $out -Encoding ascii
Write-Host "secrets.properties written: $($map.Keys -join ', ')" -ForegroundColor Green
