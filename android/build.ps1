# Builds and signs android\dist\dsh-remote.apk using the Android SDK's own tools
# (aapt2 / d8 / zipalign / apksigner) plus the JDK's javac and keytool.
#
#   .\bootstrap-sdk.ps1   # once
#   .\build.ps1
#
# No Gradle and no AGP: for a single-activity app this is far fewer moving parts than a
# Gradle project, and it reuses the JDK 11 that is already on this machine.
$ErrorActionPreference = "Stop"

$Root = $PSScriptRoot
$Sdk = if ($env:DSH_ANDROID_SDK) { $env:DSH_ANDROID_SDK } else { Join-Path $Root "android-sdk" }
$App = Join-Path $Root "app"
$Work = Join-Path $Root "build"
$Dist = Join-Path $Root "dist"
$MinSdk = 26
$TargetSdk = 34
$VersionCode = 1
$VersionName = "0.1"

$androidJar = Join-Path $Sdk "platforms\android-$TargetSdk\android.jar"
if (!(Test-Path $androidJar)) { throw "missing $androidJar - run .\bootstrap-sdk.ps1 first" }
$btRoot = Join-Path $Sdk "build-tools"
$bt = (Get-ChildItem $btRoot -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName
$aapt2 = Join-Path $bt "aapt2.exe"
$d8 = Join-Path $bt "d8.bat"
$zipalign = Join-Path $bt "zipalign.exe"
$apksigner = Join-Path $bt "apksigner.bat"
$javac = (Get-Command javac -ErrorAction Stop).Source
$keytool = (Get-Command keytool -ErrorAction Stop).Source

Remove-Item -Recurse -Force $Work -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$Work\gen", "$Work\classes", "$Work\dex", $Dist | Out-Null

Write-Host "[1/6] aapt2 compile"
& $aapt2 compile --dir (Join-Path $App "res") -o "$Work\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "[2/6] aapt2 link"
& $aapt2 link -o "$Work\base.apk" -I $androidJar --manifest (Join-Path $App "AndroidManifest.xml") `
  --java "$Work\gen" --min-sdk-version $MinSdk --target-sdk-version $TargetSdk `
  --version-code $VersionCode --version-name $VersionName "$Work\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "[3/6] javac"
$sources = Get-ChildItem (Join-Path $App "java"), "$Work\gen" -Recurse -Filter *.java | ForEach-Object FullName
# -encoding: the sources are UTF-8, while javac otherwise assumes the platform charset (GBK here)
# and rejects the Chinese UI strings as unmappable.
& $javac -source 8 -target 8 -nowarn -encoding UTF-8 -bootclasspath $androidJar -d "$Work\classes" @sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "[4/6] d8"
$classes = Get-ChildItem "$Work\classes" -Recurse -Filter *.class | ForEach-Object FullName
& $d8 --lib $androidJar --min-api $MinSdk --output "$Work\dex" @classes
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "[5/6] zipalign"
$unsigned = "$Work\unsigned.apk"
Copy-Item "$Work\base.apk" $unsigned
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($unsigned, "Update")
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
  $zip, "$Work\dex\classes.dex", "classes.dex",
  [System.IO.Compression.CompressionLevel]::NoCompression) | Out-Null
$zip.Dispose()
& $zipalign -f -p 4 $unsigned "$Work\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "[6/6] apksigner"
$ks = Join-Path $Root "dsh-debug.keystore"
if (!(Test-Path $ks)) {
  & $keytool -genkeypair -keystore $ks -alias dsh -storepass android -keypass android `
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=DSH Remote"
  if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}
$apk = Join-Path $Dist "dsh-remote.apk"
& $apksigner sign --ks $ks --ks-pass pass:android --key-pass pass:android --out $apk "$Work\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }
& $apksigner verify --print-certs $apk | Select-Object -First 3

Write-Host "built $apk"
Get-Item $apk | Select-Object Length, LastWriteTime
