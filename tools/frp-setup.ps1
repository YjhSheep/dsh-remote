# frp-setup.ps1 -- install / configure / start / verify the frp client that publishes the
# DSH bridge through your own VPS. This is the "self-hosted tunnel" route; tools\natapp-setup.ps1
# is the vendor-tunnel route. Keep both, run one client at a time.
#
# ASCII ONLY on purpose: Windows PowerShell 5.1 reads no-BOM UTF-8 as GBK and mangles quotes.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\frp-setup.ps1 -Token <frps token>
#   powershell ... -File tools\frp-setup.ps1 -Stop
#
# Topology:  phone -> http://<Server>:<PublicPort>/?k=<bridge key>
#                       (frps on the VPS, public port)
#                    -> reverse tunnel (frpc here logs into frps)
#                    -> 127.0.0.1:3080   the DSH LAN bridge
#                    -> 127.0.0.1:19387  DSH itself
# The server half is tools/vps-frp-setup.sh - run it on the VPS as root, it prints the token.
[CmdletBinding()]
param(
  [string]$Server = "47.76.59.5",
  [int]$ServerPort = 7000,
  [int]$PublicPort = 8443,
  [string]$Token,
  [switch]$Stop,
  [switch]$NoStart,
  [switch]$NoPhone,
  [int]$PhoneWaitSec = 20
)

$ErrorActionPreference = "Continue"
$Tools = $PSScriptRoot
$Root  = Split-Path -Parent $Tools
$Dir   = Join-Path $Tools "frp"
$exe   = Join-Path $Dir "frpc.exe"
$conf  = Join-Path $Dir "frpc.toml"
$log   = Join-Path $Tools "frpc.log"
$errlog= Join-Path $Tools "frpc.err.log"
$tokenFile = Join-Path $Tools ".frp-token"
$proxy = "http://127.0.0.1:7890"
$FrpVersion = "0.71.0"

function Say([string]$m) { Write-Host $m }

if ($Stop) {
  $n = 0
  Get-Process frpc -ErrorAction SilentlyContinue | ForEach-Object { Say ("stopping frpc pid " + $_.Id); $_.Kill(); $n++ }
  Say ("stopped " + $n + " process(es)")
  exit 0
}

# ---------- token ----------
if (-not $Token -and (Test-Path $tokenFile)) { $Token = ((Get-Content $tokenFile -Raw) -split "`n")[0].Trim() }
if (-not $Token) {
  Say ""
  Say "NO TOKEN."
  Say "  1. on the VPS:  bash tools/vps-frp-setup.sh      (it prints the token when it finishes)"
  Say "  2. write it into:  $tokenFile   (single line, no quotes)   or pass -Token"
  exit 2
}
if ($Token -notmatch '^[A-Za-z0-9]{8,64}$') { Say ("TOKEN LOOKS WRONG: '" + $Token + "'"); exit 2 }
Say ("server      : " + $Server + ":" + $ServerPort)
Say ("public port : " + $PublicPort)
Say ("token       : " + $Token.Substring(0,4) + "..." + $Token.Substring($Token.Length - 4) + " (" + $Token.Length + " chars)")

if (-not (Test-Path $Dir)) { New-Item -ItemType Directory -Path $Dir | Out-Null }

# ---------- fetch (direct first, then the local proxy: direct github.com times out here) ----------
function Fetch([string]$Url, [string]$Out) {
  Remove-Item $Out -ErrorAction SilentlyContinue
  & curl.exe -sL -o $Out --max-time 60 $Url 2>$null | Out-Null
  if (-not (Test-Path $Out) -or (Get-Item $Out).Length -lt 1000000) {
    Say "  direct fetch failed, retrying via $proxy"
    & curl.exe -sL -o $Out -x $proxy --max-time 90 $Url 2>$null | Out-Null
  }
  if (Test-Path $Out) { return (Get-Item $Out).Length } else { return 0 }
}

# ---------- frpc.exe ----------
if (-not (Test-Path $exe)) {
  $zip = Join-Path $Dir "frp.zip"
  $zipUrl = "https://github.com/fatedier/frp/releases/download/v" + $FrpVersion + "/frp_" + $FrpVersion + "_windows_amd64.zip"
  Say ("downloading " + $zipUrl)
  $n = Fetch $zipUrl $zip
  if ($n -lt 1000000) { Say ("FETCH FAILED: " + $zipUrl + " -> " + $n + " bytes"); exit 3 }
  Say ("downloaded " + $n + " bytes")
  $tmp = Join-Path $Dir "x"
  Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
  Expand-Archive -Path $zip -DestinationPath $tmp -Force
  $src = Join-Path $tmp ("frp_" + $FrpVersion + "_windows_amd64\frpc.exe")
  if (-not (Test-Path $src)) { Say ("frpc.exe is not inside the zip: " + $src); exit 3 }
  Copy-Item $src $exe -Force
  Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
  Remove-Item $zip -Force -ErrorAction SilentlyContinue
  Say ("installed: " + $exe + "  " + (Get-Item $exe).Length + " bytes")
} else {
  Say ("already installed: " + $exe + "  " + (Get-Item $exe).Length + " bytes")
}

# ---------- config ----------
# The token sits in this file, which is gitignored together with the whole tools\frp folder.
$toml = @"
serverAddr = "$Server"
serverPort = $ServerPort

auth.method = "token"
auth.token = "$Token"

# Keep retrying instead of exiting when frps is briefly down (frp defaults this to true).
loginFailExit = false

log.to = "console"
log.level = "info"
log.maxDays = 7

[[proxies]]
name = "dsh-bridge"
type = "tcp"
localIP = "127.0.0.1"
localPort = 3080
remotePort = $PublicPort
"@
[System.IO.File]::WriteAllText($conf, $toml, (New-Object System.Text.ASCIIEncoding))
Say ("wrote " + $conf)
if ($NoStart) { Say "no-start: done"; exit 0 }

# ---------- start detached (or reuse the client that is already up) ----------
# Never Start-Process here: a child started from this script inherits the caller's pipe, and a
# caller that pipes this script into Select-Object would hang forever on a process that outlives it.
$already = @(Get-Process frpc -ErrorAction SilentlyContinue).Count -gt 0
if ($already) {
  Say "frpc is already running - reusing it (stop it with tools\frp-setup.ps1 -Stop)"
} else {
  Remove-Item $log,$errlog -ErrorAction SilentlyContinue
  Say "starting detached: wscript tools\frp-start.vbs"
  & wscript.exe (Join-Path $Tools "frp-start.vbs")
}

# ---------- wait for the client to log in ----------
# wscript -> cmd -> frpc takes a moment to appear, so never give up before the process has been
# seen at least once: checking once and bailing made a healthy cold start look like a failure.
$ok = $false
$seen = $false
for ($i = 1; $i -le 30; $i++) {
  Start-Sleep -Seconds 1
  $alive = [bool](Get-Process frpc -ErrorAction SilentlyContinue)
  if ($alive) { $seen = $true }
  if (Test-Path $log) {
    if ((Get-Content $log -Raw) -match 'start proxy success') { $ok = $true; break }
  }
  if ($seen -and -not $alive) { Say "frpc exited"; break }
}
if ($ok) {
  Say "frpc: start proxy success (frps accepted the tunnel)"
} else {
  Say "frpc has not reported 'start proxy success' yet. last lines:"
  if (Test-Path $log)    { Get-Content $log -Tail 15    | ForEach-Object { Say ("  " + $_) } }
  if (Test-Path $errlog) { Get-Content $errlog -Tail 15 | ForEach-Object { Say ("! " + $_) } }
}

# ---------- verify from this PC ----------
$k = ((Get-Content (Join-Path $Tools ".bridge-key") -Raw)).Trim()
Say ("--- PC side: node tools\tunnel-check.cjs " + $Server + " " + $PublicPort + " http ---")
Push-Location $Root
try { & node (Join-Path $Tools "tunnel-check.cjs") $Server $PublicPort http } finally { Pop-Location }

# ---------- verify from the phone ----------
if ($NoPhone) { Say "no-phone: done"; exit 0 }
$phoneUrl = "http://" + $Server + ":" + $PublicPort
$env:TEMP = Join-Path $Root "android\.tmp"; $env:TMP = $env:TEMP
$adb = Join-Path $Root "android\android-sdk\platform-tools\adb.exe"
$serial = (& $adb devices 2>$null | Select-String -Pattern "^\S+\s+device$" | Select-Object -First 1)
if ($serial) {
  $sid = ($serial -split "\s+")[0]
  Say ("--- phone side: " + $sid + " ---")
  $code = (& $adb -s $sid shell ("curl -s --max-time " + $PhoneWaitSec + " -o /dev/null -w '%{http_code}' '" + $phoneUrl + "/?k=" + $k + "'") 2>$null | Out-String).Trim()
  Say ("phone http_code=" + $code + "   (303 = tunnel reaches the bridge and the key is right; 000 = no route)")
  if ($code -eq "303") {
    # The json body travels as a pushed file. Quoting it inline through PowerShell + adb shell
    # mangles the braces and the gateway honestly answers 400 (bad json) on a healthy tunnel.
    $body = '{"type":"client-request","rpcId":"aaaa1111-2222-3333-4444-555566667777","method":"session/list","payload":{"args":{"_request":{}}}}'
    $req = Join-Path $Root "android\.tmp\frp-req.json"
    [System.IO.File]::WriteAllText($req, $body, (New-Object System.Text.ASCIIEncoding))
    & $adb -s $sid push $req /data/local/tmp/frp-req.json 2>$null | Out-Null
    $api = (& $adb -s $sid shell ("curl -s --max-time " + $PhoneWaitSec + " -H 'Cookie: dsh-bridge=" + $k + "' -H 'Content-Type: application/json' -d @/data/local/tmp/frp-req.json -o /dev/null -w '%{http_code}' '" + $phoneUrl + "/api/session/list'") 2>$null | Out-String).Trim()
    Say ("phone api http_code=" + $api + "   (200 = the phone is really driving DSH)")
  }
} else {
  Say "no adb device online; skipped the phone check"
}
Say ("phone url: " + $phoneUrl + "/?k=" + $k)
Say "done."