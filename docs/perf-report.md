# Performance report: release candidate (M5-05)

Final measurement against the [PRD §7](PRD.md#7-performance-budgets-and-acceptance-gates)
budgets. Every number below was measured on the device on 2026-10-11. Earlier runs are in
[ADR 0004](adr/0004-tile-size-skia-and-rendering-backend.md).

## Setup

- **Device:** Xiaomi Pad 7 (2410CRP4CG), Android 16, HyperOS OS3.0, 3200×2136 at 120 Hz (HyperOS
  caps apps at 120 Hz, see ADR 0004), landscape, USB powered, battery ~55%.
- **Build:** the `benchmark` build type (release with R8, non-debuggable). The baseline profile was
  regenerated in this PR, see "Baseline profile".
- **Code:** `main` at 783b7fa plus this PR. That is before M5-04 (#68) merged; #68 only changes chrome semantics and touch-target sizes, not rendering.
- **Harness:** the `:benchmark` module (Macrobenchmark 1.5.0, `CompilationMode.Partial()`), plus
  [`tools/perf/measure_pss.sh`](../tools/perf/measure_pss.sh) for memory.
- **Frame percentages** use `frameDurationCpuMs`. "Late" means `frameOverrunMs > 0`, the same as
  in ADR 0004.

## Results

| Metric (§7) | Budget | Measured | Status |
|---|---|---|---|
| Pinch/pan/fling, doc 1 (two-column paper) | ≥ 99% ≤ 8.33 ms; 0 > 16.6 ms | 94.03% / 1 frame (17.0 ms); 3,235 frames. Earlier runs today: 98.01% / 2 and 94.72% / 1 (on `main`, without this PR) | **Missed** |
| Pinch/pan/fling, doc 3 (heavy vector plot) | same | 99.32% / 0 (max 15.0 ms); 3,660 frames | **Met** |
| Pinch/pan/fling, doc 5 (16:9 deck) | same | 99.57% / 1 frame (16.8 ms); 2,105 frames | **Met** for the 99%; one frame 0.2 ms over 16.6 |
| Fling through 30-page paper | same as doc 1 | Covered by the doc 1 row (two flings per iteration) | **Missed** (as doc 1) |
| Slide flip (16 flicks × 5) | no frame > 8.33 ms; sharp on arrival | 95.61% ≤ 8.33 ms, max 13.6 ms, 0 > 16.6 ms. "Sharp on arrival" not instrumented | **Missed** |
| Sharpening, typical text page, fit width | p95 ≤ 150 ms | After a pinch: p50 73.9, **p95 111.2**, p99 115.2 ms (60 pinches). After a drag or flick: p95 7.4 ms (one frame: already sharp) | **Met** |
| Sharpening at 800% | p95 ≤ 300 ms | After a pinch: p50 112.4, **p95 130.7**, p99 136.3 ms (60). After a drag: p95 7.4 ms | **Met** |
| Heavy vector page | no UI jank; sharp within 1 s | Open → sharp p95 909 ms, max 929 ms (10 opens). After a pinch: p95 366 ms, p99 430 ms. Frames: see doc 3 row | **Met** (tight on open) |
| Cold start → library visible | ≤ 500 ms | `timeToInitialDisplay` median **510 ms** (451–643) with the new profile. Without compilation: 539 ms. Before the new profile (same session): 530 ms | **Missed** (borderline) |
| Open 30-page paper → first page sharp | ≤ 400 ms | p50 **529 ms**, p95 653 ms (10 opens) | **Missed**, see breakdown |
| Total PSS, 100-page paper, active zooming | ≤ 350 MB on ≥ 2.5K displays | Peak **325 MB** (UI 306 + render 18). Typical 267–318 MB. At open: 250 MB | **Met** under the budget raised in PR #67. Over the earlier 250 MB |
| APK (arm64) size | ≤ 15 MB | **9.59 MB** (`app-release-unsigned.apk`): native 6.5, dex 2.8, resources 0.2, assets 0.1 MB (compressed sizes) | **Met** |
| Crash on malformed corpus PDFs | UI never crashes | No crash in the M5-01 fuzz pass ([report](fuzz-robustness-report.md)) | **Met** (from M5-01, not re-run here) |

## How the new numbers are measured

This PR adds two async trace sections to the app (`core/render/PerfTrace.kt`), and
`LatencyBenchmarks.kt` reads them from the Perfetto trace:

- **`TerbacaOpenToSharp`** starts when the activity asks to open a document, from the intent or
  from the library. It ends on the first frame where every visible detail tile of the first page
  is loaded. In the benchmark the activity is relaunched each time (warm start), so the number
  includes creating the activity, binding the render service, and copying the corpus file out of
  the APK.
- **`TerbacaSharpen`** starts when a gesture is over: the fingers are up and the fling it started
  has stopped. It ends on the first frame where every visible tile at the current level is loaded.
  A new touch before that cancels it, and cancelled sections are left out and counted. There were
  none in these runs. Each gesture is followed by a 1.5 s pause.
- Drags and flicks end sharp, because the fling's tail and the prefetch ring load the tiles before
  the view stops. That's why they read about one frame (~7 ms). The budget is really tested by
  pinches, which end on a new tile level, so pinches are reported on their own.
- The crossfade (150 ms, F-REN-3) starts at that frame and isn't included.

Memory: `tools/perf/measure_pss.sh` opens `memory_100plus.pdf` (105 pages) through MediaStore. It
then runs 6 rounds of flinging forward, zooming in 8 steps with Ctrl+=, panning, and zooming out.
After each step it sums `dumpsys meminfo` TOTAL PSS of `app.terbaca` and `app.terbaca:render`.

## Misses and plans

1. **Paper frames (doc 1): 94–98%, was 99.4–99.5% in M2.** The UI thread isn't the cause: in 3
   iterations only 2 `doFrame`s went over 8.33 ms. The slow frames are on the RenderThread: 31 slow
   `DrawFrame`s, 21 of them blocked in `eglSwapBuffers → queueBuffer → waitForever` (136 ms total)
   and 14 in the GPU `flush`. So the frame waits for the compositor and the GPU, as on the deck in
   M1 (ADR 0004). The spread between runs on the same code (94.0–98.0%) is large, so part of it is
   device state. **Plan:** compare a trace of M2's commit with today's, on the same device state,
   to find what adds GPU work on paper pages (M3/M4 overlays: search highlights, theme shader,
   selection layer). Then cut overdraw there.
2. **Open → first page sharp: 529 ms.** For the paper, the median phases across 10 opens are:
   - ~250 ms before the render process is reachable. Relaunching the activity, re-binding the
     render service, and system_server lock contention while the previous activity is destroyed
     take most of it.
   - ~300 ms from the first render call until every tile of the first page is in. That's about
     30 tiles of 512 px, one at a time on PDFium's single thread.

   **Plan:** keep the render-service binding alive across activity recreation (bind from the
   Application, or keep it in a retained holder), so a relaunch doesn't wait for a new bind. Then
   render the first page's visible tiles before any prefetch.
3. **Slide flip: 95.6% ≤ 8.33 ms** (max 13.6 ms, none over 16.6). It was 97.2–97.4% in M2, and the
   new baseline profile moved it from 94.5% to 95.6% today. ADR 0004 found the slow frames during
   the drag (input + pager `measureAndLayout` + draw). **Plan:** as in ADR 0004, cut the pager's
   per-frame layout work during a drag.
4. **Cold start: 510 ms median** (budget 500). The regenerated profile took about 20 ms off.
   **Plan:** profile the library's first frame (Room query and per-file availability checks),
   and show the grid before the availability checks finish.

## Baseline profile

`app/src/main/baseline-prof.txt` was regenerated on the Pad 7 with `BaselineProfileGenerator`
(cold start, the paper with pinch/pan/fling, and now slide flips on the deck). The old file was
from M1 and covered none of the library, slides, search or chrome code. It had 238 `app/terbaca`
rules; the new one has 655.

## Reproduce

```sh
./gradlew :benchmark:connectedBenchmarkAndroidTest   # all benchmarks; results in benchmark/build/outputs/connected_android_test_additional_output
./gradlew :app:installBenchmark && tools/perf/measure_pss.sh <adb-serial>
./gradlew :app:assembleRelease                       # APK size: app/build/outputs/apk/release/
```
