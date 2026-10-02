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
   - No license file (private, all rights reserved) unless the owner says otherwise.
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
| F-THEME-2 | **Page themes:** Original, Dark, Sepia. Selectable from the bottom bar; remembered globally with per-document override. | P0 |
| F-THEME-3 | **Dark page theme** must keep diagrams readable: perceptual lightness inversion (e.g. in OKLab) that preserves hue, so black text becomes off-white and colored plot lines keep their color identity. Raster images (photos, embedded bitmaps) are excluded from the transform using image bounding boxes from the PDF object model. Target page background ~`#121214`, text ~`#E4E4E7`. | P0 |
| F-THEME-4 | Sepia uses the same pipeline with a warm tone curve. | P0 |
| F-THEME-5 | Optional per-document "invert images too" toggle for scanned documents. | P1 |

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
  2. **Base level:** whole page at fit-width scale, for visible and adjacent pages.
  3. **Detail tiles:** above base zoom only.
- Keep the previous level's tiles visible until the new level fully covers the viewport.
- Crossfade new tiles over 100–150 ms.

### 4.5 Scheduler

- A priority queue in the UI process decides what to request; the render process executes requests in order and supports cancellation.
- Priority: visible tiles at the current target level, ordered by distance from viewport center → base layers of visible pages → prefetch ring in the scroll/fling direction → adjacent pages' base layers → thumbnails.
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
| Total PSS (UI + render process), 100-page paper, active zooming | ≤ 250 MB |
| APK (arm64) size | ≤ 15 MB |
| Crash on malformed corpus PDFs | UI process never crashes |

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
- **M2-06** Adaptive scaffold + sidebar (Thumbnails, Outline).
- **M2-07** Internal link navigation + back chip.
- **M2-08** Per-document state restore.
- **M2-09** Keyboard navigation (F-NAV-7).

**Exit criteria:** all P0 items in §3.1, §3.3 and F-MODE-1/3/5 working; §7 budgets still met.

### M3 — Paper features (target: 3 weeks)

- **M3-01** Text/link/outline/image-bounds extraction APIs in render process.
- **M3-02** Column detection + fixtures + unit tests.
- **M3-03** Column-aware double-tap zoom (F-ACAD-1), then F-ACAD-2.
- **M3-04** Citation/reference peek card (F-ACAD-3).
- **M3-05** Progressive search with sidebar results, scrollbar markers, highlights.
- **M3-06** Text selection and copy.

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
