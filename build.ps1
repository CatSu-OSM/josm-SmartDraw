param([string]$JosmJar = (Join-Path $env:USERPROFILE 'AppData\Local\JOSM\app\josm-custom.jar'))
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $JosmJar)) { throw "JOSM jar not found: $JosmJar" }
$root = $PSScriptRoot
$classes = Join-Path $root 'build\classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources = @(Get-ChildItem (Join-Path $root 'src') -Recurse -Filter '*.java' | ForEach-Object FullName)
& javac -encoding UTF-8 --release 17 -cp $JosmJar -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
New-Item -ItemType Directory -Force -Path (Join-Path $root 'dist') | Out-Null
$jarTemp = Join-Path $root 'build\tmp'
New-Item -ItemType Directory -Force -Path $jarTemp | Out-Null
$env:TEMP = $jarTemp
$env:TMP = $jarTemp
& jar cfm (Join-Path $root 'dist\smartdraw.jar') (Join-Path $root 'MANIFEST.MF') -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }
Write-Host (Join-Path $root 'dist\smartdraw.jar')
