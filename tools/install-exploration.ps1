[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$ClientDirectory,
    [Parameter(Mandatory=$true)][string]$Python,
    [Parameter(Mandatory=$true)][string]$Model
)
$ErrorActionPreference='Stop'
$client=(Resolve-Path -LiteralPath $ClientDirectory).Path
$pythonFile=(Resolve-Path -LiteralPath $Python).Path
$modelFile=(Resolve-Path -LiteralPath $Model).Path
$project=Split-Path -Parent $PSScriptRoot
$jar=Join-Path $project 'numen/fabric/build/libs/numen-fabric-1.21.1-0.1.4.jar'
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw 'Build :numen:fabric:build first.' }
if (Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -and $_.CommandLine.Contains($client) }) {
    throw 'Close this client before updating its mod.'
}
$backup=Join-Path $client ('.numen-backups/explore-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
$mods=Join-Path $client 'mods'; $config=Join-Path $client 'config/numen'
New-Item -ItemType Directory -Force -Path $backup,$mods,$config | Out-Null
$target=Join-Path $mods (Split-Path -Leaf $jar)
$props=Join-Path $config 'exploration.properties'
$prefs=Join-Path $config 'client/ui.json'
foreach ($file in @($target,$props,$prefs)) {
    if (Test-Path -LiteralPath $file -PathType Leaf) { Copy-Item -LiteralPath $file -Destination (Join-Path $backup (Split-Path -Leaf $file)) }
}
$utf8=New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText($props,('python='+$pythonFile.Replace('\','/')+"`nmodel="+$modelFile.Replace('\','/')+"`n"),$utf8)
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $prefs) | Out-Null
$ui=if(Test-Path -LiteralPath $prefs){Get-Content -LiteralPath $prefs -Raw -Encoding UTF8 | ConvertFrom-Json}else{[pscustomobject]@{}}
$ui | Add-Member -MemberType NoteProperty -Name experimentalVisionEnabled -Value $true -Force
[IO.File]::WriteAllText($prefs,($ui | ConvertTo-Json -Depth 30),$utf8)
Copy-Item -LiteralPath $jar -Destination $target -Force
$receipt=[ordered]@{client=$client;jar=$target;jar_sha256=(Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash;
    model=$modelFile;model_sha256=(Get-FileHash -LiteralPath $modelFile -Algorithm SHA256).Hash;python=$pythonFile;backup=$backup}
[IO.File]::WriteAllText((Join-Path $backup 'installation.json'),($receipt | ConvertTo-Json -Depth 4),$utf8)
$receipt | ConvertTo-Json
