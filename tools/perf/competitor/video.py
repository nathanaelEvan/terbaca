"""Frame access for the competitor benchmark's screen recordings (needs ffmpeg/ffprobe and numpy).

`screenrecord` writes a variable-frame-rate video: a frame only when the screen changes, each with
its real presentation time. So "the first frame where X is true" is accurate to one display frame.
"""
import subprocess

import numpy as np

W, H = 400, 267  # analysis size; the recordings are 1600x1068 (3200x2136 / 2)
# Columns compared: the outer 8 % on each side are left out, because HyperOS floats live-activity
# bubbles (a sports score during these runs) at the screen's left edge, and they change on their own.
COLS = (0.08, 0.92)


def frames(path):
    """Returns (times in ms, gray frames as an array [n, H, W])."""
    pts = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v", "-show_entries", "frame=pts_time",
         "-of", "csv=p=0", path],
        capture_output=True, text=True, check=True,
    ).stdout.split()
    times = np.array([float(t) * 1000 for t in pts if t and t != "N/A"])
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", path, "-vf", f"scale={W}:{H},format=gray",
         "-fps_mode", "passthrough", "-f", "rawvideo", "-"],
        capture_output=True, check=True,
    ).stdout
    imgs = np.frombuffer(raw, np.uint8).reshape(-1, H, W).astype(np.int16)
    n = min(len(times), len(imgs))
    return times[:n], imgs[:n]


def changed_fraction(a, b, rows=(0.0, 1.0), threshold=40):
    """Fraction of pixels in the row band [rows] that differ by more than [threshold] levels."""
    r0, r1 = int(rows[0] * H), int(rows[1] * H)
    c0, c1 = int(COLS[0] * W), int(COLS[1] * W)
    return float((np.abs(a[r0:r1, c0:c1] - b[r0:r1, c0:c1]) > threshold).mean())


def save_png(img, path):
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "gray", "-s", f"{W}x{H}",
         "-i", "-", path],
        input=img.astype(np.uint8).tobytes(), check=True,
    )

