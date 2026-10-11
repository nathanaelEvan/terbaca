# Performance report: release candidate (M5-05)

Final measurement against the [PRD §7](PRD.md#7-performance-budgets-and-acceptance-gates)
budgets. The numbers below were measured on the device on 2026-10-11. Earlier runs are in
[ADR 0004](adr/0004-tile-size-skia-and-rendering-backend.md).

**Update after the M5 review (2026-10-11).** The latency sections ended when the visible tiles
were loaded, which is the frame where their 150 ms crossfade *starts*. The owner decided "sharp"
includes the crossfade (PRD §7), and the trace sections now end when the detail layer is fully
opaque. The Pad 7 wasn't connected for a re-run, so the affected rows below show the measured
number **plus 150 ms, as an estimate**. The real number can be up to one frame (~8 ms) higher,
because the section ends on the first frame after the fade is done. It can also be lower, because
tiles that were revealed early or prefetched had already started fading. These rows need a
re-measure (see "Not verified").

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
| Sharpening, typical text page, fit width | p95 ≤ 150 ms | After a pinch, with the crossfade: p50 ≈ 224, **p95 ≈ 261**, p99 ≈ 265 ms, estimated (measured to the fade start: 73.9 / 111.2 / 115.2 ms, 60 pinches). Drags and flicks (p95 7.4 ms) don't test this budget, see below | **Missed** (estimate) |
| Sharpening at 800% | p95 ≤ 300 ms | After a pinch, with the crossfade: p50 ≈ 262, **p95 ≈ 281**, p99 ≈ 286 ms, estimated (measured: 112.4 / 130.7 / 136.3 ms, 60). Drags: not a test of the budget | **Met** (tight, estimate) |
| Heavy vector page | no UI jank; sharp within 1 s | Open → sharp, with the crossfade: p95 ≈ **1,059 ms**, max ≈ 1,079 ms, estimated (measured: 909 / 929 ms, 10 opens). After a pinch: p95 ≈ 516, p99 ≈ 580 ms (measured 366 / 430). Frames: see doc 3 row | **Missed** on open (estimate) |
| Cold start → library visible | ≤ 500 ms | `timeToInitialDisplay` median **510 ms** (451–643) with the new profile. Without compilation: 539 ms. Before the new profile (same session): 530 ms | **Missed** (borderline) |
| Open 30-page paper → first page sharp | ≤ 400 ms | With the crossfade: p50 ≈ **679 ms**, p95 ≈ 803 ms, estimated (measured: 529 / 653 ms, 10 opens) | **Missed**, see breakdown |
| Total PSS, 100-page paper, active zooming | ≤ 350 MB on ≥ 2.5K displays | Peak **325 MB** (UI 306 + render 18). Typical 267–318 MB. At open: 250 MB | **Met** under the 350 MB budget from PR #67, which the owner approved on 2026-10-11. Over the earlier 250 MB |
| APK (arm64) size | ≤ 15 MB | **9.59 MB** (`app-release-unsigned.apk`): native 6.5, dex 2.8, resources 0.2, assets 0.1 MB (compressed sizes) | **Met** |
| Crash on malformed corpus PDFs | UI never crashes | No crash in the M5-01 fuzz pass ([report](fuzz-robustness-report.md)) | **Met** (from M5-01, not re-run here) |

## How the new numbers are measured

This PR adds two async trace sections to the app (`core/render/PerfTrace.kt`), and
`LatencyBenchmarks.kt` reads them from the Perfetto trace:

- **`TerbacaOpenToSharp`** starts when the activity asks to open a document, from the intent or
  from the library. It ends on the first frame where the first page's detail layer is fully
  opaque: every visible tile loaded and its crossfade finished. (Until the M5 review it ended
  when the tiles were loaded; the numbers above were measured that way.) An open that fails, opens
  a deck in slide mode, or is left before it's sharp, is left unfinished, and the benchmark ignores
  it. In the benchmark the activity is relaunched each time (warm start), so the number
  includes creating the activity, binding the render service, and copying the corpus file out of
  the APK.
- **`TerbacaSharpen`** starts when a gesture is over: the fingers are up and the fling it started
  has stopped. It ends on the first frame where the detail layer is fully opaque: every visible
  tile at the current level loaded and faded in. A new touch before that cancels it, and so does
  leaving the reader; cancelled sections are left out and counted. There were none in these runs.
  Each gesture is followed by a 1.5 s pause. Sections only start while tracing is on, so a
  release build pays only a flag check.
- **Drag and flick numbers don't test the budget.** The section starts when the fling stops, not
  when the fingers lift, and the fling's tail and the prefetch ring load (and fade in) the tiles
  before the view stops. That's why they read about one frame (~7 ms). Starting at finger-up
  instead would end the section mid-fling, while the view is still moving, which isn't "gesture
  end" either. The budget is really tested by pinches, which end on a new tile level, so pinches
  are reported on their own.

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
2. **Open → first page sharp: 529 ms measured to the fade start, ≈ 679 ms with the crossfade.**
   The crossfade adds a fixed 150 ms on top of the phases below, so the plan also needs a shorter
   fade on first open. For the paper, the median phases across 10 opens are:
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
5. **Sharpening after a pinch at fit width: p95 ≈ 261 ms with the crossfade** (budget 150,
   estimate; added by the M5 review). About 110 ms is loading the new level's tiles, and the other
   150 ms is the crossfade itself, so the fade alone uses the whole budget. **Plan:** re-measure
   first. Then shorten the crossfade (F-REN-3 allows 100 ms) and cut tile loading after a pinch,
   for example by rendering the centre tiles first. Both change how the reader feels (the batched
   fade was owner-tested), so they need a check on the Pad 7. If 150 ms still can't be met with a
   visible fade, bring the budget back to the owner instead of quietly relaxing it.
6. **Heavy vector page, open → sharp: p95 ≈ 1,059 ms with the crossfade** (budget 1 s, estimate;
   added by the M5 review). **Plan:** item 2's open-path fixes (keep the render-service binding,
   first page's tiles before prefetch) take off ~250 ms, which would put it back under 1 s.
   Re-measure after them.

## Not verified

- **The crossfade-included numbers are estimates.** No re-run on the Pad 7 has used the new trace
  end. Re-run `LatencyBenchmarks` and replace the "≈" rows.
- **"Next slide always sharp on arrival"** (slide flip row) isn't instrumented.
- **Crash on malformed PDFs** wasn't re-run here. That row is taken from M5-01, and the M5 review
  found gaps in that fuzz test.
- **Fling through the 30-page paper** wasn't measured on its own. It's folded into the doc 1 row.
- **Code version:** the numbers come from code before M5-04 (#68) and before the M5 review fixes.
  #68 only changed chrome semantics and touch targets, but its larger floating pills haven't been
  measured.
- **Baseline profile:** it has no `PerfTrace` or `openCorpusAsset` rules. That fits R8 inlining
  those small methods into their callers in the minified `benchmark` build the profile was taken
  from, but nobody checked the mapping file. The generator opens files only through the benchmark
  extra, not through a library tap or a VIEW intent, although `openFromUri` has rules. Regenerate
  it on the Pad 7 after the review fixes.

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
