#!/usr/bin/env python3
"""
M7-01: Where does the sharpen time go?
Analyzes Perfetto traces from SharpenLatencyBenchmark across 5 cases and 3 runs.

For each TerbacaSharpen section, extracts:
- total duration
- gesture end -> first request
- render-service busy time (sum of AIDL IPdfiumRenderService::#4::server binder slices)
- last render -> last swap
- last swap -> reveal start
- reveal start -> sharp (crossfade)
- tile count
"""

import argparse
import glob
import json
import os
import re
import sys
from collections import defaultdict
from perfetto.trace_processor import TraceProcessor

CASES = [
    "paper_two_column.pdf_fit_pan",
    "paper_two_column.pdf_fit_pinch",
    "paper_two_column.pdf_max_pan",
    "paper_two_column.pdf_max_pinch",
    "heavy_plot.pdf_fit_pinch",
]

def percentile(vals, p):
    if not vals:
        return 0.0
    sorted_vals = sorted(vals)
    k = (len(sorted_vals) - 1) * (p / 100.0)
    f = int(k)
    c = min(f + 1, len(sorted_vals) - 1)
    d = k - f
    return sorted_vals[f] * (1.0 - d) + sorted_vals[c] * d

def analyze_trace(tp, trace_path):
    # Query all TerbacaSharpen slices
    sharpens = list(tp.query("""
        SELECT s.id, s.ts, s.dur
        FROM slice s
        WHERE s.name = 'TerbacaSharpen' AND s.dur > 0
        ORDER BY s.ts
    """))

    results = []
    for sh in sharpens:
        t_start = sh.ts
        t_end = sh.ts + sh.dur
        total_ms = sh.dur / 1e6

        # Check for reveal start
        reveals = list(tp.query(f"""
            SELECT ts FROM slice
            WHERE name = 'TerbacaRevealStart' AND ts >= {t_start} AND ts <= {t_end}
            ORDER BY ts
        """))
        has_reveal = bool(reveals)
        reveal_start_ts = reveals[0].ts if has_reveal else t_end

        # 1. Gesture end -> first request
        reqs = list(tp.query(f"""
            SELECT ts FROM slice
            WHERE name = 'TerbacaTileRequested' AND ts >= {t_start} AND ts <= {reveal_start_ts}
            ORDER BY ts
        """))
        first_req_ms = (reqs[0].ts - t_start) / 1e6 if reqs else 0.0

        # 2. Render service busy time (sum of binder renderTile slices before reveal)
        renders = list(tp.query(f"""
            SELECT ts, dur FROM slice
            WHERE name LIKE '%IPdfiumRenderService%#4%' AND ts >= {t_start} AND ts <= {reveal_start_ts}
            ORDER BY ts
        """))
        render_busy_ms = sum(r.dur for r in renders) / 1e6
        last_render_end = max((r.ts + r.dur) for r in renders) if renders else t_start

        # 3. Swaps before reveal
        swaps = list(tp.query(f"""
            SELECT ts, name FROM slice
            WHERE name LIKE 'TerbacaTileSwapped%' AND ts >= {t_start} AND ts <= {reveal_start_ts}
            ORDER BY ts
        """))
        last_swap_ts = swaps[-1].ts if swaps else last_render_end

        tile_count = 0
        for sw in swaps:
            try:
                tile_count += int(sw.name.split(":")[1])
            except (IndexError, ValueError):
                tile_count += 1

        last_render_to_last_swap_ms = max(0.0, (last_swap_ts - last_render_end) / 1e6)
        last_swap_to_reveal_ms = max(0.0, (reveal_start_ts - last_swap_ts) / 1e6) if has_reveal else 0.0
        reveal_to_sharp_ms = (t_end - reveal_start_ts) / 1e6 if has_reveal else 0.0

        results.append({
            "total_ms": total_ms,
            "first_req_ms": first_req_ms,
            "render_busy_ms": render_busy_ms,
            "last_render_to_last_swap_ms": last_render_to_last_swap_ms,
            "last_swap_to_reveal_ms": last_swap_to_reveal_ms,
            "reveal_to_sharp_ms": reveal_to_sharp_ms,
            "tile_count": tile_count,
        })
    return results

def main():
    parser = argparse.ArgumentParser(description="Analyze TerbacaSharpen breakdowns from Perfetto traces.")
    parser.add_argument("--traces-dir", default="build/perf-traces", help="Directory containing perf traces.")
    parser.add_argument("--save-cache", default="docs/perf-raw/m7-01/breakdown_data.json", help="Save extracted data to JSON cache.")
    parser.add_argument("--from-cache", action="store_true", help="Load extracted data from JSON cache instead of traces.")
    parser.add_argument("--print-sections", action="store_true", help="Print breakdown for every individual TerbacaSharpen section.")
    args = parser.parse_args()

    cache_file = args.save_cache
    case_data = defaultdict(lambda: defaultdict(list))

    if args.from_cache and os.path.exists(cache_file):
        print(f"Loading cached breakdown data from {cache_file}...")
        with open(cache_file, "r") as f:
            raw = json.load(f)
            for case, runs in raw.items():
                for run_name, samples in runs.items():
                    case_data[case][run_name] = samples
                    if args.print_sections:
                        for i, s in enumerate(samples):
                            print(f"[{case} {run_name} #{i+1}] total={s['total_ms']:.2f}ms; "
                                  f"gesture end -> first request={s['first_req_ms']:.2f}ms; "
                                  f"render-service busy={s['render_busy_ms']:.2f}ms; "
                                  f"last render -> last swap={s['last_render_to_last_swap_ms']:.2f}ms; "
                                  f"last swap -> reveal start={s['last_swap_to_reveal_ms']:.2f}ms; "
                                  f"reveal start -> sharp={s['reveal_to_sharp_ms']:.2f}ms; "
                                  f"tile count={s['tile_count']}")
    else:
        # Search for traces
        runs = ["run1", "run2", "run3"]
        found_any = False
        for run_name in runs:
            run_dir = os.path.join(args.traces_dir, run_name)
            if not os.path.isdir(run_dir):
                continue
            trace_files = glob.glob(os.path.join(run_dir, "*.perfetto-trace"))
            if not trace_files:
                continue
            found_any = True
            print(f"Processing {len(trace_files)} traces from {run_dir}...")
            for tf in sorted(trace_files):
                matched_case = None
                for c in CASES:
                    if c in tf:
                        matched_case = c
                        break
                if not matched_case:
                    continue

                tp = TraceProcessor(tf)
                samples = analyze_trace(tp, tf)
                tp.close()

                if args.print_sections:
                    for i, s in enumerate(samples):
                        print(f"[{os.path.basename(tf)} #{i+1}] total={s['total_ms']:.2f}ms; "
                              f"gesture end -> first request={s['first_req_ms']:.2f}ms; "
                              f"render-service busy={s['render_busy_ms']:.2f}ms; "
                              f"last render -> last swap={s['last_render_to_last_swap_ms']:.2f}ms; "
                              f"last swap -> reveal start={s['last_swap_to_reveal_ms']:.2f}ms; "
                              f"reveal start -> sharp={s['reveal_to_sharp_ms']:.2f}ms; "
                              f"tile count={s['tile_count']}")

                case_data[matched_case][run_name].extend(samples)

        if not found_any:
            if os.path.exists(cache_file):
                print(f"No traces found in {args.traces_dir}. Loading from cache {cache_file}...")
                with open(cache_file, "r") as f:
                    raw = json.load(f)
                    for case, runs_dict in raw.items():
                        for run_name, samples in runs_dict.items():
                            case_data[case][run_name] = samples
            else:
                print(f"Error: No traces in {args.traces_dir} and no cache file at {cache_file}")
                sys.exit(1)
        elif cache_file:
            os.makedirs(os.path.dirname(cache_file), exist_ok=True)
            with open(cache_file, "w") as f:
                json.dump(case_data, f, indent=2)
            print(f"Saved extracted data to {cache_file}")

    # Print individual sections if requested or print summary tables
    print("\n" + "=" * 100)
    print("M7-01 SHARPEN LATENCY PHASE BREAKDOWN (Xiaomi Pad 7, 3 Runs, 5 Iterations Each)")
    print("=" * 100)

    phases = [
        ("gesture->first_req", "first_req_ms"),
        ("render_busy", "render_busy_ms"),
        ("last_render->last_swap", "last_render_to_last_swap_ms"),
        ("last_swap->reveal", "last_swap_to_reveal_ms"),
        ("reveal->sharp", "reveal_to_sharp_ms"),
        ("total", "total_ms"),
        ("tile_count", "tile_count"),
    ]

    for case in CASES:
        all_samples = []
        for run_name in sorted(case_data[case].keys()):
            all_samples.extend(case_data[case][run_name])

        print(f"\n### Case: {case} (N = {len(all_samples)} sections across 3 runs)")
        print("-" * 100)
        print(f"{'Phase':<28} | {'Median (p50)':<15} | {'p95':<15} | {'Min - Max':<20}")
        print("-" * 100)

        phase_stats = {}
        for label, key in phases:
            vals = [s[key] for s in all_samples]
            p50 = percentile(vals, 50)
            p95 = percentile(vals, 95)
            min_v = min(vals) if vals else 0.0
            max_v = max(vals) if vals else 0.0
            unit = "tiles" if key == "tile_count" else "ms"
            phase_stats[label] = (p50, p95)
            print(f"{label:<28} | {p50:8.2f} {unit:<6} | {p95:8.2f} {unit:<6} | {min_v:6.2f} - {max_v:6.2f} {unit}")

        # Determine biggest phase (excluding total and tile_count)
        time_phases = [
            ("gesture->first_req", phase_stats["gesture->first_req"][0], phase_stats["gesture->first_req"][1]),
            ("render_busy", phase_stats["render_busy"][0], phase_stats["render_busy"][1]),
            ("last_render->last_swap", phase_stats["last_render->last_swap"][0], phase_stats["last_render->last_swap"][1]),
            ("last_swap->reveal", phase_stats["last_swap->reveal"][0], phase_stats["last_swap->reveal"][1]),
            ("reveal->sharp", phase_stats["reveal->sharp"][0], phase_stats["reveal->sharp"][1]),
        ]
        max_p50 = max(x[1] for x in time_phases)
        max_p95 = max(x[2] for x in time_phases)
        if max_p50 == 0.0:
            print("\n  -> Biggest phase by median: **N/A (already sharp)** (0.00 ms)")
        else:
            biggest_p50 = max(time_phases, key=lambda x: x[1])
            print(f"\n  -> Biggest phase by median: **{biggest_p50[0]}** ({biggest_p50[1]:.2f} ms)")

        if max_p95 == 0.0:
            print("  -> Biggest phase by p95:    **N/A (already sharp)** (0.00 ms)")
        else:
            biggest_p95 = max(time_phases, key=lambda x: x[2])
            print(f"  -> Biggest phase by p95:    **{biggest_p95[0]}** ({biggest_p95[2]:.2f} ms)")

if __name__ == "__main__":
    main()
