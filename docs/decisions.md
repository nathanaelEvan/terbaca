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
