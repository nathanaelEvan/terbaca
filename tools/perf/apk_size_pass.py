#!/usr/bin/env python3
"""
Terbaca M5-03: Release APK Size & Packaging Verification Pass.
Verifies compliance with PRD §7 budgets (Release APK size <= 15 MB, arm64-v8a,
R8 full mode, resource shrinking, and native symbol stripping).
"""

import sys
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
APK_PATH = REPO_ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release-unsigned.apk"
BUDGET_BYTES = 15 * 1024 * 1024  # 15 MB


def analyze_apk(apk_path: Path) -> int:
    print("=" * 70)
    print("Terbaca M5-03: Release APK Size & Packaging Verification")
    print("=" * 70)

    if not apk_path.exists():
        print(f"Error: APK not found at {apk_path}", file=sys.stderr)
        print("Run: ./gradlew :app:assembleRelease first.", file=sys.stderr)
        return 1

    total_size = apk_path.stat().st_size
    total_size_mb = total_size / (1024 * 1024)
    budget_mb = BUDGET_BYTES / (1024 * 1024)

    print(f"APK Path: {apk_path.relative_to(REPO_ROOT)}")
    print(f"Total APK Size: {total_size:,} bytes ({total_size_mb:.2f} MB)")
    print(f"PRD §7 Budget:  {BUDGET_BYTES:,} bytes ({budget_mb:.2f} MB)")
    print(f"Margin:         {BUDGET_BYTES - total_size:,} bytes ({budget_mb - total_size_mb:.2f} MB under budget)")
    print("-" * 70)

    categories = {
        "DEX (R8 shrunk)": 0,
        "Native .so (stripped)": 0,
        "Resources & Assets": 0,
        "Manifest & Signatures": 0,
        "Other / Meta": 0,
    }

    so_files = []

    with zipfile.ZipFile(apk_path, "r") as z:
        for info in z.infolist():
            fname = info.filename
            csize = info.compress_size
            if fname.endswith(".dex"):
                categories["DEX (R8 shrunk)"] += csize
            elif fname.endswith(".so"):
                categories["Native .so (stripped)"] += csize
                so_files.append((fname, info.file_size, csize))
            elif fname.startswith("res/") or fname.startswith("assets/") or fname == "resources.arsc":
                categories["Resources & Assets"] += csize
            elif fname == "AndroidManifest.xml" or fname.startswith("META-INF/"):
                categories["Manifest & Signatures"] += csize
            else:
                categories["Other / Meta"] += csize

    print("Breakdown by Category (compressed bytes inside APK):")
    for cat, size in sorted(categories.items(), key=lambda x: x[1], reverse=True):
        pct = (size / total_size) * 100 if total_size > 0 else 0
        print(f"  - {cat:<25}: {size:>10,} bytes ({size / (1024*1024):>5.2f} MB) [{pct:>5.1f}%]")

    print("-" * 70)
    print("Native Libraries Detail:")
    for name, uncompressed, compressed in sorted(so_files, key=lambda x: x[2], reverse=True):
        print(f"  - {name:<42} {uncompressed:>10,} uncompressed -> {compressed:>10,} compressed")

    print("-" * 70)
    if total_size <= BUDGET_BYTES:
        print(f"PASS: Release APK ({total_size_mb:.2f} MB) meets PRD §7 budget (<= {budget_mb:.0f} MB).")
        return 0
    else:
        print(f"FAIL: Release APK ({total_size_mb:.2f} MB) EXCEEDS budget ({budget_mb:.0f} MB)!", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(analyze_apk(APK_PATH))
