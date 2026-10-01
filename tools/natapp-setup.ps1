# natapp-setup.ps1 -- install / start / verify the natapp tunnel in front of the DSH bridge.
#
# ASCII ONLY on purpose: Windows PowerShell 5.1 reads no-BOM UTF-8 as GBK and mangles quotes.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\natapp-setup.ps1
#   powershell ... -File tools\natapp-setup.ps1 -Stop
#
# Token comes from tools\.natapp-token (one line) or -Token. Everything admin-side
# (register / real-name / buy tunnel / local port 3080) is done on https://natapp.cn/ by the user.
[CmdletBinding()]
param(
  [string]$Token,
  [switch]$Stop,
  [switch]$NoStart,
  [int]$PhoneWaitSec = 15
)

$ErrorActionPreference = "Continue"
$Tools = $PSScriptRoot
$Root  = Split-Path -Parent $Tools
$Dir   = Join-Path $Tools "natapp"
$exe   = Join-Path $Dir "natapp.exe"
$bat   = Join-Path $Dir "run_natapp.bat"
$log   = Join-Path $Tools "natapp.log"
$errlog= Join-Path $Tools "natapp.err.log"
$tokenFile = Join-Path $Tools ".natapp-token"
$proxy = "http://127.0.0.1:7890"

function Say([string]$m) { Write-Host $m }

if ($Stop) {
  $n = 0
  Get-Process natapp -ErrorAction SilentlyContinue | ForEach-Object { Say ("stopping natapp pid " + $_.Id); $_.Kill(); $n++ }
  Say ("stopped " + $n + " process(es)")
  exit 0
}

# ---------- token ----------
if (-not $Token -and (Test-Path $tokenFile)) { $Token = ((Get-Content $tokenFile -Raw) -split "`n")[0].Trim() }
if (-not $Token) {
  Say ""
  Say "NO TOKEN."
  Say "  1. https://natapp.cn/  register + real-name verification (required even for the free tunnel)"
  Say "  2. buy tunnel -> protocol Web -> local port 3080   (NOT 19387)"
  Say "  3. My tunnels -> copy Authtoken"
  Say "  4. write it into:  $tokenFile   (single line, no quotes)"
  exit 2
}
if ($Token -notmatch '^[A-Za-z0-9]{8,64}$') { Say ("TOKEN LOOKS WRONG: '" + $Token + "'"); exit 2 }
Say ("token: " + $Token.Substring(0,4) + "..." + $Token.Substring($Token.Length - 4) + " (" + $Token.Length + " chars)")

if (-not (Test-Path $Dir)) { New-Item -ItemType Directory -Path $Dir | Out-Null }

# ---------- fetch (direct first, then the local proxy: direct curl to natapp.cn failed here) ----------
function Fetch([string]$Url, [string]$Out) {
  Remove-Item $Out -ErrorAction SilentlyContinue
  & curl.exe -s -o $Out --max-time 30 $Url 2>$null | Out-Null
  if (-not (Test-Path $Out) -or (Get-Item $Out).Length -lt 32) {
    Say "  direct fetch failed, retrying via $proxy"
    & curl.exe -s -o $Out -x $proxy --max-time 45 $Url 2>$null | Out-Null
  }
  if (Test-Path $Out) { return (Get-Item $Out).Length } else { return 0 }
}

# ---------- install (vendor installer drops natapp.exe + run_natapp.bat into the cwd) ----------
if (-not (Test-Path $exe)) {
  $get = Join-Path $Dir "get.ps1"
  $n = Fetch ("https://natapp.cn/get.ps1?authtoken=" + $Token) $get
  if ($n -lt 64) { Say ("FETCH FAILED: get.ps1 -> " + $n + " bytes"); exit 3 }
  $body = [System.IO.File]::ReadAllText($get, [System.Text.Encoding]::UTF8)
  Say ("vendor installer fetched: " + $n + " bytes -- first 1200 chars:")
  if ($body.Length -gt 1200) { Say $body.Substring(0, 1200) } else { Say $body }
  # Windows PowerShell 5.1 reads a no-BOM UTF-8 .ps1 as ANSI/GBK. The Chinese comment directly
  # above the vendor's $AUTHTOKEN assignment then eats the newline, the assignment becomes part
  # of the comment, and the installer dies with "missing authtoken". Re-encode with a BOM first.
  [System.IO.File]::WriteAllText($get, $body, (New-Object System.Text.UTF8Encoding($true)))
  Say ("re-encoded as UTF-8 with BOM (" + (Get-Item $get).Length + " bytes)")
  Say "--- running it in $Dir ---"
  Push-Location $Dir
  try { & powershell -NoProfile -ExecutionPolicy Bypass -File $get } finally { Pop-Location }
  if (-not (Test-Path $exe)) {
    Say "INSTALL FAILED: natapp.exe missing. dir contents:"
    Get-ChildItem $Dir | ForEach-Object { Say ("  " + $_.Name + "  " + $_.Length) }
    exit 3
  }
  Say ("installed: " + $exe + "  " + (Get-Item $exe).Length + " bytes")
  if (Test-Path $bat) { Say "vendor bat:"; Get-Content $bat | ForEach-Object { Say ("  " + $_) } }
} else {
  Say ("already installed: " + $exe + "  " + (Get-Item $exe).Length + " bytes")
}
if ($NoStart) { Say "no-start: done"; exit 0 }

# ---------- start hidden, capture the console status into logs ----------
Get-Process natapp -ErrorAction SilentlyContinue | ForEach-Object { Say ("stopping old natapp pid " + $_.Id); $_.Kill() }
Start-Sleep -Milliseconds 500
Remove-Item $log,$errlog -ErrorAction SilentlyContinue
Say "starting: natapp.exe -authtoken=**** -log=stdout"
# -log=stdout is not optional: without it the client prints the auth banner but never the forwarding
# url, so the log can only be misread (an earlier version picked up the vendor host auth.natapp.cn).
$p = Start-Process -FilePath $exe -ArgumentList @("-authtoken=" + $Token, "-log=stdout") -WorkingDirectory $Dir `
     -WindowStyle Hidden -RedirectStandardOutput $log -RedirectStandardError $errlog -PassThru
Say ("pid " + $p.Id)

# ---------- find the public url in the log ----------
# Strict: the client's own "Tunnel established at <url>" / "forwarding=<url>".
# Loose fallback: any url in the log, minus vendor infrastructure hosts (*.natapp.cn).
$domain = $null; $scheme = "http"
$rStrict = [regex]'(?:Tunnel established at|forwarding=)\s*(https?://[^\s"]+)'
$rLoose  = [regex]'https?://([A-Za-z0-9][A-Za-z0-9.\-]*\.[A-Za-z]{2,})(?::\d+)?'
for ($i = 1; $i -le 30; $i++) {
  Start-Sleep -Seconds 1
  if (Test-Path $log) {
    $raw = Get-Content $log -Raw
    $m = $rStrict.Match($raw)
    if ($m.Success) {
      $domain = ([regex]'^https?://([^/]+)').Match($m.Groups[1].Value).Groups[1].Value
      if ($m.Groups[1].Value -like "https*") { $scheme = "https" }
      break
    }
    foreach ($c in $rLoose.Matches($raw)) {
      $h = $c.Groups[1].Value
      if ($h -notmatch '(^|\.)natapp\.cn$') { $domain = $h; break }
    }
    if ($domain) { break }
  }
  if ($p.HasExited) { Say ("natapp exited early, code " + $p.ExitCode); break }
}
if (-not $domain) {
  Say "NO PUBLIC URL in the log yet. last lines:"
  if (Test-Path $log)    { Get-Content $log -Tail 15    | ForEach-Object { Say ("  " + $_) } }
  if (Test-Path $errlog) { Get-Content $errlog -Tail 15 | ForEach-Object { Say ("! " + $_) } }
  exit 4
}
$port = if ($scheme -eq "https") { 443 } else { 80 }
Say ("PUBLIC: " + $scheme + "://" + $domain)

# ---------- verify from this PC ----------
$k = ((Get-Content (Join-Path $Tools ".bridge-key") -Raw)).Trim()
Say ("--- PC side: node tools\tunnel-check.cjs " + $domain + " " + $port + " " + $scheme + " ---")
Push-Location $Root
try { & node (Join-Path $Tools "tunnel-check.cjs") $domain $port $scheme } finally { Pop-Location }

# ---------- verify from the phone (it is on 4G, i.e. really from outside) ----------
$env:TEMP = Join-Path $Root "android\.tmp"; $env:TMP = $env:TEMP
$adb = Join-Path $Root "android\android-sdk\platform-tools\adb.exe"
$serial = (& $adb devices 2>$null | Select-String -Pattern "^\S+\s+device$" | Select-Object -First 1)
if ($serial) {
  $sid = ($serial -split "\s+")[0]
  Say ("--- phone side: " + $sid + " ---")
  $code = (& $adb -s $sid shell ("curl -s --max-time " + $PhoneWaitSec + " -o /dev/null -w '%{http_code}' '" + $scheme + "://" + $domain + "/?k=" + $k + "'") 2>$null | Out-String).Trim()
  Say ("phone http_code=" + $code + "   (303 = tunnel reaches the bridge and the key is right; 000 = no route)")
  if ($code -eq "303") {
    $api = (& $adb -s $sid shell ("curl -s --max-time " + $PhoneWaitSec + " -H 'Cookie: dsh-bridge=" + $k + "' -H 'Content-Type: application/json' -d '{\""type\"":\""client-request\"",\""rpcId\"":\""aaaa1111-2222-3333-4444-555566667777\"",\""method\"":\""session/list\"",\""payload\"":{\""args\"":{\""_request\"":{}}}}' -o /dev/null -w '%{http_code}' '" + $scheme + "://" + $domain + "/api/session/list") 2>$null | Out-String).Trim()
    Say ("phone api http_code=" + $api + "   (200 = the phone is really driving DSH)")
  }
} else {
  Say "no adb device online; skipped the phone check"
}
Say "done."