#!/usr/bin/env python3
"""Summarize M6-10 Macrobenchmark raw JSON files."""

import json
from pathlib import Path

DIR = Path(__file__).resolve().parent

def summarize_benchmark(json_path: Path):
    with open(json_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    b = data["benchmarks"][0]
    name = b["name"]
    frame_counts = b["metrics"]["frameCount"]["runs"]
    overruns = b["sampledMetrics"]["frameOverrunMs"]["runs"]
    durations = b["sampledMetrics"]["frameDurationCpuMs"]["runs"]

    all_overruns = [x for r in overruns for x in r]
    all_durations = [x for r in durations for x in r]

    total_frames = len(all_overruns)
    total_late = sum(1 for x in all_overruns if x > 0)
    late_pct = (total_late / total_frames * 100) if total_frames else 0.0

    per_iter_late = []
    for fc, r in zip(frame_counts, overruns):
        iter_late = sum(1 for x in r if x > 0)
        iter_pct = (iter_late / len(r) * 100) if r else 0.0
        per_iter_late.append((len(r), iter_late, iter_pct))

    sorted_overruns = sorted(all_overruns)
    sorted_durations = sorted(all_durations)

    def p(arr, pct):
        if not arr:
            return 0.0
        idx = int(len(arr) * (pct / 100.0))
        return arr[min(idx, len(arr) - 1)]

    p50_overrun = p(sorted_overruns, 50)
    p90_overrun = p(sorted_overruns, 90)
    p50_cpu = p(sorted_durations, 50)
    p90_cpu = p(sorted_durations, 90)

    under_8_33 = sum(1 for x in all_durations if x <= 8.33)
    under_8_33_pct = (under_8_33 / total_frames * 100) if total_frames else 0.0
    over_16_6 = sum(1 for x in all_durations if x > 16.6)

    print(f"=== Benchmark: {name} ({json_path.name}) ===")
    print(f"Total frames: {total_frames}")
    print(f"Frames per iteration: {frame_counts}")
    print(f"Overall late frames: {total_late} / {total_frames} ({late_pct:.2f}%)")
    print("Per-iteration breakdown:")
    for i, (n_frames, n_late, pct_late) in enumerate(per_iter_late, 1):
        print(f"  Iteration {i}: {n_frames} frames, {n_late} late ({pct_late:.2f}%)")
    iter_pcts = [p[2] for p in per_iter_late]
    print(f"Per-iteration late %: min={min(iter_pcts):.2f}%, median={sorted(iter_pcts)[len(iter_pcts)//2]:.2f}%, max={max(iter_pcts):.2f}%")
    print(f"Overrun: P50={p50_overrun:.2f} ms, P90={p90_overrun:.2f} ms")
    print(f"CPU Duration: P50={p50_cpu:.2f} ms, P90={p90_cpu:.2f} ms")
    print(f"Frames <= 8.33 ms (CPU): {under_8_33} ({under_8_33_pct:.2f}%)")
    print(f"Frames > 16.6 ms (CPU): {over_16_6}")
    print()

def main():
    for f in ["app.terbaca__scrollFlings.json", "app.terbaca__pinchZoom.json"]:
        path = DIR / f
        if path.exists():
            summarize_benchmark(path)

if __name__ == "__main__":
    main()
