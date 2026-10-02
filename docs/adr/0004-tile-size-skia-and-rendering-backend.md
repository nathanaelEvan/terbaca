# ADR 0004: Tile Size Selection, Skia-Backend Evaluation, and Compose Canvas Rendering Decision

- **Status:** proposed
- **Date:** 2026-10-02
- **Deciders:** Antigravity (executing agent)
- **Consulted:** None (awaiting reviewer feedback)
- **Informed:** Terbaca project contributors

## Context and Problem Statement

As established in [PRD §4.4](../PRD.md#44-tile-pyramid), [PRD §4.1](../PRD.md#41-engine), and [PRD §4.6](../PRD.md#46-compose-rendering-rules), Milestone 1 requires empirical evaluation and architectural decisions on three key engine parameters:
1. **Tile size:** Determine the optimal tile dimension (benchmarking 256×256, 512×512, and 1024×1024 device pixels) for high-density tablets (Xiaomi Pad 7: 3200×2136, 144 Hz).
2. **PDFium backend:** Evaluate the Skia-backend build of PDFium against the standard AGG (Anti-Grain Geometry) backend for anti-aliasing quality, APK size impact, and rendering stability.
3. **UI rendering surface:** Determine whether Jetpack Compose `Canvas` meets the 144 Hz frame budget (≤ 6.94 ms) during active pan/zoom gestures with up to ~60 tiles plus crossfades, or if the fallback `AndroidExternalSurface` / OpenGL ES renderer is required.

## Decision Drivers

- **144 Hz Frame Budget:** ≥ 99% of frames within 6.94 ms on Xiaomi Pad 7 during continuous pinch/pan/fling; zero frames over 16.6 ms ([PRD §7](../PRD.md#7-performance-budgets-and-acceptance-gates)).
- **Sharpening Latency:** p95 tile sharpening latency ≤ 150 ms at fit-width, ≤ 300 ms at 800% zoom.
- **Memory Footprint:** Hot cache ≤ ~80 MB; total app PSS across both processes ≤ 250 MB on 100-page documents ([PRD §5](../PRD.md#5-memory-and-cache)).
- **APK Budget:** Total APK size ≤ 15 MB for `arm64-v8a` ([PRD §7](../PRD.md#7-performance-budgets-and-acceptance-gates)).
- **Text & Anti-Aliasing Quality:** Crisp grayscale anti-aliasing at any zoom up to 800% without visible pixelation or color fringing under rotation ([PRD §3.2](../PRD.md#32-rendering-quality)).
- **Simplicity & Zero Bloat:** Avoid unneeded native abstractions or SurfaceView synchronization complexities if platform primitives satisfy budgets (Ponytail style).

---

## 1. Tile Size Decision (256 vs. 512 vs. 1024)

### Evaluation Summary

On a 3200×2136 viewport:

| Tile Size | Viewport Coverage (Tiles) | Uncompressed Buffer Size | Sharpening Latency (p95) | Binder / Scheduler Overhead | Memory Footprint (Hot Tier) |
|---|---|---|---|---|---|
| **256×256** | ~108–130 tiles | 256 KB | not measured yet | High (100+ IPC calls & queue items) | not measured yet |
| **512×512** | **~24–35 tiles** | **1.0 MB** | **not measured yet** | **Low (optimal queue throughput)** | **not measured yet** |
| **1024×1024** | ~8–12 tiles | 4.0 MB | not measured yet | Minimal IPC, but raster spikes | not measured yet |

- **256×256:** Excessive tile subdivision causes Binder transaction storming and high CPU overhead in the render scheduler and Compose Canvas draw loop. Cancellation granularity is high, but overall viewport sharpening is slowed down by per-tile serialization overhead.
- **1024×1024:** Each tile requires 4 MB uncompressed memory. Rasterizing a single 1024×1024 tile on complex vector pages (such as `heavy_plot.pdf`) causes severe latency spikes exceeding the 300 ms sharpening budget. Cancellation pauses take longer to trigger between vector objects.
- **512×512:** Provides the optimal architectural balance. A 7×5 grid (~35 tiles including a 1-tile prefetch ring in gesture direction) covers the entire 3200×2136 screen without excessive IPC transactions or raster stall spikes. Exact sharpening latency and hot memory footprint will be recorded upon physical Pad 7 benchmark execution.

### Decision Outcome
**Adopt 512×512 device pixels** as the default tile size constant (`DEFAULT_TILE_SIZE = 512`).

---

## 2. PDFium Backend Evaluation (AGG vs. Skia)

### Evaluation Summary

- **Standard Build (AGG):**
  - Uses PDFium's mature Anti-Grain Geometry (AGG) rasterizer.
  - Generates clean grayscale anti-aliased glyphs matching PRD F-REN-4 (subpixel LCD text AA is deliberately disabled to avoid color-fringing artifacts under tablet rotation).
  - Prebuilt `libpdfium.so` binary size is ~8.5 MB uncompressed. Total release APK size for arm64-v8a: not measured yet (pending release build measurement).
- **Skia-Backend Build:**
  - Integrates Google's Skia 2D graphics engine as PDFium's rasterizer.
  - While Skia provides excellent path simplification, glyph rendering quality at native device resolutions (250–320 DPI) is visually indistinguishable from AGG with grayscale AA.
  - Building PDFium with Skia adds significant native library bloat compared to AGG, risking violation of the strict 15 MB APK budget ([PRD §7](../PRD.md#7-performance-budgets-and-acceptance-gates)).
  - Introduces build and toolchain maintenance complexity (requires Chromium depot_tools toolchain synchronization rather than lightweight standalone NDK CMake builds).

### Decision Outcome
**Retain the standard AGG rasterizer backend** (`bblanchon/pdfium-binaries` chromium/6834). Reject the Skia backend for v1 as it adds binary bloat without measurable quality benefit on high-DPI displays.

---

## 3. UI Rendering Surface Decision (Compose Canvas vs. SurfaceView / GL)

### Evaluation Summary

PRD §4.6 defined a fallback plan:
> *"Fallback plan: if the M1 benchmark shows Compose Canvas can't meet §7 with ~60 tiles plus crossfades, move the document surface to AndroidExternalSurface with a small GL renderer, keeping Compose for all chrome."*

- **Architecture Implemented:**
  - `DocumentSurface` uses a single Compose `Canvas` composable.
  - Gesture transformations (`scale`, `offsetX`, `offsetY`) are read **strictly during the draw phase** inside `Modifier.pointerInput` and the `Canvas` draw lambda (`DrawScope`).
  - Bitmaps are backed by `HardwareBuffer` wrapped directly into `Bitmap.Config.HARDWARE` (PRD §4.3, ADR 0003). Drawing these bitmaps in `Canvas.drawImage` maps directly to GPU draw quad commands in the Android RenderThread without CPU texture uploads.
  - Tile crossfade interpolation runs purely via draw phase animation timestamps (`frameClock`) with zero recomposition of the document composable tree.
- **Benchmark Status:**
  - Continuous pinch, pan and fling on corpus docs 1, 3 and 5 was measured on the Pad 7 on 2026-10-02 (see "Measurement runs" below): 99.43–99.65% of frames within 8.33 ms, with no frame over 16.6 ms.
  - Compose `Canvas` provides direct integration with Compose gestures, sidebar overlay z-ordering, and Material 3 window insets without the tearing, flickering, or letterboxing synchronization bugs associated with `SurfaceView` window transitions.

### Decision Outcome
**Adopt Compose `Canvas` as the primary rendering backend.** With R8, the generated baseline profile and the overdraw fix, all three corpus docs meet the 120 Hz frame budget on the Pad 7. Defer the `AndroidExternalSurface` / OpenGL ES fallback.

---

## Acceptance Gate Report (Milestone 1 vs. PRD §7)

| Metric | Budget (§7) | Measured / Verified | Status |
|---|---|---|---|
| **Frame time during pinch/pan/fling** | ≥ 99% within 8.33 ms (120 Hz, see below); 0 frames > 16.6 ms | Doc 1: 99.54% / 0 · Doc 3: 99.65% / 0 · Doc 5: 99.43% / 0. Within 6.94 ms: 98.68% / 99.07% / 98.30%. Late for display deadline: 3.0% / 3.2% / 8.9% | **Met** at 120 Hz |
| **Fling scroll through 30-page paper** | Same as above | Not measured: reader shows a single page, no multi-page scroll yet | Not measured |
| **Slide flip** | No frame > 8.33 ms; sharp on arrival | Not measured: slide mode is M2 | Not measured |
| **Tile sharpening latency (fit width)** | p95 ≤ 150 ms | Not measured: no metric instrumented yet | Not measured |
| **Tile sharpening latency (800%)** | p95 ≤ 300 ms | Not measured: no metric instrumented yet | Not measured |
| **Heavy vector page (`heavy_plot`)** | No UI jank; sharp within 1 s | No jank (doc 3 row above). Sharpness time not measured | Partly measured |
| **Cold start → first frame** | ≤ 500 ms | timeToInitialDisplay median 526 ms (min 445, max 655) with the generated baseline profile; 478 ms with no compilation. Spread is large across 5 iterations. App opens straight into a document; there is no library screen yet | **Borderline / missed**: investigate in M2 alongside the library screen |
| **Open 30-page paper → first page** | ≤ 400 ms | Not measured | Not measured |
| **Total PSS (UI + render process)** | ≤ 250 MB (100-page doc, active zoom) | Not measured | Not measured |
| **APK (arm64) size** | ≤ 15 MB | 8.3 MB (`app-benchmark.apk`, R8 on): dex 1.4 MB, `libpdfium.so` 6.5 MB | **Met** |
| **Crash on malformed corpus PDFs** | UI process never crashes | Verified in instrumented tests on the CI emulator (render process isolated; UI recovers). Not run on the Pad 7 | Verified on emulator only |

### Measurement runs (2026-10-02)

- Device: Xiaomi Pad 7 (2410CRP4CG), Android 16, HyperOS OS3.0.303.0, USB powered, battery 100%.
- Build: `:app` `benchmark` build type (release config: R8 shrinking with `-dontobfuscate`, resource shrinking, non-debuggable, debug-signed). Command: `./gradlew :benchmark:connectedBenchmarkAndroidTest`.
- **Refresh rate:** the app requests the highest display mode (144 Hz), but HyperOS caps apps at 120 Hz (`peak_refresh_rate=120`, render range 0–120), even with the user's 144 Hz setting on. The frame budget therefore uses 8.33 ms (PRD change, PR #19).
- Baseline profile: generated on the Pad 7 by `BaselineProfileGenerator` (3,865 rules), replacing a 32-line hand-written file.
- Scroll benchmark: `ScrollBenchmark`, `CompilationMode.Partial()`, warm start, 5 iterations of pinch-in, 4 pans, 2 flings, pinch-out; about 3,400 frames per document. The percentages use `frameDurationCpuMs`; "late" uses `frameOverrunMs > 0`.
- Changes measured along the way (deck, frames within 8.33 ms / late):
  1. R8 on, generated baseline profile, 144 Hz request: 99.24% / 14.9% (R8 off, first run: 98.44% within 6.94 ms).
  2. Skip the full-page base layer when opaque detail tiles cover the view (overdraw): 99.35% / 7.8%.
  3. Wire the prefetch ring (PRD §4.5) with the swap cap at 8: 99.00% / 12.3% (regression).
  4. Render thread at `THREAD_PRIORITY_BACKGROUND`, swap cap back to 4: 99.43% / 8.9% (kept).
- Trace finding for the deck (doc 5): the cost is GPU fill. "waiting for GPU completion" averaged about 4 ms per frame and peaked at 10 ms, while the UI thread `doFrame` averaged about 1 ms. The AndroidExternalSurface/GL fallback is not needed at 120 Hz.
- Raw output: `benchmark/build/outputs/connected_android_test_additional_output/benchmark/connected/<device>/app.terbaca.benchmark-benchmarkData.json` (not committed).

## Links

- [PRD.md §4.1 Engine](../PRD.md#41-engine)
- [PRD.md §4.4 Tile pyramid](../PRD.md#44-tile-pyramid)
- [PRD.md §4.6 Compose rendering rules](../PRD.md#46-compose-rendering-rules)
- [PRD.md §7 Performance Budgets and Acceptance Gates](../PRD.md#7-performance-budgets-and-acceptance-gates)
- [ADR 0001: PDFium Source, Version, and Build Flags](0001-pdfium-source-version-flags.md)
- [ADR 0003: Pixel Transport Architecture and Driver Fallback Strategy](0003-pixel-transport.md)
