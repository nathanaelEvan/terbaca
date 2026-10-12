# Product & Project Decisions

This document records key decisions aligned with the repository owner as outlined in Section 10 of `docs/PRD.md`.

---

## 1. Initial Setup Decisions (Milestone 0)

| Decision Question | Answer | Rationale / Notes |
|---|---|---|
| **GitHub Account / Org** | `nathanaelEvan` | Repository created as private repo `nathanaelEvan/terbaca`. |
| **Application ID / Package** | `app.terbaca` | Standard clean reverse-DNS identifier; matching `:app` namespace and package namespace. |
| **Primary Test Device** | Xiaomi Pad 7 | Optimization targets the Snapdragon 7+ Gen 3 tablet display (3200×2136, 3:2, 144 Hz). Compatible with Pad 7 Pro and Android 13+ tablets. |
| **Distribution Target** | Planning on release later (Google Play) | Retains strict compliance with Google Play target SDK policies and signing requirements (without compromising offline/privacy guarantees). |
| **Branding & Visuals** | Minimalist, dark theme | Dark theme palette (target background `#121214`, text `#E4E4E7`). Assets and final app icon will be handled in a later milestone PR. |

---

## 2. Architectural Decisions

Architectural decisions with technical trade-offs are documented as individual ADRs in `docs/adr/`.

---

## 3. Owner Decisions During Milestone Reviews

| Date | Decision | Notes |
|---|---|---|
| 2026-10-11 | **PRD §7 total-PSS budget is ≤ 350 MB on ≥ 2.5K displays** (≤ 250 MB on ≤ 1080p). | It was raised in PR #67 without sign-off. The owner approved it in the M5 review. Reason: about 82 MB of window buffers at 3200×2136 plus the 180 MB §5 tile cache can't fit 250 MB. |
| 2026-10-11 | **"Sharp" in PRD §7 includes the 150 ms tile crossfade.** | The latency sections end when the detail layer is fully opaque, not when the tiles reach the cache. Rows re-stated in `docs/perf-report.md`. |
| 2026-10-12 | **M6R-02 pinch-out check approved.** | The owner saw no blank pages ("gak verasa") during fast pinch-out on Pad 7. PR #92 approved for merge once CI is green. |
| 2026-10-12 | **Keep `TILE_CROSSFADE_DURATION_MS = 100` (M6R-03).** | The owner cannot feel a difference between 100 ms and 150 ms and requested industry standard. 100 ms matches Coil default and Material short motion range (50–200 ms). PRD §7 "Sharp" definition updated to 100 ms crossfade. |
