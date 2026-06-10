#!/usr/bin/env bash
# Push a karoo-garage config file onto a connected Karoo via adb and trigger import.
#
# Usage:
#   ./tools/push-config.sh path/to/garage.kgcfg            # release build (default)
#   ./tools/push-config.sh path/to/garage.kgcfg --debug    # debug build
#   ./tools/push-config.sh path/to/garage.kgcfg com.hainesy.karoogarage.debug
#
# Requires: adb on PATH, the Karoo connected with USB debugging enabled,
# and the Garage app already installed (the Import activity needs to exist
# to handle the intent).
#
# The file is pushed to the app's own external-files directory
# (/sdcard/Android/data/<package>/files/), which the app can read with a
# plain file:// URI and no storage permission. Pushing to /sdcard/Download
# does NOT work under scoped storage: the app gets EACCES on the file://
# path, and adb (UID 2000) cannot grant a content:// documents URI either.
#
# The pushed file is deleted from the device afterwards — it contains a
# plaintext HA token.

set -euo pipefail

DEFAULT_PACKAGE="com.hainesy.karoogarage"
ACTIVITY="com.hainesy.karoogarage.ImportConfigActivity"

CONFIG_FILE="${1:-}"
PACKAGE="$DEFAULT_PACKAGE"

case "${2:-}" in
    "")            ;;
    --debug|debug) PACKAGE="${DEFAULT_PACKAGE}.debug" ;;
    *)             PACKAGE="$2" ;;
esac

REMOTE_PATH="/sdcard/Android/data/${PACKAGE}/files/garage.kgcfg"

if [[ -z "$CONFIG_FILE" ]]; then
    echo "usage: $0 <path-to-garage.kgcfg> [--debug | <package>]" >&2
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

if ! adb shell pm path "$PACKAGE" >/dev/null 2>&1; then
    echo "error: $PACKAGE is not installed on the device" >&2
    exit 1
fi

echo "→ pushing config to $REMOTE_PATH"
adb push "$CONFIG_FILE" "$REMOTE_PATH"

echo "→ triggering import"
adb shell am start \
    -n "${PACKAGE}/${ACTIVITY}" \
    -a android.intent.action.VIEW \
    -d "file://${REMOTE_PATH}"

# Give the activity a moment to read the file before removing it — it holds
# a plaintext HA token and must not be left on the device.
sleep 3
echo "→ deleting pushed file from device"
adb shell rm -f "$REMOTE_PATH"

echo "✓ done. Check the Karoo for an 'imported' toast."
