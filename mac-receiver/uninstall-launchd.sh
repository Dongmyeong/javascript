#!/bin/sh
# Stop and remove the Telegram receiver launchd agent.
#   sh uninstall-launchd.sh

LABEL="com.dongmyeong.telegram-receiver"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"

launchctl unload "$PLIST" 2>/dev/null || true
rm -f "$PLIST"
echo "Removed: $LABEL"
