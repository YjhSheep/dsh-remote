# Builds the native app with Gradle, using the JDK + Gradle unpacked under android\tools.
#   .\gradle-build.ps1                 -> :app:assembleDebug
#   .\gradle-build.ps1 assembleRelease -> any Gradle tasks
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Tasks = @("assembleDebug"))

$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot

$Jdk = Join-Path $Root "tools\jdk17"
if (-not (Test-Path (Join-Path $Jdk "bin\javac.exe"))) {
    $Jdk = (Get-ChildItem (Join-Path $Root "tools") -Directory -Filter "jdk*" -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName "bin\javac.exe") } | Select-Object -First 1).FullName
}
if (-not $Jdk) { throw "JDK 17 not found: unpack it into android\tools\jdk17" }

$Gradle = Join-Path $Root "tools\gradle-8.9\bin\gradle.bat"
if (-not (Test-Path $Gradle)) { throw "Gradle not found: unpack android\tools\gradle-8.9-bin.zip into android\tools" }

$env:JAVA_HOME = $Jdk
$env:PATH = (Join-Path $Jdk "bin") + ";" + $env:PATH
$env:ANDROID_HOME = Join-Path $Root "android-sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
# Gradle's user home must stay inside this workspace: writing to C:\Users\<user>\.gradle\native
# is refused here, and Gradle then dies with "Could not initialize native services".
$env:GRADLE_USER_HOME = Join-Path $Root ".gradle-home"
# Same story for the JVM temp dir: the user's default (C:\Temp) is refused for the daemon, and
# apkzlib then dies with "java.nio.file.AccessDeniedException: C:\Temp\tempdir_<n>" while
# merging java resources. Keep it inside the workspace too.
$env:TEMP = Join-Path $Root ".tmp"
$env:TMP = $env:TEMP
New-Item -ItemType Directory -Force $env:TEMP | Out-Null
# ...and the same for ~/.android (analytics, adb keys, cached AVD info): keep it in the workspace.
$env:ANDROID_USER_HOME = Join-Path $Root ".android-home"
New-Item -ItemType Directory -Force $env:ANDROID_USER_HOME | Out-Null

Write-Host "JAVA_HOME = $env:JAVA_HOME"
Write-Host "ANDROID_HOME = $env:ANDROID_HOME"
Write-Host "TEMP = $env:TEMP"
& $Gradle -p $Root @Tasks
exit $LASTEXITCODE