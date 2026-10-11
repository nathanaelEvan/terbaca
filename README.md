# Terbaca

Terbaca is a fast, read-only PDF reader for Android tablets, tuned for high-density, high-refresh displays like the Xiaomi Pad 7. It aims to feel like macOS Preview and Apple Books: documents open instantly, text stays sharp up to 800% zoom, and pan and pinch keep up with the display (120 Hz, the highest rate HyperOS grants apps). It has two first-class use cases: **academic papers** and **pitch decks**.

It works fully offline: no `INTERNET` permission, no ads, no accounts, no analytics.

## Status

Under active development. Progress follows the milestones in [`docs/PRD.md`](docs/PRD.md) §8.

| Milestone | Scope | State |
|---|---|---|
| M1 — Engine core | PDFium JNI, `:render` process, HardwareBuffer transport, tile pyramid, caches, crash recovery, benchmarks | Done |
| M2 — Navigation core | Library and recents, intent handling, continuous vertical mode, slide mode, thumbnails, sidebar, state restore, keyboard navigation | Done |
| M3 — Paper features | Outline and links, column-aware zoom, citation peek, search, text selection | Done |
| M4 — Deck features and themes | Grid overview, presentation mode, 2-up/spread, Dark and Sepia themes | Done |
| M5 — Hardening | Fuzzing, memory and APK size passes, accessibility, final perf report | Next |

M2–M4 were checked on a Xiaomi Pad 7 (Android 16) on 2026-10-11: all instrumented tests pass on the device, and the reader, paper and deck features were walked through by hand in landscape.

## How Terbaca compares

Measured on a Xiaomi Pad 7 (Android 16, 120 Hz) on 2026-10-11, using the same PDFs and the same
gestures in every app. Opening was timed from screen recordings, smoothness and memory with Android's
frame timeline and `dumpsys`. Lower is better, and the best value in each row is in **bold**.

| | Terbaca | Acrobat | Google Drive | WPS Office | Xiaomi viewer | Moon+ Reader |
|---|---|---|---|---|---|---|
| Open a paper → first page visible (ms) | 658 | 1,815 | 870 | 1,991 | **306** | 883 |
| Scrolling: late frames (%) | 34.6 | 4.8 | 3.6 | **1.4** | 3.8 | n/c |
| Pinch zoom: late frames (%) | 29.4 | 15.3 | **5.2** | 9.6 | 27.1 | 2.5* |
| Memory, 105-page doc (MB) | **324** | 998 | 725 | 967 | 416 | 491* |
| App size (MB) | 9.6 | 301.1 | 59.0 | 337.6 | **9.4** | 54.5 |

Terbaca uses the least memory, is about as small as Xiaomi's built-in viewer, and opens files faster
than everything except that built-in viewer. Its weak spot is smoothness: about a third of its frames
miss the display deadline, against 1–5 % for the others when scrolling. That's the next thing to fix.

n/c: not comparable. \*Moon+ pages sideways, so vertical swipes didn't scroll it, and its zoom wasn't
verified. The other apps also edit, sync and run AI features, which accounts for much of their size
and memory. Method, spread and caveats are in [`docs/competitor-benchmark.md`](docs/competitor-benchmark.md).

## Architecture

- **Engine:** [PDFium](https://pdfium.googlesource.com/pdfium/) (BSD) through a thin custom JNI layer. No MuPDF or other GPL/AGPL renderers.
- **Process model:** PDFium isn't thread-safe, so every PDFium call runs on one render thread inside a separate `:render` service process. A malformed PDF that crashes PDFium can't take down the UI; the app restarts the service and resumes.
- **Rendering:** a base layer plus a pyramid of detail tiles, scheduled and coalesced off the UI thread, with pixels delivered over `HardwareBuffer`.
- **UI:** Jetpack Compose with Material 3, MVI-style state per screen, Room and DataStore for persistence.

### Modules

```
:app                 – entry point, navigation, intent handling
:core:model          – pure Kotlin models (no Android dependencies)
:core:pdfium         – JNI bindings, native code, render service (":render" process)
:core:render         – PageRenderer interface, tile pyramid, scheduler, caches, thumbnails
:core:ui             – theme, design tokens, shared components, AGSL shaders
:core:data           – Room, DataStore, recents, per-document state
:feature:library     – recents/library screen
:feature:reader      – document surface, gestures, continuous mode, sidebar
:feature:slides      – slide pager and deck modes
:benchmark           – Macrobenchmark + baseline profile generation
```

Feature modules depend on core modules, never on each other.

## Requirements

| | |
|---|---|
| Target device | Xiaomi Pad 7 / Pad 7 Pro (HyperOS, Android 15 base); works on any Android 13+ tablet |
| minSdk / targetSdk | 33 / 36 |
| ABI | `arm64-v8a` (CI also builds `x86_64` for the emulator) |

## Building from source

### Prerequisites

- JDK 21 (e.g. Eclipse Temurin 21)
- Android SDK with platform `android-36` and Build-Tools `36.0.0`
- NDK `27.2.12479018` and CMake `3.22.1` (install both from the SDK Manager)

### Commands

```bash
# Build the debug APK
./gradlew assembleDebug

# Unit tests across all modules
./gradlew test

# Android Lint and detekt
./gradlew lintDebug
./gradlew detekt

# All local gates before opening a PR
./gradlew test detekt lintDebug assembleDebug

# Native instrumented tests (needs a device or emulator)
./gradlew :core:pdfium:connectedDebugAndroidTest

# Same, on an x86_64 emulator as CI does
./gradlew :core:pdfium:connectedDebugAndroidTest -Pterbaca.abis=x86_64

# Install the debug build on a connected tablet (USB debugging on)
./gradlew :app:installDebug
```

`adb` lives in the SDK's `platform-tools` folder (for example `%LOCALAPPDATA%\Android\Sdk\platform-tools` on Windows); add it to `PATH`, or call it by full path, to check that the tablet is visible with `adb devices`. On HyperOS, each install from USB shows a prompt on the tablet that has to be accepted by hand.

The Macrobenchmark and baseline profile generator live in `:benchmark` and need a physical device for meaningful numbers.

## Test corpus

[`testdata/corpus`](testdata/corpus) holds the PDFs used by tests and benchmarks (decks, heavy plots, encrypted and malformed files). They are all generated by `generate_corpus.py`, so they carry no copyright baggage; provenance is in [`SOURCES.md`](testdata/corpus/SOURCES.md).

## Documentation

- [`docs/PRD.md`](docs/PRD.md) — product requirements and engineering spec (the source of truth)
- [`docs/adr/`](docs/adr) — architecture decisions: PDFium source and flags, IPC, pixel transport, tile size and rendering backend
- [`docs/competitor-benchmark.md`](docs/competitor-benchmark.md) — Terbaca vs Acrobat, Drive, WPS, Xiaomi's viewer and Moon+ on the Pad 7
- [`docs/release-signing.md`](docs/release-signing.md) — building a signed release (keys stay off the repo)
- [`AGENTS.md`](AGENTS.md) — workflow rules for agents working on this repo

## Contributing

Every change goes through a PR from a `<type>/<task-id>-<slug>` branch using the [PR template](.github/pull_request_template.md). CI runs detekt, unit tests, lint, a debug build and the native instrumented tests on an emulator; `main` is protected, and PRs are squash-merged once checks are green.

## License

Copyright 2026 Nathanael Evan. Apache License 2.0, see [`LICENSE`](LICENSE). Bundled PDFium binaries keep their own licenses, see [`THIRD_PARTY_NOTICES`](core/pdfium/src/main/cpp/THIRD_PARTY_NOTICES).
