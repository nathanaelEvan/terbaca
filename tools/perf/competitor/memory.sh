#!/usr/bin/env bash
# Memory (total PSS of all the app's processes) with the 105-page memory_100plus.pdf open, for
# every app in common.sh: once after opening, then after each of 5 rounds of flings forward.
# Prints one CSV row per sample. adb's `input` can't pinch, so this run doesn't zoom.
#
# Usage: tools/perf/competitor/memory.sh [runs]  > memory.csv
source "$(dirname "$0")/common.sh"
RUNS=${1:-3}
APP_LIST=(${ONLY_APPS:-${APPS[@]}})
read -r W H < <(screen_size)
CX=$((W / 2)); CY=$((H / 2))

echo "app,run,step,pss_kb"
for run in $(seq "$RUNS"); do
  for p in "${APP_LIST[@]}"; do
    adb shell am force-stop "$p"
    view "$p" memory_100plus.pdf >/dev/null
    sleep 5
    echo "$p,$run,open,$(package_pss "$p")"
    for round in 1 2 3 4 5; do
      for _ in 1 2 3 4; do adb shell input swipe "$CX" $((CY + CY / 2)) "$CX" $((CY / 2)) 60; sleep 0.4; done
      sleep 1.5
      echo "$p,$run,scroll$round,$(package_pss "$p")"
    done
    adb shell am force-stop "$p"
  done
done
