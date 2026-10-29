#!/usr/bin/env python3
"""Generates the responsive image and video variants used by the landing page.

Run manually and commit the output, so a deploy never depends on the build
machine having Pillow or ffmpeg available:

    python3 tools/optimize-media.py          # regenerate everything
    python3 tools/optimize-media.py --check  # verify budgets, write nothing

Budgets mirror the numbers agreed in the performance plan. `--check` exits
non-zero if any output is missing or over budget, so it can gate CI later.
"""

import argparse
import os
import shutil
import subprocess
import sys

from PIL import Image

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SITE = os.path.join(REPO, "assets", "site")

# Google's recommended explicit dimensions for the JitPack badge.
# (Not generated here; recorded so the numbers in the plan are traceable.)

# --- responsive stills -------------------------------------------------------
# The device frames render at roughly 300px on mobile and 380px on desktop, so
# two candidates are enough: 320w for phones, the native 610w for everything else.
STILLS = [
    "toric.webp",
    "liquid-dock.webp",
    "droplet.webp",
    "split-comparison.webp",
    "live-capture.webp",
    "legacy.webp",
]
STILL_WIDTHS = (320, 610)

# The hero poster feeds the <video poster> and the LCP preload, so it matches the
# two video encodes rather than the 610w stills.
POSTER = ("hero-poster.jpg", (480, 600))
POSTER_QUALITY = 78

# --- video encodes -----------------------------------------------------------
# The source is 600x1334 at ~66 fps (863 frames over 13.06 s). Dropping to 30 fps
# is where most of the saving comes from; the animation is UI motion, so 30 fps is
# visually indistinguishable and halves the frame count.
VIDEO_SRC = "hero-demo.mp4"
# CRF values chosen by measuring SSIM against the source rather than guessing:
#   480w crf37 -> 231 KB, SSIM 0.964
#   600w crf39 -> 305 KB, SSIM 0.968
# Both sit well above the ~0.95 mark where gradient banding becomes visible on
# this content (smooth gradients behind a refracting glass panel).
VIDEOS = [
    # (output, width, crf, budget_bytes)
    ("hero-demo-480.mp4", 480, 37, 250_000),
    ("hero-demo-600.mp4", 600, 39, 330_000),
]
VIDEO_FPS = 30

# Per-file ceilings in bytes, checked by --check. These are regression guards set
# a little above the achieved sizes, not aspirational targets: dropping the 320w
# tier from quality 80 to 74 only saves ~16 KB across all six images, which is
# not worth softening text in the device screenshots.
BUDGETS = {
    "toric-320.webp": 22_000,
    "liquid-dock-320.webp": 22_000,
    "droplet-320.webp": 22_000,
    "split-comparison-320.webp": 22_000,
    "live-capture-320.webp": 22_000,
    "legacy-320.webp": 22_000,
    "toric-610.webp": 44_000,
    "liquid-dock-610.webp": 44_000,
    "droplet-610.webp": 44_000,
    "split-comparison-610.webp": 44_000,
    "live-capture-610.webp": 44_000,
    "legacy-610.webp": 44_000,
    "hero-poster-480.webp": 34_000,
    "hero-poster-600.webp": 42_000,
}


def out(name):
    return os.path.join(SITE, name)


def resize_webp(src, dst, width, quality=80):
    """Writes a proportionally scaled WebP. Returns (bytes, (w, h))."""
    im = Image.open(src)
    if im.mode not in ("RGB", "RGBA"):
        im = im.convert("RGB")
    height = max(2, round(im.height * width / im.width))
    im = im.resize((width, height), Image.LANCZOS)
    im.save(dst, "WEBP", quality=quality, method=6)
    return os.path.getsize(dst), (width, height)


def encode_video(src, dst, width, crf):
    """Re-encodes to H.264 at 30 fps, no audio, with the moov atom up front."""
    cmd = [
        "ffmpeg", "-y", "-loglevel", "error", "-i", src,
        "-vf", f"scale={width}:-2:flags=lanczos,fps={VIDEO_FPS}",
        "-c:v", "libx264",
        "-profile:v", "main",
        "-pix_fmt", "yuv420p",      # broadest device compatibility
        "-crf", str(crf),
        "-preset", "slow",
        "-an",                      # the clip is muted: the audio track is dead weight
        "-movflags", "+faststart",  # moov atom first, so playback starts while downloading
        dst,
    ]
    subprocess.run(cmd, check=True)
    return os.path.getsize(dst)


def kb(n):
    return f"{n / 1024:.1f} KB"


def generate():
    if not shutil.which("ffmpeg"):
        sys.exit("ffmpeg not found on PATH")

    # bytes per responsive tier, keyed by the width the browser would pick
    still_bytes = {w: 0 for w in STILL_WIDTHS}
    poster_bytes = {w: 0 for w in POSTER[1]}

    print("== stills ==")
    before_all_stills = 0
    for name in STILLS:
        src = out(name)
        before_all_stills += os.path.getsize(src)
        stem = name[: -len(".webp")]
        for w in STILL_WIDTHS:
            dst = out(f"{stem}-{w}.webp")
            size, dim = resize_webp(src, dst, w)
            still_bytes[w] += size
            print(f"  {os.path.basename(dst):34} {dim[0]}x{dim[1]:<5} {kb(size):>10}")

    print("== poster ==")
    src = out(POSTER[0])
    poster_before = os.path.getsize(src)
    for w in POSTER[1]:
        dst = out(f"hero-poster-{w}.webp")
        size, dim = resize_webp(src, dst, w, quality=POSTER_QUALITY)
        poster_bytes[w] = size
        print(f"  {os.path.basename(dst):34} {dim[0]}x{dim[1]:<5} {kb(size):>10}")

    print("== video ==")
    src = out(VIDEO_SRC)
    video_before = os.path.getsize(src)
    video_bytes = {}
    for name, width, crf, _budget in VIDEOS:
        dst = out(name)
        size = encode_video(src, dst, width, crf)
        video_bytes[width] = size
        print(f"  {name:34} {width}px {VIDEO_FPS}fps crf{crf} {kb(size):>10}")

    # Report per breakpoint: a phone fetches the 320w stills + 480px poster and
    # video; anything wider fetches the 610w set and the 600px encodes. Averaging
    # or taking the max of both tiers would misrepresent what is actually shipped.
    mobile_after = still_bytes[320] + poster_bytes[480] + video_bytes[480]
    desktop_after = still_bytes[610] + poster_bytes[600] + video_bytes[600]
    mobile_before = before_all_stills + poster_before + video_before
    desktop_before = mobile_before

    print()
    print("  first-load media payload")
    print(f"  {'':12}{'before':>12}{'after':>12}{'saved':>9}")
    for label, b, a in (
        ("mobile", mobile_before, mobile_after),
        ("desktop", desktop_before, desktop_after),
    ):
        pct = 100 * (1 - a / b)
        print(f"  {label:12}{kb(b):>12}{kb(a):>12}{pct:>8.0f}%")
    print()
    print("  Source assets are left in place. Delete hero-demo.mp4 and hero-poster.jpg")
    print("  only after the page is verified, so the originals are recoverable.")


def check():
    failures = []

    for name, budget in BUDGETS.items():
        path = out(name)
        if not os.path.exists(path):
            failures.append(f"missing: {name}")
            continue
        size = os.path.getsize(path)
        status = "ok" if size <= budget else "OVER"
        if size > budget:
            failures.append(f"{name}: {size} > {budget}")
        print(f"  {name:34} {size / 1024:7.1f} KB  budget {budget / 1024:6.1f} KB  {status}")

    for name, _w, _crf, budget in VIDEOS:
        path = out(name)
        if not os.path.exists(path):
            failures.append(f"missing: {name}")
            continue
        size = os.path.getsize(path)
        status = "ok" if size <= budget else "OVER"
        if size > budget:
            failures.append(f"{name}: {size} > {budget}")
        print(f"  {name:34} {size / 1024:7.1f} KB  budget {budget / 1024:6.1f} KB  {status}")

    if not os.path.exists(out(VIDEO_SRC)):
        print(f"  note: {VIDEO_SRC} already removed (expected after verification)")

    if failures:
        print("\nFAILED:")
        for f in failures:
            print(f"  {f}")
        return 1
    print("\nall media within budget")
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--check", action="store_true",
                    help="verify budgets without writing anything")
    args = ap.parse_args()
    sys.exit(check() if args.check else (generate() or 0))


if __name__ == "__main__":
    main()
