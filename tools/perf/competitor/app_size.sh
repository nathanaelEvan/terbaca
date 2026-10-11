#!/usr/bin/env bash
# Installed APK size (base + split APKs) and version of every app in common.sh.
# Usage: tools/perf/competitor/app_size.sh > sizes.csv
source "$(dirname "$0")/common.sh"
echo "app,version,apk_bytes,apk_files"
for p in "${APPS[@]}"; do
  version=$(adb shell dumpsys package "$p" | sed -n 's/.*versionName=//p' | head -1 | tr -d '\r')
  total=0; n=0
  for apk in $(adb shell pm path "$p" | sed 's/^package://' | tr -d '\r'); do
    bytes=$(adb shell stat -c %s "$apk" | tr -d '\r')
    total=$((total + bytes)); n=$((n + 1))
  done
  echo "$p,$version,$total,$n"
done
