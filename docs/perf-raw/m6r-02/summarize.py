#!/usr/bin/env python3
"""Summarize M6R-02 CompetitorBenchmarks#pinchZoom before and after runs."""

import json
from pathlib import Path

DIR = Path(__file__).resolve().parent

def analyze_file(path: Path):
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)

    b = data["benchmarks"][0]
    frame_counts = b["metrics"]["frameCount"]["runs"]
    overruns = b["sampledMetrics"]["frameOverrunMs"]["runs"]
    durations = b["sampledMetrics"]["frameDurationCpuMs"]["runs"]

    all_overruns = [x for r in overruns for x in r]
    all_durations = [x for r in durations for x in r]

    total_frames = len(all_overruns)
    total_late = sum(1 for x in all_overruns if x > 0)
    late_pct = (total_late / total_frames * 100) if total_frames else 0.0

    per_iter = []
    for r in overruns:
        n_late = sum(1 for x in r if x > 0)
        pct = (n_late / len(r) * 100) if r else 0.0
        per_iter.append(pct)

    sorted_overruns = sorted(all_overruns)
    sorted_durations = sorted(all_durations)

    def p(arr, pct):
        if not arr:
            return 0.0
        idx = int(len(arr) * (pct / 100.0))
        return arr[min(idx, len(arr) - 1)]

    return {
        "name": path.stem,
        "total_frames": total_frames,
        "frames_per_iter": frame_counts,
        "frames_per_iter_median": sorted(frame_counts)[len(frame_counts) // 2],
        "total_late": total_late,
        "late_pct": late_pct,
        "per_iter_late": per_iter,
        "per_iter_median": sorted(per_iter)[len(per_iter) // 2],
        "p50_overrun": p(sorted_overruns, 50),
        "p90_overrun": p(sorted_overruns, 90),
        "p50_cpu": p(sorted_durations, 50),
        "p90_cpu": p(sorted_durations, 90)
    }

def print_group(label: str, runs: list):
    print(f"=== {label} ===")
    late_pcts = [r["late_pct"] for r in runs]
    frames_per_iters = [r["frames_per_iter_median"] for r in runs]
    for r in runs:
        print(f"  {r['name']}: {r['late_pct']:.2f}% late ({r['total_late']}/{r['total_frames']} frames), "
              f"median {r['frames_per_iter_median']:.1f} frames/iter, "
              f"per-iter: {min(r['per_iter_late']):.2f}%–{max(r['per_iter_late']):.2f}% (median {r['per_iter_median']:.2f}%), "
              f"overrun P50={r['p50_overrun']:.2f}ms P90={r['p90_overrun']:.2f}ms, CPU P50={r['p50_cpu']:.2f}ms")
    print(f"  --> Median late %: {sorted(late_pcts)[len(late_pcts)//2]:.2f}% (min {min(late_pcts):.2f}%, max {max(late_pcts):.2f}%)")
    print()

def main():
    before_files = sorted(DIR.glob("before_run*.json"))
    after_files = sorted(DIR.glob("after_run*.json"))

    before_runs = [analyze_file(f) for f in before_files]
    after_runs = [analyze_file(f) for f in after_files]

    print_group("BEFORE (main, frozen base layers skipped mid-pinch)", before_runs)
    print_group("AFTER (M6R-02, base layers requested mid-pinch)", after_runs)

if __name__ == "__main__":
    main()
