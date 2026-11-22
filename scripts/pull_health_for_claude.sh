#!/usr/bin/env bash
set -euo pipefail

# Pull Soma Watch longitudinal health export (JSON) for Claude Code / local analysis.
# Usage:
#   ./scripts/pull_health_for_claude.sh                          # Pulls via ADB from OnePlus 15
#   ./scripts/pull_health_for_claude.sh http://192.168.0.106:8787 # Pulls over local Wi-Fi API

OUT_FILE="${2:-$(pwd)/soma_health_export.json}"
API_URL="${1:-}"

if [[ -n "$API_URL" ]]; then
  echo "Fetching live health JSON from $API_URL/api/health ..."
  curl -fsSL "$API_URL/api/health" -o "$OUT_FILE"
  echo "Saved -> $OUT_FILE ($(wc -c < "$OUT_FILE") bytes)"
  exit 0
fi

PHONE_ID=$(adb devices -l | awk '/CPH2747/ && /device / {print $1; exit}')
if [[ -z "$PHONE_ID" ]]; then
  PHONE_ID=$(adb devices -l | awk '!/SM_L330/ && /device / {print $1; exit}')
fi

if [[ -z "$PHONE_ID" ]]; then
  echo "No Android phone connected via ADB. Pass the phone Wi-Fi API URL instead, e.g.:"
  echo "  $0 http://192.168.0.106:8787"
  exit 1
fi

adb -s "$PHONE_ID" pull /sdcard/Android/data/com.aisling.somawatch/files/soma_health_export.json "$OUT_FILE"
echo "Pulled via ADB -> $OUT_FILE"
