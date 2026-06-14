#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JDK_LINK="/private/tmp/askmyscreenshots-jdk-home"
SDK_LINK="/private/tmp/askmyscreenshots-android-sdk-root"
GRADLE_HOME="/private/tmp/askmyscreenshots-gradle-home"
LOCAL_GRADLE="$ROOT_DIR/.local/gradle/gradle-8.10.2/bin/gradle"

ln -sfn "$ROOT_DIR/.local/jdk/Contents/Home" "$JDK_LINK"
ln -sfn "$ROOT_DIR/.local/android-sdk" "$SDK_LINK"
mkdir -p "$GRADLE_HOME"

export JAVA_HOME="$JDK_LINK"
export ANDROID_HOME="$SDK_LINK"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="$GRADLE_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

if [ -x "$LOCAL_GRADLE" ]; then
  "$LOCAL_GRADLE" :app:assembleDebug
else
if [[ -x "$ROOT_DIR/gradlew" ]]; then
    "$ROOT_DIR/gradlew" :app:assembleDebug
else
    "$ROOT_DIR/.local/gradle/gradle-8.10.2/bin/gradle" :app:assembleDebug
fi
fi
