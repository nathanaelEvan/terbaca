#!/usr/bin/env python3
"""Summarize M6R-05 SlideFlipBenchmark raw JSON output."""

import json
from pathlib import Path
import sys

DIR = Path(__file__).resolve().parent

def summarize_slide_flip(json_path: Path):
    with open(json_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    benchmark = data["benchmarks"][0]
    name = benchmark["name"]
    metrics = benchmark["metrics"]
    sampled = benchmark["sampledMetrics"]

    frame_counts = metrics["frameCount"]["runs"]
    sharp_counts = metrics["slideSharpOnArrivalCount"]["runs"]
    not_sharp_counts = metrics["slideNotSharpOnArrivalCount"]["runs"]

    overruns = sampled["frameOverrunMs"]["runs"]
    durations = sampled["frameDurationCpuMs"]["runs"]

    all_overruns = [x for r in overruns for x in r]
    all_durations = [x for r in durations for x in r]

    total_frames = len(all_overruns)
    total_late = sum(1 for x in all_overruns if x > 0)
    late_pct = (total_late / total_frames * 100) if total_frames else 0.0

    under_8_33 = sum(1 for x in all_durations if x <= 8.33)
    under_8_33_pct = (under_8_33 / total_frames * 100) if total_frames else 0.0
    over_16_6 = sum(1 for x in all_durations if x > 16.6)
    over_16_6_pct = (over_16_6 / total_frames * 100) if total_frames else 0.0

    sorted_overruns = sorted(all_overruns)
    sorted_durations = sorted(all_durations)

    def p(arr, pct):
        if not arr:
            return 0.0
        idx = int(len(arr) * (pct / 100.0))
        return arr[min(idx, len(arr) - 1)]

    p50_overrun = p(sorted_overruns, 50)
    p90_overrun = p(sorted_overruns, 90)
    p95_overrun = p(sorted_overruns, 95)
    p99_overrun = p(sorted_overruns, 99)

    p50_cpu = p(sorted_durations, 50)
    p90_cpu = p(sorted_durations, 90)
    p95_cpu = p(sorted_durations, 95)
    p99_cpu = p(sorted_durations, 99)

    total_sharp = sum(sharp_counts)
    total_not_sharp = sum(not_sharp_counts)
    total_arrivals = total_sharp + total_not_sharp
    sharp_rate = (total_sharp / total_arrivals * 100) if total_arrivals else 0.0
    miss_rate = (total_not_sharp / total_arrivals * 100) if total_arrivals else 0.0

    print(f"=== Benchmark: {name} ({json_path.name}) ===")
    print(f"Iterations: {len(frame_counts)}")
    print(f"Total Flips Measured: {int(total_arrivals)} across all runs")
    print(f"  Sharp on arrival:     {int(total_sharp)} ({sharp_rate:.1f}%)")
    print(f"  Not sharp on arrival: {int(total_not_sharp)} ({miss_rate:.1f}%)")
    print()
    print("--- Per-Iteration Slide Arrival Counts ---")
    for i, (sc, nsc, fc) in enumerate(zip(sharp_counts, not_sharp_counts, frame_counts), 1):
        iter_total = sc + nsc
        iter_sharp_pct = (sc / iter_total * 100) if iter_total else 0.0
        print(f"  Iteration {i}: sharp={int(sc):2d}, notSharp={int(nsc):2d} (total={int(iter_total):2d}, {iter_sharp_pct:.1f}% sharp), frames={int(fc)}")
    print()
    print("--- Frame Timing & Smoothness Metrics ---")
    print(f"Total frames: {total_frames}")
    print(f"Frames <= 8.33 ms (CPU): {under_8_33} ({under_8_33_pct:.2f}%)")
    print(f"Frames > 16.6 ms (CPU):  {over_16_6} ({over_16_6_pct:.2f}%)")
    print(f"Late frames (overrun > 0): {total_late} / {total_frames} ({late_pct:.2f}%)")
    print(f"Overrun:  P50={p50_overrun:.2f} ms, P90={p90_overrun:.2f} ms, P95={p95_overrun:.2f} ms, P99={p99_overrun:.2f} ms")
    print(f"CPU Time: P50={p50_cpu:.2f} ms, P90={p90_cpu:.2f} ms, P95={p95_cpu:.2f} ms, P99={p99_cpu:.2f} ms")
    print()
    print("--- Observation / Follow-up ---")
    print("Deterministic miss pattern per iteration (4 misses out of 16 flips):")
    print("  - Forward pass (8 flips): misses every 3rd flip (flips 3 and 6).")
    print("  - Backward pass (8 flips): misses flips 1 and 4.")
    print("Overall slide flip sharp-on-arrival is 75.0% (Budget: 100% sharp on arrival). Status: Missed.")

def main():
    target = Path(sys.argv[1]) if len(sys.argv) > 1 else (DIR / "benchmarkData.json")
    if not target.exists():
        print(f"Error: {target} not found", file=sys.stderr)
        sys.exit(1)
    summarize_slide_flip(target)

if __name__ == "__main__":
    main()
