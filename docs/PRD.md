# Terbaca — Product Requirements & Engineering Spec

> **Audience:** the executing agent (agy). This document is the source of truth. If anything here conflicts with an earlier draft or proposal, this document wins.
> **Reviewer:** Claude reviews each milestone as a whole, and reviews risky PRs (see §0.2) as they come. The executing agent merges routine PRs itself once CI is green.
> **App name:** Terbaca. **UI language:** English (all strings externalized for future localization).

---

## 0. Instructions for the Executor (read first)

### 0.1 Repository setup (the only work allowed outside a PR)

1. Create a **new private GitHub repository** named `terbaca` under the owner's account.
2. Make **one initial commit directly to `main`** containing only the scaffold:
   - Gradle (Kotlin DSL) multi-module Android project matching §6.2, building an empty app that launches to a placeholder screen.
   - `docs/PRD.md` (this file, verbatim).
   - `docs/adr/` with `0000-template.md` (Architecture Decision Record template).
   - `.github/pull_request_template.md` (see §0.3).
   - `.github/workflows/ci.yml` that runs build, lint, ktlint/detekt and unit tests on every PR.
   - `.gitignore`, `.editorconfig`, `README.md` (one paragraph + build instructions).
   - `LICENSE`: Apache-2.0 (owner decision, 2026-10-12, when the repo went public).
3. Enable branch protection on `main` if the account plan supports it for private repos: require a PR, require CI to pass, block force pushes. If the plan doesn't support it, state this in the first PR description and follow the rule by convention anyway.
4. Report back to the owner: repo URL and confirmation that the scaffold builds.

### 0.2 Rules for every change after the initial commit

- **Every change goes through a Pull Request.** Never push to `main` directly.
- **Merging:** The executing agent (or Claude) merges its own routine PRs (`gh pr merge --squash`) once CI is green on the latest commit. Before that, the local gates (`test`, `detekt`, `lintDebug`, `assembleDebug`) must pass and the PR must state anything not verified. The owner can block a merge at any time by requesting changes.
- **Risky PRs wait for review:** PRs that add or change native/C++/JNI code, touch security (file access, permissions, passwords or encryption, IPC), add a third-party dependency or binary, or include an ADR are not self-merged. They wait for review while the agent carries on with independent tasks.
- **Milestone review:** At the end of each milestone, Claude reviews the whole milestone's diff at once. Findings are fixed in follow-up PRs.
- **One task per PR.** Use the task IDs in §8 (e.g. `M1-03`). Keep diffs reviewable: target under ~1000 changed lines, excluding generated or vendored files (third-party headers, prebuilt binaries, license texts, test fixtures). Split larger tasks into sequential PRs (`M1-03a`, `M1-03b`).
- **Branch naming:** `<type>/<task-id>-<short-slug>`, e.g. `feat/M1-03-tile-scheduler`, `fix/M2-01-pager-overscroll`.
- **Commits:** Conventional Commits (`feat:`, `fix:`, `perf:`, `refactor:`, `test:`, `docs:`, `build:`, `ci:`).
- **Never force-push a PR branch.** Address review findings with new commits. Merge with squash-merge.
- **Self-contained PR descriptions.** The reviewer may see only the description and the diff. Explain the why, not just the what.
- **Architecture decisions** listed as "ADR required" in this document get their own `docs/adr/NNNN-title.md` in the same PR that implements them.
- **Do not change this PRD silently.** If you believe a requirement is wrong, open a PR that edits `docs/PRD.md` with the rationale (never self-merged), and move on to tasks that don't depend on it.
- **Stop and ask the owner** instead of guessing whenever a task needs credentials, a paid service, a new permission, or a dependency with a non-permissive license (GPL/AGPL/LGPL, or anything unclear).

### 0.3 PR template (`.github/pull_request_template.md`)

```markdown
## Task
<task ID and title from docs/PRD.md §8>

## Summary
<what changed and why, 3–6 sentences>

## Design notes
<non-obvious decisions, alternatives rejected, links to ADRs>

## How it was tested
- [ ] Unit tests added/updated
- [ ] Instrumented/manual test on device (model + Android version)
- [ ] Test corpus files used: <list>

## Performance evidence (required for anything touching rendering, gestures, scrolling or caching)
<Perfetto/macrobenchmark numbers vs. budgets in §7: frame jank %, p95 tile latency, memory PSS>

## Screenshots / screen recording (required for UI changes)

## Risks & follow-ups
```

---

## 1. Product Overview

### 1.1 Problem

On high-density, high-refresh tablets such as the Xiaomi Pad 7 (11.2", 3200×2136, 3:2, 144 Hz), Android PDF viewers commonly render pages as low-resolution bitmaps that blur when zoomed, drop frames during pan/zoom because rendering competes with the UI thread, and bury reading behind accounts, cloud sync and ads.

### 1.2 Vision

A pure viewer that feels like macOS Preview and Apple Books: opens instantly, text stays razor-sharp at any zoom up to 800%, pan and pinch stay at the display's refresh rate, and the interface gets out of the way. Two first-class use cases: **academic papers** and **pitch decks**.

### 1.3 Target device and platform

| Item | Value |
|---|---|
| Primary device | Xiaomi Pad 7 (Snapdragon 7+ Gen 3) and Pad 7 Pro (8s Gen 3) |
| Display | 3200×2136, 3:2, up to 144 Hz panel; HyperOS grants ordinary apps at most 120 Hz (measured 2026-10-02) |
| OS on device | HyperOS (Android 15 base) |
| minSdk | 33 (required for AGSL `RuntimeShader`) |
| targetSdk / compileSdk | Latest stable |
| ABIs | `arm64-v8a` only |
| Other tablets | Must work correctly on any Android 13+ tablet; optimization targets the Pad 7 |

### 1.4 Non-negotiable product guarantees

- **No `INTERNET` permission.** The app never touches the network. No analytics, no crash reporting SDKs, no accounts.
- **No ads, no subscriptions, no sign-in.**
- **Read-only.** No annotation, signing, form filling or editing in v1.

### 1.5 Out of scope (v1)

Annotations and highlights, form filling, signatures, editing, cloud sync, file management beyond a recents list, EPUB and other formats, password-protected PDFs (show a clear "not supported yet" message), printing, text-to-speech, phone-optimized layouts (must not crash on phones, but no special design work).

---

## 2. Users and Core Flows

**Primary user:** a reader who reviews academic papers and student pitch decks on a tablet, often in long sessions, and cares about sharpness and fluidity above features.

**Core flows:**

1. **Open** a PDF from the Files app, Downloads, a share sheet, an SD card or a USB-C drive → first page is visible in under 400 ms (see §7) → reading position is restored if the file was opened before.
2. **Read a paper:** continuous vertical scroll, double-tap a column to fit it to the screen width, tap a citation to peek at the reference without losing place, jump via the outline, search text.
3. **Review a deck:** horizontal slide paging, grid overview to jump, full-screen presentation with clicker or volume-key navigation.
4. **Return later:** the library screen shows recently opened files with thumbnails and last position.

---

## 3. Functional Requirements

Priority: **P0** = required for v1, **P1** = should ship in v1, **P2** = post-v1 candidate.

### 3.1 Opening and library

| ID | Requirement | Pri |
|---|---|---|
| F-OPEN-1 | Handle `ACTION_VIEW` and `ACTION_SEND` for `application/pdf` from any app. | P0 |
| F-OPEN-2 | In-app "Open" button using `ACTION_OPEN_DOCUMENT` (Storage Access Framework). No storage permissions requested. | P0 |
| F-OPEN-3 | Read the file through its `ParcelFileDescriptor` using positional reads; never copy the whole file into memory or app storage. | P0 |
| F-OPEN-4 | Call `takePersistableUriPermission` so recents survive restarts. If the permission was lost or the file was moved, show a clear "file unavailable" state in recents with a remove option. | P0 |
| F-LIB-1 | Library screen: recents grid with first-page thumbnail, title (PDF metadata title, fallback filename), last page, last opened time. | P0 |
| F-LIB-2 | Per-document state (page, zoom, scroll offset, mode) keyed by a content hash (hash of file size + first and last 64 KB), not by URI, so it survives the file being reopened from a different location. | P0 |
| F-OPEN-5 | Encrypted/password PDFs and corrupt files show a friendly error, never a crash. | P0 |

### 3.2 Rendering quality

| ID | Requirement | Pri |
|---|---|---|
| F-REN-1 | Text and vector graphics are rendered at native device resolution for the current zoom at every zoom level from fit-page to 800%. No visible pixelation once tiles settle. | P0 |
| F-REN-2 | During a gesture, never show blank areas: always show the best available lower-resolution content, upscaled. | P0 |
| F-REN-3 | Higher-resolution tiles crossfade in (100–150 ms); no flicker or layout shift. | P0 |
| F-REN-4 | Grayscale anti-aliasing for text (no LCD/subpixel AA, which breaks under rotation). | P0 |
| F-REN-5 | Correct rendering of annotations' appearance streams (e.g. existing highlights in the file) — display only. | P1 |

### 3.3 Navigation (all modes)

| ID | Requirement | Pri |
|---|---|---|
| F-NAV-1 | Pinch to zoom (fit-page to 800%), anchored at the gesture focal point. Fling with natural deceleration. | P0 |
| F-NAV-2 | Page indicator with tap-to-jump (enter page number). | P0 |
| F-NAV-3 | Fast-scroll handle on the vertical scrollbar for continuous mode. | P0 |
| F-NAV-4 | Sidebar with tabs: Thumbnails, Outline (PDF bookmarks, nested), Search results. Docked in landscape, overlay in portrait. Collapsible. | P0 |
| F-NAV-5 | Internal links (GoTo actions/named destinations) navigate within the document; a "back" chip returns to the previous position. | P0 |
| F-NAV-6 | External URI links show the URL and offer to open it in another app via an intent (the app itself has no network access). | P1 |
| F-NAV-7 | Keyboard support: arrows, PageUp/PageDown, Space/Shift+Space, Home/End, Ctrl+F, Ctrl +/-/0. | P1 |
| F-NAV-8 | User bookmarks (local, per document). | P2 |

### 3.4 Reading modes

| ID | Requirement | Pri |
|---|---|---|
| F-MODE-1 | **Continuous vertical** (default for portrait-oriented documents). | P0 |
| F-MODE-2 | **Two-page spread** in landscape for portrait pages, with an option for "cover page alone". | P1 |
| F-MODE-3 | **Slide mode** (default for documents whose first page is landscape): horizontal paging, one slide per screen, snap with slight overshoot, haptic tick on settle. | P0 |
| F-MODE-4 | **Slide 2-up stacked** in portrait orientation: two consecutive slides stacked vertically, paging by two. (Note: side-by-side 16:9 slides in 3:2 landscape uses only ~42% of the screen and must NOT be offered as the dual view.) | P1 |
| F-MODE-5 | Auto-detect default mode from page aspect ratio; user override is remembered per document. | P0 |

### 3.5 Academic paper features

| ID | Requirement | Pri |
|---|---|---|
| F-ACAD-1 | **Column-aware double-tap zoom:** double-tapping inside a text column animates zoom/pan to fit that column's width plus a small margin. Double-tap again returns to the previous zoom. If the tap is in a full-width region (title, abstract block, full-width figure), fit page width instead. | P0 |
| F-ACAD-2 | When zoomed to a column, scrolling down past the column bottom offers (or auto-performs, setting-controlled) a jump to the top of the next column. | P1 |
| F-ACAD-3 | **Citation/reference peek:** tapping an internal link opens a floating, non-modal card showing a sharp render of the destination region (bibliography entry, figure, equation). Card has "Go there" and dismiss. Reading position is untouched. | P0 |
| F-ACAD-4 | Peek for unlinked citations (e.g. `[14]` in papers without hyperref) via text heuristics. | P2 |
| F-ACAD-5 | **Search:** full-text search with progressive results (streamed as pages are searched), hit list with page and excerpt in the sidebar, hit markers on the scrollbar, highlighted matches on the page, next/previous. | P0 |
| F-ACAD-6 | **Text selection and copy** via long-press, with handles. | P1 |

### 3.6 Pitch deck features

| ID | Requirement | Pri |
|---|---|---|
| F-DECK-1 | **Grid overview:** pinch-out from a slide or tap a button to show all slides in an adaptive grid (3–5 columns by width); tap to jump with a shared-element zoom transition. | P0 |
| F-DECK-2 | **Presentation mode:** hides all chrome; navigation by tap zones (left/right thirds), swipe, keyboard, Bluetooth presenter remotes (PageUp/PageDown/arrows), and volume keys. Volume keys are intercepted only while presentation mode is active. | P0 |
| F-DECK-3 | Keep screen on while in presentation mode. | P0 |
| F-DECK-4 | Next/previous slides always pre-rendered at full screen resolution, so a flip never shows a low-res frame. | P0 |

### 3.7 Themes and comfort

| ID | Requirement | Pri |
|---|---|---|
| F-THEME-1 | App chrome follows system light/dark (Material 3, dynamic color optional). | P0 |
| F-THEME-2 | **Page themes:** Original, Dark, Sepia. Selectable in the View Settings popover (F-UI-2); remembered globally with per-document override. | P0 |
| F-THEME-3 | **Dark page theme** must keep diagrams readable: perceptual lightness inversion (e.g. in OKLab) that preserves hue, so black text becomes off-white and colored plot lines keep their color identity. Raster images (photos, embedded bitmaps) are excluded from the transform using image bounding boxes from the PDF object model. Target page background ~`#121214`, text ~`#E4E4E7`. | P0 |
| F-THEME-4 | Sepia uses the same pipeline with a warm tone curve. | P0 |
| F-THEME-5 | Optional per-document "invert images too" toggle for scanned documents. | P1 |

### 3.8 Chrome and layout (owner-approved 2026-10-11)

The chrome follows Preview, Books and PDF Expert on iPad: one thin toolbar, icons rather than text buttons, nothing floating over the page while reading.

| ID | Requirement | Pri |
|---|---|---|
| F-UI-1 | **Toolbar:** back to library and sidebar on the left, the document title in the middle, search and "aA" on the right (slides add grid and present). It extends under the status bar as one strip. | P0 |
| F-UI-2 | **View Settings popover** ("aA"): layout tiles (Vertical Scroll, Two Pages, Slides, Two Stacked as each applies), First Page Alone, Day/Sepia/Night swatches, Theme Images Too. | P0 |
| F-UI-3 | **Focus:** a tap on the page (not on a link) or a scroll/swipe hides the toolbar, the page pill and the system bars; another tap brings them back. They stay while the sidebar, search or settings are open. Pages draw edge to edge, so hiding the bars never resizes the page. | P0 |
| F-UI-4 | **Search** lives in the toolbar: field, hit counter, previous/next, Done. The sidebar uses a segmented control (Pages / Outline / Results). | P0 |
| F-UI-5 | **Titles** are cut in the middle when too long, so a file name keeps its format (`…Experiences.pptx.pdf`). | P1 |
| F-UI-6 | **Library:** large title, covers in their own shape on a shared baseline with a soft shadow, one-line title and "Page N of M · time". | P1 |

---

## 4. Rendering Architecture

### 4.1 Engine

- **PDFium**, BSD-licensed, via a thin custom JNI layer in `:core:pdfium`. Start from prebuilt Android arm64 binaries (e.g. `bblanchon/pdfium-binaries`); building from source is optional and only needed if the Skia backend or feature stripping is pursued. **ADR required** recording the binary source, version and build flags.
- Evaluate the Skia-backend build of PDFium for anti-aliasing quality as part of M1; record the result in the ADR.
- Do **not** use MuPDF (AGPL) or any GPL/LGPL renderer.
- Custom `FPDF_FILEACCESS` implementation backed by `pread` on the file descriptor (F-OPEN-3).

### 4.2 Threading and process model (critical)

PDFium is **not thread-safe**: it has process-global state, and concurrent calls crash even on different documents.

- All PDFium calls run on **one dedicated render thread** inside a **separate service process** (`android:process=":render"`). Reasons: a malformed PDF that crashes PDFium must not kill the UI, and process separation leaves a path to a second render process later if profiling demands parallelism.
- The UI process talks to the render process through a Binder interface (AIDL or Messenger; **ADR required**).
- Define a `PageRenderer` interface in `:core:render` from day one. An in-process implementation is allowed only during the M1 spike; the out-of-process implementation is an M1 exit criterion.
- On render process death: the UI shows the last good content, restarts the service, reopens the document and resumes. The user should at most see tiles re-sharpen.

### 4.3 Pixel transport

Preferred design (**ADR required**, with fallback documented):

1. UI process requests a tile.
2. Render process allocates a `HardwareBuffer` (`RGBA_8888`, usage `CPU_WRITE_OFTEN | GPU_SAMPLED_IMAGE`), locks it in native code, and has PDFium render directly into the locked memory using `FPDFBitmap_CreateEx` with the buffer's stride.
3. Buffer is unlocked and sent across Binder (HardwareBuffer is `Parcelable`).
4. UI process wraps it with `Bitmap.wrapHardwareBuffer` → ready for GPU drawing with no further upload.

Fallback if (2) proves unreliable on the device's driver: render into `SharedMemory`, then in the UI process copy to a `HARDWARE` bitmap on a background thread. In either design, **no GPU texture upload may happen on the UI thread during a frame**.

### 4.4 Tile pyramid

- Coordinates: work in **device pixels per PDF point** (scale), not DPI.
- **Discrete zoom levels:** powers of √2 from the fit-page scale up to 8× fit-width scale. For a given zoom, use tiles from the next level **at or above** the current zoom; the GPU downscales slightly, which stays sharp.
- **Tile size:** 512×512 device pixels (tunable constant; benchmark 256 and 1024 in M1 and record in ADR).
- **Layers per page, drawn back to front:**
  1. **Thumbnail** (~400 px wide), resident for all pages once generated.
  2. **Base level:** whole page at fit-width scale, capped at 2048 px on the long side (the renderer rejects bitmaps over 4096 px; full fit-width on a 3200 px wide tablet exceeds that), for visible and adjacent pages.
  3. **Detail tiles:** above base zoom only.
- Keep the previous level's tiles visible until the new level fully covers the viewport.
- Crossfade new tiles over 100–150 ms.

### 4.5 Scheduler

- A priority queue in the UI process decides what to request; the render process executes requests in order and supports cancellation.
- Priority: base layers of visible pages (one small render, so a page is never blank) → visible tiles at the current target level, ordered by distance from viewport center → adjacent pages' base layers (so a page scrolled onto already has its base) → prefetch ring in the scroll/fling direction → thumbnails. During a fast scroll only base layers are requested.
- On every gesture update, requests that are no longer relevant are cancelled.
- **In-flight cancellation** uses PDFium's progressive rendering API (`FPDF_RenderPageBitmap_Start` / `FPDF_RenderPage_Continue` with an `IFSDK_PAUSE` callback that checks a cancel flag). A heavy tile at a stale zoom must never block the queue.
- Keep `FPDF_PAGE` handles loaded for pages near the viewport (LRU, ~6 pages) to avoid re-parsing content streams per tile.
- Coalesce: never more than one outstanding request for the same tile key.

### 4.6 Compose rendering rules

- Document surface is a single custom composable. Pan/zoom state is read **only in the draw phase** (lambda `Modifier.graphicsLayer { }`, `drawWithCache`, `Canvas`). A gesture must cause **zero recompositions** of the document surface; verify with recomposition counts in the PR.
- Gesture handling via `pointerInput` with custom transform detection (pinch focal point anchoring, fling with `splineBasedDecay`).
- Cap the number of newly arrived tiles swapped in per frame (start at 4) to spread work across frames.
- Request the high refresh rate explicitly (`Surface.setFrameRate` / preferred display mode; on Android 15 also the frame-rate APIs for views/windows). Verify on device with the developer FPS overlay; HyperOS may override.
- **Fallback plan:** if the M1 benchmark shows Compose `Canvas` can't meet §7 with ~60 tiles plus crossfades, move the document surface to `AndroidExternalSurface` with a small GL renderer, keeping Compose for all chrome. The `PageRenderer`/tile manager APIs must not depend on the drawing backend.

---

## 5. Memory and Cache

| Tier | Contents | Storage | Budget |
|---|---|---|---|
| Hot | Visible tiles + 1-tile ring in movement direction | Hardware bitmaps | ~80 MB |
| Warm | Recently visible tiles and base layers, LRU by bytes | Hardware bitmaps | ~80 MB |
| Thumbnails | All pages of open document | Hardware bitmaps + disk cache | ~20 MB in memory |
| Disk | Thumbnails only, keyed by content hash + page + size | WebP in app cache dir | ~200 MB, LRU |

- Budgets are in **bytes**, configurable, and scale down on devices with less RAM (`ActivityManager.memoryClass`).
- No disk cache for detail tiles.
- `onTrimMemory`: drop the warm tier at `TRIM_MEMORY_RUNNING_LOW` and above; drop everything except visible content when backgrounded.
- Close and release hardware buffers deterministically when evicted (`HardwareBuffer.close()`, `Bitmap.recycle()` where appropriate). No reliance on finalizers.
- Total app PSS (both processes) must stay within §7 budgets.

---

## 6. Software Architecture

### 6.1 Stack

| Area | Choice |
|---|---|
| Language | Kotlin (latest stable), C++17 for JNI |
| UI | Jetpack Compose, Material 3, Material 3 Adaptive |
| Concurrency | Kotlin Coroutines and Flow |
| Architecture | MVI-style unidirectional data flow per screen (`State`, `Intent`, reducer in ViewModel) |
| DI | Hilt (or manual DI if agy prefers; ADR required either way) |
| Persistence | Room for library/recents and per-document state; DataStore for settings |
| Build | Gradle Kotlin DSL, version catalog, CMake for native |
| Quality | ktlint or detekt, Android Lint (warnings-as-errors on new code), unit tests, Macrobenchmark |

No dependency may require network access at runtime. Every new dependency must be justified in its PR description, with its license.

### 6.2 Modules

```
:app                 – entry point, navigation, intent handling, DI graph
:core:model          – pure Kotlin models (DocumentId, PageInfo, TileKey, Outline, Link, SearchHit…)
:core:pdfium         – JNI bindings, native code, file access, render service (":render" process)
:core:render         – PageRenderer interface, tile pyramid, scheduler, caches
:core:ui             – theme, design tokens, shared components, AGSL shaders
:core:data           – Room, DataStore, recents, per-document state
:feature:library     – recents/library screen
:feature:reader      – document surface, gestures, continuous/spread modes, sidebar, search, peek
:feature:slides      – slide pager, 2-up stacked, grid overview, presentation mode
:benchmark           – Macrobenchmark + baseline profile generation
```

Dependency rule: `feature` → `core`, never `feature` → `feature`. `:core:model` has no Android dependencies.

### 6.3 Text and structure extraction (in render process)

Exposed through the same Binder interface, all off the UI thread and cancellable:

- Character boxes and text per page (`FPDFText_*`) → used for search, selection, column detection.
- Links and destinations (`FPDFLink_*`, `FPDFDest_*`, named destinations).
- Outline (`FPDFBookmark_*`).
- Image object bounding boxes (`FPDFPage_GetObject` / `FPDFPageObj_GetBounds` for image objects) → dark-theme mask.
- Results cached per page in the UI process.

### 6.4 Column detection (F-ACAD-1)

- Build a horizontal projection histogram of character boxes for the page; find near-empty vertical bands (gutters) spanning a large fraction of the page height.
- Segment the page vertically into regions (full-width vs. multi-column) so title/abstract/wide figures are handled.
- Output: list of `(yRange, columns: List<xRange>)`. Cache per page.
- Unit-test against the corpus with expected column boxes stored as fixtures.

### 6.5 Page theme shader (F-THEME-3)

- AGSL `RuntimeShader` applied when drawing tiles: convert to OKLab, invert/remap L with a tone curve, keep a/b (hue/chroma), convert back.
- Image mask: per page, a list of image rects in page coordinates, passed as uniforms (cap the count; beyond the cap, rasterize a low-res mask texture). Pixels inside image rects are drawn untransformed.
- Shader applies at draw time, so switching themes is instant and needs no re-render.

---

## 7. Performance Budgets and Acceptance Gates

Measured on a Xiaomi Pad 7 (or Pro), release build with baseline profile, using Macrobenchmark and Perfetto. Rendering-related PRs must report these numbers.

| Metric | Budget |
|---|---|
| Frame time during continuous pinch/pan on corpus docs | ≥ 99% of frames within 8.33 ms (120 Hz, the highest rate HyperOS grants apps); zero frames over 16.6 ms |
| Fling scroll through a 30-page paper | Same as above |
| Slide flip | No frame over 8.33 ms; next slide always sharp on arrival |
| Tile sharpening latency (gesture end → visible area fully sharp), typical text page | p95 ≤ 150 ms at fit width, ≤ 300 ms at 800% |
| Heavy vector page (corpus "heavy-plot") | No UI jank; sharp within 1 s |
| Cold start → library visible | ≤ 500 ms |
| Open 30-page paper → first page sharp | ≤ 400 ms |
| Total PSS (UI + render process), 100-page paper, active zooming | ≤ 350 MB on ≥ 2.5K displays (≤ 250 MB on ≤ 1080p; accounts for ~82 MB triple-buffered display buffers on 3.2K displays alongside §5 180 MB TileCache) |
| APK (arm64) size | ≤ 15 MB |
| Crash on malformed corpus PDFs | UI process never crashes |

"Sharp" means every visible detail tile is loaded **and** its crossfade (F-REN-3, 100 ms) has finished, so the detail layer is fully opaque (owner decision, 2026-10-12).

If a budget is missed, the PR must say so explicitly, with the measured number and a plan. Do not quietly relax budgets.

### 7.1 Test corpus

Store in `testdata/corpus/` with `testdata/corpus/SOURCES.md` listing origin and license for every file. Prefer files generated in-repo (LaTeX/Beamer sources plus build script) so nothing copyrighted is committed. Required categories:

1. Two-column LaTeX paper, ~30 pages, with hyperref citations, equations, figures.
2. Single-column paper without hyperref.
3. Page with a very heavy vector plot (thousands of paths).
4. Scanned (image-only) PDF.
5. 16:9 deck, ~60 slides, with photos and gradients.
6. 4:3 deck.
7. 100+ page document for memory testing.
8. Malformed/truncated PDFs and an encrypted PDF.

---

## 8. Milestones and Task Breakdown

Each task = one PR (or a short sequence of `a/b/c` PRs). Order within a milestone is the suggested order. A milestone is complete only when its exit criteria are demonstrated in a PR description.

### M0 — Initialization (direct commit, see §0.1)

- **M0-00** Repo, scaffold, CI, PR template, PRD copy.

### M1 — Engine core (target: 2–3 weeks)

- **M1-01** PDFium integration: prebuilt binaries, CMake, JNI skeleton, `FPDF_FILEACCESS` over fd, open/close, page count and sizes. ADR: PDFium source/version/flags.
- **M1-02** Test corpus generation scripts and `SOURCES.md`.
- **M1-03** Render service in `:render` process, Binder interface, `PageRenderer` interface. ADR: IPC mechanism.
- **M1-04** HardwareBuffer pixel transport (with fallback path). ADR: pixel transport.
- **M1-05** Progressive rendering with cancellation.
- **M1-06** Tile pyramid model + scheduler + coalescing (pure Kotlin, heavily unit-tested).
- **M1-07** Document surface composable: base layer + detail tiles, pinch/pan/fling, crossfade, zero-recomposition gestures.
- **M1-08** Caches and memory budgets, `onTrimMemory`.
- **M1-09** Render process crash recovery.
- **M1-10** Macrobenchmark module, baseline profile, first full measurement against §7. ADR: tile size result, Skia-backend evaluation, Compose-vs-SurfaceView decision.

**Exit criteria:** corpus docs 1, 3, 5 pan/zoom within §7 frame budgets; malformed PDFs don't crash the UI; numbers recorded in M1-10.

### M2 — Navigation core (target: 3 weeks)

- **M2-01** Library screen + Room recents + persisted URI permissions + content hash.
- **M2-02** Intent handling (`VIEW`, `SEND`, `OPEN_DOCUMENT`), error states (encrypted, corrupt, unavailable).
- **M2-03** Continuous vertical mode with fast-scroll and page indicator.
- **M2-04** Slide mode pager with snap, overshoot and haptics; auto mode detection.
- **M2-05** Thumbnails pipeline + disk cache.
- **M2-06** Adaptive scaffold + Thumbnails sidebar.
- **M2-08** Per-document state restore.
- **M2-09** Keyboard navigation (F-NAV-7).

**Exit criteria:** all P0 items in §3.1, §3.3 (with Outline sidebar and internal link navigation deferred to M3 following extraction in M3-01) and F-MODE-1/3/5 working; §7 budgets still met.

### M3 — Paper features (target: 3 weeks)

- **M3-01** Text/link/outline/image-bounds extraction APIs in render process.
- **M3-02** Outline sidebar + internal link navigation + back chip (F-NAV-4, F-NAV-5, F-NAV-6).
- **M3-03** Column detection + fixtures + unit tests.
- **M3-04** Column-aware double-tap zoom (F-ACAD-1), then F-ACAD-2.
- **M3-05** Citation/reference peek card (F-ACAD-3).
- **M3-06** Progressive search with sidebar results, scrollbar markers, highlights.
- **M3-07** Text selection and copy.

**Exit criteria:** F-ACAD-1, 3, 5 work on corpus docs 1 and 2; P1 items done or explicitly deferred in a PRD PR.

### M4 — Deck features and themes (target: 2 weeks)

- **M4-01** Grid overview with shared-element transition.
- **M4-02** Presentation mode, input handling (tap zones, keys, clickers, volume), keep-screen-on.
- **M4-03** Slide 2-up stacked (portrait) and two-page spread (landscape, portrait documents).
- **M4-04** AGSL page theme shader + image mask; Dark and Sepia themes; "invert images" toggle.

**Exit criteria:** all P0 in §3.4, §3.6, §3.7; dark theme screenshots of corpus docs 1, 3, 5 in the PR.

### M5 — Hardening and release candidate (target: 2–3 weeks)

- **M5-01** Fuzz/robustness pass on malformed PDFs (document method and results).
- **M5-02** Memory profiling on 100-page doc; leak checks.
- **M5-03** APK size pass (R8 full mode, resource shrinking, native strip).
- **M5-04** Accessibility pass: TalkBack labels for chrome, touch target sizes, font scaling in chrome.
- **M5-05** Final §7 measurement report in `docs/perf-report.md`.
- **M5-06** Signed release build instructions (no keys committed).

### M6 — Smoothness and speed polish (target: 3–4 weeks)

Added 2026-10-12, after v0.1.0 shipped as a GitHub release. The owner decided to release with the
§7 misses and fix them afterwards. Sources: the "Misses and plans" and "Not verified" sections of
[`docs/perf-report.md`](perf-report.md), and [`docs/competitor-benchmark.md`](competitor-benchmark.md).
Against Acrobat, Google Drive, WPS and the Xiaomi viewer, Terbaca wins on memory and size and comes
second on opening speed. **It loses badly on smoothness:** 34.6 % of frames are late while
scrolling (the others are at 1–5 %), and 29.4 % while pinching. The owner's priority is closing
that gap.

Rules for this milestone, on top of AGENTS.md:
- Every performance number comes from a run on the Pad 7 with raw output committed (or the exact
  command and a summary). No estimates in Met/Missed columns. M1 and M5 both had numbers nothing
  backed, so reviewers will check this.
- A new test or metric must be shown to fail on a deliberately broken commit (negative control),
  then reverted, with both CI runs linked in the PR.
- Changes to how the reader *feels* (crossfade length, tile reveal order, scroll physics) need an
  owner check on the Pad 7 before merge. Install the `benchmark` build (`./gradlew
  :app:installBenchmark`; debug builds are janky) and ask. The owner has already chosen these, on
  the device, in 2026-10:
  - The tile level is frozen while pinching, with one sharpen on release. Don't bring back level
    swaps mid-pinch: they shimmer.
  - Tiles are revealed in one batched fade (no "square loading").
  - The fast-scroll bar sits 8 dp from the edge.
  Keep these unless the owner agrees to change them.
- If a budget can't be met, bring it to the owner in a PRD PR. Never relax it quietly.

Tasks, in order:

- **M6-01** Baseline re-measure on current `main`, on the Pad 7.
  - Re-run `LatencyBenchmarks` with the crossfade-inclusive trace end (since #73) and replace every
    "≈" estimate row in `perf-report.md`.
  - Re-run the frame benchmarks.
  - Re-run `tools/perf/competitor/frames.sh` for Terbaca only.
  - Record the device state (battery, temperature, HyperOS version). Paper frames swing ±4 points
    between runs, so take ≥ 3 runs.
- **M6-02** Scroll smoothness investigation. No fix yet, report only.
  - Trace M2's commit (paper frames were 99.5 %) against current `main`, on the same device state.
  - Find what adds RenderThread/GPU work on paper pages. Suspects: M3/M4 overlays (search
    highlights, theme shader, selection layer), the #63 chrome, and the larger #68 pills.
  - Use Perfetto, GPU overdraw view, and `dumpsys gfxinfo`.
  - Deliver `docs/smoothness-investigation.md`: the ranked causes with trace evidence, and a fix
    plan.
  - If a rendering-backend change (e.g. SurfaceView/GL, PRD §9) is needed, write an ADR (`proposed`).
- **M6-03** Scroll smoothness fixes (`a/b/c` PRs as needed), in the order of M6-02's ranking.
  - Each PR reports `competitor/frames.sh` scroll late-frame % and the §7 paper-frame % before and
    after.
- **M6-04** Pinch smoothness: the same investigate-then-fix loop for pinch (29.4 % late; Drive is
  at 5.2 %).
- **M6-05** Open path (§7 "open → first page sharp", and heavy-plot open).
  - Keep the render-service binding alive across activity recreation, by binding from the
    Application or a retained holder.
  - Render the first page's visible tiles before any prefetch.
  - **Risky (AGENTS §3: IPC/Binder lifecycle):** open the PR and wait for review.
- **M6-06** Sharpening after a pinch at fit width (p95 budget 150 ms).
  - Render the centre tiles first.
  - If needed, shorten the crossfade. F-REN-3 allows 100 ms.
  - Needs an owner feel check. If 150 ms isn't reachable with a visible fade, return the budget to
    the owner.
- **M6-07** Slide flip: cut the pager's per-frame layout work during a drag (see ADR 0004). Add an
  instrumented check for "next slide sharp on arrival", which isn't measured today.
- **M6-08** Cold start (510 ms, budget 500): profile the library's first frame and show the grid
  before the per-file availability checks finish.
- **M6-09** Verification gaps from the M5 review:
  - A smoke test on a minified build that opens the outline and taps a link, in CI or documented
    as a manual step.
  - A TalkBack walkthrough of the reader, slides and library on the Pad 7.
  - Portrait layouts on the Pad 7.
  - Check the ~6 dp position shift of the floating pills since #68.
  - Screenshots or a recording in the PR.
- **M6-10** Regenerate the baseline profile on the Pad 7.
  - Add a library-tap or `VIEW`-intent open path to `BaselineProfileGenerator`.
  - Final §7 re-measure and `perf-report.md` update.
  - Full `tools/perf/competitor/` re-run, and update the README "How Terbaca compares" table.
  - Bump the version to `versionCode 2` / `0.2.0` and draft the release notes.
  - **Stop before releasing.** Signing and publishing the GitHub release are the owner's step (the
    key never leaves their machine).

**Exit criteria:**
- Scrolling the paper in `competitor/frames.sh` has ≤ 5 % late frames, in line with Acrobat and
  Drive.
- Pinch has ≤ 10 % late frames.
- Every §7 row is measured, not estimated. Each one is Met, or Missed with an owner-approved plan.
- The owner has done a feel check on the Pad 7.

Deferred (not in M6 unless time allows):
- Run each case of the Python fuzz harness in a subprocess.
- De-duplicate `memory_100plus.pdf` between androidTest assets and `testdata/corpus/`.
- A PSS measurement on a ≤ 1080p device.
- Time to sharp after zoom compared with other apps (not measurable from screen recordings).
- The Play Store release (the owner's decision, 2026-10-12).

### M6R — M6 review fixes and smoothness round 2

Added 2026-10-12 after Claude's M6 milestone review. M6 is **not done**: the scroll gain is
real (34.6 % → 10.4 % late frames, 12–13 % per iteration), but both exit criteria are missed,
several reports claim things nothing backs, some tests can't fail, and PR #82 only changes
behaviour while a benchmark is tracing. M6R closes that and takes a second run at smoothness.
The M6 rules above all still apply. The rules below come on top of them, and **a PR that breaks
one is not done, even when CI is green.**

#### M6R rules

**R1. Numbers.**
- Every number in a PR, report, README or release note must point to a committed raw file under
  `docs/perf-raw/<task-id>/`: the Macrobenchmark `benchmarkData.json` (or its `instrument.txt`
  plus a CSV made from it), the exact command, and the device state (battery %, thermal status,
  HyperOS version).
- If a number can't be traced to a file, delete it.
- Before you write a number, recompute it from the raw file with a script that you commit next to
  it (e.g. `docs/perf-raw/<task-id>/summarize.py`). Never copy numbers from console text or from
  memory.
- "Late frames %" always means `frameOverrunMs > 0` over all frames of all iterations. Always
  report it together with **frames per iteration** and the **per-iteration late %**.
- On the Pad 7, `CompetitorBenchmarks#scrollFlings` draws about 1,130 frames per iteration. If a
  run differs by more than 15 % from that, the run is suspect: say so, and don't use it for a
  before/after claim.
  - The M6-01 baseline (2,480 frames over 5 iterations = 496 per iteration, "42.6 %") is such a
    run. Compare against v0.1.0's 34.6 % (`docs/competitor-benchmark/summary.txt`), or against a
    fresh run of the same commit.
- A before/after claim needs ≥ 3 runs of both "before" and "after" on the same day and device
  state. Report the median of the run results and the min–max.

**R2. Status words.** A §7 row's status is exactly one of:
- **Met:** measured with the budget's own measure, and inside the budget.
- **Missed:** measured, and outside the budget.
- **Not measured:** give the reason.

"Improved", "Near budget", "Tracked", "Massive gain" and similar words are not allowed. Measure
with the budget's own measure: "≥ 99 % of frames ≤ 8.33 ms" is the share of frames ≤ 8.33 ms, not
a CPU percentile. "p95 ≤ 150 ms" is the p95, not the p90.

**R3. Tests must be able to fail.**
- Every new or changed test runs the app's real code path, never only stdlib or a constant.
- For each one, the PR shows a negative control:
  1. Push a commit that breaks the code under test on purpose, with a message starting with
     `NEGATIVE CONTROL:`.
  2. Link the CI run where the test fails.
  3. Push a revert, and link the green CI run.

  Squash merge removes both commits from `main`, which is expected.
- A test that can't be made to fail this way gets deleted, not merged.

**R4. Feel changes.** Changes to how the reader looks or feels (fade length, reveal order,
placeholders, scroll or zoom behaviour) need the owner's check before merge:
1. Install with `./gradlew :app:installBenchmark`. If adb install is blocked, push the APK to
   `/sdcard/Download` for a manual install.
2. Tell the owner, in plain non-technical language, exactly what to try and what to compare.
3. Wait for their answer.

Writing "flagged for owner check" in the PR does not count. You may carry on with other tasks
while you wait (AGENTS §3).

**R5. Claims of manual checks** (TalkBack, portrait, smoke tests) need screenshots or a
recording in the PR. Pull them with `adb exec-out screencap -p > x.png` or
`adb shell screenrecord`. Without them, the item goes under "Not verified".

**R6. Honest "Not verified".** Anything you wrote but did not run on the Pad 7 goes there. A
missed target is reported as missed, in the PR title area, not hidden in a table.

#### M6R tasks, in order

- **M6R-01 Fix the reports** (`fix/M6R-01-honest-reports`). Docs only; no code changes.
  - `docs/perf-report.md`, section "M6-10 final release verification": rewrite the status column
    under R2.
    - Doc 1, doc 3, doc 5 and slide flip: give the share of frames ≤ 8.33 ms and the count
      > 16.6 ms, as the budget asks. If the M6-10 run didn't keep those (only the competitor JSONs
      were committed), mark the row **Not measured** for now. M6R-10 re-measures it.
    - Sharpening at fit width: **Missed** (p90 242 ms against a p95 budget of 150 ms; give the
      p95 if it can be recomputed from raw data, otherwise mark it Not measured).
    - Cold start: **Missed** (median 563 ms, budget 500; regressed from 460 ms in M6-01).
    - Pinch, competitor harness: **Missed**, 29.9 % against the ≤ 10 % exit criterion, worse
      than the 25.0 % baseline.
    - Scroll, competitor harness: **Missed**, 10.4 % against ≤ 5 %. Compare it with v0.1.0's
      34.6 %, not with the suspect 42.6 % run (R1).
    - PSS and malformed-PDF rows: **Not measured** in M6-10 (324 MB is the competitor-benchmark
      number from 2026-10-11).
    - The open → sharp row stays **Missed**.
  - README milestone table: M6 status "In progress". Remove "M2–M6 were verified on a Xiaomi
    Pad 7"; say exactly what was checked by hand, and when. In "How Terbaca compares", keep 10.4 /
    29.9 and remove the "42.6 % unoptimized baseline" wording.
  - `docs/release-notes-0.2.0.md`:
    - Remove the "75 % drop" claim, or restate it as 34.6 % → 10.4 % against v0.1.0.
    - Remove "fully within budget" for 800 %. 287.7 ms is under 300 ms, so say exactly that.
    - Remove "verified … TalkBack, portrait" unless M6R-09 adds evidence.
    - Remove "cutting gesture layout overhead in half" (nothing measured it).
    - Remove the "Next slide sharp on arrival guarantee" (M6R-05 shows it didn't measure
      anything).
    - Add a "Known limitations" section: the pinch smoothness and cold start misses.
  - `docs/smoothness-investigation.md`, Rank 3: note that the tiles already arrive as
    `Bitmap.Config.HARDWARE` (`PdfiumRemotePageRenderer.kt`, `softwareBitmap.copy(HARDWARE)`).
    The texture upload happens on that copy, not on the RenderThread, so the "4 MB upload per
    frame" premise is unproven. M6R-06 tests it.
  - `docs/m6-09-verification.md`: the code snippet under "Resolution" doesn't match `Chrome.kt`
    (there, `clickable` sits on the Box, not the Surface). Copy the real code.
  - Done when every number in these four files points to a raw file, or is removed.

- **M6R-02 Pinch: keep base layers coming mid-pinch** (`fix/M6R-02-pinch-base-layers`).
  - #87 moved `if (zooming) return@collectLatest` to the top of the `collectLatest` block in
    `DocumentTileCollector` (`DocumentSurface.kt`). Mid-pinch, this skips `pinHot`,
    `cancelStaleExcept`, and the `VISIBLE_BASE` / `ADJACENT_BASE` requests. Zooming out mid-pinch,
    pages that enter the view get no base layer and stay as blank placeholders until the fingers
    lift.
  - Fix: mid-pinch, still compute the visible pages, `pinHot` the base keys, and request
    `VISIBLE_BASE` and `ADJACENT_BASE`. Skip only the detail and prefetch work (detail level stays
    frozen; owner decision, see the M6 rules above).
  - Keep the per-page `buildLevels` cache only if it is invalidated on every change it depends
    on: today it is cleared only on a viewport size change. Use it in `DocumentTileCollector` too,
    or remove it.
  - Test: extract the "what to request this frame" decision into a pure function, and unit test
    that when `zooming = true` it returns the base keys of the newly visible pages and no detail
    keys. Negative control (R3): put the early return back; the test must fail.
  - Measure (R1): `CompetitorBenchmarks#pinchZoom` ×3 before and ×3 after.
  - Feel check (R4): ask the owner to pinch out quickly on a long paper and say whether blank
    white pages still appear.

- **M6R-03 Owner check of the 100 ms fade** (no code unless the owner says so).
  - Build two benchmark APKs, one with `TILE_CROSSFADE_DURATION_MS = 150`, one with 100. Ask the
    owner to zoom in and out on a text page with each, and say which feels better.
  - Record the answer and the date in `docs/decisions.md`. If the owner picks 150, open
    `fix/M6R-03-fade-150` and return the fit-width sharpening budget question to the owner in a
    PRD PR (M6 rules).

- **M6R-04 Hollow tests and no-op code** (`fix/M6R-04-real-tests`).
  - `CentreFirstTileOrderingTest`: delete it. It tests Kotlin's `sortBy` and a constant.
    - The `visibleDetail.sortBy` / `prefetch.sortBy` lines in `DocumentTileCollector` are almost a
      no-op: `TilePriority.compareTo` already orders by `distanceToCenter`. They only change which
      tile is enqueued first.
    - Remove them, unless you show (R1, ≥ 3 runs of `SharpenLatencyBenchmark`) that they move the
      p95.
    - Instead, add a test in `core/render` that enqueues tiles in far-to-near order into the real
      scheduler, and asserts that dispatch order is near-to-far. Negative control (R3): make
      `compareTo` ignore distance; the test must fail.
  - `RecentsRepositoryTest.recents_emitsInitialStateImmediately` passes on the old code too. Make
    the availability check injectable: a constructor parameter
    `availability: suspend (Uri) -> Boolean`, defaulting to the current `isAvailable`. Test with a
    checker that suspends forever (`awaitCancellation()`). The first emission must still arrive
    within 1 s. Negative control (R3): revert `transform` back to `map`; the test must time out
    and fail.
  - `SlideBitmapCacheInstrumentedTest.nextSlideSharpOnArrival_neighbourPreRenderedInCache` only
    does a put and get on the cache. Delete it; M6R-05 replaces it.

- **M6R-05 Real "next slide sharp on arrival"** (`fix/M6R-05-slide-arrival`).
  - The current mark is a `DisposableEffect(page.index, isSharp)` in `SlideContent`. The pager
    composes neighbours before they become active (`beyondViewportPageCount`), so when a neighbour
    becomes the current page, neither key changes and no mark is emitted. The metric counts almost
    nothing.
  - Fix: in `SlidesScreen`, use one `LaunchedEffect(pagerState)` that collects
    `snapshotFlow { pagerState.settledPage }`. On each new settled page, check whether
    `slideCache` holds that page's `SlideKey` at the current viewport size, and emit
    `SLIDE_SHARP_ON_ARRIVAL` or `SLIDE_NOT_SHARP_ON_ARRIVAL` once.
  - `SlideFlipBenchmark` must report both counts. The total must equal the number of flips per
    iteration (`FLIPS` × the passes the benchmark makes). Check this and print it.
  - Negative control (R3): turn off neighbour pre-rendering (set `SLIDE_BEYOND_VIEWPORT_PAGES = 0`
    in a throwaway commit). Run the benchmark on the Pad 7 and show that `NotSharp` goes up. CI
    can't run Macrobenchmark, so commit the Pad 7 output of both runs instead of CI links.
  - Report the real sharp-on-arrival count in `perf-report.md`. If it isn't 100 %, the slide flip
    row is **Missed**.

- **M6R-06 Smoothness round 2: find the remaining causes** (`docs/M6R-06-smoothness-round2`).
  Report only, no fix. Deliver `docs/smoothness-investigation-2.md`.
  - **Clue to start from:** in the M6-10 scroll run, four iterations were 12–13 % late and one
    was 1.5 % (`docs/perf-raw/m6-10/app.terbaca__scrollFlings.json`). Find out what was different
    in that iteration. Suspects: tile cache already warm (no tile arrivals, fades or base-layer
    draws), thermal or CPU frequency state.
  - **Per-frame attribution:** for each late frame in the Perfetto traces, record:
    - whether a tile arrived (swap), or a crossfade was running, in that frame;
    - whether the base layer was drawn, and how many times (the clipped path in
      `drawTilePyramid` can draw it up to 4 times);
    - UI-thread time, RenderThread time, and GPU completion time (the `actual_frame_timeline_slice`
      and `gpu_completion` tracks).

    Commit the trace-processor SQL or Python you used. Add `Trace` sections around the
    swap/fade/base-layer draw if needed; they must cost nothing when tracing is off.
  - **Ablation runs:** add debug switches that only the `benchmark` build type reads, from an
    intent extra (e.g. `-e ablation no_fade`). They must not exist in the `release` build; check
    `BuildConfig`. Switches:
    `no_fade` (tiles appear at full alpha), `no_base_under_tiles`, `no_underlay_level`,
    `no_page_placeholders`, `no_dark_background`, `no_chrome` (hide the toolbar, pills and
    scrollbar), `swap_cap_4` (back to 4 tiles per frame), `swap_cap_unlimited`.
    - Run `CompetitorBenchmarks#scrollFlings` ×3 for each switch, plus ×3 with none, in one
      session.
    - Table: switch → median late %, min–max, frames per iteration.
    - The switch that cuts the most late frames is cause #1, and so on. Some of these switches
      look wrong (`no_fade`); that's fine, they only measure costs.
  - Also check overdraw: `adb shell setprop debug.hwui.overdraw show`, screenshot mid-scroll,
    include it.
  - Fix plan: ranked fixes, each with the expected gain taken from the ablation table, and which
    of them change the feel (R4).
  - If the table shows that even every ablation together stays above 5 %, say so plainly. Name
    the next step (e.g. a `SurfaceView`/GL backend) and write an ADR with status `proposed`.

- **M6R-07 Smoothness round 2 fixes** (`perf/M6R-07a-…`, `b`, `c`, in M6R-06's order).
  - One cause per PR. Each PR has a before/after table under R1 (≥ 3 runs each, same session):
    competitor scroll late %, per-iteration late %, frames per iteration, and the §7 doc 1
    "% ≤ 8.33 ms".
  - A fix that doesn't measurably help gets reverted, not merged.
  - Feel changes (R4) wait for the owner.
  - Target: competitor scroll ≤ 5 % late (median of 3 runs).

- **M6R-08 Pinch round 2** (`docs/M6R-08-pinch-investigation`, then `perf/M6R-08a-…`).
  - Same method as M6R-06/07, on `CompetitorBenchmarks#pinchZoom`. Start after M6R-02 is merged.
  - Keep the frozen level while pinching (owner decision).
  - Target: ≤ 10 % late.

- **M6R-09 Rework PR #82, the open path** (rework on the `feat/M6-05-open-path` branch, or close
  #82 and open a new one). **Risky (AGENTS §3): open the PR and don't merge.** Problems to fix:
  1. `PerfTrace.isOpenPending` is only set while system tracing is on (`beginOpen` returns early
     when `tracer.isEnabled` is false). So "first page before prefetch" and the thumbnail delay
     only happen during benchmarks, and real users get nothing.
     - Use an app-side signal that doesn't depend on tracing: e.g. a `StateFlow<Boolean>`
       "firstPageSharp" set by the surface when the first page's visible detail is opaque.
     - Wait on it with `first { it }` plus a timeout (e.g. 1 s), not a polling `while` loop over a
       non-`@Volatile` field.
     - Then `PerfTrace` only reads that signal.
  2. `MainActivity.onTrimMemory` now touches `thumbnailPipeline`. That forces the lazy pipeline,
     which calls `getOrCreateRenderer()` and **binds the `:render` process when the system asks
     to save memory**. Only forward `onTrimMemory` if the pipeline already exists.
  3. On recreation (rotation) with `isFinishing == false`, the open document's handle is never
     closed in `:render`. The new activity opens the document again, so every rotation leaks a
     document session.
     - Either keep the opened document across recreation (retained holder/ViewModel) and reuse
       its handle, or close the old handle.
     - Instrumented test: open a doc, recreate the activity 3× with `ActivityScenario.recreate()`,
       and assert that the `:render` service's open-session count is unchanged. Expose a test-only
       count over the existing AIDL if needed: that is an IPC change, so call it out in the PR.
       Negative control (R3).
  4. Unbinding only on `TRIM_MEMORY_COMPLETE` almost never happens on API 34+. Unbind when the app
     is in the background: `TRIM_MEMORY_BACKGROUND` / `UI_HIDDEN`, or after N seconds in the
     background. Choose one and give the reason in the PR.
     - Holders of the old renderer (`DocumentSurface`, `ThumbnailPipeline`) must not keep a closed
       instance; get the renderer through `getOrCreateRenderer()`.
     - Test: close the renderer, then open a doc; it must work.
  5. Measure (R1): §7 "open 30-page paper → first page sharp", ×3 runs before and ×3 after, and
     a real user open with tracing **off**, timed from a `screenrecord` with
     `tools/perf/competitor/cold_open.sh`.

- **M6R-10 M6-09 evidence** (`fix/M6R-10-m6-09-evidence`).
  - Add screenshots to `docs/m6-09-verification/`: TalkBack focus on the library, reader toolbar,
    page pill and slide controls; portrait in vertical mode, the sidebar overlay, and slides.
  - Pill shift: screenshots of a clickable pill (back chip) and a non-clickable pill (page
    indicator) in the same place, before (#68 build) and after (#88). Give the pill's top edge in
    pixels in each. If the visual top still differs, the shift isn't fixed. Note that the 48 dp
    Box now centres a 36 dp pill, so the visual top moves down by 6 dp; check this.
  - Minified smoke test: run the documented procedure on the Pad 7 and record it. A `file://` VIEW
    intent may not open on Android 16; use a `content://` MediaStore URI as in
    `tools/perf/competitor/common.sh`.

- **M6R-11 Final re-measure and release prep** (`release/M6R-11-final`).
  - All of §7, under R1 and R2: frames ×3, latency ×2, startup ×3, PSS (`tools/perf/measure_pss.sh`),
    APK size, the fuzz test, and the full `tools/perf/competitor/` run.
  - Update `perf-report.md`, the README table and the release notes from the raw files only.
  - Bump to `versionCode 3` / `0.3.0`, unless the owner hasn't released 0.2.0. Ask which.
  - **Stop before releasing** (owner signs).

**M6R exit criteria (all of them):**
- Competitor scroll ≤ 5 % late, and pinch ≤ 10 % late (median of 3 runs each, R1). Otherwise a
  PRD PR brings the gap and the ADR to the owner.
- Every §7 row is Met, Missed or Not measured (R2), each backed by a raw file.
- Every test added in M6/M6R has a linked negative control.
- The owner has checked the fade (M6R-03), the pinch-out (M6R-02), and any feel change from
  M6R-07/08 on the Pad 7.
- #82's rework has been reviewed by Claude.

---

### M7 — Fast sharpening after zoom

Added 2026-10-12. **Owner priority: speed over memory.** The owner's main complaint is that after
a zoom the page takes too long to turn sharp. Memory may grow, up to the §7 PSS budget, if that
buys speed.

**Owner decisions recorded 2026-10-12** (M7-00 writes them into `docs/decisions.md`):
- M6R-02 pinch-out check: the owner saw no blank pages ("gak verasa"). #92 may merge once CI is
  green.
- M6R-03 fade: the owner can't feel 100 vs 150 ms and asked for the industry standard. Keep
  `TILE_CROSSFADE_DURATION_MS = 100`. This matches Coil's default crossfade (100 ms) and sits in
  Material's "short" motion range (50–200 ms). F-REN-3 already allows 100 ms. Update §7's "Sharp"
  definition from "150 ms" to "100 ms".

**What Claude measured** (one exploratory run, not R1 evidence: `SharpenLatencyBenchmark`
`paper_two_column.pdf_fit_pinch`, Pad 7, 2026-10-12, iteration 4 trace). Each pinch that
re-renders took about 190–310 ms from gesture end to sharp:
- The render service was busy for only about 60–80 ms (about 30 tiles at about 2.5 ms each, inside
  the `AIDL::java::IPdfiumRenderService` binder slices).
- **After the last tile was rendered, it took another 137–156 ms before "sharp".** That time is
  ours, not PDFium's:
  1. `MAX_TILES_SWAPPED_PER_FRAME = 2` (`DocumentSurface.kt`): 30 tiles need 15 frames
     (about 125 ms at 120 Hz) just to enter the cache.
  2. The batched reveal (`revealTiles`, `TILE_REVEAL_TIMEOUT_OVER_BASE_MS`) waits for all
     visible tiles before any fade starts.
  3. Then the 100 ms crossfade.
- Rendering may still dominate at 800 % and on `heavy_plot.pdf`. Not measured yet; M7-01 does.
- The render service draws on **one thread** (`PdfiumRenderService.renderExecutor`,
  `newSingleThreadExecutor`) at **`THREAD_PRIORITY_BACKGROUND`**, which the scheduler tends to put
  on little cores. The app-side `RENDER_SCHEDULER_CONCURRENCY = 2` can't help while the service is
  single-threaded.

All M6R rules (R1–R6) apply to M7. Budget: §7 "tile sharpening latency": p95 ≤ 150 ms at fit
width, ≤ 300 ms at 800 %.

#### M7 tasks, in order

- **M7-00 Record decisions and merge #92** (`docs/M7-00-decisions`).
  - Add both owner decisions above to `docs/decisions.md`, with the date 2026-10-12.
  - PRD §7 "Sharp" definition: change "(F-REN-3, 150 ms)" to "(F-REN-3, 100 ms)".
  - Merge #92 (M6R-02) when its CI is green. Its R1 pinch numbers (×3 before, ×3 after) must be in
    the PR first; if missing, run them and add them.

- **M7-01 Where does the sharpen time go** (`docs/M7-01-sharpen-breakdown`). Report only, no
  behaviour change.
  - Add `Trace` sections. Each must cost nothing when tracing is off (check `Trace.isEnabled()`
    or use the existing `PerfTrace` tracer):
    - `TerbacaTileRequested` when the scheduler enqueues a visible detail tile;
    - `TerbacaTileArrived` when the bitmap reaches `pendingTileArrivals`;
    - `TerbacaTileSwapped` in `swapPendingTiles`, with the number swapped this frame
      (`TerbacaTileSwapped:3`);
    - `TerbacaRevealStart` when `revealTiles` starts the batch fade.
  - Run `SharpenLatencyBenchmark`, all 5 cases, ×3 runs each.
  - Commit `docs/perf-raw/m7-01/`: the JSONs, `device-state.txt`, and `breakdown.py` (Perfetto
    `trace_processor`). For each `TerbacaSharpen` section, the script prints:
    total; gesture end → first request; render-service busy time (sum of binder renderTile
    slices); last render → last swap; last swap → reveal start; reveal start → sharp; tile count.
  - Commit the traces needed to rerun the script, or say in the PR why not (size).
  - Table per case: median and p95 of each phase. Name the biggest phase per case.

- **M7-02 Swap tiles faster when not gesturing** (`perf/M7-02-swap-cap`).
  - `MAX_TILES_SWAPPED_PER_FRAME = 2` protects scroll smoothness during a fling. After a pinch or
    pan has ended, and no fling is running, swap all arrived tiles in one frame (or a higher cap,
    e.g. 16). Keep the cap of 2 while the user is touching or flinging.
  - Unit test: a pure function `swapBudget(isGesturing: Boolean, isFlinging: Boolean): Int`.
    Negative control (R3): make it always return 2; the test fails.
  - Measure (R1), ×3 before and ×3 after, same session:
    - `SharpenLatencyBenchmark` fit_pinch and max_pinch: p50 and p95.
    - `CompetitorBenchmarks#scrollFlings`: late %. It must not get worse by more than its min–max
      range.
  - Revert if sharpen p95 doesn't drop.

- **M7-03 Render thread priority** (`perf/M7-03-render-priority`).
  - In `PdfiumRenderService.renderExecutor`, try `THREAD_PRIORITY_DEFAULT` and
    `THREAD_PRIORITY_DISPLAY` instead of `THREAD_PRIORITY_BACKGROUND`.
  - Keep prefetch from hurting scroll: if scroll late % gets worse, use the higher priority only
    while visible-detail work is queued, and background for prefetch. The scheduler already knows
    the priority class of each tile.
  - Measure (R1) ×3 each: all `SharpenLatencyBenchmark` cases, `scrollFlings` and `pinchZoom`
    late %, and the per-core CPU time of the render thread (Perfetto `sched` table, thread name
    starts with `PdfiumRender`). Report big vs little core share.
  - Merge only if sharpen p95 improves and scroll/pinch late % don't get worse beyond the
    min–max range.

- **M7-04 Reveal policy** (`perf/M7-04-reveal`). **Feel change (R4).** Only if M7-01 shows the
  wait-for-all reveal is a big phase after M7-02.
  - Option: reveal each page (not the whole screen) as soon as that page's visible tiles are in.
  - Owner check (R4): build both, and ask the owner to zoom on a two-column paper and say which
    looks better and which looks faster. The fade stays 100 ms.

- **M7-05 Competitor zoom-to-sharp** (`docs/M7-05-competitor-sharpen`). Answers the owner's
  question "why are other apps faster".
  - New `tools/perf/competitor/zoom_sharp.sh` + `video.py` mode: for each app in
    `tools/perf/competitor/common.sh`, open `paper_two_column.pdf`, run the same pinch (adb
    `input` can't pinch; use the benchmark APK's UiAutomator `pinchOpen` against each package, as
    `CompetitorBenchmarks` does) while `adb shell screenrecord` runs. Measure finger-up → last
    pixel change in the page area, ×5 per app.
  - Table: app, median and p95 zoom-to-sharp, and how each app looks while sharpening (blurry
    then sharp, tiles appearing, or instant). Add screenshots of the mid-sharpen frame.
  - Commit raw videos' frame-diff CSVs (not the videos if > 20 MB) and the script.

- **M7-06 Parallel rendering, ADR only** (`docs/M7-06-parallel-render-adr`). **Risky: ADR, don't
  merge; Claude reviews.**
  - Only if M7-01/03 show render-service busy time is still the biggest phase at 800 % or on
    `heavy_plot.pdf`.
  - PDFium is not thread-safe, even across documents in one process. Options to compare: N render
    processes (each its own PDFium and document open), split tiles across them; or a larger tile
    size with fewer calls. For each, give the expected gain (from M7-01 numbers), memory cost
    (memory is allowed to grow up to the §7 PSS budget), and crash-isolation impact.
  - Write `docs/adr/NNN-parallel-render.md`, status `proposed`. No code.

- **M7-07 Re-measure** (`docs/M7-07-measure`).
  - All 5 `SharpenLatencyBenchmark` cases ×3, plus M7-05 for Terbaca only. Update the §7 sharpen
    rows in `perf-report.md` under R2.

**M7 exit criteria:** fit-width pinch sharpen p95 ≤ 150 ms and 800 % ≤ 300 ms (median of 3 runs,
R1), no worse scroll/pinch late %, and the M7-05 comparison table committed. If a budget is still
missed, M7-06's ADR goes to the owner.

---

## 9. Key Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Single-threaded PDFium vs. heavy pages | Slow sharpening, queue stalls | Strict prioritization, progressive render cancellation, page handle cache; second render process if still insufficient |
| Malformed PDFs crash native code | App crash | PDFium in separate process with automatic recovery (§4.2) |
| GPU upload on UI thread | Jank at 120 Hz | HardwareBuffer transport / background hardware-bitmap copy; per-frame swap cap |
| HyperOS caps refresh rate | Confirmed: apps get 120 Hz even with the 144 Hz setting on (2026-10-02) | Budgets use 120 Hz. Keep the explicit frame-rate request so the app benefits if 144 Hz is granted |
| Compose Canvas throughput ceiling | Missed frame budget | Backend-agnostic tile manager; planned SurfaceView/GL fallback |
| HardwareBuffer CPU-write path unreliable on driver | Corrupt tiles or crashes | SharedMemory fallback path (§4.3) |
| Column detection fails on unusual layouts | Bad double-tap zoom | Region segmentation, fit-width fallback, fixtures for edge cases |
| Licensing of dependencies or corpus | Legal exposure | License check in every dependency PR; generated corpus |

---

## 10. Open Questions for the Owner

Agy should ask these at the start of M0 and record answers in `docs/decisions.md`:

1. GitHub account or organization for the repo.
2. Application ID / package name (proposal: `app.terbaca`).
3. Test device: Pad 7, Pad 7 Pro, or both.
4. Distribution: personal sideload only, or Google Play later (affects signing and target SDK policy).
5. App icon and brand color, or should agy propose options in a PR.
