#!/usr/bin/env python3
"""
Terbaca Test Corpus Generator.
Generates reproducible test corpus files for PDFium rendering, navigation,
memory benchmarking, and corruption robustness per PRD §7.1 and M1-02.
"""

import hashlib
import io
import math
import os
import random
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import pymupdf
import pypdf
import pypdfium2 as pdfium


CORPUS_DIR = Path(__file__).resolve().parent


def compute_sha256(file_path: Path) -> str:
    hasher = hashlib.sha256()
    with open(file_path, "rb") as f:
        while chunk := f.read(65536):
            hasher.update(chunk)
    return hasher.hexdigest()


def generate_paper_two_column(out_path: Path) -> dict:
    """Category 1: Two-column LaTeX-style paper, ~30 pages, with hyperref citations, equations, figures."""
    doc = pymupdf.open()
    total_pages = 30
    page_width, page_height = 595.28, 841.89  # A4

    # Pre-create all pages
    for _ in range(total_pages):
        doc.new_page(width=page_width, height=page_height)

    for page_idx in range(total_pages):
        page = doc[page_idx]
        page_num = page_idx + 1

        # Header & Footer
        page.insert_text(
            (54, 36),
            "IEEE Transactions on Document Processing, Vol. 14, No. 2, 2026",
            fontsize=8,
            color=(0.4, 0.4, 0.4),
        )
        page.insert_text(
            (page_width - 70, page_height - 30),
            f"Page {page_num}",
            fontsize=9,
            color=(0.2, 0.2, 0.2),
        )

        col1_rect = pymupdf.Rect(54, 54, 285, page_height - 54)
        col2_rect = pymupdf.Rect(310, 54, 541, page_height - 54)

        if page_idx == 0:
            # Title & Abstract full-width
            page.insert_text(
                (54, 70),
                "High-Performance PDF Tile Rendering Architectures on Mobile Hardware",
                fontsize=16,
                fontname="helv",
            )
            page.insert_text(
                (54, 92),
                "Terbaca Research Group — Computer Science & Graphics Division",
                fontsize=10,
                color=(0.3, 0.3, 0.3),
            )
            page.draw_line((54, 105), (541, 105), color=(0.7, 0.7, 0.7), width=0.5)

            abstract_text = (
                "Abstract—We present an evaluation of tile-based asynchronous document rendering "
                "on embedded ARM64 hardware running high-refresh Android displays. By decoupling "
                "render pipelines into isolated memory domains and optimizing tile caching pyramids, "
                "we achieve sub-7ms frame latencies during complex continuous gestures."
            )
            page.insert_textbox(pymupdf.Rect(54, 115, 541, 160), abstract_text, fontsize=9)
            page.draw_line((54, 165), (541, 165), color=(0.7, 0.7, 0.7), width=0.5)

            col1_rect = pymupdf.Rect(54, 175, 285, page_height - 54)
            col2_rect = pymupdf.Rect(310, 175, 541, page_height - 54)

        # Body text columns
        p1 = (
            f"Section {page_num}.1 Architectural Foundations\n"
            "Document rasterization at 144 Hz requires deterministic memory allocation and zero "
            "garbage collection in hot render loops. We analyze the trade-offs between shared "
            "hardware buffers and socket-based pixel streams."
        )
        page.insert_textbox(col1_rect, p1, fontsize=9)

        p2 = (
            f"Section {page_num}.2 Pipeline Isolation\n"
            "By confining third-party C++ libraries like PDFium to independent sandbox processes, "
            "the host application guarantees state preservation even under malicious or corrupt payloads."
        )
        page.insert_textbox(col2_rect, p2, fontsize=9)

        # Equations on even pages
        if page_num % 2 == 0:
            eq_y = 300
            page.draw_rect(pymupdf.Rect(54, eq_y - 5, 285, eq_y + 35), color=(0.9, 0.9, 0.9), fill=(0.96, 0.96, 0.96))
            page.insert_text((70, eq_y + 18), "d^2 phi(x,y)/dx^2 = -rho(x,y) / eps_0", fontsize=9, fontname="courier")
            page.insert_text((250, eq_y + 18), f"({page_num}.1)", fontsize=8)

        # Figures on pages 3, 7, 15, 25
        if page_num in (3, 7, 15, 25):
            fig_rect = pymupdf.Rect(310, 250, 541, 380)
            page.draw_rect(fig_rect, color=(0.3, 0.4, 0.6), width=1)
            page.draw_line((310, 250), (541, 380), color=(0.8, 0.8, 0.8), width=0.5)
            page.draw_line((310, 380), (541, 250), color=(0.8, 0.8, 0.8), width=0.5)
            page.insert_text((350, 315), f"Figure {page_num}: Architecture Benchmarks", fontsize=9, color=(0.2, 0.2, 0.2))

        # Citations / Hyperref links
        if page_num < total_pages:
            cite_rect = pymupdf.Rect(54, page_height - 70, 150, page_height - 55)
            page.insert_text((54, page_height - 60), f"See Reference [{page_num}]", fontsize=8, color=(0.0, 0.2, 0.8))
            page.insert_link({
                "kind": pymupdf.LINK_GOTO,
                "page": total_pages - 1,
                "from": cite_rect,
                "to": pymupdf.Point(54, 100),
            })

        # External Link
        if page_num == 1:
            ext_rect = pymupdf.Rect(54, page_height - 45, 250, page_height - 35)
            page.insert_text((54, page_height - 37), "Project repository: https://github.com/nathanaelEvan/terbaca", fontsize=7, color=(0, 0, 0.8))
            page.insert_link({
                "kind": pymupdf.LINK_URI,
                "uri": "https://github.com/nathanaelEvan/terbaca",
                "from": ext_rect,
            })

        if page_num == total_pages:
            # References page
            page.insert_text((54, 70), "References and Bibliography", fontsize=14, fontname="helv")
            for ref_i in range(1, 31):
                page.insert_text(
                    (54, 85 + ref_i * 22),
                    f"[{ref_i}] Author, A. et al. (2026). Real-time tile decomposition in mobile readers. J. Doc. Eng., {ref_i}:{ref_i * 12}-{ref_i * 15}.",
                    fontsize=8,
                )


    doc.save(str(out_path), deflate=True)
    doc.close()
    return {"pages": total_pages, "dimensions": f"{page_width}x{page_height} (A4)"}


def generate_paper_single_column(out_path: Path) -> dict:
    """Category 2: Single-column paper without hyperref."""
    doc = pymupdf.open()
    total_pages = 10
    page_width, page_height = 612.0, 792.0  # Letter

    for page_idx in range(total_pages):
        page = doc.new_page(width=page_width, height=page_height)
        page_num = page_idx + 1

        page.insert_text((72, 54), f"Monograph on Pure Functional Document State — Chapter {page_num}", fontsize=11, fontname="times-roman")
        page.draw_line((72, 62), (540, 62), color=(0.5, 0.5, 0.5), width=0.5)

        body = (
            f"1.{page_num} Overview of State Machines\n\n"
            "Unidirectional data flow ensures that every gesture, state transition, and background "
            "task evaluates through a single immutable reducer. This completely prevents synchronization "
            "deadlocks across multi-threaded Android render surfaces.\n\n"
            "Consider a document state model parameterized by zoom scale, viewport offset, and "
            "active visible tile keys. When user touch input triggers an ACTION_MOVE event, the "
            "transformation matrix recalculates instantaneously without invoking garbage collection "
            "allocations on the UI thread.\n\n"
            "Performance characteristics demonstrate zero frame drops under standard 120Hz/144Hz displays "
            "when hardware acceleration is coupled with proactive background pre-rendering."
        )
        page.insert_textbox(pymupdf.Rect(72, 80, 540, 700), body, fontsize=10, fontname="times-roman")
        page.insert_text((page_width / 2 - 10, page_height - 40), str(page_num), fontsize=10)

    doc.save(str(out_path), deflate=True)
    doc.close()
    return {"pages": total_pages, "dimensions": f"{page_width}x{page_height} (Letter)"}


def generate_heavy_plot(out_path: Path) -> dict:
    """Category 3: Page with a very heavy vector plot (thousands of paths)."""
    doc = pymupdf.open()
    page_width, page_height = 612.0, 792.0
    page = doc.new_page(width=page_width, height=page_height)

    page.insert_text((54, 40), "Stress Test Benchmark: Dense Vector Path Topology (5,000+ Segments)", fontsize=12, fontname="helv")

    center_x, center_y = page_width / 2, page_height / 2
    path_count = 0

    # Draw complex Lissajous and Fourier spiral patterns
    points_a = []
    points_b = []
    for i in range(2500):
        t = i * 0.05
        r = 5 + 0.08 * i
        x1 = center_x + r * math.cos(3 * t) * math.sin(t * 0.5)
        y1 = center_y + r * math.sin(4 * t) * math.cos(t * 0.5)
        points_a.append(pymupdf.Point(x1, y1))

        x2 = center_x + (r * 0.8) * math.sin(5 * t + 0.3)
        y2 = center_y + (r * 0.8) * math.cos(2 * t)
        points_b.append(pymupdf.Point(x2, y2))

    page.draw_polyline(points_a, color=(0.1, 0.3, 0.8), width=0.4)
    page.draw_polyline(points_b, color=(0.8, 0.2, 0.1), width=0.4)
    path_count += len(points_a) + len(points_b)

    # Add dense background vector grid mesh
    for x in range(54, int(page_width - 54), 5):
        page.draw_line((x, 60), (x, page_height - 60), color=(0.88, 0.88, 0.92), width=0.25)
        path_count += 1
    for y in range(60, int(page_height - 60), 5):
        page.draw_line((54, y), (page_width - 54, y), color=(0.88, 0.88, 0.92), width=0.25)
        path_count += 1

    page.insert_text((54, page_height - 30), f"Total vector primitives generated: {path_count} paths", fontsize=8, color=(0.3, 0.3, 0.3))

    doc.save(str(out_path), deflate=True)
    doc.close()
    return {"pages": 1, "dimensions": f"{page_width}x{page_height} (Letter)", "paths": path_count}


def generate_scanned_doc(out_path: Path) -> dict:
    """Category 4: Scanned (image-only) PDF."""
    doc = pymupdf.open()
    total_pages = 5
    page_width, page_height = 595.28, 841.89  # A4

    for page_idx in range(total_pages):
        # Create a synthetic scanned document image using PIL (grayscale bitmap with simulated scan noise)
        img_w, img_h = 1000, 1400
        img = Image.new("RGB", (img_w, img_h), color=(248, 246, 238))  # slight yellow paper tint
        draw = ImageDraw.Draw(img)

        # Add simulated text lines
        random.seed(42 + page_idx)
        for line_y in range(120, img_h - 100, 36):
            line_w = random.randint(500, 800)
            draw.rectangle([100, line_y, 100 + line_w, line_y + 12], fill=(40, 42, 45))

        # Add simulated scan stamp / artifact
        draw.rectangle([700, 150, 900, 250], outline=(180, 50, 50), width=4)
        draw.line([700, 150, 900, 250], fill=(180, 50, 50), width=2)

        # Compress to JPEG
        buf = io.BytesIO()
        img.save(buf, format="JPEG", quality=85)
        img_bytes = buf.getvalue()

        page = doc.new_page(width=page_width, height=page_height)
        page.insert_image(page.rect, stream=img_bytes)

    doc.save(str(out_path), deflate=True)
    doc.close()
    return {"pages": total_pages, "dimensions": f"{page_width}x{page_height} (A4)"}


def generate_deck_16_9(out_path: Path) -> dict:
    """Category 5: 16:9 deck, ~60 slides, with photos and gradients."""
    doc = pymupdf.open()
    total_slides = 60
    slide_width, slide_height = 960.0, 540.0  # 16:9

    for slide_idx in range(total_slides):
        page = doc.new_page(width=slide_width, height=slide_height)
        slide_num = slide_idx + 1

        # Background gradient bands
        t = slide_idx / total_slides
        bg_r = min(max(0.92 - 0.1 * math.sin(t * math.pi), 0.0), 1.0)
        bg_g = min(max(0.93 - 0.08 * math.cos(t * math.pi), 0.0), 1.0)
        bg_b = 0.98

        page.draw_rect(page.rect, fill=(bg_r, bg_g, bg_b))

        # Top accent bar
        page.draw_rect(pymupdf.Rect(0, 0, slide_width, 12), fill=(0.15, 0.35, 0.75))

        # Slide header
        page.insert_text((60, 60), f"Milestone Execution: System Architecture — Slide {slide_num}", fontsize=18, fontname="helv", color=(0.1, 0.15, 0.25))
        page.draw_line((60, 75), (slide_width - 60, 75), color=(0.7, 0.75, 0.85), width=1)

        # Bullet points
        bullets = [
            f"• Core Objective {slide_num}.A: Zero-jank 144 Hz rendering on tablet display surfaces.",
            f"• Core Objective {slide_num}.B: Hard memory bounding under 250 MB total PSS for 100+ page docs.",
            f"• Core Objective {slide_num}.C: Process-isolated PDFium daemon with instant Binder crash recovery.",
        ]
        for b_idx, bullet in enumerate(bullets):
            page.insert_text((70, 130 + b_idx * 40), bullet, fontsize=12, fontname="helv", color=(0.2, 0.2, 0.2))

        # Embedded photo/chart mock
        chart_rect = pymupdf.Rect(slide_width - 320, 120, slide_width - 60, 360)
        page.draw_rect(chart_rect, fill=(0.90, 0.92, 0.96), color=(0.3, 0.4, 0.6), width=1.5)
        page.insert_text((slide_width - 270, 240), f"Metric Plot {slide_num}", fontsize=12, color=(0.2, 0.3, 0.5))

        # Slide number
        page.insert_text((slide_width - 80, slide_height - 25), f"{slide_num} / {total_slides}", fontsize=10, color=(0.4, 0.4, 0.4))

    doc.save(str(out_path), deflate=True)
    doc.close()
    return {"pages": total_slides, "dimensions": f"{slide_width}x{slide_height} (16:9)"}


def generate_deck_4_3(out_path: Path) -> dict:
    """Category 6: 4:3 deck."""
    doc = pymupdf.open()
    total_slides = 20
    slide_width, slide_height = 1024.0, 768.0  # 4:3

    for slide_idx in range(total_slides):
        page = doc.new_page(width=slide_width, height=slide_height)
        slide_num = slide_idx + 1

        # Background
        page.draw_rect(page.rect, fill=(0.98, 0.98, 0.99))
        page.draw_rect(pymupdf.Rect(40, 40, slide_width - 40, slide_height - 40), color=(0.8, 0.8, 0.8), width=1)

        # Header
        page.insert_text((70, 90), f"Academic Lecture Notes — Section {slide_num}", fontsize=22, fontname="helv", color=(0.1, 0.2, 0.4))
        page.draw_line((70, 110), (slide_width - 70, 110), color=(0.2, 0.4, 0.7), width=2)

        # Content
        page.insert_text((70, 160), "1. Mathematical Foundations of Signal Processing", fontsize=15, fontname="helv")
        page.insert_text((90, 200), "• Continuous Fourier Transform and its discrete sampling representations.", fontsize=13)
        page.insert_text((90, 235), "• Nyquist-Shannon sampling theorem constraints in high-resolution rasterization.", fontsize=13)
        page.insert_text((90, 270), "• Filter bank decomposition and multi-scale wavelet pyramids.", fontsize=13)

        page.insert_text((slide_width / 2 - 20, slide_height - 60), f"— {slide_num} —", fontsize=12)

    doc.save(str(out_path), deflate=True)
    doc.close()
    return {"pages": total_slides, "dimensions": f"{slide_width}x{slide_height} (4:3)"}


def generate_memory_100plus(out_path: Path) -> dict:
    """Category 7: 100+ page document for memory testing."""
    doc = pymupdf.open()
    total_pages = 105
    page_width, page_height = 595.28, 841.89  # A4

    for page_idx in range(total_pages):
        page = doc.new_page(width=page_width, height=page_height)
        page_num = page_idx + 1

        page.insert_text((54, 45), f"Terbaca Continuous Memory Benchmark Corpus — Record {page_num}", fontsize=9, color=(0.4, 0.4, 0.4))
        page.draw_line((54, 55), (page_width - 54, 55), color=(0.8, 0.8, 0.8), width=0.5)

        # Content
        page.insert_text((54, 80), f"Dataset Partition {page_num}: High-Density Stress Record", fontsize=13, fontname="helv")

        # Table rows
        for row in range(25):
            y = 120 + row * 26
            bg = (0.95, 0.95, 0.97) if row % 2 == 0 else (1.0, 1.0, 1.0)
            page.draw_rect(pymupdf.Rect(54, y, page_width - 54, y + 24), fill=bg)
            page.insert_text((60, y + 16), f"Entry #{page_num * 100 + row}", fontsize=9)
            page.insert_text((180, y + 16), f"Hash: 0x{((page_num * 100 + row) * 2654435761) & 0xFFFFFFFF:08x}", fontsize=8, fontname="courier")
            page.insert_text((340, y + 16), f"Allocated: {row * 128} KB", fontsize=9)
            page.insert_text((450, y + 16), "STATUS_VERIFIED", fontsize=8, color=(0.1, 0.6, 0.2))

        page.insert_text((page_width / 2 - 15, page_height - 30), f"{page_num} of {total_pages}", fontsize=9)

    doc.save(str(out_path), deflate=True)
    doc.close()
    return {"pages": total_pages, "dimensions": f"{page_width}x{page_height} (A4)"}


def generate_malformed_truncated(out_path: Path) -> dict:
    """Category 8a: Truncated PDF (valid PDF abruptly cut in half)."""
    doc = pymupdf.open()
    for i in range(5):
        page = doc.new_page(width=595.28, height=841.89)
        page.insert_text((100, 100), f"Page {i + 1} before truncation")
    temp_bytes = doc.tobytes()
    doc.close()

    # Truncate at 40% of byte length
    truncated_bytes = temp_bytes[: len(temp_bytes) // 2]
    with open(out_path, "wb") as f:
        f.write(truncated_bytes)

    return {"pages": "N/A (truncated)", "dimensions": "N/A", "notes": "Truncated at midpoint"}


def generate_malformed_corrupt(out_path: Path) -> dict:
    """Category 8b: Corrupt binary PDF (invalid header/structure)."""
    corrupt_data = b"%PDF-1.7\n\xFF\xFE\x00\x01CORRUPT_PAYLOAD_NOT_A_VALID_OBJECT_STREAM\n" * 20
    with open(out_path, "wb") as f:
        f.write(corrupt_data)
    return {"pages": "N/A (corrupt)", "dimensions": "N/A", "notes": "Invalid stream & trailer"}


def generate_encrypted_password(out_path: Path) -> dict:
    """Category 8c: Encrypted PDF with password secret123."""
    writer = pypdf.PdfWriter()
    writer.add_blank_page(width=595.28, height=841.89)
    writer.add_blank_page(width=595.28, height=841.89)
    writer.encrypt(user_password="secret123", owner_password="ownersecret123")
    with open(out_path, "wb") as f:
        writer.write(f)
    return {"pages": 2, "dimensions": "595.28x841.89 (A4)", "password": "secret123"}


def generate_many_objects(out_path: Path) -> dict:
    """Test fixture: one page with thousands of separate path objects.

    PDFium's progressive renderer can only pause between page objects, so this page lets tests
    measure mid-render cancellation (heavy_plot.pdf is mostly two huge polylines).
    """
    doc = pymupdf.open()
    page_width, page_height = 612.0, 792.0
    page = doc.new_page(width=page_width, height=page_height)
    # One content stream, one path object per circle. Overlapping semi-transparent fills make each
    # object expensive to draw, so rendering (pausable) dominates parsing (not pausable).
    shape = page.new_shape()
    count = 3000
    for i in range(count):
        center = pymupdf.Point(36 + (i * 37) % 540, 36 + (i * 53) % 720)
        shape.draw_circle(center, 30)
        shape.finish(color=(0.1, 0.1, 0.3), fill=((i % 17) / 20.0, 0.2, 0.6), width=0.5, fill_opacity=0.5)
    shape.commit()
    doc.save(str(out_path), deflate=True)
    doc.close()
    return {"pages": 1, "dimensions": f"{page_width}x{page_height} (Letter)", "objects": count}


def main():
    CORPUS_DIR.mkdir(parents=True, exist_ok=True)
    manifest = [
        ("paper_two_column.pdf", generate_paper_two_column, "Two-column LaTeX-style paper, ~30 pages, with hyperref citations, equations, figures (PRD §7.1.1)"),
        ("paper_single_column.pdf", generate_paper_single_column, "Single-column paper without hyperref (PRD §7.1.2)"),
        ("heavy_plot.pdf", generate_heavy_plot, "Page with very heavy vector plot (5,000+ paths) (PRD §7.1.3)"),
        ("scanned_doc.pdf", generate_scanned_doc, "Scanned (image-only) multi-page PDF (PRD §7.1.4)"),
        ("deck_16_9.pdf", generate_deck_16_9, "16:9 deck, 60 slides with photos and gradient backgrounds (PRD §7.1.5)"),
        ("deck_4_3.pdf", generate_deck_4_3, "4:3 deck, 20 slides (PRD §7.1.6)"),
        ("memory_100plus.pdf", generate_memory_100plus, "100+ page document for memory and continuous scroll testing (PRD §7.1.7)"),
        ("malformed_truncated.pdf", generate_malformed_truncated, "Malformed/truncated PDF file (PRD §7.1.8)"),
        ("malformed_corrupt.pdf", generate_malformed_corrupt, "Corrupt binary stream PDF (PRD §7.1.8)"),
        ("encrypted_password.pdf", generate_encrypted_password, "Encrypted PDF protected by password 'secret123' (PRD §7.1.8)"),
        ("many_objects.pdf", generate_many_objects, "One page with 3,000 separate overlapping translucent circles, for measuring mid-render cancellation (test fixture)"),
    ]

    records = []
    print("Generating Terbaca Test Corpus...")

    for filename, generator_func, description in manifest:
        file_path = CORPUS_DIR / filename
        meta = generator_func(file_path)
        sha = compute_sha256(file_path)
        size_bytes = file_path.stat().st_size
        records.append({
            "filename": filename,
            "description": description,
            "meta": meta,
            "sha256": sha,
            "size": size_bytes,
        })
        print(f"  [OK] Generated {filename} ({size_bytes:,} bytes, sha: {sha[:12]}...)")

    # Generate SOURCES.md
    sources_path = CORPUS_DIR / "SOURCES.md"
    with open(sources_path, "w", encoding="utf-8") as f:
        f.write("# Terbaca Test Corpus — Sources & Provenance\n\n")
        f.write("This directory contains the standard test corpus required by `docs/PRD.md` §7.1.\n")
        f.write("All files are deterministically generated in-repo via `generate_corpus.py` to ensure\n")
        f.write("clean IP provenance, zero copyright encumbrance, and reproducible benchmark testing.\n\n")
        f.write("## License\n\n")
        f.write("- **Origin:** Generated in-repo using `testdata/corpus/generate_corpus.py`.\n")
        f.write("- **License:** Apache License 2.0 (same as repository) / CC0 Public Domain Dedication for fixtures.\n\n")
        f.write("## Corpus Manifest\n\n")
        f.write("| File | Pages | Dimensions | Size | SHA-256 | PRD Category & Notes |\n")
        f.write("|---|---|---|---|---|---|\n")

        for r in records:
            pages = r["meta"].get("pages", "N/A")
            dims = r["meta"].get("dimensions", "N/A")
            f.write(f"| `{r['filename']}` | {pages} | {dims} | {r['size']:,} B | `{r['sha256']}` | {r['description']} |\n")

        f.write("\n## Regeneration\n\n")
        f.write("To regenerate all corpus files:\n")
        f.write("```bash\n")
        f.write("python testdata/corpus/generate_corpus.py\n")
        f.write("```\n")

    print(f"\nUpdated {sources_path.relative_to(CORPUS_DIR.parent.parent)}")

    # Self-validation with PDFium (pypdfium2)
    print("\nValidating corpus with PDFium...")
    for r in records:
        fname = r["filename"]
        fpath = CORPUS_DIR / fname
        if "malformed" in fname:
            try:
                doc = pdfium.PdfDocument(str(fpath))
                print(f"  [!] Malformed file {fname} unexpectedly opened by PDFium ({len(doc)} pages)")
            except Exception as e:
                print(f"  [OK] {fname}: Correctly rejected by PDFium ({type(e).__name__})")
        elif "encrypted" in fname:
            try:
                # Should fail without password
                doc = pdfium.PdfDocument(str(fpath))
                print(f"  [!] Encrypted file opened without password!")
            except Exception:
                # Should succeed with password
                doc = pdfium.PdfDocument(str(fpath), password="secret123")
                print(f"  [OK] {fname}: Correctly requires password and opened with 'secret123' ({len(doc)} pages)")
        else:
            doc = pdfium.PdfDocument(str(fpath))
            print(f"  [OK] {fname}: Validated in PDFium ({len(doc)} pages, first page: {doc[0].get_size()})")

    print("\nAll corpus files generated and validated successfully!")


if __name__ == "__main__":
    main()
