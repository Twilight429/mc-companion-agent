[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)]
    [string]$LauncherPath,
    [ValidateRange(1024,65535)]
    [int]$Port = 8766
)
$ErrorActionPreference = 'Stop'
$launcher = (Resolve-Path -LiteralPath $LauncherPath).Path
if (Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $launcher }) {
    throw 'Close the existing launcher first so the new game inherits the camera session settings.'
}
if ($env:JAVA_TOOL_OPTIONS -match 'numen.camera.port') {
    throw 'JAVA_TOOL_OPTIONS already sets numen.camera.port; use a fresh PowerShell session.'
}
# 只给当前终端及子进程设置,不修改用户或系统环境变量,不把令牌写进存档。
$env:NUMEN_CAMERA_TOKEN = [Guid]::NewGuid().ToString('N')
$env:JAVA_TOOL_OPTIONS = ($env:JAVA_TOOL_OPTIONS + " -Dnumen.camera.port=$Port").Trim()
Start-Process -FilePath $launcher -WindowStyle Normal
Write-Host "Camera session prepared on 127.0.0.1:$Port. Start your existing Fabric instance."
Write-Host 'In Numen Settings > Theme, explicitly enable Visual experiments when you want to test vision.'
Write-Host 'Keep this terminal open: run npc_surface_observer.py --capture-only here after entering the world.'
