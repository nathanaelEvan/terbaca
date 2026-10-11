#!/usr/bin/env bash
# PRD §7 "Total PSS (UI + render process), 100-page paper, active zooming" on a connected device.
#
# Pushes testdata/corpus/memory_100plus.pdf to the device's Download folder, opens it in the
# installed app (build it with ./gradlew :app:installBenchmark first), then scrolls through the
# document and zooms in and out with Ctrl +/- while sampling `dumpsys meminfo` for both processes.
# Prints one line per sample and the peak. Deletes the pushed file at the end.
#
# Usage: tools/perf/measure_pss.sh [adb-serial]
set -euo pipefail
export MSYS_NO_PATHCONV=1 # Git Bash on Windows: keep /sdcard paths as they are

# An array, so an adb path with spaces (Windows user folders) survives.
ADB=("${ADB:-adb}")
if [[ $# -ge 1 ]]; then ADB+=(-s "$1"); fi
PKG=app.terbaca
NAME=terbaca-memory_100plus.pdf
REMOTE=/sdcard/Download/$NAME
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# adb joins its arguments into one remote shell command, so the SQL quotes need a second layer.
WHERE="\"_display_name='$NAME'\""

cd "$ROOT" # a relative path, which a Windows adb can read too
"${ADB[@]}" push testdata/corpus/memory_100plus.pdf "$REMOTE" >/dev/null
trap '"${ADB[@]}" shell "content delete --uri content://media/external/file --where $WHERE" >/dev/null 2>&1; "${ADB[@]}" shell rm -f "$REMOTE" >/dev/null' EXIT
"${ADB[@]}" shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///storage/emulated/0/Download/$NAME" >/dev/null
sleep 3
ID=$("${ADB[@]}" shell "content query --uri content://media/external/file --projection _id --where $WHERE" | sed -n 's/.*_id=\([0-9]*\).*/\1/p' | head -1)
if [[ -z "$ID" ]]; then echo "MediaStore has no entry for $REMOTE" >&2; exit 1; fi

"${ADB[@]}" shell am force-stop $PKG
"${ADB[@]}" shell am start -a android.intent.action.VIEW -t application/pdf --grant-read-uri-permission \
  -d "content://media/external/file/$ID" -n $PKG/.MainActivity >/dev/null
sleep 4

# Total PSS in KB of one process, 0 if it isn't running.
pss() { "${ADB[@]}" shell dumpsys meminfo "$1" | awk '/TOTAL PSS:/ {print $3; found=1} END {if (!found) print 0}'; }
peak=0
sample() {
  local ui render total
  ui=$(pss $PKG); render=$(pss $PKG:render); total=$((ui + render))
  (( total > peak )) && peak=$total
  printf '%-14s UI %4d MB  render %3d MB  total %4d MB\n' "$1" $((ui / 1024)) $((render / 1024)) $((total / 1024))
}

# The screen size in its current rotation ("cur=3200x2136"); `wm size` gives the panel's natural
# size, and a swipe planned on that can start below a landscape screen: the home gesture.
read -r W H < <("${ADB[@]}" shell dumpsys window displays | sed -n 's/.*cur=\([0-9]*\)x\([0-9]*\).*/\1 \2/p' | head -1)
CX=$((W / 2)); CY=$((H / 2))
zoom() { for _ in $(seq "$2"); do "${ADB[@]}" shell input keycombination KEYCODE_CTRL_LEFT "$1"; sleep 0.3; done; }
swipe() { "${ADB[@]}" shell input swipe "$CX" "$1" "$CX" "$2" 120; }

sample "open"
for round in 1 2 3 4 5 6; do
  for _ in 1 2 3 4; do swipe $((CY + CY / 2)) $((CY / 2)); done # fling forward a few pages
  sleep 1; sample "scroll $round"
  zoom KEYCODE_EQUALS 8 # 1.25^8 ≈ 6× fit width
  sleep 1.5; sample "zoom-in $round"
  for _ in 1 2; do swipe $((CY + CY / 3)) $((CY - CY / 3)); done
  sleep 1.5; sample "zoom-pan $round"
  zoom KEYCODE_MINUS 8
  sleep 1; sample "zoom-out $round"
done
echo "PEAK total PSS: $((peak / 1024)) MB"
