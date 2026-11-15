# Rebuild, stage and push a new Library build.
#
#   powershell -File publish.ps1 -Version 1.1 -Code 2 -Notes "background playback fixed"
#
# The repository is what the phone reads: Library.apk + latest.json. Changing the
# web player does NOT need this - only the container does.
param(
  [Parameter(Mandatory = $true)][string]$Version,
  [Parameter(Mandatory = $true)][int]$Code,
  [string]$Notes = ""
)
$ErrorActionPreference = 'Stop'
$repo    = $PSScriptRoot
$android = Join-Path (Split-Path $repo -Parent) 'music-resolver\android'

& (Join-Path $android 'build-apk.ps1') -Version $Version -Code $Code -Notes $Notes
if ($LASTEXITCODE) { throw 'build failed' }

Copy-Item (Join-Path $android 'out\Library.apk')  $repo -Force
Copy-Item (Join-Path $android 'out\latest.json')  $repo -Force
if (Test-Path (Join-Path $repo 'src')) { Remove-Item (Join-Path $repo 'src') -Recurse -Force }
Copy-Item (Join-Path $android 'src') (Join-Path $repo 'src') -Recurse -Force
Copy-Item (Join-Path $android 'build-apk.ps1') $repo -Force

Push-Location $repo
git add -A
git commit -m "Library $Version ($Code)${Notes:+ - $Notes}"
git push
Pop-Location

Write-Host "pushed $Version ($Code) - the phone will offer it on next launch"
