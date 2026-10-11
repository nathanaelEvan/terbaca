#!/usr/bin/env bash
# Shared helpers for the competitor benchmark (docs/competitor-benchmark.md).
# Expects `adb` on PATH (set ADB_DIR to add a platform-tools folder) and one connected device.
set -euo pipefail
export MSYS_NO_PATHCONV=1 # Git Bash on Windows: keep /sdcard paths as they are
if [[ -n "${ADB_DIR:-}" ]]; then
  if command -v cygpath >/dev/null 2>&1; then
    ADB_DIR="$(cygpath -u "$ADB_DIR")"
  fi
  export PATH="$PATH:$ADB_DIR"
fi

DIR=/sdcard/Download/terbaca-bench
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
FILES=(paper_two_column.pdf heavy_plot.pdf memory_100plus.pdf deck_16_9.pdf)
APPS=(app.terbaca com.adobe.reader com.google.android.apps.docs cn.wps.moffice_eng cn.wps.xiaomi.abroad.lite com.flyersoft.moonreader)
# The activity each app registers for VIEW application/pdf (Acrobat has two; this is the viewer).
declare -A VIEWER=(
  [app.terbaca]=app.terbaca/.MainActivity
  [com.adobe.reader]=com.adobe.reader/.AdobeReader
  [com.google.android.apps.docs]=com.google.android.apps.docs/com.google.android.apps.viewer.PdfViewerActivity
  [cn.wps.moffice_eng]=cn.wps.moffice_eng/cn.wps.moffice.documentmanager.PreStartActivity2
  [cn.wps.xiaomi.abroad.lite]=cn.wps.xiaomi.abroad.lite/cn.wps.moffice.plugin.app.entrance.PDFEntranceActivity
  [com.flyersoft.moonreader]=com.flyersoft.moonreader/.ActivityMain
)

# MediaStore _id of a pushed file.
media_id() {
  adb shell content query --uri content://media/external/file --projection _id:_display_name |
    sed -n "s/.*_id=\([0-9]*\), _display_name=bench-$1.*/\1/p"
}

push_files() {
  adb shell mkdir -p "$DIR"
  for f in "${FILES[@]}"; do
    (cd "$ROOT" && adb push "testdata/corpus/$f" "$DIR/bench-$f" >/dev/null)
    adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
      -d "file:///storage/emulated/0/Download/terbaca-bench/bench-$f" >/dev/null
  done
  sleep 3
}

cleanup_files() {
  adb shell "content delete --uri content://media/external/file --where \"_display_name LIKE 'bench-%'\"" >/dev/null 2>&1 || true
  adb shell rm -rf "$DIR"
}

# Opens file $2 in package $1 through a VIEW intent on its content:// URI; prints `am start -W` output.
view() {
  local id
  id=$(media_id "$2")
  adb shell am start -W -a android.intent.action.VIEW -t application/pdf --grant-read-uri-permission \
    -d "content://media/external/file/$id" -n "${VIEWER[$1]}"
}

# Total PSS in KB summed over every running process of package $1 (main + :sub-processes).
package_pss() {
  local total=0 pid kb
  for pid in $(adb shell ps -A -o PID,NAME | awk -v p="$1" '$2 == p || index($2, p ":") == 1 {print $1}'); do
    kb=$(adb shell dumpsys meminfo "$pid" | awk '/TOTAL PSS:/ {print $3; exit}')
    total=$((total + ${kb:-0}))
  done
  echo "$total"
}

# Screen size in the current rotation, e.g. "3200 2136".
screen_size() {
  adb shell dumpsys window displays | sed -n 's/.*cur=\([0-9]*\)x\([0-9]*\).*/\1 \2/p' | head -1
}
