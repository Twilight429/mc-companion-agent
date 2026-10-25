param(
    [ValidateSet('Build','Unit','PlayerModel','E2E')]
    [string]$Mode = 'Build'
)
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java.exe' }
if (-not (Get-Command $java -ErrorAction SilentlyContinue)) { throw 'Install JDK 21 and set JAVA_HOME first.' }
$version = (& $java --version | Out-String)
if ($LASTEXITCODE -ne 0 -or $version -notmatch '(?m)^\s*(?:openjdk|java) 21(?:[.\s+-]|$)') { throw 'This project requires JDK 21; check JAVA_HOME and the IDE Gradle JVM.' }
function Invoke-TeamGradle([string[]]$Tasks) {
    & (Join-Path $projectDirectory 'gradlew.bat') @Tasks --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw ('Gradle failed: ' + ($Tasks -join ' ')) }
}
Push-Location $projectDirectory
try {
    switch ($Mode) {
        'Build' {
            Invoke-TeamGradle @(':numen-api:fabric:runDatagen')
            Invoke-TeamGradle @(':numen:fabric:runDatagen')
            Invoke-TeamGradle @(':numen:fabric:build')
        }
        'Unit' {
            Invoke-TeamGradle @(':ai:test',':script:test',':agent:test',':numen-api:common:test',':numen:common:test',':ui:test',':plugins:sceneadapt:test',':plugins:sceneadapt:playerModelTest',':plugins:ysm:test')
        }
        'PlayerModel' { Invoke-TeamGradle @(':plugins:sceneadapt:playerModelTest') }
        'E2E' { Invoke-TeamGradle @(':e2e:e2eTest','-Pe2eServer=fabric') }
    }
} finally { Pop-Location }
