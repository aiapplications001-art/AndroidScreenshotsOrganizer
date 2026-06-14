#!/usr/bin/env bash
set -euo pipefail

ADB="${ADB:-/Users/bmf955/Documents/Finding problems/phone_screenshot_agent/tools/platform-tools/adb}"

"$ADB" logcat -v time \
  AskScreenshots:D \
  AskScreenshotsSkill:D \
  AndroidRuntime:E \
  WorkManager:I \
  WM-WorkerWrapper:I \
  '*:S'
