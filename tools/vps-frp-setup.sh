#!/bin/bash
# One-shot frps installer for Alibaba Cloud Linux 3 (RHEL family, dnf + systemd).
#
# What it does: runs frp's server so that a client on your home PC can publish
# 127.0.0.1:3080 (the DSH LAN bridge) as this server's public port 8443.
# Safe to re-run: it reuses the existing token and binary.
#
# Usage:  bash vps-frp-setup.sh          (as root)
#         bash vps-frp-setup.sh --print-token   (just show the token)
#         bash vps-frp-setup.sh --token <hex>   (use this token instead of generating one)
set -euo pipefail

FRP_VERSION="0.71.0"
BIND_PORT="7000"
PUBLIC_PORT="8443"
CONF_DIR="/etc/frp"
TOKEN_FILE="${CONF_DIR}/token"
TARBALL_DIR="frp_${FRP_VERSION}_linux_amd64"

if [ "$(id -u)" != "0" ]; then echo "run me as root" >&2; exit 1; fi

if [ "${1:-}" = "--print-token" ]; then
  cat "$TOKEN_FILE" 2>/dev/null || { echo "(no token yet - run without --print-token first)" >&2; exit 1; }
  echo
  exit 0
fi

GIVEN_TOKEN=""
if [ "${1:-}" = "--token" ]; then
  GIVEN_TOKEN="${2:-}"
  case "$GIVEN_TOKEN" in
    ''|*[!A-Za-z0-9]*) echo "--token needs an alphanumeric value" >&2; exit 1 ;;
  esac
fi

echo "== frps ${FRP_VERSION} on $(hostname) =="

# --- 1. shared secret between frps and frpc -------------------------------
install -d -m 0750 "$CONF_DIR"
if [ -n "$GIVEN_TOKEN" ]; then
  TOKEN="$GIVEN_TOKEN"
  printf '%s' "$TOKEN" > "$TOKEN_FILE"
  chmod 600 "$TOKEN_FILE"
  echo "[1/5] token taken from --token"
elif [ -s "$TOKEN_FILE" ]; then
  TOKEN="$(cat "$TOKEN_FILE")"
  echo "[1/5] reusing existing token"
else
  TOKEN="$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  printf '%s' "$TOKEN" > "$TOKEN_FILE"
  chmod 600 "$TOKEN_FILE"
  echo "[1/5] generated a new token"
fi

# --- 2. binary ------------------------------------------------------------
if command -v frps >/dev/null 2>&1; then
  echo "[2/5] frps already installed: $(frps --version 2>&1 | head -1)"
else
  cd /tmp
  URL="https://github.com/fatedier/frp/releases/download/v${FRP_VERSION}/frp_${FRP_VERSION}_linux_amd64.tar.gz"
  echo "[2/5] downloading ${URL}"
  curl -fL --retry 3 --retry-delay 2 --connect-timeout 15 -o frp.tgz "$URL"
  tar xzf frp.tgz
  install -m 0755 "${TARBALL_DIR}/frps" /usr/local/bin/frps
  install -m 0755 "${TARBALL_DIR}/frpc" /usr/local/bin/frpc
  rm -rf frp.tgz "$TARBALL_DIR"
  echo "      installed $(frps --version 2>&1 | head -1)"
fi

# --- 3. config ------------------------------------------------------------
cat > "${CONF_DIR}/frps.toml" <<EOF
bindPort = ${BIND_PORT}

auth.method = "token"
auth.token = "${TOKEN}"

# the only port a client is allowed to publish publicly
allowPorts = [{ start = ${PUBLIC_PORT}, end = ${PUBLIC_PORT} }]

# local-only dashboard, reachable through an ssh tunnel: ssh -L 7500:127.0.0.1:7500
webServer.addr = "127.0.0.1"
webServer.port = 7500
webServer.user = "dsh"
webServer.password = "${TOKEN:0:12}"

log.to = "console"
log.level = "info"
log.maxDays = 7
EOF
chmod 600 "${CONF_DIR}/frps.toml"
echo "[3/5] wrote ${CONF_DIR}/frps.toml"

# --- 4. service -----------------------------------------------------------
cat > /etc/systemd/system/frps.service <<'EOF'
[Unit]
Description=frp server (DSH reverse tunnel)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=/usr/local/bin/frps -c /etc/frp/frps.toml
Restart=always
RestartSec=5s
LimitNOFILE=1048576

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now frps >/dev/null 2>&1 || true
# Never let a failing restart abort the script before it prints the summary.
systemctl restart frps || echo "WARNING: 'systemctl restart frps' failed"
sleep 2
echo "[4/5] frps.service: $(systemctl is-active frps) / $(systemctl is-enabled frps)"
if [ "$(systemctl is-active frps)" != "active" ]; then
  echo "      frps is NOT running. last log lines:"
  journalctl -u frps -n 20 --no-pager 2>/dev/null || true
fi

# --- 5. host firewall (the Alibaba Cloud security group is NOT touched) ----
if systemctl is-active --quiet firewalld; then
  firewall-cmd --permanent --add-port=${BIND_PORT}/tcp >/dev/null
  firewall-cmd --permanent --add-port=${PUBLIC_PORT}/tcp >/dev/null
  firewall-cmd --reload >/dev/null
  echo "[5/5] firewalld opened ${BIND_PORT} and ${PUBLIC_PORT}"
else
  echo "[5/5] firewalld not active, nothing to open locally"
fi

echo
echo "================ summary ================"
echo "frps version : $(frps --version 2>&1 | head -1)"
echo "token        : ${TOKEN}"
echo "listening    :"
ss -lntp 2>/dev/null | grep -E ":(7000|7500|8443)\b" || echo "  (nothing yet - the public port appears once the client connects)"
echo
echo "NEXT, in the Alibaba Cloud console (this script cannot do it):"
echo "  ECS -> Security Groups -> inbound -> add TCP ${BIND_PORT} and TCP ${PUBLIC_PORT} (source 0.0.0.0/0)"
echo "THEN, on the PC (the token is already stored in tools\\.frp-token):"
echo "  powershell -NoProfile -ExecutionPolicy Bypass -File tools\\frp-setup.ps1"
echo "========================================="