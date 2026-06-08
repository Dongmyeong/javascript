#!/bin/sh
# Install the Telegram receiver as a macOS launchd agent so it runs in the
# background, restarts if it crashes, and starts automatically at login/boot.
#
#   sh install-launchd.sh
#
# Requires that .env (with TELEGRAM_BOT_TOKEN) already exists in this folder.

set -e

LABEL="com.dongmyeong.telegram-receiver"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
LOG="$HOME/telegram-receiver.log"

NODE_BIN="$(command -v node || true)"
if [ -z "$NODE_BIN" ]; then
  echo "ERROR: node not found in PATH. Install Node.js (e.g. 'brew install node') first."
  exit 1
fi
if [ ! -f "$SCRIPT_DIR/.env" ]; then
  echo "ERROR: $SCRIPT_DIR/.env not found. Copy .env.example to .env and set TELEGRAM_BOT_TOKEN."
  exit 1
fi

echo "node:   $NODE_BIN"
echo "script: $SCRIPT_DIR/receive-telegram.js"
echo "log:    $LOG"

# Stop any manually-started instance so launchd is the only one (avoids the
# Telegram getUpdates conflict of two pollers).
pkill -f "receive-telegram.js" 2>/dev/null || true
sleep 2

mkdir -p "$HOME/Library/LaunchAgents"
cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key>
  <array>
    <string>$NODE_BIN</string>
    <string>$SCRIPT_DIR/receive-telegram.js</string>
  </array>
  <key>WorkingDirectory</key><string>$SCRIPT_DIR</string>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>$LOG</string>
  <key>StandardErrorPath</key><string>$LOG</string>
</dict>
</plist>
EOF

# Reload (unload if already installed, then load).
launchctl unload "$PLIST" 2>/dev/null || true
launchctl load "$PLIST"

echo ""
echo "Installed and started: $LABEL"
echo "Check it:   launchctl list | grep telegram-receiver"
echo "Follow log: tail -f $LOG"
echo "Stop/remove: sh uninstall-launchd.sh"
