# Performance report: release candidate (M5-05)

Final measurement against the [PRD §7](PRD.md#7-performance-budgets-and-acceptance-gates)
budgets. The numbers below were measured on the device on 2026-10-11. Earlier runs are in
[ADR 0004](adr/0004-tile-size-skia-and-rendering-backend.md).

> **M6-01 baseline re-measure (2026-10-12).** The baseline for milestone M6 is in
> [M6-01 baseline](#m6-01-baseline-2026-10-12) below: the latency sections now end when the
> detail layer is fully opaque, so the rows that carried a "≈" estimate are measured. The
> `Results` table keeps the M5-05 numbers and adds the M6-01 ones beside them; where the two
> describe the same thing, M6-01 is the current baseline.

**Update after the M5 review (2026-10-11).** The latency sections ended when the visible tiles
were loaded, which is the frame where their 150 ms crossfade *starts*. The owner decided "sharp"
includes the crossfade (PRD §7), and the trace sections now end when the detail layer is fully
opaque. The Pad 7 wasn't connected for a re-run, so the affected rows below show the measured
number **plus 150 ms, as an estimate**. The real number can be up to one frame (~8 ms) higher,
because the section ends on the first frame after the fade is done. It can also be lower, because
tiles that were revealed early or prefetched had already started fading. These rows need a
re-measure (see "Not verified"). **Every one of them was re-measured in M6-01; see below.**

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

This table is the M5-05 measurement of 2026-10-11; the M6 baseline is the
[M6-01 section](#m6-01-baseline-2026-10-12) above. Rows that M6-01 re-measured are marked.

| Metric (§7) | Budget | Measured (M5-05, 2026-10-11) | Status |
|---|---|---|---|
| Pinch/pan/fling, doc 1 (two-column paper) | ≥ 99% ≤ 8.33 ms; 0 > 16.6 ms | 94.03% / 1 frame (17.0 ms); 3,235 frames. Earlier runs today: 98.01% / 2 and 94.72% / 1 (on `main`, without this PR) | **Missed** |
| Pinch/pan/fling, doc 3 (heavy vector plot) | same | 99.32% / 0 (max 15.0 ms); 3,660 frames | **Met** |
| Pinch/pan/fling, doc 5 (16:9 deck) | same | 99.57% / 1 frame (16.8 ms); 2,105 frames | **Met** for the 99%; one frame 0.2 ms over 16.6 |
| Fling through 30-page paper | same as doc 1 | Covered by the doc 1 row (two flings per iteration) | **Missed** (as doc 1) |
| Slide flip (16 flicks × 5) | no frame > 8.33 ms; sharp on arrival | 95.95% ≤ 8.33 ms | **100% sharp on arrival** (80/80 in each of 3 runs; was 75.0% before M6R-05b), 95.94% ≤ 8.33 ms CPU (median of 3 runs, 95.92–95.97), 3–9 frames > 16.6 ms per run (`docs/perf-raw/m6r-05b/`) | **Missed** (frames over 8.33 ms remain) |
| Sharpening, typical text page, fit width | p95 ≤ 150 ms | After a pinch, with the crossfade: p50 ≈ 224, **p95 ≈ 261**, p99 ≈ 265 ms, estimated (measured to the fade start: 73.9 / 111.2 / 115.2 ms, 60 pinches). Drags and flicks (p95 7.4 ms) don't test this budget, see below | **Missed** (estimate) |
| Sharpening at 800% | p95 ≤ 300 ms | After a pinch, with the crossfade: p50 ≈ 262, **p95 ≈ 281**, p99 ≈ 286 ms, estimated (measured: 112.4 / 130.7 / 136.3 ms, 60). Drags: not a test of the budget | **Met** (tight, estimate) |
| Heavy vector page | no UI jank; sharp within 1 s | Open → sharp, with the crossfade: p95 ≈ **1,059 ms**, max ≈ 1,079 ms, estimated (measured: 909 / 929 ms, 10 opens). After a pinch: p95 ≈ 516, p99 ≈ 580 ms (measured 366 / 430). Frames: see doc 3 row | **Missed** on open (estimate) |
| Cold start → library visible | ≤ 500 ms | `timeToInitialDisplay` median **510 ms** (451–643) with the new profile. Without compilation: 539 ms. Before the new profile (same session): 530 ms | **Missed** (borderline) |
| Open 30-page paper → first page sharp | ≤ 400 ms | With the crossfade: p50 ≈ **679 ms**, p95 ≈ 803 ms, estimated (measured: 529 / 653 ms, 10 opens) | **Missed**, see breakdown |
| Total PSS, 100-page paper, active zooming | ≤ 350 MB on ≥ 2.5K displays | Peak **325 MB** (UI 306 + render 18). Typical 267–318 MB. At open: 250 MB | **Met** under the 350 MB budget from PR #67, which the owner approved on 2026-10-11. Over the earlier 250 MB |
| APK (arm64) size | ≤ 15 MB | **9.59 MB** (`app-release-unsigned.apk`): native 6.5, dex 2.8, resources 0.2, assets 0.1 MB (compressed sizes) | **Met** |
| Crash on malformed corpus PDFs | UI never crashes | No crash in the M5-01 fuzz pass ([report](fuzz-robustness-report.md)) | **Met** (from M5-01, not re-run here) |

## M6-01 baseline (2026-10-12)

The M6 baseline, measured on the Pad 7 before any M6 change. This replaces every "≈" estimate that
the M5 review left behind: the trace sections end when the detail layer is fully opaque, so
"sharp" includes the crossfade (PRD §7, [decisions](decisions.md)).

**Setup**

- **Device:** Xiaomi Pad 7 (2410CRP4CG), Android 16, HyperOS OS3.0.303.0.WOZIDXM, 3200×2136 at
  120 Hz, landscape (locked), animations off, USB powered, battery 55–58 %, thermal status 0
  throughout (per-run battery, temperature and CPU-zone readings are in
  [`perf-raw/m6-01/device-state.jsonl`](perf-raw/m6-01/device-state.jsonl)).
- **Build:** `:app` `benchmark` build type (release config: R8, resource shrinking,
  non-debuggable, debug-signed) from `main` at 82b63ba. Reinstalled for this run, so the recents
  database was empty.
- **Harness:** `:benchmark` (Macrobenchmark 1.5.0, `CompilationMode.Partial()`), the same classes
  as the M5-05 run. Frame benchmarks were run 3×, latency 2×; room temperature and battery were
  recorded per run.
- **Raw evidence:** [`perf-raw/m6-01/`](perf-raw/m6-01) — `frames.csv`, `latency.csv`,
  `startup.csv` (one row per sample/run, aggregated from the Macrobenchmark
  `benchmarkData.json` files of these runs), `summary.md`, and the device state. The JSON files
  themselves and the Perfetto traces are reproducible, not committed.

| Metric (§7) | Budget | M6-01 baseline (2026-10-12) | Status |
|---|---|---|---|
| **Competitor harness, scrolling the paper** (M6 exit criterion: ≤ 5 % late frames) | `frameOverrunMs > 0` | **42.6 % late** (2,480 frames), 92.6 % ≤ 8.33 ms, P50 overrun −1.93 ms, P90 +1.37 ms | **Missed**, baseline for M6-03 |
| **Competitor harness, pinching the paper** (M6 exit criterion: ≤ 10 %) | `frameOverrunMs > 0` | **25.0 % late** (3,780 frames), 90.0 % ≤ 8.33 ms, P50 overrun −1.85 ms, P90 +1.87 ms | **Missed**, baseline for M6-04 |
| Pinch/pan/fling, doc 1 (two-column paper) | ≥ 99% ≤ 8.33 ms; 0 > 16.6 ms | 3 runs: 95.21 / 97.80 / 97.28 % ≤ 8.33 ms (2,409–2,463 frames); 0 / 0 / 2 frames > 16.6 ms (max 20.9 ms). Late for display: 11.6 / 7.0 / 6.8 % | **Missed** |
| Pinch/pan/fling, doc 3 (heavy vector plot) | same | 3 runs: 98.50 / 98.48 / 98.37 % (2,739–2,761 frames); 3 / 1 / 1 frames > 16.6 ms (max 24.2 ms) | **Missed** on the 99 %; the > 16.6 ms clause is not met either |
| Pinch/pan/fling, doc 5 (16:9 deck) | same | 3 runs: 99.42 / 99.48 / 97.29 % (1,720–1,736 frames); 0 frames > 16.6 ms (max 16.3 ms) | **Met** in 2 of 3 runs; the third is a device-state outlier |
| Fling through 30-page paper | same as doc 1 | Covered by the doc 1 row (two flings per iteration) | **Missed** (as doc 1) |
| Slide flip (16 flicks × 5) | no frame > 8.33 ms; sharp on arrival | 3 runs: 95.95 / 94.03 / 96.48 % ≤ 8.33 ms (2,296–2,302 frames); 5 / 5 / 3 frames > 16.6 ms (max 22.1 ms). "Sharp on arrival" still not instrumented | **Missed** |
| Sharpening, typical text page, fit width | p95 ≤ 150 ms | After a pinch: p50 **250**, p95 **258** ms (2 runs, n = 5 each). Drags/flicks: p95 6.8 ms, which does not test this budget | **Missed**, see below |
| Sharpening at 800% | p95 ≤ 300 ms | After a pinch: p50 **270**, p95 **292** ms (2 runs, n = 5 each) | **Met** |
| Heavy vector page | no UI jank; sharp within 1 s | Open → sharp: p50 **766**, p95 **835** ms (2 runs, n = 10 each). After a pinch at fit: p50 466 / 558, max 633 ms | **Met** on open |
| Cold start → library visible | ≤ 500 ms | `timeToInitialDisplay` median **460 ms** (3 runs: 509 / 460 / 440 ms; 15 samples, range 364–552 ms) | **Met** (borderline; the first run of the session was 509 ms) |
| Open 30-page paper → first page sharp | ≤ 400 ms | p50 **451 / 552**, p95 **802 / 655** ms (2 runs, n = 10 each) | **Missed** |
| Total PSS, 100-page paper, active zooming | ≤ 350 MB on ≥ 2.5K displays | Not re-measured in M6-01 | M5-05 number (325 MB) still stands |
| APK (arm64) size | ≤ 15 MB | Not re-measured in M6-01 | M5-05 number (9.59 MB) still stands |
| Crash on malformed corpus PDFs | UI never crashes | Not re-measured in M6-01 | M5-01 fuzz pass |

**What the baseline confirms for M6**

1. **Scrolling is the gap, and it is on the display deadline, not the app's frame time.** The
   competitor harness (the measurement M6's exit criterion is written in) puts the paper at
   **42.6 % late frames**; `ScrollBenchmark` puts the same code at 92.6 % of frames ≤ 8.33 ms with
   6.8–11.6 % of frames missing the display deadline. Both agree on the cause and the direction:
   the app's own frame work mostly fits, and the misses are downstream, on the RenderThread. M6-03
   has to move the competitor number under 5 %.
2. **Pinch starts at 25.0 % late frames** against a 10 % target (M6-04).
3. **The sharpening budgets are close, not unreachable.** Fit width is p95 258 ms against 150 ms,
   and the 150 ms crossfade alone is most of it. At 800 % the same pipeline is inside its budget
   at p95 292 ms. That matches the M5 plan: shorten the fade and render the centre tiles first.
4. **Only the open→sharp budget is far off** (p95 655–802 ms against 400 ms). ~250 ms of it is
   still before the render process is reachable, which is what M6-05 targets.
5. **Cold start already reads inside its budget** (median 460 ms across the session, 509 ms in the
   first run). Not a regression statement: this build is a fresh install with no baseline-profile
   regeneration, so M6-08 and M6-10 should measure again before claiming the budget.
6. **This is the baseline, not a regression check.** The perf report's M5 rows came from code at
   783b7fa; the tree has moved since (M5 review fixes, #68 chrome, #73–#76). Frame shares are also
   device-state sensitive: the same code swung 94.0–98.0 % in the M5-05 session and 97.3–97.8 %
   here on the paper, and a single competitor-harness run read 42.6 % against 30.8–39.6 % in the
   M5 session. Compare an M6 change against repeated runs of these, not against a single one.

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

**Closed by M6-01 (2026-10-12):** the crossfade-included latency numbers are now measured, not
estimated (see [M6-01 baseline](#m6-01-baseline-2026-10-12)), and startup was re-measured on the
current tree.

Still open:

- **"Next slide always sharp on arrival"** (slide flip row): instrumented and measured in M6R-05 (`docs/perf-raw/m6r-05/`). Yields 75.0% sharp on arrival (12/16 flips per iter; misses follow a deterministic pattern: forward pass misses every 3rd flip, backward pass misses flips 1 and 4). Marked **Missed**.
- **Crash on malformed PDFs** wasn't re-run in M6-01. That row is taken from M5-01, and the M5
  review found gaps in that fuzz test.
- **Fling through the 30-page paper** wasn't measured on its own. It's folded into the doc 1 row.
- **Total PSS and APK size** weren't re-measured in M6-01; those rows still show M5-05 numbers.
- **Baseline profile:** it has no `PerfTrace` or `openCorpusAsset` rules. That fits R8 inlining
  those small methods into their callers in the minified `benchmark` build the profile was taken
  from, but nobody checked the mapping file. The generator opens files only through the benchmark
  extra, not through a library tap or a VIEW intent, although `openFromUri` has rules. M6-10
  regenerates it on the Pad 7.
- **A note on the M6-01 competitor runs:** the first attempt at `CompetitorBenchmarks#pinchZoom`
  was killed by a bad `adb` invocation on the host, and its truncated output read as
  `Process crashed`. It was re-run cleanly; the committed
  [`instrument.txt`](perf-raw/m6-01/app.terbaca__pinchZoom.instrument.txt) shows all 5 iterations
  finishing (`OK (1 test)`). Nothing in the app crashed, and the first attempt's output is void.

## Baseline profile

`app/src/main/baseline-prof.txt` was regenerated on the Pad 7 with `BaselineProfileGenerator`
(cold start, VIEW-intent open path, library-tap open path, the paper with pinch/pan/fling, and slide flips on the deck).

## M6-10 final release verification (2026-10-12)

Final re-measure across all benchmarks on the Xiaomi Pad 7 following completion of M6 optimizations (scroll fixes, pinch smoothness, centre-first sharpening, slide flip, library cold start, and verification gaps).

**Setup**
- **Device:** Xiaomi Pad 7 (2410CRP4CG), Android 16, HyperOS OS3.0.303.0.WOZIDXM, 3200×2136 @ 120 Hz, USB powered.
- **Build:** `:app` `benchmark` build type with regenerated baseline profile (M6-10) and release R8 optimization.
- **Harness:** `:benchmark` (Macrobenchmark 1.5.0, `CompilationMode.Partial()`), competitor frames harness (`tools/perf/competitor/frames.sh`).
- **Raw outputs:** [`docs/perf-raw/m6-10/`](perf-raw/m6-10) (`app.terbaca__scrollFlings.json`, `app.terbaca__pinchZoom.json`, summarized by `summarize.py`).

| Metric (§7) | Budget | M6-01 baseline | M6-10 Final Measured | Status |
|---|---|---|---|---|
| **Competitor harness, scrolling the paper** | `frameOverrunMs > 0` (≤ 5% late) | 34.6 % late (v0.1.0 in `docs/competitor-benchmark/summary.txt`) | **10.4 % late** (5,663 frames), 1,133 frames/iter, per-iteration late: 1.5–13.4% (median 12.5%), P50 overrun **-5.82 ms**, P90 **+0.12 ms**, CPU P50 **2.70 ms** | **Missed** |
| **Competitor harness, pinching the paper** | `frameOverrunMs > 0` (≤ 10% late) | 25.0 % late, P50 overrun -1.85 ms, P90 +1.87 ms | **29.9 % late** (4,196 frames), 839 frames/iter, per-iteration late: 22.9–34.1% (median 30.4%), P50 overrun **-1.56 ms**, P90 **+1.98 ms**, CPU P50 **4.86 ms** | **Missed** |
| Pinch/pan/fling, doc 1 (two-column paper) | ≥ 99% ≤ 8.33 ms; 0 > 16.6 ms | P50 CPU 3.8 ms | Not committed in `perf-raw/m6-10` | **Not measured** (raw files omitted; to be re-measured in M6R-10) |
| Pinch/pan/fling, doc 3 (heavy vector plot) | same | 98.48% ≤ 8.33 ms | Not committed in `perf-raw/m6-10` | **Not measured** (raw files omitted; to be re-measured in M6R-10) |
| Pinch/pan/fling, doc 5 (16:9 deck) | same | 99.42% ≤ 8.33 ms | Not committed in `perf-raw/m6-10` | **Not measured** (raw files omitted; to be re-measured in M6R-10) |
| Slide flip (16 flicks × 5) | no frame > 8.33 ms; sharp on arrival | 95.95% ≤ 8.33 ms | **75.0% sharp on arrival** (12/16 sharp per iter; 4 misses/iter), 95.56% ≤ 8.33 ms, 4 frames > 16.6 ms (4.74% late) (`docs/perf-raw/m6r-05/`) | **Missed** |
| Sharpening, text page, fit width | p95 ≤ 150 ms | p50 250 ms, p95 258 ms | After pinch: p50 200.8 ms, p90 242.4 ms | **Missed** (p90 242.4 ms exceeds p95 budget of 150 ms; p95 raw data not committed) |
| Sharpening at 800% | p95 ≤ 300 ms | p50 270 ms, p95 292 ms | Not committed in `perf-raw/m6-10` | **Not measured** (raw latency files omitted; to be re-measured in M6R-10) |
| Heavy vector page | open sharp ≤ 1 s | p50 766 ms, p95 835 ms | Not committed in `perf-raw/m6-10` | **Not measured** (raw latency files omitted; to be re-measured in M6R-10) |
| Cold start → library visible | ≤ 500 ms | median 460 ms | min 511 ms, median 563 ms | **Missed** (median 563 ms vs ≤ 500 ms budget; regressed from 460 ms in M6-01) |
| Open 30-page paper → first page sharp | ≤ 400 ms | p50 451–552 ms | p50 579 ms, p90 782 ms (PR #82 pending rework) | **Missed** |
| Total PSS, 100-page paper | ≤ 350 MB | 325 MB | 324 MB (`docs/competitor-benchmark/summary.txt` from 2026-10-11) | **Not measured** (not re-measured in M6-10; earlier competitor run cited) |
| APK (arm64) size | ≤ 15 MB | 9.59 MB | **9.62 MB** (`app-release.apk`) | **Met** |
| Crash on malformed corpus PDFs | UI never crashes | Met (M5-01) | Not re-run in M6-10 | **Not measured** (not re-run in M6-10; carried over from M5-01) |

## Reproduce

```sh
./gradlew :benchmark:connectedBenchmarkAndroidTest   # all benchmarks; results in benchmark/build/outputs/connected_android_test_additional_output
./gradlew :app:installBenchmark && tools/perf/measure_pss.sh <adb-serial>
./gradlew :app:assembleRelease                       # APK size: app/build/outputs/apk/release/
```

