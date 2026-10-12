#!/usr/bin/env python3
"""Summarizes M7-02 before vs after benchmark measurements.

Evaluates:
1. SharpenLatencyBenchmark (fit_pinch, max_pinch): p50 and p95 latency.
   Rule: Revert if sharpen p95 doesn't drop.
2. CompetitorBenchmarks#scrollFlings: late % (frameOverrunMs > 0), frames/iter, overrun P50/P90/P99.
   Rule: late % must not get worse by more than its min-max range.

Usage:
    python docs/perf-raw/m7-02/summarize.py
"""

import glob
import itertools
import json
import os
import statistics as st
import sys

DIR = os.path.dirname(os.path.abspath(__file__))


def load_jsons(pattern):
    files = sorted(glob.glob(os.path.join(DIR, pattern)))
    return [(os.path.basename(f), json.load(open(f))) for f in files]


def calc_percentile(data, p):
    if not data:
        return 0.0
    sorted_d = sorted(data)
    k = (len(sorted_d) - 1) * (p / 100.0)
    f = int(k)
    c = min(f + 1, len(sorted_d) - 1)
    d0 = sorted_d[f]
    d1 = sorted_d[c]
    return d0 + (d1 - d0) * (k - f)


def summarize_scroll(runs_data, label):
    """Summarizes CompetitorBenchmarks#scrollFlings runs."""
    if not runs_data:
        return None
    all_overruns = []
    run_late_pcts = []
    frames_per_iter = []
    p50_list, p90_list, p99_list = [], [], []

    for name, data in runs_data:
        b = data["benchmarks"][0]
        sm = b["sampledMetrics"]
        over_runs = sm["frameOverrunMs"]["runs"]
        counts = b["metrics"]["frameCount"]["runs"]
        frames_per_iter.extend(counts)

        run_all_over = list(itertools.chain(*over_runs))
        all_overruns.extend(run_all_over)
        run_late = 100.0 * sum(v > 0 for v in run_all_over) / len(run_all_over)
        run_late_pcts.append(run_late)

        o = sm["frameOverrunMs"]
        p50_list.append(o["P50"])
        p90_list.append(o["P90"])
        p99_list.append(o["P99"])

    pooled_late = 100.0 * sum(v > 0 for v in all_overruns) / len(all_overruns)
    return {
        "label": label,
        "runs_count": len(runs_data),
        "total_frames": len(all_overruns),
        "pooled_late_pct": pooled_late,
        "run_late_pcts": run_late_pcts,
        "min_late_pct": min(run_late_pcts),
        "max_late_pct": max(run_late_pcts),
        "median_late_pct": st.median(run_late_pcts),
        "frames_per_iter_median": st.median(frames_per_iter),
        "frames_per_iter_min": min(frames_per_iter),
        "frames_per_iter_max": max(frames_per_iter),
        "overrun_p50": st.median(p50_list),
        "overrun_p90": st.median(p90_list),
        "overrun_p99": st.median(p99_list),
    }


def summarize_sharpen(runs_data, case_filter):
    """Summarizes SharpenLatencyBenchmark for a specific case."""
    if not runs_data:
        return None

    all_samples = []
    run_p50s = []
    run_p95s = []

    for name, data in runs_data:
        matches = [b for b in data["benchmarks"] if case_filter in b["name"]]
        if not matches:
            continue
        b = matches[0]
        sm = b.get("sampledMetrics", {})
        if "sharpenMs" not in sm:
            continue
        runs = sm["sharpenMs"]["runs"]
        flat = list(itertools.chain(*runs))
        all_samples.extend(flat)
        run_p50s.append(sm["sharpenMs"]["P50"])
        run_p95s.append(sm["sharpenMs"]["P95"])

    if not all_samples:
        return None

    return {
        "case": case_filter,
        "runs_count": len(runs_data),
        "total_samples": len(all_samples),
        "pooled_p50": calc_percentile(all_samples, 50),
        "pooled_p95": calc_percentile(all_samples, 95),
        "run_p50_median": st.median(run_p50s),
        "run_p50_min": min(run_p50s),
        "run_p50_max": max(run_p50s),
        "run_p95_median": st.median(run_p95s),
        "run_p95_min": min(run_p95s),
        "run_p95_max": max(run_p95s),
    }


def main():
    before_scroll = load_jsons("before_scroll*.json")
    after_scroll = load_jsons("after_scroll*.json")
    before_sharpen = load_jsons("before_sharpen*.json")
    after_sharpen = load_jsons("after_sharpen*.json")

    print("=" * 70)
    print("M7-02 PERFORMANCE BENCHMARK SUMMARY (Xiaomi Pad 7)")
    print("=" * 70)

    # 1. Scroll Flings
    s_before = summarize_scroll(before_scroll, "Before (main, cap=2)")
    s_after = summarize_scroll(after_scroll, "After (swap-cap, budget=inf when idle)")

    print("\n### 1. CompetitorBenchmarks#scrollFlings (Smoothness during fling)")
    print("-" * 70)
    if s_before and s_after:
        print(f"{'Condition':<35} | {'Late % (pooled)':<15} | {'Late % min-max':<15} | {'Frames/iter':<12} | {'Overrun P50/P90/P99 (ms)'}")
        print("-" * 110)
        print(f"{s_before['label']:<35} | {s_before['pooled_late_pct']:>13.2f}% | {s_before['min_late_pct']:.2f}%–{s_before['max_late_pct']:.2f}% | {s_before['frames_per_iter_median']:>11.0f} | {s_before['overrun_p50']:.1f} / {s_before['overrun_p90']:.1f} / {s_before['overrun_p99']:.1f}")
        print(f"{s_after['label']:<35} | {s_after['pooled_late_pct']:>13.2f}% | {s_after['min_late_pct']:.2f}%–{s_after['max_late_pct']:.2f}% | {s_after['frames_per_iter_median']:>11.0f} | {s_after['overrun_p50']:.1f} / {s_after['overrun_p90']:.1f} / {s_after['overrun_p99']:.1f}")

        # Check regression rule
        # "late %. It must not get worse by more than its min–max range."
        allowed_increase = s_before['max_late_pct'] - s_before['min_late_pct']
        delta_late = s_after['pooled_late_pct'] - s_before['pooled_late_pct']
        print(f"\nRule Check (Scroll late %):")
        print(f"  Before min-max range: {allowed_increase:.2f}%")
        print(f"  Delta pooled late %:  {delta_late:+.2f}%")
        if delta_late <= allowed_increase:
            print("  Status: PASSED (scroll late % did not regress beyond min-max range)")
        else:
            print("  Status: REGRESSED beyond allowed range")
    elif s_before:
        print(f"{'Condition':<35} | {'Late % (pooled)':<15} | {'Late % min-max':<15} | {'Frames/iter':<12} | {'Overrun P50/P90/P99 (ms)'}")
        print("-" * 110)
        print(f"{s_before['label']:<35} | {s_before['pooled_late_pct']:>13.2f}% | {s_before['min_late_pct']:.2f}%–{s_before['max_late_pct']:.2f}% | {s_before['frames_per_iter_median']:>11.0f} | {s_before['overrun_p50']:.1f} / {s_before['overrun_p90']:.1f} / {s_before['overrun_p99']:.1f}")
        print("  (After runs not yet available)")
    else:
        print("  (No scroll benchmark data found)")

    # 2. Sharpen Latency
    cases = [
        ("paper_two_column.pdf_fit_pinch", "Fit Pinch"),
        ("paper_two_column.pdf_max_pinch", "Max Pinch (800%)"),
        ("paper_two_column.pdf_fit_pan", "Fit Pan"),
        ("paper_two_column.pdf_max_pan", "Max Pan"),
        ("heavy_plot.pdf_fit_pinch", "Heavy Plot Pinch"),
    ]

    print("\n### 2. SharpenLatencyBenchmark (Sharpen Latency)")
    print("-" * 70)
    for case_id, case_title in cases:
        sh_b = summarize_sharpen(before_sharpen, case_id)
        sh_a = summarize_sharpen(after_sharpen, case_id)
        if not sh_b and not sh_a:
            continue
        print(f"\n* Case: {case_title} ({case_id})")
        if sh_b and sh_a:
            print(f"  {'Condition':<30} | {'p50 (median run)':<16} | {'p95 (median run)':<16} | {'p95 min-max':<18} | {'p95 (pooled)'}")
            print(f"  {'-'*105}")
            print(f"  {'Before (cap=2)':<30} | {sh_b['run_p50_median']:>13.2f} ms | {sh_b['run_p95_median']:>13.2f} ms | {sh_b['run_p95_min']:>7.2f}–{sh_b['run_p95_max']:<7.2f} ms | {sh_b['pooled_p95']:>7.2f} ms")
            print(f"  {'After (swap-cap)':<30} | {sh_a['run_p50_median']:>13.2f} ms | {sh_a['run_p95_median']:>13.2f} ms | {sh_a['run_p95_min']:>7.2f}–{sh_a['run_p95_max']:<7.2f} ms | {sh_a['pooled_p95']:>7.2f} ms")
            diff_p95 = sh_a['run_p95_median'] - sh_b['run_p95_median']
            diff_pct = (diff_p95 / sh_b['run_p95_median']) * 100.0
            print(f"  Delta p95 (median run): {diff_p95:+.2f} ms ({diff_pct:+.1f}%)")
            if diff_p95 < 0:
                print("  Status: IMPROVED (sharpen p95 dropped)")
            else:
                print("  Status: NO IMPROVEMENT")
        elif sh_b:
            print(f"  Before p50: {sh_b['run_p50_median']:.2f} ms, p95: {sh_b['run_p95_median']:.2f} ms (p95 min-max: {sh_b['run_p95_min']:.2f}–{sh_b['run_p95_max']:.2f} ms)")
            print("  (After runs not yet available)")


if __name__ == "__main__":
    main()
