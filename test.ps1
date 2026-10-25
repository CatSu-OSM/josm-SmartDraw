param([string]$JosmJar = (Join-Path $env:USERPROFILE 'AppData\Local\JOSM\app\josm-custom.jar'))
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'build.ps1') -JosmJar $JosmJar
if ($LASTEXITCODE -ne 0) { throw 'build failed' }
$testClasses = Join-Path $PSScriptRoot 'build\test-classes'
New-Item -ItemType Directory -Force -Path $testClasses | Out-Null
& javac -encoding UTF-8 --release 17 -cp "$JosmJar;$(Join-Path $PSScriptRoot 'build\classes')" -d $testClasses (Join-Path $PSScriptRoot 'tests\SmartDrawCoreTest.java')
if ($LASTEXITCODE -ne 0) { throw 'test compilation failed' }
& java -cp "$JosmJar;$(Join-Path $PSScriptRoot 'build\classes');$testClasses" SmartDrawCoreTest (Join-Path $PSScriptRoot 'dist\smartdraw.jar')
if ($LASTEXITCODE -ne 0) { throw 'tests failed' }
