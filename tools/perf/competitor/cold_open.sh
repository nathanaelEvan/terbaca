#!/usr/bin/env bash
# Cold open → first page visible, for every app in common.sh.
#
# Each run: force-stop the app, go to the home screen, start `screenrecord`, send the VIEW intent,
# and keep recording for a few seconds. tools/perf/competitor/analyze_open.py then reads the
# videos. `am start -W` TotalTime is saved alongside as a secondary number.
#
# Usage: tools/perf/competitor/cold_open.sh <out-dir> [runs] [file...]
source "$(dirname "$0")/common.sh"
OUT=$1; RUNS=${2:-5}; shift 2 || true
TARGETS=("$@"); [[ ${#TARGETS[@]} -eq 0 ]] && TARGETS=(paper_two_column.pdf heavy_plot.pdf deck_16_9.pdf)
APP_LIST=(${ONLY_APPS:-${APPS[@]}})
mkdir -p "$OUT"
REC=/sdcard/terbaca-bench-rec.mp4

for run in $(seq "$RUNS"); do
  for f in "${TARGETS[@]}"; do
    for p in "${APP_LIST[@]}"; do
      name="${p}__${f%.pdf}__$run"
      adb shell am force-stop "$p"
      adb shell input keyevent KEYCODE_HOME
      target=$f
      if [[ -n "${FRESH:-}" ]]; then
        # A copy under a name this app has never seen, so no per-file cache (thumbnail, last
        # page, rendered first page) can help.
        target="fresh-$RANDOM$RANDOM-$f"
        adb shell cp "$DIR/bench-$f" "$DIR/bench-$target"
        adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
          -d "file:///storage/emulated/0/Download/terbaca-bench/bench-$target" >/dev/null
        sleep 2
      fi
      sleep 2
      adb shell screenrecord --size 1600x1068 --time-limit 7 "$REC" &
      rec=$!
      sleep 1
      view "$p" "$target" | sed -n 's/^TotalTime: //p' > "$OUT/$name.totaltime"
      wait $rec || true
      adb pull "$REC" "$OUT/$name.mp4" >/dev/null
      adb shell rm -f "$REC"
      adb shell am force-stop "$p"
      echo "$name totaltime=$(cat "$OUT/$name.totaltime")"
    done
  done
done
