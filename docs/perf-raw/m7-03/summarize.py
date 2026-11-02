#!/usr/bin/env python3
"""Summarizes M7-03 performance benchmark measurements (Xiaomi Pad 7).

Evaluates:
1. SharpenLatencyBenchmark across all 5 cases: p50 and p95 before vs after.
2. CompetitorBenchmarks#scrollFlings: late % (frameOverrunMs > 0).
3. CompetitorBenchmarks#pinchZoom: late % (frameOverrunMs > 0).
4. Per-core CPU time of render thread (PdfiumRender) from Perfetto sched table:
   Big vs Little core share.

Usage:
    python docs/perf-raw/m7-03/summarize.py
"""

import glob
import itertools
import json
import os
import statistics as st
import sys

DIR = os.path.dirname(os.path.abspath(__file__))
M7_02_DIR = os.path.join(DIR, "..", "m7-02")


def load_jsons(dir_path, pattern):
    files = sorted(glob.glob(os.path.join(dir_path, pattern)))
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


def summarize_frame_metric(runs_data, label):
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
        "min_late_pct": min(run_late_pcts),
        "max_late_pct": max(run_late_pcts),
        "median_late_pct": st.median(run_late_pcts),
        "frames_per_iter_median": st.median(frames_per_iter),
        "overrun_p50": st.median(p50_list),
        "overrun_p90": st.median(p90_list),
        "overrun_p99": st.median(p99_list),
    }


def summarize_sharpen(runs_data, case_filter):
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
        "run_p95_median": st.median(run_p95s),
        "run_p95_min": min(run_p95s),
        "run_p95_max": max(run_p95s),
    }


def analyze_sched_traces(traces_dir):
    """Analyzes CPU core distribution for PdfiumRender thread from Perfetto traces."""
    try:
        from perfetto.trace_processor import TraceProcessor
    except ImportError:
        return None

    trace_files = glob.glob(os.path.join(traces_dir, "*.perfetto-trace"))
    if not trace_files:
        return None

    cpu_durations = {i: 0 for i in range(8)}
    for tf in trace_files:
        try:
            tp = TraceProcessor(trace=tf)
            q = list(tp.query("""
                SELECT s.cpu, sum(s.dur) as dur_ns
                FROM sched s
                JOIN thread t USING (utid)
                WHERE t.name LIKE 'PdfiumRender%'
                GROUP BY s.cpu
            """))
            for r in q:
                if r.cpu in cpu_durations:
                    cpu_durations[r.cpu] += r.dur_ns
            tp.close()
        except Exception:
            pass

    total_ns = sum(cpu_durations.values())
    if total_ns == 0:
        return None

    # On Snapdragon 7+ Gen 3 / Pad 7: Cores 0-2 Little (1.9GHz), 3-6 Mid/Big (2.6GHz), 7 Prime (2.8GHz)
    little_ns = sum(cpu_durations[c] for c in [0, 1, 2])
    big_ns = sum(cpu_durations[c] for c in [3, 4, 5, 6, 7])

    return {
        "total_ms": total_ns / 1e6,
        "little_pct": 100.0 * little_ns / total_ns,
        "big_pct": 100.0 * big_ns / total_ns,
        "per_cpu_ms": {c: ns / 1e6 for c, ns in cpu_durations.items()},
    }


def main():
    print("=" * 80)
    print("M7-03 PERFORMANCE BENCHMARK SUMMARY (Xiaomi Pad 7)")
    print("THREAD_PRIORITY_DISPLAY vs THREAD_PRIORITY_BACKGROUND")
    print("=" * 80)

    # 1. Scroll Flings
    s_before = summarize_frame_metric(load_jsons(M7_02_DIR, "after_scroll*.json"), "M7-02 baseline (BACKGROUND)")
    s_after = summarize_frame_metric(load_jsons(DIR, "scroll*.json"), "M7-03 (DISPLAY priority)")

    print("\n### 1. CompetitorBenchmarks#scrollFlings (Smoothness during fling)")
    print("-" * 80)
    if s_before and s_after:
        print(f"{'Condition':<35} | {'Late % (pooled)':<15} | {'Late % min-max':<15} | {'Frames/iter':<12} | {'Overrun P50/P90/P99 (ms)'}")
        print("-" * 110)
        print(f"{s_before['label']:<35} | {s_before['pooled_late_pct']:>13.2f}% | {s_before['min_late_pct']:.2f}%–{s_before['max_late_pct']:.2f}% | {s_before['frames_per_iter_median']:>11.0f} | {s_before['overrun_p50']:.1f} / {s_before['overrun_p90']:.1f} / {s_before['overrun_p99']:.1f}")
        print(f"{s_after['label']:<35} | {s_after['pooled_late_pct']:>13.2f}% | {s_after['min_late_pct']:.2f}%–{s_after['max_late_pct']:.2f}% | {s_after['frames_per_iter_median']:>11.0f} | {s_after['overrun_p50']:.1f} / {s_after['overrun_p90']:.1f} / {s_after['overrun_p99']:.1f}")
    elif s_after:
        print(f"M7-03 pooled late %: {s_after['pooled_late_pct']:.2f}% (min-max: {s_after['min_late_pct']:.2f}%–{s_after['max_late_pct']:.2f}%)")

    # 2. Pinch Zoom Smoothness
    p_after = summarize_frame_metric(load_jsons(DIR, "pinch*.json"), "M7-03 (DISPLAY priority)")
    print("\n### 2. CompetitorBenchmarks#pinchZoom (Smoothness during pinch)")
    print("-" * 80)
    if p_after:
        print(f"{p_after['label']:<35} | Late %: {p_after['pooled_late_pct']:.2f}% (min-max: {p_after['min_late_pct']:.2f}%–{p_after['max_late_pct']:.2f}%) | Frames/iter: {p_after['frames_per_iter_median']:.0f} | Overrun P50/P90/P99: {p_after['overrun_p50']:.1f} / {p_after['overrun_p90']:.1f} / {p_after['overrun_p99']:.1f}")

    # 3. Sharpen Latency
    cases = [
        ("paper_two_column.pdf_fit_pan", "Fit Pan"),
        ("paper_two_column.pdf_fit_pinch", "Fit Pinch"),
        ("paper_two_column.pdf_max_pan", "Max Pan"),
        ("paper_two_column.pdf_max_pinch", "Max Pinch (800%)"),
        ("heavy_plot.pdf_fit_pinch", "Heavy Plot Pinch"),
    ]

    print("\n### 3. SharpenLatencyBenchmark (Sharpen Latency)")
    print("-" * 80)
    before_sharpen = load_jsons(M7_02_DIR, "after_sharpen*.json")
    after_sharpen = load_jsons(DIR, "sharpen*.json")

    for case_id, case_title in cases:
        sh_b = summarize_sharpen(before_sharpen, case_id)
        sh_a = summarize_sharpen(after_sharpen, case_id)
        if not sh_b and not sh_a:
            continue
        print(f"\n* Case: {case_title} ({case_id})")
        if sh_b and sh_a:
            print(f"  {'Condition':<30} | {'p50 (median run)':<16} | {'p95 (median run)':<16} | {'p95 min-max':<18}")
            print(f"  {'-'*90}")
            print(f"  {'Before (BACKGROUND)':<30} | {sh_b['run_p50_median']:>13.2f} ms | {sh_b['run_p95_median']:>13.2f} ms | {sh_b['run_p95_min']:>7.2f}–{sh_b['run_p95_max']:<7.2f} ms")
            print(f"  {'After (DISPLAY)':<30} | {sh_a['run_p50_median']:>13.2f} ms | {sh_a['run_p95_median']:>13.2f} ms | {sh_a['run_p95_min']:>7.2f}–{sh_a['run_p95_max']:<7.2f} ms")
            diff = sh_a['run_p95_median'] - sh_b['run_p95_median']
            print(f"  Delta p95: {diff:+.2f} ms ({diff/sh_b['run_p95_median']*100:+.1f}%)")
        elif sh_a:
            print(f"  After (DISPLAY): p50 = {sh_a['run_p50_median']:.2f} ms, p95 = {sh_a['run_p95_median']:.2f} ms")

    # 4. Sched Table per-core CPU time
    print("\n### 4. Render Thread CPU Core Distribution (sched table)")
    print("-" * 80)
    sched_info = analyze_sched_traces(DIR)
    if sched_info:
        print(f"Total CPU time: {sched_info['total_ms']:.2f} ms")
        print(f"Little cores (CPU 0-2): {sched_info['little_pct']:.1f}%")
        print(f"Big / Prime cores (CPU 3-7): {sched_info['big_pct']:.1f}%")
        for cpu, ms in sched_info['per_cpu_ms'].items():
            print(f"  CPU {cpu}: {ms:.2f} ms")
    else:
        print("  (Trace files pending)")


if __name__ == "__main__":
    main()
