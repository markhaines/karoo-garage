#!/usr/bin/env bash
# Push a karoo-garage config file onto a connected Karoo via adb and trigger import.
#
# Usage:
#   ./tools/push-config.sh path/to/garage.kgcfg
#
# Requires: adb on PATH, the Karoo connected with USB debugging enabled,
# and the Garage app already installed (the Import activity needs to exist
# to handle the broadcast).

set -euo pipefail

CONFIG_FILE="${1:-}"
PACKAGE="com.hainesy.karoogarage.debug"
REMOTE_PATH="/sdcard/Download/garage.kgcfg"

if [[ -z "$CONFIG_FILE" ]]; then
    echo "usage: $0 <path-to-garage.kgcfg>" >&2
    exit 1
fi

if [[ ! -f "$CONFIG_FILE" ]]; then
    echo "error: $CONFIG_FILE not found" >&2
    exit 1
fi

if ! command -v adb >/dev/null; then
    echo "error: adb not on PATH" >&2
    exit 1
fi

DEVICES=$(adb devices | awk 'NR>1 && $2=="device" {print $1}' | wc -l | tr -d ' ')
if [[ "$DEVICES" -eq 0 ]]; then
    echo "error: no Karoo detected via adb. Is USB debugging enabled?" >&2
    exit 1
fi

echo "→ pushing config to $REMOTE_PATH"
adb push "$CONFIG_FILE" "$REMOTE_PATH"

echo "→ triggering import"
adb shell am start \
    -n "${PACKAGE}/com.hainesy.karoogarage.ImportConfigActivity" \
    -a android.intent.action.VIEW \
    -d "file://${REMOTE_PATH}" \
    --grant-read-uri-permission

echo "✓ done. Check the Karoo for an 'imported' toast."
