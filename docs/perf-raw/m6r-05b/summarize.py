#!/usr/bin/env python3
"""Summarize the M6R-05b SlideFlipBenchmark runs (before = main at 4fdba57, after = this fix).

Usage: python summarize.py   (reads before-run*.benchmarkData.json and after-run*.benchmarkData.json)
"""

import json
from pathlib import Path
from statistics import median

DIR = Path(__file__).resolve().parent


def run_stats(path: Path) -> dict:
    bench = json.loads(path.read_text(encoding="utf-8"))["benchmarks"][0]
    metrics, sampled = bench["metrics"], bench["sampledMetrics"]
    sharp = metrics["slideSharpOnArrivalCount"]["runs"]
    not_sharp = metrics["slideNotSharpOnArrivalCount"]["runs"]
    overruns = sampled["frameOverrunMs"]["runs"]
    durations = sampled["frameDurationCpuMs"]["runs"]
    frames = sum(len(r) for r in overruns)
    return {
        "sharp": sum(sharp),
        "flips": sum(sharp) + sum(not_sharp),
        "per_iter_not_sharp": [int(x) for x in not_sharp],
        "frames_per_iter": [len(r) for r in overruns],
        "late_pct": 100 * sum(1 for r in overruns for x in r if x > 0) / frames,
        "per_iter_late_pct": [round(100 * sum(1 for x in r if x > 0) / len(r), 1) for r in overruns],
        "le_8_33_pct": 100 * sum(1 for r in durations for x in r if x <= 8.33) / frames,
        "gt_16_6": sum(1 for r in durations for x in r if x > 16.6),
    }


def main():
    for label in ("before", "after"):
        files = sorted(DIR.glob(f"{label}-run*.benchmarkData.json"))
        if not files:
            continue
        print(f"=== {label} ({len(files)} runs x 5 iterations, 16 flips per iteration) ===")
        stats = []
        for f in files:
            s = run_stats(f)
            stats.append(s)
            print(f"{f.name}: sharp on arrival {s["sharp"]:.0f}/{s["flips"]:.0f} "
                  f"(not sharp per iteration {s['per_iter_not_sharp']}), "
                  f"frames/iter {s['frames_per_iter']}, late {s['late_pct']:.2f}% "
                  f"(per iteration {s['per_iter_late_pct']}), "
                  f"CPU <= 8.33 ms {s['le_8_33_pct']:.2f}%, CPU > 16.6 ms {s['gt_16_6']}")
        for key, name in (("late_pct", "late %"), ("le_8_33_pct", "CPU <= 8.33 ms %")):
            values = [s[key] for s in stats]
            print(f"  {name}: median {median(values):.2f}, min {min(values):.2f}, max {max(values):.2f}")
        sharp_pct = [100 * s["sharp"] / s["flips"] for s in stats]
        print(f"  sharp on arrival %: median {median(sharp_pct):.1f}, "
              f"min {min(sharp_pct):.1f}, max {max(sharp_pct):.1f}")


if __name__ == "__main__":
    main()
