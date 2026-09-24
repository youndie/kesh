#!/usr/bin/env python3
"""What the collector's second pause goes with, epoch by epoch, across heap-probe runs.

    epochs.py <run.err> ...      (each with its <run.out> beside it)

For every epoch inside the churn window: the second stop-the-world pause, the objects marked (kept),
the objects swept (garbage made since the previous epoch), the heap size before the sweep, and the
epoch's total time. Prints the correlation of the pause with each, separately for the first two
epochs of the window (the ones right after the load) and for the rest, then one line per run.

A correlation over a handful of runs is a lead, not a mechanism: it says which experiment to run
next — the one that moves that quantity alone.
"""
import re
import statistics as st
import sys
from pathlib import Path

EPOCH = re.compile(r"\[([\d.]+)s\] Epoch #(\d+): (.*)")


def epochs(err_path):
    out = Path(err_path).with_suffix(".out").read_text()
    lo = float(re.search(r"window-start at=([\d.]+)", out).group(1))
    hi = float(re.search(r"window-end.* at=([\d.]+)", out).group(1))
    found = {}
    for m in EPOCH.finditer(Path(err_path).read_text()):
        t, e, rest = float(m.group(1)), int(m.group(2)), m.group(3)
        d = found.setdefault(e, {"t": t})
        for key, pattern in (("kept", r"Mark: (\d+)"), ("swept", r"Sweep: swept (\d+)"),
                             ("heap", r"Heap memory usage: before (\d+)"),
                             ("p2", r"Mutators pause time #2: (\d+)"),
                             ("epoch", r"Finished\. Total GC epoch time is (\d+)")):
            x = re.match(pattern, rest)
            if x:
                d[key] = int(x.group(1))
    return [d for _, d in sorted(found.items()) if lo <= d["t"] <= hi and "p2" in d]


def corr(xs, ys):
    mx, my = st.mean(xs), st.mean(ys)
    sx = sum((x - mx) ** 2 for x in xs) ** 0.5
    sy = sum((y - my) ** 2 for y in ys) ** 0.5
    return sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / (sx * sy) if sx and sy else float("nan")


runs = {Path(p).stem: epochs(p) for p in sys.argv[1:]}
first = [d for ds in runs.values() for d in ds[:2]]
steady = [d for ds in runs.values() for d in ds[2:]]
for label, ds in (("first two epochs of the window", first), ("steady (third epoch on)", steady)):
    pauses = [d["p2"] for d in ds]
    print(f"{label}: n={len(ds)}")
    for key in ("kept", "swept", "heap", "epoch"):
        print(f"  corr(pause #2, {key:5}) = {corr([d[key] for d in ds], pauses):+.2f}")
print()
print(f"{'run':30} {'first two (ms)':>16} {'steady med':>10} {'steady max':>10} {'kept':>7} {'swept/ep':>9} {'epoch ms':>9}")
for name, ds in runs.items():
    s = [d["p2"] / 1000 for d in ds[2:]]
    print(f"{name:30} {str([round(d['p2'] / 1000, 1) for d in ds[:2]]):>16} "
          f"{(st.median(s) if s else 0):>10.2f} {(max(s) if s else 0):>10.2f} "
          f"{ds[-1]['kept'] / 1e6:>6.1f}M {st.median(d['swept'] for d in ds) / 1e6:>8.1f}M "
          f"{st.median(d['epoch'] for d in ds) / 1000:>9.0f}")
