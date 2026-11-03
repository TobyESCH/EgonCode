$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
New-Item -ItemType Directory -Force out | Out-Null
$sources = Get-ChildItem -Recurse src/main/java -Filter *.java | ForEach-Object { $_.FullName }
& javac -d out @sources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
if (Test-Path src/main/resources) { Copy-Item -Path src/main/resources/* -Destination out -Recurse -Force }
& jar --create --file out/egoncode.jar --main-class dev.egoncode.Main -C out dev -C out bunny-idle.gif -C out bunny-run.gif -C out bunny-sleep.gif
exit $LASTEXITCODE
