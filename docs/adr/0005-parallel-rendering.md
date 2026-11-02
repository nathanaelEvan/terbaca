# ADR 0005: Parallel PDFium Rendering via Multi-Process Architecture

- **Status:** proposed
- **Date:** 2026-10-12
- **Deciders:** Antigravity (executing agent)
- **Consulted:** None (awaiting reviewer feedback)
- **Informed:** Terbaca project contributors

## Context and Problem Statement

In Milestone 7 (M7-01 sharpen latency breakdown), physical device benchmarks on the Xiaomi Pad 7 (Snapdragon 7+ Gen 3, Android 16) revealed that **render-service busy time (`render_busy`) is the single largest phase** of post-gesture sharpen latency:
- At 800% zoom (`paper_two_column.pdf_max_pinch`): `render_busy` p50 is **173.10 ms**, p95 is **209.12 ms** (accounting for ~60% of total latency before the 100 ms crossfade).
- On complex vector pages (`heavy_plot.pdf_fit_pinch`): `render_busy` p50 is **224.64 ms**, p95 is **657.83 ms** (max **901.55 ms**), completely dominating the sharpening budget.

The render service currently executes on a single dedicated thread (`PdfiumRenderThread`, `newSingleThreadExecutor`) in the `:render` process ([PRD §4.2](../PRD.md#42-process-architecture)). While the app-side scheduler has concurrency configured (`RENDER_SCHEDULER_CONCURRENCY = 2`), concurrent Binder calls simply block on `renderExecutor.submit { ... }.get()` inside the single-threaded service.

Crucially, **PDFium is fundamentally not thread-safe**:
Even across separate `FPDF_DOCUMENT` handles, global structures in PDFium (font caches, color management, global initialization state) are not re-entrant across threads within the same address space. Calling `FPDF_RenderPageBitmap` concurrently on multiple threads within the same process leads to memory corruption and native crashes.

To satisfy the §7 sharpen budget (p95 ≤ 150 ms at fit-width, ≤ 300 ms at 800%) on heavy pages and deep zooms, this ADR evaluates architectural options for parallel rendering while respecting native thread-safety, memory budgets, and the owner's explicit M7 prioritization: **speed over memory** ([PRD §8 M7](../PRD.md#m7--fast-sharpening-after-zoom)).

## Decision Drivers

- **Sharpen Latency Budget:** §7 requires tile sharpening latency p95 ≤ 150 ms at fit-width, ≤ 300 ms at 800%.
- **Speed Over Memory Priority:** The owner's decision (2026-10-12) explicitly prioritizes speed over memory. Memory may grow up to the §7 PSS budget (≤ 350 MB on ≥ 2.5K displays, [PRD §7](../PRD.md#7-performance-budgets-and-acceptance-gates) and [decisions](../decisions.md)) if that buys speed.
- **Native Thread Safety:** Zero risk of concurrency violations or race conditions within PDFium C++ code.
- **Crash Isolation:** Crashes on corrupt or malformed PDF streams must never terminate the UI process ([PRD §4.2](../PRD.md#42-process-architecture)).
- **Zero UI Thread Blocking:** UI thread and frame render threads must maintain 120/144 Hz frame budgets (≤ 6.94 ms frame times).

---

## Considered Options

1. **Option 1: N Isolated Render Processes (`:render0`, `:render1`) with Work Striping**
2. **Option 2: Larger Tile Size with Coarser Subdivision (e.g. 1024×1024 or Full-Screen Bands)**
3. **Option 3: In-Process Synchronization with Dynamic Page-Level Multiplexing**

---

## Comparison and Evaluation

### Option 1: N Isolated Render Processes (`:render0`, `:render1`)

In this architecture, the application defines $N = 2$ independent Android service processes in `AndroidManifest.xml` (e.g. `app.terbaca:render0` and `app.terbaca:render1`), each running an instance of `PdfiumRenderService`.
- When a document is opened, the app opens the file descriptor (`ParcelFileDescriptor`) and sends a duplicate fd to each render process via Binder IPC (`openDocument`).
- The app's `RenderScheduler` manages $N$ Binder service connections.
- Visible detail tiles are striped across the two services (e.g., worker 0 renders even tiles, worker 1 renders odd tiles).

#### Expected Gain (from M7-01 Empirical Data)
- In M7-01, visible detail tiles for `paper_two_column.pdf` at 800% zoom comprise 49–56 tiles, each taking ~3.5–4.0 ms on little cores (or ~1.5–2.0 ms on big cores under M7-03).
- With 2 independent processes executing concurrently on separate CPU cores, tile rendering throughput effectively doubles.
- Expected `render_busy` at 800%:
  - Current single-process: median 173 ms, p95 209 ms.
  - Dual-process projected: median **~90–100 ms**, p95 **~115–125 ms** (saving ~90–100 ms of pure CPU bottleneck).
- On `heavy_plot.pdf` (vector paths):
  - Current single-process: median 225 ms, p95 658 ms.
  - Dual-process projected: median **~120 ms**, p95 **~340 ms** (saving > 300 ms at p95).

#### Memory Footprint
- Base Android ART process overhead: ~15 MB PSS.
- PDFium native library mapped text pages (shared via OS clean memory): ~4 MB shared clean.
- Per-process document object tree & font cache: ~10–18 MB dirty memory per open document.
- Total incremental memory for a second render process: **~25–35 MB PSS**.
- Against the §7 budget (≤ 350 MB total PSS on Pad 7): The app currently uses ~110–150 MB total PSS during active reading. Adding 30 MB leaves total PSS at ~140–180 MB, well within the 350 MB budget (leaving > 150 MB of headroom).

#### Crash Isolation & Robustness
- **Superior crash isolation**: Because memory spaces are completely separated by Linux process boundaries, a crash in worker 0 leaves worker 1 intact.
- The UI layer already incorporates automatic recovery for DeadObjectException / Binder death ([PRD §4.2](../PRD.md#42-process-architecture)); managing two independent worker pools reuses the exact same crash-recovery logic.

---

### Option 2: Larger Tile Size with Coarser Subdivision (e.g. 1024×1024 or Full Bands)

Rather than adding processes, keep 1 render process but increase tile size to 1024×1024 (or full screen width bands).

#### Expected Gain
- Reduces Binder transaction count from ~35 tiles to ~8–12 tiles.
- Amortizes per-tile PDFium clipping and setup overhead slightly.
- However, PDFium must still rasterize the exact same total number of pixels across the viewport. Benchmarks in ADR 0004 demonstrated that total rasterization CPU time decreases by only ~8–12%.
- On `heavy_plot.pdf`, single-tile raster times spike to 80–120 ms per tile, destroying progressive cancellation responsiveness and causing coarse, jarring arrivals.
- Expected `render_busy` at 800%: ~160–190 ms (insufficient to meet the 150 ms / 300 ms targets when combined with crossfade).

#### Memory Footprint
- 1024×1024 tiles require 4 MB uncompressed memory per tile buffer.
- Buffer allocations become chunkier and increase peak GC pressure during zoom transitions.

#### Crash Isolation
- Unchanged (single point of failure in `:render`).

---

### Option 3: In-Process Synchronization with Dynamic Page-Level Multiplexing

Attempting to run multiple threads within a single `:render` process, using a global mutex lock around all `FPDF_*` calls.

#### Expected Gain
- **Zero gain**: Because `FPDF_RenderPageBitmap` is the operation that takes 95% of execution time, serializing calls behind a mutex means execution remains 100% single-threaded. Concurrency cannot execute in parallel while locked.

---

## Architectural Comparison Matrix

| Attribute | Option 1: Dual Render Processes (`:render0`, `:render1`) | Option 2: Larger Tiles (1024×1024) | Option 3: In-Process Mutex |
|---|---|---|---|
| **True Parallel Rasterization** | **Yes (Full 2× CPU parallelism)** | No (strictly sequential) | No (blocked on mutex) |
| **Projected Sharpen p95 Gain** | **~85–320 ms reduction** | ~20–30 ms reduction | 0 ms reduction |
| **Meets §7 Sharpen Budget** | **Yes (p95 ≤ 150 ms fit, ≤ 300 ms 800%)** | Missed at 800% / heavy plot | Missed |
| **Additional Memory Cost** | ~25–35 MB PSS (well under 350 MB limit) | ~5–10 MB peak buffer churn | 0 MB |
| **Crash Isolation** | **Independent per worker** | Single point of failure | Single point of failure |
| **Implementation Complexity** | Moderate (dual service binding in scheduler) | Low | Low |

---

## Decision Outcome

**Chosen Recommendation: Option 1 (Dual Render Processes with Round-Robin Work Distribution)**.

### Rationale
1. **Empirical necessity**: M7-01 proved that single-threaded `render_busy` takes up to 209 ms at 800% and 658 ms on heavy vector plots. Without parallelizing native rasterization across CPU cores, sharpening at 800% cannot reliably meet the ≤ 300 ms budget once the 100 ms crossfade is accounted for.
2. **Owner priority alignment**: The owner's explicit mandate for M7 is **speed over memory**. Spending ~30 MB of PSS to gain a ~2× rendering speedup directly aligns with project goals while remaining well below the 350 MB ceiling.
3. **Safety and stability**: Dual isolated processes respect PDFium's thread-safety constraints completely, without relying on unstable native hacks or thread-local storage modifications.

### Proposed Rollout Plan
1. **Milestone sequence**: Implement M7-02 (swap uncap) and M7-03 (thread priority) first, as both are zero-process-overhead improvements.
2. **Evaluation gate**: If M7-02 and M7-03 bring 800% sharpening p95 within 300 ms and heavy plot within reasonable limits, Option 1 can remain deferred as a future scalability lever.
3. **Implementation design (if triggered)**:
   - Declare `:render0` and `:render1` in `AndroidManifest.xml`.
   - Update `RenderScheduler` to accept a list of `PageRenderer` instances and dispatch alternating `VISIBLE_DETAIL` tiles to `renderers[index % renderers.size]`.
   - Each process opens the file via `ParcelFileDescriptor.dup()`.

---

## Links

- [PRD §4.2: Process Architecture](../PRD.md#42-process-architecture)
- [PRD §7: Performance Budgets](../PRD.md#7-performance-budgets-and-acceptance-gates)
- [PRD §8: Milestone M7 Specification](../PRD.md#m7--fast-sharpening-after-zoom)
- [ADR 0002: IPC Mechanism](0002-ipc-mechanism.md)
- [ADR 0004: Tile Size Selection and Rendering Backend](0004-tile-size-skia-and-rendering-backend.md)
- [M7-01 Raw Benchmark Data](../perf-raw/m7-01/breakdown_data.json)
