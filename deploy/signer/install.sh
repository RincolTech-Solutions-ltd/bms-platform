#!/bin/bash
# Installs the image signer on the maintainer's Mac (ADR-040): copies bms_signer.py to ~/bms-ops/signer and
# loads a launchd agent that runs it every 30 seconds. Run as the logged-in user, not root.
#
# Needs: cosign, crane and gh on the PATH (brew install cosign crane gh), `gh auth login` done with a token
# that may write packages, and the Keychain items bms-cosign-key (base64 of the PEM) and bms-cosign-password.
set -euo pipefail
[ "$(id -u)" != "0" ] || { echo "run as your own user, not root" >&2; exit 1; }
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST="$HOME/bms-ops/signer"
LABEL="com.rindzo.bms-signer"
PLIST="$HOME/Library/LaunchAgents/${LABEL}.plist"
for tool in cosign crane gh python3 security; do
  command -v "$tool" >/dev/null || { echo "missing: $tool" >&2; exit 1; }
done
security find-generic-password -s bms-cosign-key >/dev/null 2>&1 || { echo "Keychain item bms-cosign-key is missing" >&2; exit 1; }
mkdir -p "$DEST"
install -m 700 "$HERE/bms_signer.py" "$DEST/bms_signer.py"
PATHS="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin"
cat > "$PLIST" <<PLIST_EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>${LABEL}</string>
  <key>ProgramArguments</key><array>
    <string>/usr/bin/python3</string><string>${DEST}/bms_signer.py</string><string>--loop</string><string>30</string>
  </array>
  <key>EnvironmentVariables</key><dict><key>PATH</key><string>${PATHS}</string></dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ThrottleInterval</key><integer>30</integer>
  <key>StandardOutPath</key><string>${DEST}/signer.log</string>
  <key>StandardErrorPath</key><string>${DEST}/signer.log</string>
</dict></plist>
PLIST_EOF
launchctl bootout "gui/$(id -u)/${LABEL}" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$PLIST"
echo "installed: ${LABEL}; log at ${DEST}/signer.log"
