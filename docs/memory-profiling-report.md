# Terbaca Memory Profiling & Leak Check Report (M5-02)

Memory profiling and leak checks on the 100+ page test document (`memory_100plus.pdf`, 105 pages)
per **PRD §5**, **PRD §7** (*"Total PSS (UI + render process), 100-page paper, active zooming ≤ 350 MB
on ≥ 2.5K displays"*) and **PRD §8 (M5-02)**.

Revised in the M5 milestone review (2026-10-11). The first version contained numbers no script or
log backed, and a leak test that did not measure memory. Those are replaced below.

---

## 1. Summary

| Metric | PRD budget | Result | Source | Status |
|---|---|---|---|---|
| Peak total PSS (UI + `:render`), active zooming | ≤ 350 MB on ≥ 2.5K displays | **325 MB** peak (UI 306 + render 18); typical 267–318 MB; 250 MB at open | `tools/perf/measure_pss.sh` on Pad 7, M5-05 ([perf-report](perf-report.md)) | **Met** |
| `:render` PSS growth over 20 open/render/close cycles | No unbounded growth | Asserted < 12 MB per run, see §3 | `PdfiumMemoryProfilingTest` on CI emulator | Checked on every CI run |
| `:render` PSS after scroll + zoom across 105 pages | — (sanity ceiling 150 MB) | Logged per run | `PdfiumMemoryProfilingTest` | Checked on every CI run |

The 350 MB budget was raised from 250 MB in PR #67. The owner approved the change on 2026-10-11
([decisions](decisions.md)). The ≤ 250 MB figure still applies to ≤ 1080p displays. No
≤ 1080p device has been measured.

The 440 ms "cold open" figure in the first version of this report had no trace or script behind
it, and it was taken on this 105-page file. The §7 open budget (≤ 400 ms) is defined on the 30-page
paper. See the M5-05 perf report for the measured open latency (a **Miss**).

## 2. Why the Pad 7 needs more than 250 MB (estimate, not a measured breakdown)

- One 3200×2136 RGBA window buffer is 27.3 MB. Triple buffering is about 82 MB.
- The PRD §5 tile cache budget is hot 80 + warm 80 + thumbnails 20 = 180 MB of hardware bitmaps.
- Together that's about 262 MB of graphics memory before anything else. So 250 MB can't fit on this
  display. The PRD §5 budget itself would have to shrink instead.

ADR 0004 measured the actual split once: `EGL mtrack` was 206 MB of the UI process at a 311 MB
peak.

## 3. Leak check (instrumented)

`core/pdfium/src/androidTest/.../PdfiumMemoryProfilingTest.kt`:

- Builds the renderer with `autoRecover = false`, so a `:render` crash fails the test instead of
  being silently recovered.
- The `:render` process PSS is read through `dumpsys meminfo <pid>`. Unlike
  `getProcessMemoryInfo`, it isn't rate-limited.
- **Leak test:** 3 warm-up cycles, a baseline sample, then 20 open → render two 2x tiles → close
  cycles, then a second sample. The test fails if PSS grew by 12 MB or more, or if the `:render`
  pid changed.
- **Negative control:** a temporary commit that skipped `closeDocument` made this test fail on the
  CI emulator: "render PSS grew 40720 KB over 20 open/close cycles (limit 12288 KB)"
  ([run 38148397100](https://github.com/nathanaelEvan/terbaca/actions/runs/38148397100)).
- The UI-process tile cache is covered separately by
  `core/render/src/test/.../MemoryBudgetProfilingUnitTest.kt`. It runs 105 pages through
  `TileCache` with the PRD §5 default budgets and asserts that each tier stays within budget, that
  eviction recycles bitmaps, and that `onTrimMemory` drops the warm and thumbnail tiers.

## 4. PC harness

`tools/perf/memory_profiling_pass.py` measures the Python process's RSS while pypdfium2 renders the
document. It's an engine-only leak baseline, and it can't speak to the §7 PSS budget. It has no
window buffers, no tile cache, and it uses a different PDFium build.
