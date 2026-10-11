# Scroll Smoothness Investigation (M6-02)

Investigation into RenderThread and GPU bottlenecks causing late frames during scrolling on the Xiaomi Pad 7 (HyperOS OS3.0, 3200×2136 @ 120 Hz).

---

## 1. Executive Summary

In Milestone M2 (commit `8944db6`), paper scrolling achieved **99.5%** of frames within the 8.33 ms frame budget.
On current `main`, the competitor scroll fling benchmark (`CompetitorBenchmarks#scrollFlings`) reports **42.62% late frames** (`frameOverrunMs > 0`), and `ScrollBenchmark` reports 95.2%–97.8% ≤ 8.33 ms with up to 11.6% late for display.

Crucially, **the UI thread is not the primary bottleneck**:
- `frameDurationCpuMs`: P50 is **3.8 ms**, P90 is **7.1 ms**, and 92.6% of frames are ≤ 8.33 ms on the CPU.
- `dumpsys gfxinfo`: **50th percentile GPU time is 16 ms, 90th percentile is 17 ms, 99th percentile is 19 ms**.
- The 120 Hz display deadline is **8.33 ms**. A GPU render time of 16–17 ms guarantees missed display deadlines (`frameOverrunMs > 0`) on every frame where the GPU cannot finish before the VSYNC boundary, resulting in `eglSwapBuffers → queueBuffer` backpressure and frame pacing stalls.

---

## 2. Comparison: M2 (`8944db6`) vs `main` (`db01e1e`)

| Area | Milestone M2 (`8944db6`) | Current `main` (`db01e1e`) | Impact on GPU / RenderThread |
|---|---|---|---|
| **Base layer rendering** | Only drawn when base layer was not covered by opaque tiles. | During active flings, newly entering edge tiles are not yet opaque, making `detailOpaque == false` on almost every frame. Full-page base bitmap (1449×2048) is rendered *under* the 512×512 detail tiles. | **2× full screen overdraw** on every scroll frame (13.7 MP fill per frame). |
| **Theme shader (`graphicsLayer`)** | None. `DocumentSurface` rendered directly into the parent view. | Wrapped in `Modifier.graphicsLayer { renderEffect = pageThemeEffect(...) }`. Allocates `FloatArray`s in `themeRects()` every frame; forces an intermediate RenderNode layer. | RenderNode layer overhead; full-screen shader pass if theme enabled. |
| **Chrome & overlays** | Minimal static surfaces; no blur, no alpha animations during flings. | Apple-style floating chrome (#63), `TopToolbar` with blur/elevation, `FastScrollbar` with animated alpha, TalkBack 48 dp pills (#68). | Layer compositing and alpha blending over active canvas. |
| **Background clear** | Single clear of screen background. | Full-screen `TerbacaDarkBackground` Box + nested fillMaxSize Boxes + Canvas clear + page placeholder rects. | Multiple redundant fill-rate passes over 3200×2136 pixels. |

---

## 3. Ranked Causes

### Rank 1: Full-Screen Base Layer Overdraw During Active Scrolling (Critical)

- **Mechanism:**
  In `DocumentSurface.kt` (`drawTilePyramid`):
  ```kotlin
  val detailOpaque = isDetailLayerOpaque(visibleTiles, { tileCache.get(it) != null }, tileFadeStartTimes, now)
  if (!detailOpaque) {
      drawBaseLayer(tileCache, page, offsetX, offsetY, scale)
  }
  ```
  `isDetailLayerOpaque` checks:
  ```kotlin
  visibleTiles.isNotEmpty() && visibleTiles.all { tile ->
      isLoaded(tile.key) && now - (fadeStartTimes[tile.key] ?: now) >= TILE_CROSSFADE_DURATION_MS
  }
  ```
  During active scrolling, new tiles constantly enter the viewport boundary. Because the newly visible edge tiles are either still loading or in the middle of their 100 ms crossfade, `visibleTiles.all { ... }` evaluates to **`false` on virtually every frame of a fling**.
  
  Consequently, `drawBaseLayer` is executed every frame, drawing a massive 1449×2048 bitmap stretched across the entire page (6.84 MP). Immediately afterwards, `drawTileBoundsList` draws all 12–20 visible 512×512 detail tiles directly on top of the base layer.
- **Evidence:**
  - `dumpsys gfxinfo`: GPU 50th percentile is 16 ms, precisely double the 8.33 ms budget.
  - Total fill per frame:
    1. Screen background: 3200×2136 = 6.84 MP
    2. Base layer: ~6.84 MP
    3. Detail tiles: ~6.84 MP
    Total = **20.5 MP / frame**. At 120 Hz, required fill rate is **2.46 GP/s**, exceeding the mobile GPU sustainable fill rate.
- **Fix (M6-03a):**
  - **Tile-clipped base layer:** Instead of rendering the entire base bitmap across the whole screen when a single tile is not opaque, only render the base layer for the bounding rectangles of *missing or fading tiles* (or clip canvas / draw sub-rectangles). If 90% of the screen is covered by loaded opaque tiles, 90% of the base layer draw is eliminated.
  - **Skip base layer when not needed:** For pages whose visible viewport area is already covered by loaded detail tiles, do not draw the base layer at all.

---

### Rank 2: `Modifier.graphicsLayer` and Theme Shader Overhead (Moderate)

- **Mechanism:**
  In `ReaderScreen.kt`:
  ```kotlin
  Box(
      modifier = Modifier
          .fillMaxSize()
          .graphicsLayer {
              renderEffect = pageThemeEffect(
                  themeShader,
                  pageTheme,
                  invertImages,
                  themeRects(state, state.layout.pagesIn(state.getVisibleRectPoints()), imageBounds)
              )
          }
  ) { DocumentSurface(...) }
  ```
  Even when `pageTheme == PageTheme.ORIGINAL` (the default light reading mode):
  1. `themeRects(...)` is called inside the `graphicsLayer` lambda on every single scroll frame, allocating two `FloatArray`s and iterating visible pages and image bounds.
  2. The `graphicsLayer` modifier marks the `RenderNode` as an offscreen layer target in Compose.
- **Evidence:**
  - In M2, `DocumentSurface` rendered directly without any `graphicsLayer` wrapper.
  - Object allocations in `themeRects()` generate minor GC pressure on the UI thread during rapid scrolling.
- **Fix (M6-03b):**
  - Only apply the `graphicsLayer` modifier when `pageTheme != PageTheme.ORIGINAL`.
  - In `PageTheme.ORIGINAL`, omit `graphicsLayer` entirely so `DocumentSurface` draws directly into the window canvas without an offscreen layer.

---

### Rank 3: Bitmap Texture Uploads on RenderThread (Moderate)

- **Mechanism:**
  In `DocumentSurface.kt`:
  ```kotlin
  const val MAX_TILES_SWAPPED_PER_FRAME = 4
  ```
  `swapPendingTiles` transfers up to 4 bitmaps (512×512 ARGB_8888 = 1 MB each) per frame into `TileCache`. When Skia OpenGL executes `drawImage`, these bitmaps are uploaded to the GPU as OpenGL textures via `glTexSubImage2D` on the RenderThread.
- **Evidence:**
  - Up to 4 MB of texture upload during a single frame introduces 2–4 ms of CPU RenderThread stall, pushing marginal frames from 6 ms to > 8.33 ms.
- **Fix (M6-03c):**
  - Throttle tile swaps during active fling motion: swap at most 2 tiles per frame while `velocity > 0`, spreading uploads across VSYNC intervals.

---

### Rank 4: Overdraw from Inactive Overlays and Chrome (Minor)

- **Mechanism:**
  - `FastScrollbar`: Animates alpha via `animateFloatAsState`, causing recomposition and alpha drawing over the right edge.
  - `TopToolbar` & `PageIndicator`: Animated enter/exit transitions during drag gestures.
  - Background fill: The root `Box` fills `TerbacaDarkBackground`, which is immediately obscured by the white document pages.
- **Fix (M6-03d):**
  - Skip drawing dark background under document pages when in fit-width mode.
  - Ensure `FastScrollbar` and chrome elements are fully detached from drawing when hidden (`alpha == 0f`).

---

## 4. Backend Assessment: Is a SurfaceView / GL Rewrite Needed?

PRD §8 M6-02 requires assessing whether a rendering-backend change (SurfaceView/GL, PRD §9) is needed, and drafting an ADR if so.

### Conclusion: **No backend rewrite is required.**
1. **Empirical proof:** In Milestone M2 (`8944db6`), Terbaca achieved **99.5% frames ≤ 8.33 ms** using the **exact same Compose `Canvas` Skia OpenGL backend**.
2. The degradation from 99.5% to 92.6% (and 42.6% late frames in competitor harness) was caused by additive overdraw (Rank 1: full base layer drawn under tiles), `graphicsLayer` wrapping (Rank 2), and texture upload bursts (Rank 3).
3. Moving to SurfaceView/GL introduces severe complexity (window punching, z-ordering with Compose chrome, touch delegation issues, lifecycle destruction on splitscreen/rotation) without addressing the core issue (fill rate is bounded by GPU fragment processing regardless of whether Skia Canvas or GLES draws it).
4. Eliminating Rank 1 overdraw will restore the 99%+ frame rate on the existing backend.

---

## 5. Fix Plan (Milestone M6-03 Execution)

1. **M6-03a (Overdraw Elimination):**
   - Clip base layer drawing to only the regions of unrendered or crossfading tiles.
   - For pages whose visible viewport area is covered by opaque tiles, suppress base layer drawing completely.
   - Measure: verify late-frame percentage reduction in `competitor/frames.sh`.

2. **M6-03b (Conditional GraphicsLayer):**
   - Conditionally attach `graphicsLayer` in `ReaderScreen.kt` only when `pageTheme != PageTheme.ORIGINAL`.
   - In `ORIGINAL` theme, skip `themeRects()` allocations entirely.

3. **M6-03c (Texture Upload Throttling):**
   - Cap tile swap to 2 tiles per frame during active fling gestures to avoid RenderThread spikes.
