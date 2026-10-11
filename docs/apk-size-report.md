# Release APK Size & Packaging Report (M5-03)

This report details the APK size measurements, toolchain configurations, and packaging budgets per **PRD §7** and **PRD §8 (M5-03)**.

---

## 1. Requirement & Budget Summary (PRD §7)

| Metric | PRD §7 Budget | Measured Value | Margin | Status |
|---|---|---|---|---|
| **Release APK Size** | $\le 15$ MB (15,728,640 bytes) | **9,585,169 bytes (9.14 MB)** | **+5.86 MB (39% headroom)** | **PASS** |
| **Architecture** | `arm64-v8a` only (ADR-0001) | `arm64-v8a` | Single ABI | **PASS** |
| **R8 Mode** | Full mode (`android.enableR8.fullMode=true`) | Enabled | Shrunk to single DEX | **PASS** |
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
   - Explicitly configured `android.enableR8.fullMode=true` in `gradle.properties`.
   - Resulting code shrunk into a single monolithic `classes.dex` of 2.63 MB.
2. **Resource Shrinking**:
   - `isShrinkResources = true` in `:app` release build type.
   - Resource tables and non-referenced layouts pruned to 253 KB.
3. **Native Symbol Stripping (`llvm-strip`)**:
   - Configured `ndkVersion = "27.2.12479018"` in `:app/build.gradle.kts` matching `:core:pdfium` to ensure AGP binds to the installed LLVM toolchain.
   - Verified execution of `llvm-strip --strip-unneeded` across all JNI libraries:
     - `libterbaca_pdfium.so` dropped from 155,792 bytes to 25,560 bytes (84% reduction in JNI bridge size).
     - Prebuilt `libpdfium.so` packaged cleanly at 6.17 MB.
4. **Single-ABI Enforcement**:
   - Default ABI set to `arm64-v8a` per ADR-0001, eliminating multi-architecture bloating.

---

## 4. Verification

- Local test pass: `./gradlew :app:testDebugUnitTest` runs `ApkPackagingBudgetUnitTest` asserting configuration and size bounds $\le 15$ MB.
- Harness script: `python tools/perf/apk_size_pass.py` produces the categorized breakdown.
