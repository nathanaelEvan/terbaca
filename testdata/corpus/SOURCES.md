# Terbaca Test Corpus — Sources & Provenance

This directory contains the standard test corpus required by `docs/PRD.md` §7.1.
All files are deterministically generated in-repo via `generate_corpus.py` to ensure
clean IP provenance, zero copyright encumbrance, and reproducible benchmark testing.

## License

- **Origin:** Generated in-repo using `testdata/corpus/generate_corpus.py`.
- **License:** Apache License 2.0 (same as repository) / CC0 Public Domain Dedication for fixtures.

## Corpus Manifest

| File | Pages | Dimensions | Size | SHA-256 | PRD Category & Notes |
|---|---|---|---|---|---|
| `paper_two_column.pdf` | 30 | 595.28x841.89 (A4) | 74,881 B | `bec0311e2403132d5218592a049b09ea7c02179f93c4cb41b846d4bc256ca94f` | Two-column LaTeX-style paper, ~30 pages, with hyperref citations, equations, figures (PRD §7.1.1) |
| `paper_single_column.pdf` | 10 | 612.0x792.0 (Letter) | 16,382 B | `d14e51cc93cd784c3b13d1dbb82acf22fb50b5481588e963c1a33a68fa3f8829` | Single-column paper without hyperref (PRD §7.1.2) |
| `heavy_plot.pdf` | 1 | 612.0x792.0 (Letter) | 82,309 B | `947cceaad76d650b35463dd0e2a0dc8d1c1494a9e6d90eb7e6e4f9b99cd361c4` | Page with very heavy vector plot (5,000+ paths) (PRD §7.1.3) |
| `scanned_doc.pdf` | 5 | 595.28x841.89 (A4) | 517,955 B | `089999248efce00ff8bb6573b338406bdbaa255891c0cdb75b7cce44904586ee` | Scanned (image-only) multi-page PDF (PRD §7.1.4) |
| `deck_16_9.pdf` | 60 | 960.0x540.0 (16:9) | 133,030 B | `e953bc011317a401e7553a254221dd0b193e677054e91f059936d7f6d269fffc` | 16:9 deck, 60 slides with photos and gradient backgrounds (PRD §7.1.5) |
| `deck_4_3.pdf` | 20 | 1024.0x768.0 (4:3) | 39,198 B | `5e4fdf7db8873ac22a7cca7180c877cc7f1b3100579d8e926aee8ebd738c652d` | 4:3 deck, 20 slides (PRD §7.1.6) |
| `memory_100plus.pdf` | 105 | 595.28x841.89 (A4) | 2,490,928 B | `ead7f3d0e4593b1585067d8e0fec39813c2d2df0229059489a5e0f4919218081` | 100+ page document for memory and continuous scroll testing (PRD §7.1.7) |
| `malformed_truncated.pdf` | N/A (truncated) | N/A | 1,208 B | `5ddfaafa6d294993d4496a4b8497fd210c55c0610aa492f7e3cac0d9347ed3c7` | Malformed/truncated PDF file (PRD §7.1.8) |
| `malformed_corrupt.pdf` | N/A (corrupt) | N/A | 1,100 B | `ed80f4c8e9d7da8f3f49ea6c9866d5f3bc551b65458bceb60c323fe0e0ccc27e` | Corrupt binary stream PDF (PRD §7.1.8) |
| `encrypted_password.pdf` | 2 | 595.28x841.89 (A4) | 960 B | `f1219db3ce233f34cadd8912f72859ce89da47a9bb67a27df56f8870eae57e22` | Encrypted PDF protected by password 'secret123' (PRD §7.1.8) |

## Regeneration

To regenerate all corpus files:
```bash
python testdata/corpus/generate_corpus.py
```
