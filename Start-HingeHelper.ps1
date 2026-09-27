param([string]$Serial, [string]$AdbPath)
$ErrorActionPreference = 'Stop'
if (-not $AdbPath) {
    $AdbPath = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
    if (-not (Test-Path -LiteralPath $AdbPath)) { $AdbPath = (Get-Command adb -ErrorAction Stop).Source }
}
if (-not $Serial) {
    $connected = @(& $AdbPath devices | ForEach-Object {
        if ($_ -match '^(\S+)\s+device$' -and $Matches[1] -notmatch 'emulator|:|_adb-tls') { $Matches[1] }
    })
    if ($connected.Count -ne 1) { throw 'Connect one authorized USB phone, or supply -Serial.' }
    $Serial = $connected[0]
}
function Invoke-Adb([string[]]$Arguments) {
    & $AdbPath -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB failed ($LASTEXITCODE)" }
}
Invoke-Adb @('shell', 'am', 'start', '-W', '-n', 'dev.duohome.hingelab/.MainActivity')
Invoke-Adb @('push', (Join-Path $PSScriptRoot 'start-shell-helper.sh'), '/data/local/tmp/hingelab-start.sh')
Invoke-Adb @('shell', 'sh', '/data/local/tmp/hingelab-start.sh')
Write-Host 'Look for READY above. Test the shell-helper button in Hinge Lab; then disconnect USB.'
