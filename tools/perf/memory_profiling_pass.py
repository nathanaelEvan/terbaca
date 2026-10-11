#!/usr/bin/env python3
"""
Terbaca M5-02: Memory Profiling & Leak Check Harness.
Profiles memory footprint on the 100+ page document (memory_100plus.pdf)
as an engine-only baseline. It measures this Python process's RSS with pypdfium2, not the
app's PSS, so it cannot check the PRD §7 budget; use tools/perf/measure_pss.sh on a device for that.
"""

import os
import sys
import time
import gc
from pathlib import Path

try:
    import pypdfium2 as pdfium
    import psutil
except ImportError:
    print("Requires pypdfium2 and psutil. Run: pip install pypdfium2 psutil", file=sys.stderr)
    sys.exit(1)

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
CORPUS_PDF = REPO_ROOT / "testdata" / "corpus" / "memory_100plus.pdf"


def get_process_memory_mb() -> float:
    process = psutil.Process(os.getpid())
    return process.memory_info().rss / (1024 * 1024)


def run_memory_profile():
    print("=" * 70)
    print("Terbaca M5-02: Memory Profiling on 100+ Page Document")
    print("=" * 70)

    if not CORPUS_PDF.exists():
        print(f"Error: {CORPUS_PDF} does not exist", file=sys.stderr)
        return 1

    file_size_mb = CORPUS_PDF.stat().st_size / (1024 * 1024)
    print(f"Corpus file: {CORPUS_PDF.name} ({file_size_mb:.2f} MB)")

    gc.collect()
    baseline_mb = get_process_memory_mb()
    print(f"Baseline memory RSS: {baseline_mb:.2f} MB")

    # Step 1: Open document
    t0 = time.time()
    doc = pdfium.PdfDocument(CORPUS_PDF)
    open_time_ms = (time.time() - t0) * 1000
    page_count = len(doc)
    after_open_mb = get_process_memory_mb()
    print(f"Document opened in {open_time_ms:.1f} ms. Page count: {page_count}")
    print(f"Memory after open: {after_open_mb:.2f} MB (+{after_open_mb - baseline_mb:.2f} MB)")

    # Step 2: Traverse all pages (extract geometry and text metadata)
    t1 = time.time()
    for i in range(page_count):
        page = doc[i]
        _ = page.get_size()
        tp = page.get_textpage()
        _ = tp.count_chars()
        tp.close()
        page.close()
    traverse_time_s = time.time() - t1
    after_traverse_mb = get_process_memory_mb()
    print(f"Traversed {page_count} pages in {traverse_time_s:.2f} s")
    print(f"Memory after traversal: {after_traverse_mb:.2f} MB (+{after_traverse_mb - baseline_mb:.2f} MB)")

    # Step 3: Active multi-zoom tile rendering on sampled pages (simulating reading & zooming)
    peak_mb = after_traverse_mb
    zoom_scales = [0.5, 1.0, 2.0, 4.0]
    rendered_tiles = 0
    t2 = time.time()

    for p_idx in range(0, page_count, 5):  # Sample every 5th page across entire 100-page doc
        page = doc[p_idx]
        for scale in zoom_scales:
            bitmap = page.render(scale=scale)
            rendered_tiles += 1
            bitmap.close()
            current_mb = get_process_memory_mb()
            if current_mb > peak_mb:
                peak_mb = current_mb
        page.close()

    render_time_s = time.time() - t2
    print(f"Rendered {rendered_tiles} multi-zoom tiles in {render_time_s:.2f} s")
    print(f"Peak memory during active zooming: {peak_mb:.2f} MB (+{peak_mb - baseline_mb:.2f} MB)")

    # Step 4: Close document and verify leak check
    doc.close()
    del doc
    gc.collect()
    final_mb = get_process_memory_mb()
    leak_delta_mb = final_mb - baseline_mb
    print(f"Memory after close & GC: {final_mb:.2f} MB (Delta from baseline: {leak_delta_mb:+.2f} MB)")

    print("\nRunning 20 repeated open/close cycles for handle/memory leak verification...")
    cycles_peak = 0.0
    post_warmup_mb = get_process_memory_mb()
    for cycle in range(20):
        d = pdfium.PdfDocument(CORPUS_PDF)
        _ = len(d)
        p = d[0]
        bm = p.render(scale=1.0)
        bm.close()
        p.close()
        d.close()
        del d
        c_mb = get_process_memory_mb()
        if c_mb > cycles_peak:
            cycles_peak = c_mb

    gc.collect()
    post_cycles_mb = get_process_memory_mb()
    true_leak_delta = post_cycles_mb - post_warmup_mb
    print(f"Memory after 20 cycles: {post_cycles_mb:.2f} MB (Delta across 20 cycles: {true_leak_delta:+.2f} MB)")

    print("\n" + "=" * 70)
    print("MEMORY PROFILING SUMMARY & BUDGET VERIFICATION")
    print("=" * 70)
    passed = abs(true_leak_delta) < 10.0

    print(f"Engine-only peak RSS:          {peak_mb:.2f} MB (not the PRD §7 PSS budget; see measure_pss.sh)")
    print(f"Leak Delta Across 20 Cycles:   {true_leak_delta:+.2f} MB (limit 10 MB)")
    print(f"Status:                        {'PASS' if passed else 'FAIL'}")
    print("=" * 70)

    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(run_memory_profile())
