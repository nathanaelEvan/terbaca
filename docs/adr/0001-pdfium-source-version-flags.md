# ADR 0001: PDFium Binary Source, Version, and Build Flags

- **Status:** accepted (2026-10-02, by the repo owner)
- **Date:** 2026-10-02
- **Deciders:** Antigravity (executing agent), repo owner
- **Consulted:** Claude (PR reviewer)
- **Informed:** Terbaca project contributors

## Context and Problem Statement

As defined in PRD §4.1, Terbaca requires a high-performance, BSD-licensed PDF rendering engine. MuPDF and other copyleft engines (GPL/AGPL/LGPL) are strictly barred. PDFium was selected as the rendering core, integrated via a thin custom JNI layer in `:core:pdfium`.
To ensure reproducibility, optimal binary size, and compliance with the target platform requirements (Android 13+, arm64-v8a only, Snapdragon 7+ Gen 3), we need to formalize the prebuilt binary source, exact version, build configuration flags, and licensing.

## Decision Drivers

- **Performance & Stability:** Stable, production-grade PDFium build targeting 64-bit ARM (`arm64-v8a`).
- **Binary Footprint:** Lean binary under the PRD §7 APK budget (< 15 MB total APK). Disable heavyweight optional subsystems such as JavaScript (V8) and dynamic XML forms (XFA).
- **License Hygiene:** Pure permissive licensing (BSD / Apache / MIT / zlib). Strictly zero copyleft (GPL / AGPL / LGPL).
- **Maintainability:** Traceable releases with automated builds from upstream Chromium branches.

## Considered Options

1. **Prebuilt binaries from `bblanchon/pdfium-binaries` (release `chromium/8076`)**
2. **Build PDFium from source using Chromium depot_tools**
3. **Android's internal `PdfRenderer` framework API**

## Decision Outcome

Chosen option: **Option 1 (`bblanchon/pdfium-binaries` tag `chromium/8076`)**, because it provides verified, reproducible, standalone Android arm64 binaries from modern Chromium releases without requiring the extensive multi-gigabyte Chromium depot_tools build infrastructure during initial milestones.

### Selected Binary Details & Provenance

- **Origin:** `https://github.com/bblanchon/pdfium-binaries`
- **Release Tag:** `chromium/8076`
- **PDFium Version:** `156.0.8076.0` (MAJOR=156, MINOR=0, BUILD=8076, PATCH=0)
- **Target ABI:** `arm64-v8a` (`target_cpu = "arm64"`, `target_os = "android"`)
- **Library File:** `libpdfium.so` (6,468,272 bytes uncompressed)
- **Artifact:** `pdfium-android-arm64.tgz`
- **Artifact SHA-256:** `b7a6919dc130dfdf7e409bd1762ed2a306408c4faee1173668837fc446217bf7`
- **Library (`libpdfium.so`) SHA-256:** `323b0da6b7d5cbc096528d3025c8a649c606a1ca6954e83a7bf1b4ca172c658b`

#### Test-only ABI: `x86_64`

So instrumented tests can run on a CI emulator, the x86_64 build from the same release is also checked in at `core/pdfium/src/main/jniLibs/x86_64/`. It's only built and packaged when Gradle runs with `-Pterbaca.abis=x86_64`. By default `abiFilters` is `arm64-v8a`, so app and release APKs stay arm64-only.

- **Artifact:** `pdfium-android-x64.tgz` (`https://github.com/bblanchon/pdfium-binaries/releases/download/chromium%2F8076/pdfium-android-x64.tgz`)
- **Artifact SHA-256:** `9d043689096c7cdd4ca98fff95599be115b2a38feba0c68d2a79ba5eedbda415`
- **Library (`libpdfium.so`) SHA-256:** `4cd3161eede5ea57375be2687a75bc9c177af8a3936bbcff69ad9adac9e3902c` (6,717,384 bytes)
- **Version / flags:** `156.0.8076.0`, identical `args.gn` except `target_cpu = "x64"`. The public headers are byte-identical to the arm64 package (ignoring line endings), and the third-party license set is the same, so the existing `THIRD_PARTY_NOTICES` covers it.

### GN Build Flags (`args.gn`)

```gn
clang_use_chrome_plugins = false
default_min_sdk_version = 23
is_component_build = false
is_debug = false
pdf_enable_v8 = false
pdf_enable_xfa = false
pdf_is_standalone = true
pdf_use_partition_alloc = false
target_cpu = "arm64"
target_os = "android"
treat_warnings_as_errors = false
use_mold = false
```

Key configuration rationale:
- `pdf_enable_v8 = false`: JavaScript is out of scope for a read-only viewer (PRD §1.4). Omitting V8 significantly reduces binary size and mitigates JS sandbox security risks.
- `pdf_enable_xfa = false`: XFA form processing is not needed in v1, saving memory and attack surface.
- `pdf_is_standalone = true`: Decouples PDFium from Chromium's broader monolithic dependencies.
- `pdf_use_partition_alloc = false`: Uses standard system allocator compatible with Android's Bionic libc.

### Storage & Repository History Trade-Off

In M1-01, `libpdfium.so` (6.4 MB) is checked into `core/pdfium/src/main/jniLibs/arm64-v8a/` directly. This enables zero-configuration offline builds and eliminates network download steps during local development and CI runs.
However, committing binary files directly into Git permanently increases repository history size with each subsequent binary upgrade. As the project progresses:
- If PDFium version bumps are infrequent and limited to single-ABI (`arm64-v8a`), checking the binary in remains acceptable.
- The test-only `x86_64` binary (6.7 MB) doubles the binary footprint in history. One more ABI or a version bump should trigger the move to the approach below.
- If multiple ABIs are added or updates become frequent, we will transition to an automated Gradle task downloading verified artifacts by SHA-256 checksum or configure Git LFS.

### License Audit

PDFium is licensed under the 3-Clause BSD License (Copyright 2014 PDFium Authors). Third-party subcomponents included in this build (Abseil, FreeType, ICU, libjpeg-turbo, libopenjpeg, libpng, zlib, LCMS) are all permissive open-source licenses (BSD, MIT, FTL, IJG, zlib). Full upstream notices are maintained in `core/pdfium/src/main/cpp/LICENSE.pdfium-binaries` and `THIRD_PARTY_NOTICES`.

### Evaluation of Skia Backend

In M1-10, text rendering and anti-aliasing quality will be benchmarked between AGG (PDFium default) and the Skia backend. Should Skia demonstrate noticeable improvements for sub-pixel geometry or rendering throughput on the 3200×2136 144Hz display, a custom Skia-enabled source build will be re-evaluated and recorded in a subsequent ADR.

## Links

- [PRD.md §4.1 Engine](../PRD.md#41-engine)
- [bblanchon/pdfium-binaries](https://github.com/bblanchon/pdfium-binaries)
