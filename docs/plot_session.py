#!/usr/bin/env python3
"""Turn a Reach session CSV into graphs.

Pull a session off the phone first:

    adb pull /sdcard/Android/data/com.google.ar.core.examples.java.helloar/files/sessions/ .

Then:

    python3 docs/plot_session.py sessions/reach-<timestamp>.csv

Writes a PNG next to the CSV. Needs matplotlib; nothing else.
"""

import csv
import math
import sys
from collections import Counter

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt


def load(path):
    with open(path, newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def number(row, key, default=float("nan")):
    try:
        return float(row[key])
    except (KeyError, ValueError, TypeError):
        return default


def main(path):
    rows = load(path)
    if not rows:
        print("empty session")
        return

    seconds = [number(r, "ms") / 1000.0 for r in rows]
    hazard = [r["state"] == "HAZARD" for r in rows]

    fig = plt.figure(figsize=(14, 11))
    fig.suptitle("Reach session  ·  %d frames  ·  %.0f seconds"
                 % (len(rows), seconds[-1] - seconds[0]), fontsize=14)

    # 1. how far the nearest obstacle was, over time
    ax = fig.add_subplot(3, 2, 1)
    ax.plot([s for s, h in zip(seconds, hazard) if h],
            [number(r, "distance_m") for r, h in zip(rows, hazard) if h],
            ".", markersize=3, color="#e0564a")
    ax.axhline(1.0, color="#888", linestyle="--", linewidth=1, label="stopping distance")
    ax.set_title("Obstacle distance")
    ax.set_xlabel("seconds")
    ax.set_ylabel("metres")
    ax.legend(fontsize=8)

    # 2. where they were, left to right
    ax = fig.add_subplot(3, 2, 2)
    colours = {"left": "#5aa6e0", "centre": "#7fe08a", "right": "#e0a24a"}
    for side in ("left", "centre", "right"):
        xs = [s for s, r, h in zip(seconds, rows, hazard) if h and r["direction"] == side]
        ys = [number(r, "lateral_m") for r, h in zip(rows, hazard) if h and r["direction"] == side]
        ax.plot(xs, ys, ".", markersize=3, color=colours[side], label=side)
    ax.axhline(0.12, color="#555", linewidth=0.8)
    ax.axhline(-0.12, color="#555", linewidth=0.8)
    ax.set_title("Which side of the corridor")
    ax.set_xlabel("seconds")
    ax.set_ylabel("metres from centre")
    ax.legend(fontsize=8)

    # 3. a bird's eye view: every obstacle plotted where it actually was
    ax = fig.add_subplot(3, 2, 3)
    xs = [number(r, "lateral_m") for r, h in zip(rows, hazard) if h]
    ys = [number(r, "distance_m") for r, h in zip(rows, hazard) if h]
    ax.scatter(xs, ys, s=8, alpha=0.5, color="#e0564a")
    ax.plot(0, 0, "^", markersize=12, color="#7fe08a")
    ax.axvline(-0.30, color="#555", linestyle="--", linewidth=0.8)
    ax.axvline(0.30, color="#555", linestyle="--", linewidth=0.8, label="corridor edge")
    ax.set_title("Seen from above (wearer at the origin)")
    ax.set_xlabel("metres left / right")
    ax.set_ylabel("metres ahead")
    ax.legend(fontsize=8)

    # 4. who was driving the output
    ax = fig.add_subplot(3, 2, 4)
    counts = Counter(r["channel"] for r in rows)
    names = list(counts)
    ax.barh(names, [counts[n] for n in names], color="#5aa6e0")
    ax.set_title("Which sense owned the output")
    ax.set_xlabel("frames")

    # 5. what the sensor was doing
    ax = fig.add_subplot(3, 2, 5)
    counts = Counter(r["state"] for r in rows)
    names = list(counts)
    ax.barh(names, [counts[n] for n in names],
            color=["#e0564a" if n in ("BLIND", "STALE") else "#7fe08a" for n in names])
    ax.set_title("Depth state")
    ax.set_xlabel("frames")

    # 6. how much of the free-space fan could be read
    ax = fig.add_subplot(3, 2, 6)
    ax.plot(seconds, [number(r, "coverage") * 100 for r in rows], linewidth=1, color="#5aa6e0")
    ax.axhline(60, color="#888", linestyle="--", linewidth=1, label="trust threshold")
    ax.set_title("Free-space fan coverage")
    ax.set_xlabel("seconds")
    ax.set_ylabel("% of fan with depth")
    ax.set_ylim(0, 105)
    ax.legend(fontsize=8)

    fig.tight_layout(rect=(0, 0, 1, 0.97))
    out = path.rsplit(".", 1)[0] + ".png"
    fig.savefig(out, dpi=130)
    print("wrote", out)

    named = Counter(r["label"] for r in rows if r["label"])
    if named:
        print("objects named:", ", ".join("%s x%d" % (k, v) for k, v in named.most_common(10)))
    blind = sum(1 for r in rows if r["state"] in ("BLIND", "STALE"))
    print("depth unusable on %d of %d frames (%.0f%%)" % (blind, len(rows), 100.0 * blind / len(rows)))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(1)
    main(sys.argv[1])
