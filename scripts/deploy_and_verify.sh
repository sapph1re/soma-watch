#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_DIR"

export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$JAVA_HOME/bin:/opt/homebrew/bin:$ANDROID_HOME/platform-tools:$PATH"

WATCH_SERIAL="${1:-}"
PHONE_SERIAL="${2:-}"
SCREENSHOT_OUT="${3:-$PROJECT_DIR/watch_screen.png}"

# Auto-detect Galaxy Watch 8 (SM_L330) and Phone if not passed
if [[ -z "$WATCH_SERIAL" ]]; then
  WATCH_SERIAL=$(adb devices -l | awk '/SM_L330/ && /device / {print $1; exit}')
fi
if [[ -z "$PHONE_SERIAL" ]]; then
  PHONE_SERIAL=$(adb devices -l | awk '!/SM_L330/ && /device / {print $1; exit}')
fi

echo "=== [1/4] Сборка Soma Watch (:wear и :mobile) ==="
./gradlew :wear:assembleDebug :mobile:assembleDebug

WEAR_APK="$PROJECT_DIR/wear/build/outputs/apk/debug/wear-debug.apk"
MOBILE_APK="$PROJECT_DIR/mobile/build/outputs/apk/debug/mobile-debug.apk"

echo "=== [2/4] Подключенные ADB-устройства ==="
adb devices -l

if [[ -n "$PHONE_SERIAL" ]]; then
  echo "=== Установка :mobile на телефон ($PHONE_SERIAL) ==="
  adb -s "$PHONE_SERIAL" install -r "$MOBILE_APK"
  adb -s "$PHONE_SERIAL" shell am start -n com.aisling.somawatch/.MobileMainActivity
fi

if [[ -n "$WATCH_SERIAL" ]]; then
  echo "=== [3/4] Установка :wear на Galaxy Watch 8 ($WATCH_SERIAL) ==="
  adb -s "$WATCH_SERIAL" install -r "$WEAR_APK"
  adb -s "$WATCH_SERIAL" shell input keyevent KEYCODE_WAKEUP
  adb -s "$WATCH_SERIAL" shell am start -n com.aisling.somawatch/.WearMainActivity
  sleep 2
  echo "=== [4/4] Снятие скриншота с экрана Galaxy Watch 8 -> $SCREENSHOT_OUT ==="
  adb -s "$WATCH_SERIAL" exec-out screencap -p > "$SCREENSHOT_OUT"
  echo "Готово! Скриншот сохранен в $SCREENSHOT_OUT"
fi
