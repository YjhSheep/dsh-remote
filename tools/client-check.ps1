# client-check.ps1 -- run the app's DshClient on the PC against the real bridge.
#
# DshClient uses only okhttp + org.json + java.*, with no android.* dependency, so it
# compiles and runs unchanged on a desktop JVM. That lets us verify the protocol layer
# (gate cookie, session/list fields, frame shapes, argument shapes) without a phone.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\client-check.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\client-check.ps1 <host> <port> [key]
#
# Without a key it reads tools\.bridge-key. Default target: 192.168.31.216:3080.
# NOTE: keep this file ASCII only -- Windows PowerShell 5.1 reads BOM-less UTF-8 as GBK.
param(
  [string]$Srv = "192.168.31.216",
  [int]$Port = 3080,
  [string]$Key = ""
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot

if (-not $Key) {
  $keyFile = Join-Path $Root "tools\.bridge-key"
  if (-not (Test-Path $keyFile)) { throw "no tools\.bridge-key -- pass the gate key as an argument" }
  $Key = (Get-Content $keyFile -Raw).Trim()
}

$cache = Join-Path $Root "android\.gradle-home\caches\modules-2\files-2.1"

function Find-Jar([string]$sub, [string]$pattern) {
  $hit = Get-ChildItem (Join-Path $cache $sub) -Recurse -Filter $pattern -ErrorAction SilentlyContinue |
    Select-Object -First 1
  if (-not $hit) { throw "not in gradle cache: $pattern -- run android\gradle-build.ps1 once first" }
  return $hit.FullName
}

$okhttp = Find-Jar "com.squareup.okhttp3\okhttp" "okhttp-*.jar"
$okio = Find-Jar "com.squareup.okio\okio-jvm" "okio-jvm-*.jar"
$kotlin = Find-Jar "org.jetbrains.kotlin\kotlin-stdlib" "kotlin-stdlib-*.jar"

$json = Join-Path $Root "tools\client-check\json-20231013.jar"
if (-not (Test-Path $json)) {
  Write-Host "downloading org.json ..."
  & curl.exe -sSL -o $json "https://maven.aliyun.com/repository/public/org/json/json/20231013/json-20231013.jar"
  if ($LASTEXITCODE -ne 0) { throw "org.json download failed (or place it yourself at $json)" }
}

$cp = "$okhttp;$okio;$kotlin;$json"
$out = Join-Path $Root "android\.tmp\client-check"
New-Item -ItemType Directory -Force $out | Out-Null

$javac = Join-Path $Root "android\tools\jdk17\bin\javac.exe"
$java = Join-Path $Root "android\tools\jdk17\bin\java.exe"
if (-not (Test-Path $javac)) { throw "missing android\tools\jdk17 (see android\gradle-build.ps1)" }

& $javac -encoding UTF-8 -nowarn -cp $cp -d $out `
  (Join-Path $Root "android\app\src\main\java\app\dsh\remote\DshClient.java") `
  (Join-Path $PSScriptRoot "client-check\It.java")
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

& $java "-Dfile.encoding=UTF-8" -cp "$out;$cp" It $Srv $Port $Key
exit $LASTEXITCODE