"""Summarizes the competitor benchmark's raw results into the tables in docs/competitor-benchmark.md.

Usage: python tools/perf/competitor/summarize.py <results-dir>
where <results-dir> holds open.csv, open-fresh.csv, memory.csv, sizes.csv and
frames/<app>__<test>.json (Macrobenchmark output).
"""
import collections
import csv
import glob
import itertools
import json
import os
import statistics as st
import sys


def spread(values):
    return f"{st.median(values):.0f} ({min(values):.0f}–{max(values):.0f})"


def open_table(path, title):
    rows = list(csv.DictReader(open(path)))
    by = collections.defaultdict(list)
    am = collections.defaultdict(list)
    for r in rows:
        if r["open_ms"]:
            by[(r["app"], r["file"])].append(float(r["open_ms"]))
        if r["am_start_totaltime_ms"]:
            am[(r["app"], r["file"])].append(float(r["am_start_totaltime_ms"]))
    print(f"\n## {title}\napp | file | n | first page visible, ms median (min–max) | am start TotalTime median")
    for k in sorted(by):
        print(f"{k[0]} | {k[1]} | {len(by[k])} | {spread(by[k])} | {st.median(am[k]):.0f}")
    missing = [r for r in rows if not r["open_ms"]]
    if missing:
        print(f"no result: {[(r['app'], r['file'], r['run'], r['settled_late']) for r in missing]}")


def frames_table(root):
    print("\n## Frames\napp | test | iterations | frames/iter median | late frames % (overrun > 0), pooled (per-iteration min–max) | "
          "overrun P50/P90/P99 ms | CPU frame time P50/P90/P99 ms")
    for path in sorted(glob.glob(os.path.join(root, "frames", "*.json"))):
        app, test = os.path.basename(path)[:-5].split("__")
        b = json.load(open(path))["benchmarks"][0]
        sm = b["sampledMetrics"]
        over = list(itertools.chain(*sm["frameOverrunMs"]["runs"]))
        cpu = sm["frameDurationCpuMs"]
        counts = b["metrics"]["frameCount"]["runs"]
        late = 100 * sum(v > 0 for v in over) / len(over)
        per_run = [100 * sum(v > 0 for v in run) / len(run) for run in sm["frameOverrunMs"]["runs"]]
        o = sm["frameOverrunMs"]
        print(f"{app} | {test} | {len(counts)} | {st.median(counts):.0f} | {late:.1f} ({min(per_run):.1f}–{max(per_run):.1f}) | "
              f"{o['P50']:.1f}/{o['P90']:.1f}/{o['P99']:.1f} | {cpu['P50']:.1f}/{cpu['P90']:.1f}/{cpu['P99']:.1f}")


def memory_table(path):
    rows = list(csv.DictReader(open(path)))
    peak = collections.defaultdict(lambda: collections.defaultdict(int))
    opened = collections.defaultdict(list)
    for r in rows:
        kb = int(r["pss_kb"])
        peak[r["app"]][r["run"]] = max(peak[r["app"]][r["run"]], kb)
        if r["step"] == "open":
            opened[r["app"]].append(kb / 1024)
    print("\n## Memory (MB, total PSS of all the app's processes)\napp | runs | after open, median | peak while scrolling, median (min–max)")
    for app in sorted(peak):
        peaks = [v / 1024 for v in peak[app].values()]
        print(f"{app} | {len(peaks)} | {st.median(opened[app]):.0f} | {spread(peaks)}")


def size_table(path):
    print("\n## Size\napp | version | installed APK MB (files)")
    for r in csv.DictReader(open(path)):
        print(f"{r['app']} | {r['version']} | {int(r['apk_bytes']) / 1e6:.1f} ({r['apk_files']})")


def main():
    root = sys.argv[1]
    open_table(os.path.join(root, "open.csv"), "Cold open")
    if os.path.exists(os.path.join(root, "open-fresh.csv")):
        open_table(os.path.join(root, "open-fresh.csv"), "Cold open, never-seen file copies")
    frames_table(root)
    for name, fn in (("memory.csv", memory_table), ("sizes.csv", size_table)):
        if os.path.exists(os.path.join(root, name)):
            fn(os.path.join(root, name))


if __name__ == "__main__":
    main()
