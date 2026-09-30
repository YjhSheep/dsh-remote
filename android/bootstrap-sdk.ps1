# Downloads the Android SDK pieces this app needs. No Android Studio, no Gradle, no sdkmanager.
#
#   .\bootstrap-sdk.ps1        # ~121 MB, lands in .\android-sdk
#
# Why not the official command-line tools: every cmdline-tools release that still knows about
# api 34 needs JDK 17, and this machine has JDK 11 - the JDK that gives us javac and keytool.
# The two artifacts below are plain zips whose URLs were verified to answer 200, they need no
# license prompt and no particular JDK, so they are strictly fewer moving parts.
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$Root = $PSScriptRoot
$Sdk = if ($env:DSH_ANDROID_SDK) { $env:DSH_ANDROID_SDK } else { Join-Path $Root "android-sdk" }
$Dl = Join-Path $Root "dl"
$Api = 34
$Repo = "https://dl.google.com/android/repository"

$Artifacts = @(
  @{
    Label = "build-tools;34.0.0"
    File  = "build-tools_r$Api-windows.zip"
    Probe = "aapt2.exe"
    Dest  = Join-Path $Sdk "build-tools\34.0.0"
  },
  @{
    Label = "platforms;android-$Api"
    File  = "platform-$Api-ext7_r03.zip"
    Probe = "android.jar"
    Dest  = Join-Path $Sdk "platforms\android-$Api"
  }
)

New-Item -ItemType Directory -Force -Path $Dl, $Sdk | Out-Null

foreach ($a in $Artifacts) {
  $zip = Join-Path $Dl $a.File
  if (!(Test-Path $zip)) {
    Write-Host "downloading $($a.Label) <- $a.File"
    Invoke-WebRequest -Uri "$Repo/$($a.File)" -OutFile $zip -UseBasicParsing
  }
  # The zips wrap everything in a folder named after the api level, and that name has changed
  # across releases - so find the payload by the tool we actually need instead of guessing.
  $tmp = Join-Path $Dl ("x-" + [IO.Path]::GetFileNameWithoutExtension($a.File))
  Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
  Expand-Archive -Path $zip -DestinationPath $tmp -Force
  $hit = Get-ChildItem $tmp -Recurse -Filter $a.Probe -File | Select-Object -First 1
  if (!$hit) { throw "$($a.File): no $($a.Probe) inside" }
  Remove-Item -Recurse -Force $a.Dest -ErrorAction SilentlyContinue
  New-Item -ItemType Directory -Force -Path (Split-Path $a.Dest) | Out-Null
  Move-Item $hit.Directory.FullName $a.Dest
  Remove-Item -Recurse -Force $tmp
  Write-Host "  -> $($a.Dest)"
}

foreach ($a in $Artifacts) {
  if (!(Test-Path (Join-Path $a.Dest $a.Probe))) { throw "missing $($a.Probe) in $($a.Dest)" }
}
Write-Host "sdk ready: $Sdk"
Write-Host 'next: powershell -ExecutionPolicy Bypass -File .\build.ps1'
