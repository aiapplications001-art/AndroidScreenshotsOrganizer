#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="/Users/bmf955/Documents/Finding problems/phone_screenshot_agent/tools/platform-tools/adb"
APK="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"

"$ADB" install -r "$APK"

