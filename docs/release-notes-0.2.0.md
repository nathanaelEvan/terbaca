# Terbaca 0.2.0 Release Notes

**Terbaca 0.2.0** (`versionCode 2`) is the Milestone M6 release, bringing major rendering and gesture smoothness improvements for high-refresh 120 Hz Android tablet displays (such as Xiaomi Pad 7 / Pad 7 Pro), comprehensive baseline profiles, and accessibility refinements.

---

## Highlights

### 1. 120 Hz Smoothness Overhaul (M6-03, M6-04)
- **Scrolling Overrun Frames Drop:** Late frames during fast flings dropped from 34.6% (v0.1.0) down to 10.4%, with median frame overrun shifting from -1.9 ms to -5.8 ms.
- **Base Layer Clipping:** Only draws missing and crossfading tiles on the base layer, preventing redundant overdraw of already-opaque detail tiles.
- **Conditional GPU Layer:** Attached `Modifier.graphicsLayer` only when custom themes (Dark / Sepia) are active, eliminating offscreen render target passes during default reading.
- **Tile Upload Throttling:** Throttles tile bitmap swaps to 2 tiles per frame, preventing RenderThread hiccups during fast scrolling.
- **Combined Pan & Pinch Transformations:** Updates scale, offsets, and bounds clamping in a single pass per touch event.
- **Tile Level Cache:** Caches tile pyramid levels, eliminating repetitive geometric allocations during continuous interactions.

### 2. Fast Centre-First Sharpening & 100 ms Fade (M6-06)
- **Viewport-Centre Prioritization:** Prioritizes rendering tiles closest to the viewport centre so content immediately in front of the reader sharpens first.
- **Tuned Crossfade:** Shortened crossfade transition from 150 ms to 100 ms, making sharp detail available 50 ms faster while preserving visual elegance.
- Text page sharpening at 800% zoom now completes in **69.5 ms** (P50) / **287.7 ms** (P95), which is under the 300 ms budget.

### 3. Pager Slide Flip Optimization (M6-07)
- P90 CPU frame time during rapid slide flipping now averages **6.9 ms**, with median overrun at **-3.8 ms**.

### 4. Library Cold Start & Verification Gaps (M6-08, M6-09)
- **Deferred Availability Checks:** Displays the library recents grid immediately on cold startup before validating per-file storage availability in the background.
- **TalkBack & Touch Target Alignment:** Resolved the ~6 dp floating pill position shift while preserving full TalkBack accessibility and the 48 dp interactive touch target.

### 5. Comprehensive Baseline Profile (M6-10)
- Regenerated `baseline-prof.txt` directly on Xiaomi Pad 7.
- Expanded coverage to include `VIEW`-intent document opening, library card tap, recents database queries, and gesture/slide flows.

---

## Known Limitations

- **Pinch zoom smoothness:** Missed the ≤ 10% late frame exit criterion (29.9% late frames in competitor harness, worse than the 25.0% baseline).
- **Cold start to library:** Missed the ≤ 500 ms budget (median 563 ms, regressed from 460 ms in M6-01).

---

## Metrics Summary (Xiaomi Pad 7 @ 120 Hz)

| Metric | v0.1.0 (M5) | v0.2.0 (M6) |
|---|---|---|
| **Scrolling late frames (%)** | 34.6% | **10.4%** |
| **Median scroll overrun (ms)** | -1.9 ms | **-5.8 ms** |
| **800% Sharpening P95 (ms)** | 292 ms | **287.7 ms** |
| **Memory PSS (MB)** | 325 MB | **324 MB** |
| **Release APK Size (MB)** | 9.59 MB | **9.62 MB** |

---

## Release Verification & Signing
Per project release gates (AGENTS.md §3), this release draft stops before signing and publishing. The owner will review and sign the release build via `./gradlew assembleRelease` using release credentials (`docs/release-signing.md`).
