# Release APK Size & Packaging Report (M5-03)

This report details the APK size measurements, toolchain configurations, and packaging budgets per **PRD §7** and **PRD §8 (M5-03)**.

---

## 1. Requirement & Budget Summary (PRD §7)

| Metric | PRD §7 Budget | Measured Value | Margin | Status |
|---|---|---|---|---|
| **Release APK Size** | $\le 15$ MB (15,728,640 bytes) | **9,585,169 bytes (9.14 MB)** | **+5.86 MB (39% headroom)** | **PASS** |
| **Architecture** | `arm64-v8a` only (ADR-0001) | `arm64-v8a` | Single ABI | **PASS** |
| **R8 Mode** | Full mode (AGP default with minify) | Enabled | Shrunk to single DEX | **PASS** |
| **Resource Shrinking** | `isShrinkResources = true` | Enabled | 253 KB resources | **PASS** |
| **Native Strip** | `llvm-strip --strip-unneeded` | Executed via NDK 27.2.12479018 | `libterbaca_pdfium.so` reduced from 155 KB to 25 KB | **PASS** |

---

## 2. APK Size Breakdown

Measurement taken from `app/build/outputs/apk/release/app-release-unsigned.apk`:

```
======================================================================
Terbaca M5-03: Release APK Size & Packaging Verification
======================================================================
Total APK Size: 9,585,169 bytes (9.14 MB)
PRD §7 Budget:  15,728,640 bytes (15.00 MB)
Margin:         6,143,471 bytes (5.86 MB under budget)
----------------------------------------------------------------------
Breakdown by Category (compressed bytes inside APK):
  - Native .so (stripped)    :  6,503,928 bytes ( 6.20 MB) [ 67.9%]
  - DEX (R8 shrunk)          :  2,758,376 bytes ( 2.63 MB) [ 28.8%]
  - Resources & Assets       :    253,215 bytes ( 0.24 MB) [  2.6%]
  - Manifest & Signatures    :     24,277 bytes ( 0.02 MB) [  0.3%]
  - Other / Meta             :     13,130 bytes ( 0.01 MB) [  0.1%]
----------------------------------------------------------------------
Native Libraries Detail:
  - lib/arm64-v8a/libpdfium.so                  6,468,272 uncompressed ->  6,468,272 compressed
  - lib/arm64-v8a/libterbaca_pdfium.so             25,560 uncompressed ->     25,560 compressed
  - lib/arm64-v8a/libandroidx.graphics.path.so     10,096 uncompressed ->     10,096 compressed
----------------------------------------------------------------------
PASS: Release APK (9.14 MB) meets PRD §7 budget (<= 15 MB).
```

---

## 3. Key Optimizations & Configurations Applied

1. **R8 Full Mode**:
   - Enabled by default in AGP 8.x with `isMinifyEnabled = true`.
   - Resulting code shrunk into a single monolithic `classes.dex` of 2.63 MB.
2. **Resource Shrinking**:
   - `isShrinkResources = true` in `:app` release build type.
   - Resource tables and non-referenced layouts pruned to 253 KB.
3. **Native Symbol Stripping (`llvm-strip`)**:
   - Configured `ndkVersion = "27.2.12479018"` in `:app/build.gradle.kts` matching `:core:pdfium` so AGP's `stripReleaseDebugSymbols` locates the installed LLVM toolchain.
   - Stripping via `llvm-strip --strip-unneeded` reduced `libterbaca_pdfium.so` from 155,792 bytes to 25,560 bytes inside the APK.
4. **Single-ABI Enforcement**:
   - Default ABI set to `arm64-v8a` per ADR-0001, eliminating multi-architecture bloating.

---

## 4. Verification

- **Host Build Verification**: `./gradlew :app:assembleRelease` produced `app-release-unsigned.apk` at 9,585,169 bytes (9.14 MB).
- **Harness Script**: `python tools/perf/apk_size_pass.py` verified the category breakdown.
- **CI**: since the M5 review, every CI run builds `:app:assembleRelease` (R8 + resource shrinking + strip).
- **Not verified** (corrected in the M5 review, 2026-10-11):
  - The unsigned release APK was never installed on a device. The first version of this report listed
    an "on-device size" equal to the host-built file's size, which isn't a separate measurement.
  - The first version also said the benchmark build "opened `memory_100plus.pdf` cold in 440 ms with
    zero JNI/AIDL regression". No trace or script backs that. For measured open latency, see
    [perf-report](perf-report.md).
  - No automated test runs the minified build. The keep rules for JNI-built parcels
    (`app/proguard-rules.pro`) were checked in R8's `seeds.txt` only.
