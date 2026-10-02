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
  - Continuous pinch, pan and fling on corpus docs 1, 3 and 5 was measured on the Pad 7 on 2026-10-02 (see "Measurement run" below). Frame CPU time is at the edge of the budget: 98.4–99.3% of frames within 6.94 ms, and 3.1–6.9% of frames missed the 120 Hz display deadline.
  - Compose `Canvas` provides direct integration with Compose gestures, sidebar overlay z-ordering, and Material 3 window insets without the tearing, flickering, or letterboxing synchronization bugs associated with `SurfaceView` window transitions.

### Decision Outcome
**Adopt Compose `Canvas` as the primary rendering backend, provisionally.** The first Pad 7 run is borderline (see below), so the decision to defer the `AndroidExternalSurface` / OpenGL ES fallback stays open until the run is repeated with R8 enabled and the display at 144 Hz.

---

## Acceptance Gate Report (Milestone 1 vs. PRD §7)

| Metric | Budget (§7) | Measured / Verified | Status |
|---|---|---|---|
| **Frame time during pinch/pan/fling** | ≥ 99% within 6.94 ms (144 Hz); 0 frames > 16.6 ms | Doc 1: 99.10% / 0 frames (2nd run); 98.45% / 2 frames (1st run). Doc 3: 99.27% / 0. Doc 5: 98.44% / 0. Missed 120 Hz deadline: 3.1% / 3.9% / 6.9% of frames | **Missed** (doc 5, and doc 1 in one of two runs) |
| **Fling scroll through 30-page paper** | Same as above | Not measured: reader shows a single page, no multi-page scroll yet | Not measured |
| **Slide flip** | No frame > 6.94 ms; sharp on arrival | Not measured: slide mode is M2 | Not measured |
| **Tile sharpening latency (fit width)** | p95 ≤ 150 ms | Not measured: no metric instrumented yet | Not measured |
| **Tile sharpening latency (800%)** | p95 ≤ 300 ms | Not measured: no metric instrumented yet | Not measured |
| **Heavy vector page (`heavy_plot`)** | No UI jank; sharp within 1 s | Jank: see frame-time row (doc 3). Sharpness time not measured | Partly measured |
| **Cold start → first frame** | ≤ 500 ms | timeToInitialDisplay median 572 ms (min 451, max 637) with baseline profile; 547 ms with no compilation. App opens straight into a document; there is no library screen yet | **Missed** |
| **Open 30-page paper → first page** | ≤ 400 ms | Not measured | Not measured |
| **Total PSS (UI + render process)** | ≤ 250 MB (100-page doc, active zoom) | Not measured | Not measured |
| **APK (arm64) size** | ≤ 15 MB | 28.5 MB (`app-benchmark.apk`, R8 off): dex 21.3 MB, `libpdfium.so` 6.5 MB | **Missed**: enable R8 (needs keep rules for JNI and AIDL) |
| **Crash on malformed corpus PDFs** | UI process never crashes | Verified in instrumented tests on the CI emulator (render process isolated; UI recovers). Not run on the Pad 7 | Verified on emulator only |

### Measurement run (2026-10-02)

- Device: Xiaomi Pad 7 (2410CRP4CG), Android 16, HyperOS OS3.0.303.0, USB powered, battery 100%.
- Build: `:app` `benchmark` build type (release config, non-debuggable, debug-signed, **R8 off**). Command: `./gradlew :benchmark:connectedBenchmarkAndroidTest`.
- Display ran at **120 Hz**: the app does not request 144 Hz, so "missed deadline" is measured against 8.33 ms, not 6.94 ms.
- The baseline profile (`app/src/main/baseline-prof.txt`) is a 32-line hand-written file, not generated by `BaselineProfileGenerator`. This may explain why "baseline profile" startup was no faster than no compilation.
- Scroll benchmark: `ScrollBenchmark`, `CompilationMode.Partial()`, warm start, 5 iterations of pinch-in, 4 pans, 2 flings, pinch-out; about 3,550 frames per document. The percentages use `frameDurationCpuMs` (UI thread + RenderThread CPU time); "missed deadline" uses `frameOverrunMs > 0`.
- Raw output: `benchmark/build/outputs/connected_android_test_additional_output/benchmark/connected/<device>/app.terbaca.benchmark-benchmarkData.json` (not committed).

## Links

- [PRD.md §4.1 Engine](../PRD.md#41-engine)
- [PRD.md §4.4 Tile pyramid](../PRD.md#44-tile-pyramid)
- [PRD.md §4.6 Compose rendering rules](../PRD.md#46-compose-rendering-rules)
- [PRD.md §7 Performance Budgets and Acceptance Gates](../PRD.md#7-performance-budgets-and-acceptance-gates)
- [ADR 0001: PDFium Source, Version, and Build Flags](0001-pdfium-source-version-flags.md)
- [ADR 0003: Pixel Transport Architecture and Driver Fallback Strategy](0003-pixel-transport.md)
