#!/usr/bin/env bash
# Frame timing (scroll flings, pinch zoom) for every app in common.sh,
# through :benchmark's CompetitorBenchmarks. Install the test APK first:
#   ./gradlew :app:installBenchmark :benchmark:installBenchmark
#
# Usage: tools/perf/competitor/frames.sh <out-dir> [test...]   (tests: scrollFlings pinchZoom)
source "$(dirname "$0")/common.sh"
OUT=$1; shift
TESTS=("$@"); [[ ${#TESTS[@]} -eq 0 ]] && TESTS=(scrollFlings pinchZoom)
APP_LIST=(${ONLY_APPS:-${APPS[@]}})
FILE=${FILE:-paper_two_column.pdf}
RUNNER=app.terbaca.benchmark/androidx.test.runner.AndroidJUnitRunner
MEDIA=/sdcard/Android/media/app.terbaca.benchmark
mkdir -p "$OUT"
id=$(media_id "$FILE")

for p in "${APP_LIST[@]}"; do
  for t in "${TESTS[@]}"; do
    name="${p}__${t}"
    adb shell rm -rf "$MEDIA/additional_test_output" >/dev/null 2>&1 || true
    adb shell am instrument -w \
      -e class "app.terbaca.benchmark.CompetitorBenchmarks#$t" \
      -e competitor.package "$p" -e competitor.component "${VIEWER[$p]}" -e competitor.mediaId "$id" \
      -e androidx.benchmark.suppressErrors EMULATOR,DEBUGGABLE \
      "$RUNNER" > "$OUT/$name.instrument.txt" 2>&1 || true
    json=$(adb shell "ls $MEDIA/*benchmarkData.json 2>/dev/null" | tr -d '\r' | head -1)
    if [[ -n "$json" ]]; then
      adb pull "$json" "$OUT/$name.json" >/dev/null 2>&1
      adb shell rm -f "$json"
    fi
    adb shell am force-stop "$p"
    echo "$name: $(grep -cE 'OK \(|FAILURES' "$OUT/$name.instrument.txt") result line(s)"
  done
done
