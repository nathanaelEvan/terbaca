"""Cold open → first page visible, from the recordings cold_open.sh made.

For each video:
- t0: the first frame that differs from the home screen (the system's reaction to the intent).
- t1: the first frame that (a) shows the page in the page band (rows 30–70 % of the screen): a
  light background (median > 120) with at least 60 % as many dark "ink" pixels (35+ levels below
  the background) as the recording's last frame, and (b) holds still: for the next 400 ms the
  band changes in < 2 % of its pixels. (b) keeps launch animations, where the home screen and the
  app blend, from counting. Ink rather than "equal to the last frame", because some apps move the
  page a little later (Acrobat's banners, for example). Popups at the very top or bottom (WPS
  "upgrade" banner, Xiaomi's action bar) are outside the band.
Prints one CSV row per video and writes t0/t1 stills next to it for spot checks.

Usage: python tools/perf/competitor/analyze_open.py <dir-with-mp4s> > open.csv
"""
import glob
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
import numpy as np  # noqa: E402
from video import COLS, changed_fraction, frames, save_png  # noqa: E402

BAND = (0.30, 0.70)
TEXT_SHARE = 0.6
HOLD_MS = 400
STILL = 0.02


def text_fraction(img):
    """(median brightness, share of text pixels) in the page band."""
    r0, r1 = int(BAND[0] * img.shape[0]), int(BAND[1] * img.shape[0])
    c0, c1 = int(COLS[0] * img.shape[1]), int(COLS[1] * img.shape[1])
    band = img[r0:r1, c0:c1]
    median = float(np.median(band))
    return median, float((band < median - 35).mean())


def analyze(path):
    times, imgs = frames(path)
    t0 = next((i for i in range(1, len(imgs)) if changed_fraction(imgs[i], imgs[0]) > 0.01), None)
    if t0 is None:
        return None
    _, final_text = text_fraction(imgs[-1])
    t1 = None
    for i in range(t0, len(imgs)):
        median, text = text_fraction(imgs[i])
        if median <= 120 or text < TEXT_SHARE * final_text:
            continue
        held = [j for j in range(i + 1, len(imgs)) if times[j] - times[i] <= HOLD_MS]
        if times[-1] - times[i] < HOLD_MS:
            break  # not enough recording left to know
        if all(changed_fraction(imgs[j], imgs[i], BAND) < STILL for j in held):
            t1 = i
            break
    if t1 is None or final_text == 0:
        return None
    stem = path[:-4]
    save_png(imgs[t0], stem + "_t0.png")
    save_png(imgs[t1], stem + "_t1.png")
    save_png(imgs[t1 - 1], stem + "_t1prev.png")
    late = times[-1] - times[t1] < 300
    return times[t1] - times[t0], late


def main():
    print("app,file,run,open_ms,am_start_totaltime_ms,settled_late")
    for path in sorted(glob.glob(os.path.join(sys.argv[1], "*.mp4"))):
        app, file, run = os.path.basename(path)[:-4].split("__")
        result = analyze(path)
        tt_path = path[:-4] + ".totaltime"
        tt = open(tt_path).read().strip() if os.path.exists(tt_path) else ""
        if result is None:
            print(f"{app},{file},{run},,{tt},no-change")
        else:
            print(f"{app},{file},{run},{result[0]:.0f},{tt},{'yes' if result[1] else ''}")


if __name__ == "__main__":
    main()
