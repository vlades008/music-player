# Builds Library.apk without Gradle: aapt2 + javac + d8 + zipalign + apksigner.
# Needs the JDK and Android SDK already in C:\Users\vlade\AndroidBuild (from the pig-speed build).
#
#   powershell -File build-apk.ps1                  # build what the manifest says
#   powershell -File build-apk.ps1 -Version 1.1 -Code 2 -Notes "what changed"
#
# Writes android\out\Library.apk and android\out\latest.json (for the in-app update check).
param(
  [string]$Version = "",
  [int]$Code = 0,
  [string]$Notes = ""
)
$ErrorActionPreference = 'Stop'
$here  = $PSScriptRoot
$tools = 'C:\Users\vlade\AndroidBuild'
$jdk   = (Get-ChildItem "$tools\jdk" -Directory | Select-Object -First 1).FullName
$sdk   = "$tools\sdk"
$bt    = (Get-ChildItem "$sdk\build-tools" -Directory | Sort-Object Name | Select-Object -Last 1).FullName
$jar   = (Get-ChildItem "$sdk\platforms" -Directory | Sort-Object Name | Select-Object -Last 1).FullName + '\android.jar'
$env:JAVA_HOME = $jdk
$manifest = Join-Path $here 'AndroidManifest.xml'

# Version: the manifest is the source of truth; -Version/-Code rewrite it.
$mf = Get-Content $manifest -Raw
if ($Version) { $mf = $mf -replace 'android:versionName="[^"]*"', "android:versionName=`"$Version`"" }
if ($Code -gt 0) { $mf = $mf -replace 'android:versionCode="\d+"', "android:versionCode=`"$Code`"" }
[System.IO.File]::WriteAllText($manifest, $mf, (New-Object System.Text.UTF8Encoding($false)))
$vName = ([regex]::Match($mf, 'android:versionName="([^"]*)"')).Groups[1].Value
$vCode = [int]([regex]::Match($mf, 'android:versionCode="(\d+)"')).Groups[1].Value

$out  = Join-Path $here 'out'
$work = Join-Path $tools 'work-music'
Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$work\compiled","$work\classes","$work\gen",$out | Out-Null

Write-Host "building Library $vName ($vCode)"

& "$bt\aapt2.exe" compile --dir (Join-Path $here 'res') -o "$work\compiled"
if ($LASTEXITCODE) { throw 'aapt2 compile failed' }
$flat = Get-ChildItem "$work\compiled" -Recurse -Filter *.flat | ForEach-Object { $_.FullName }

& "$bt\aapt2.exe" link -o "$work\base.apk" -I $jar --manifest $manifest `
  --min-sdk-version 29 --target-sdk-version 34 --auto-add-overlay --java "$work\gen" $flat
if ($LASTEXITCODE) { throw 'aapt2 link failed' }

$java = @(Get-ChildItem (Join-Path $here 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$java += @(Get-ChildItem "$work\gen" -Recurse -Filter R.java | ForEach-Object { $_.FullName })
& "$jdk\bin\javac.exe" -source 8 -target 8 -nowarn -classpath $jar -d "$work\classes" $java
if ($LASTEXITCODE) { throw 'javac failed' }

$cls = Get-ChildItem "$work\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& "$bt\d8.bat" --min-api 29 --lib $jar --output $work $cls
if ($LASTEXITCODE) { throw 'd8 failed' }

Push-Location $work
& "$jdk\bin\jar.exe" uf base.apk classes.dex
if ($LASTEXITCODE) { Pop-Location; throw 'jar failed' }
Pop-Location

& "$bt\zipalign.exe" -f -p 4 "$work\base.apk" "$work\aligned.apk"
if ($LASTEXITCODE) { throw 'zipalign failed' }

$ks = "$tools\music.keystore"
if (-not (Test-Path $ks)) {
  & "$jdk\bin\keytool.exe" -genkeypair -keystore $ks -alias library -storepass library2026 `
    -keypass library2026 -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Library, O=Vlade"
}
$apk = Join-Path $out 'Library.apk'
& "$bt\apksigner.bat" sign --ks $ks --ks-key-alias library --ks-pass pass:library2026 `
  --key-pass pass:library2026 --out $apk "$work\aligned.apk"
if ($LASTEXITCODE) { throw 'apksigner failed' }

# What the app reads to notice an update.
$latest = [ordered]@{
  versionCode = $vCode
  versionName = $vName
  apk         = 'Library.apk'
  notes       = $Notes
  built       = (Get-Date).ToString('yyyy-MM-dd HH:mm')
}
[System.IO.File]::WriteAllText((Join-Path $out 'latest.json'),
  ($latest | ConvertTo-Json), (New-Object System.Text.UTF8Encoding($false)))

& "$bt\apksigner.bat" verify --print-certs $apk | Select-Object -First 2
Get-Item $apk | Select-Object Name, @{n = 'MB'; e = { [math]::Round($_.Length / 1MB, 1) } }, LastWriteTime
