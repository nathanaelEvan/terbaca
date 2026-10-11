# Terbaca vs other PDF viewers

The same PDFs and the same gestures, on the same tablet, on the same day. Measured
on 2026-10-11 on a Xiaomi Pad 7 (2410CRP4CG, Android 16 / HyperOS, 3200×2136, 120 Hz). Raw
results are in [`competitor-benchmark/`](competitor-benchmark). The scripts are in
[`tools/perf/competitor/`](../tools/perf/competitor), and the gesture tests are in
[`CompetitorBenchmarks.kt`](../benchmark/src/main/kotlin/app/terbaca/benchmark/CompetitorBenchmarks.kt).

## Apps

| App | Package | Version | Opened through |
|---|---|---|---|
| **Terbaca** | `app.terbaca` | 0.1.0, `benchmark` build (R8, baseline profile) of `main` at 9e038ea | VIEW intent |
| Adobe Acrobat Reader | `com.adobe.reader` | 26.9.1.49959 | VIEW intent → `.AdobeReader` |
| Google Drive (PDF viewer) | `com.google.android.apps.docs` | 2.26.397.0 | VIEW intent → `PdfViewerActivity` |
| WPS Office | `cn.wps.moffice_eng` | 26.9.1 | VIEW intent |
| Xiaomi's built-in viewer (WPS lite) | `cn.wps.xiaomi.abroad.lite` | 2.6.9 | VIEW intent |
| Moon+ Reader | `com.flyersoft.moonreader` | 10.7 | VIEW intent |

No app was signed into, cleared or reconfigured. Each file was opened through the same
`content://` MediaStore URI that a file manager would hand over. Files came from
[`testdata/corpus`](../testdata/corpus): `paper_two_column.pdf` (30 pages), `heavy_plot.pdf`
(5,000+ vector segments), `deck_16_9.pdf` and `memory_100plus.pdf` (105 pages).

## Results

Best value per row in **bold**. Lower is better on every row.

| | Terbaca | Acrobat | Drive | WPS | Xiaomi | Moon+ |
|---|---|---|---|---|---|---|
| Cold open → first page visible, paper, median ms | 658 | 1,815 | 870 | 1,991 | **306** | 883 |
| Cold open, heavy plot | 681 | 1,962 | 876 | 1,953 | **348** | 865 |
| Cold open, 16:9 deck | 661 | 1,823 | 830 | 1,942 | **235** | 851 |
| Scrolling the paper: late frames % | 34.6 | 4.8 | 3.6 | **1.4** | 3.8 | n/c |
| Pinch zoom on the paper: late frames % | 29.4 | 15.3 ¹ | **5.2** | 9.6 | 27.1 | 2.5 ² |
| Memory peak, 105-page doc, scrolling, MB | **324** | 998 | 725 | 967 | 416 | 491 ³ |
| Installed size, MB | 9.6 | 301.1 | 59.0 | 337.6 | **9.4** | 54.5 |

n/c: not comparable (see notes). ¹ Acrobat draws about 400 frames per pinch test where the others
draw 800–1,100, so it updates the zoom less often than the display can. The late-frame share
doesn't show that. ² The test didn't check that Moon+ actually zoomed. ³ Moon+ shows the PDF as
side-by-side pages, so vertical swipes didn't scroll it.

**In short:**
- **Terbaca wins:** memory. It uses the least, a third of Acrobat's and WPS's. It's also tied
  for smallest app with Xiaomi's viewer.
- **Terbaca is second:** opening a file. It's about 650 ms, faster than Drive, Moon+, Acrobat
  (2.8×) and WPS (3×). Only Xiaomi's built-in viewer is faster, at about 300 ms.
- **Terbaca loses:** smoothness. About a third of its frames miss the display's deadline while
  scrolling, and 29 % while pinching. Drive, WPS, Xiaomi and Acrobat all stay under 5 % when
  scrolling. This is the biggest gap, and the next thing to fix (see "Terbaca's smoothness" below).

### Spread

Five runs each for opening and the gesture tests, three for memory. Medians with min–max:

- **Cold open, paper (ms):**
  - Terbaca 658 (562–708)
  - Acrobat 1,815 (1,665–2,102)
  - Drive 870 (801–998)
  - WPS 1,991 (1,843–2,320)
  - Xiaomi 306 (277–378)
  - Moon+ 883 (843–888)
  
  The other files are in [`summary.txt`](competitor-benchmark/summary.txt).
- **Late frames per iteration, scroll (%):**
  - Terbaca 30.8–39.6
  - Acrobat 2.1–10.4
  - Drive 2.1–9.5
  - WPS 1.1–1.6
  - Xiaomi 1.7–6.0
- **Late frames per iteration, pinch (%):**
  - Terbaca 26.2–34.6
  - Acrobat 5.1–26.3
  - Drive 3.7–6.5
  - WPS 8.8–10.9
  - Xiaomi 22.5–29.7
- **Memory peak (MB):**
  - Terbaca 324 (324–327)
  - Acrobat 998 (830–1,225)
  - Drive 725 (716–726)
  - WPS 967 (961–971)
  - Xiaomi 416 (414–417)
  - Moon+ 491 (488–501)

## Method

**Cold open → first page visible** (`cold_open.sh`, `analyze_open.py`). Each run:
1. Force-stop the app and go to the home screen.
2. Start `screenrecord` (1600×1068) and send the VIEW intent.
3. Record for 7 s.

In the video:
- **Start** is the first frame that differs from the home screen.
- **End** is the first frame where the middle of the screen shows the page's ink (text or plot
  lines, at least 60 % of what the final frame shows) on a light background, and holds still for
  400 ms.

`screenrecord` writes a frame only when the screen changes, each with its real time stamp, so the
result is accurate to about one display frame (8 ms). This is **first page visible, not sharp**:
an app that shows a blurry first pass counts as soon as the ink is there.

I spot-checked the start and end stills for every app. `am start -W` TotalTime is in the CSV as a
secondary number. It only times the activity's first frame, often a blank page.

A second pass (`FRESH=1`, 3 runs) opened copies under names the apps had never seen, to rule out
per-file caches. The results matched: Terbaca 650, Acrobat 1,769, Drive 851, WPS 1,834,
Xiaomi 305, Moon+ 844 ms.

**Late frames** (`frames.sh` → `CompetitorBenchmarks`). These are Jetpack Macrobenchmark runs with
`FrameTimingMetric`, which reads Android's own frame timeline in a Perfetto trace. That works for
any app, with no access to its code. Each of the 5 iterations opens the paper fresh, then:
- **Scroll:** six identical flings down, then six up (UiAutomator swipes, 6 steps, 700 ms apart).
- **Pinch:** three pinch-out/pinch-in pairs (60 %, 2,500 px/s).

"Late" means a frame finished after its display deadline (`frameOverrunMs` > 0), which shows on
screen as a stutter. `CompilationMode.Ignore` leaves every app's compiled code as installed.
P50/P90/P99 overrun and CPU frame times are in [`summary.txt`](competitor-benchmark/summary.txt).
The per-frame JSON files (about 4 MB) aren't committed. Rerunning `frames.sh` regenerates them.

**Memory** (`memory.sh`). Open `memory_100plus.pdf`, then do 5 rounds of 4 flings forward. After
opening and after each round, sum the total PSS (`dumpsys meminfo`) of every running process of
the package. Report the peak. It's 3 runs per app. adb can't pinch, so this run doesn't zoom.

**Size** (`app_size.sh`): the bytes of every installed APK split (`pm path`).

## Caveats

- **The other apps do more.** Acrobat, WPS and Drive edit, sync, and run accounts and AI
  features, which explains much of their size and memory. Terbaca is a read-only viewer. The
  comparison is fair for "open and read a PDF", not for features.
- **Xiaomi's viewer is a system app.** It comes with HyperOS. It may get preloading or other
  system help that a store app can't, and its installed APK may rely on shared system code. Its
  9.4 MB and fast open are measured, but I didn't check how much the system contributes.
- **Scroll physics differ.** The same swipe moves each app a different distance, so each app
  draws a different amount per frame. Late-frame share is still the fairest single number for
  "does it stutter".
- **One device, one day, one build of each app.** HyperOS showed a floating live-activity bubble
  (a sports score) at the left edge during the runs. The video analysis ignores the outer 8 % of
  each side.
- The Terbaca numbers here use a different measure than the PRD §7 report
  ([`perf-report.md`](perf-report.md)). That report counts frames whose app-side work fits 8.33 ms.
  Here, a frame counts as late when the whole frame, including RenderThread and GPU, misses the
  display deadline. They agree on the cause: the perf report already traced Terbaca's paper-scroll
  misses to RenderThread `queueBuffer`/GPU flush.

## Terbaca's smoothness

This is the one area where Terbaca is clearly behind: 35 % late frames while scrolling, against
1–5 % for the other viewers on the same swipes. Pinching is also high, at 29 %, though Xiaomi's
viewer is about the same (27 %). The perf report traced the paper-scroll misses to RenderThread
`queueBuffer`/GPU flush, not to the app's own per-frame work. That makes it the top follow-up from
M5 ("Misses and plans" #1 in the perf report).

## Not measured / not comparable

- **Zoom → sharp time.** I tried timing it from screen recordings around a UiAutomator pinch, and
  dropped it. At recording resolution (half the panel), after video compression, the blurry and
  sharp frames can't be told apart reliably. Apps also reopen a file at its last zoom, which made
  the runs inconsistent. Terbaca's own trace-based number is in the perf report. There's no
  equivalent for apps we don't own.
- **Moon+ scroll and memory.** Moon+ pages sideways, so the vertical swipes didn't scroll it. Its
  pinch result is listed, but the test didn't check that it zoomed.
- **Rendering quality**: text sharpness, color, correctness.
- **Heavy-page stutter, battery, warm start.**
- **Search, text selection and outline speed.**
- **Portrait orientation, and other devices.**
