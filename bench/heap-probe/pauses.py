#!/usr/bin/env python3
"""The heap probe's verdict line, from its stdout (marks) and stderr (the runtime's GC log).

    pauses.py <probe.out> <probe.err> [--label TEXT]
    pauses.py --selftest

Research D-3 fixed the reading before any number was seen (B-20): EVERY stop-the-world pause the
collector makes inside the churn window is one sample — under CMS both "Mutators pause time #1" and
"#2" of an epoch, each on its own — and the verdict is p99 of those samples at most 10 ms. The sum of
an epoch's pauses is printed beside it, for comparison only.

The window is cut by the probe's own marks ("window-start at=…s", "window-end at=…s") against the log
lines' runtime timestamps ("[12.345s]"). Both clocks start within milliseconds of each other at
process start; epochs whose pause line falls outside the window are not counted.

A run with no pause inside its window is an error, not a pass: a reader that finds nothing looks
exactly like a collector that never stops.
"""
import re, sys

PAUSE = re.compile(r"\[(\d+\.\d+)s\] Epoch #(\d+): Mutators pause time #(\d): (\d+) microseconds")
MARK = re.compile(r"\[(\d+\.\d+)s\] Epoch #(\d+): Mark: (\d+) objects")
ALIVE = re.compile(r"\[(\d+\.\d+)s\] Updated heap boundaries: alive (\d+)")
PROBE = re.compile(r"PROBE (\S+).* at=([\d.]+)s vmrss=(\d+)kB vmhwm=(\d+)kB threads=(\d+)")
THRESHOLD_US = 10_000


def pct(values, q):
    values = sorted(values)
    return values[min(len(values) - 1, int(len(values) * q))]


def verdict(out_text, err_text, label=""):
    marks = {}
    for line in out_text.splitlines():
        m = PROBE.search(line)
        if m:
            marks[m.group(1)] = (float(m.group(2)), int(m.group(3)), int(m.group(4)), int(m.group(5)), line)
    lo, hi = marks["window-start"][0], marks["window-end"][0]
    pauses, per_epoch, marked, alive = [], {}, [], []
    for line in err_text.splitlines():
        m = PAUSE.search(line)
        if m and lo <= float(m.group(1)) <= hi:
            us = int(m.group(4))
            pauses.append(us)
            per_epoch[m.group(2)] = per_epoch.get(m.group(2), 0) + us
            continue
        m = MARK.search(line)
        if m and lo <= float(m.group(1)) <= hi:
            marked.append(int(m.group(3)))
            continue
        m = ALIVE.search(line)
        if m and lo <= float(m.group(1)) <= hi:
            alive.append(int(m.group(2)))
    if not pauses:
        raise SystemExit(f"{label}: no collector pause inside the window {lo}-{hi}s — the reader found nothing")
    start = marks["start"][4]
    ops = re.search(r"ops_per_s=(\d+)", marks["window-end"][4]).group(1)
    keys = re.search(r"keys=(\d+)", marks["loaded"][4]).group(1)
    p99 = pct(pauses, 0.99)
    return {
        "label": label or re.search(r"encoding=(\S+) scale=(\S+)", start).group(0),
        "keys": int(keys),
        "load_s": marks["loaded"][0],
        "rss_loaded_mb": marks["loaded"][1] / 1024,
        "rss_peak_mb": marks["window-end"][2] / 1024,
        "threads": marks["window-end"][3],
        "alive_mb": (sorted(alive)[len(alive) // 2] / 2**20) if alive else None,
        "marked_median": sorted(marked)[len(marked) // 2] if marked else None,
        "epochs": len(per_epoch),
        "pauses": len(pauses),
        "p50_ms": pct(pauses, 0.5) / 1000,
        "p99_ms": p99 / 1000,
        "max_ms": max(pauses) / 1000,
        "epoch_sum_p99_ms": pct(list(per_epoch.values()), 0.99) / 1000,
        "ops_per_s": int(ops),
        "holds": p99 <= THRESHOLD_US,
    }


HEADER = ("run", "keys", "load s", "RSS loaded", "RSS peak", "alive", "marked", "epochs", "pauses",
          "p50 ms", "p99 ms", "max ms", "epoch-sum p99", "ops/s", "p99 ≤ 10 ms")


def row(v):
    alive = f"{v['alive_mb']:.0f} MB" if v["alive_mb"] is not None else "-"
    marked = f"{v['marked_median']:,}" if v["marked_median"] is not None else "-"
    return (v["label"], f"{v['keys']:,}", f"{v['load_s']:.0f}", f"{v['rss_loaded_mb']:,.0f} MB",
            f"{v['rss_peak_mb']:,.0f} MB", alive, marked, str(v["epochs"]), str(v["pauses"]),
            f"{v['p50_ms']:.2f}", f"{v['p99_ms']:.2f}", f"{v['max_ms']:.2f}", f"{v['epoch_sum_p99_ms']:.2f}",
            f"{v['ops_per_s']:,}", "yes" if v["holds"] else "**no**")


def selftest():
    out = "\n".join([
        "PROBE start encoding=naive scale=1.0 seed=42 at=0.0s vmrss=1kB vmhwm=1kB threads=3",
        "PROBE loaded keys=10 at=10.0s vmrss=2048kB vmhwm=2048kB threads=3",
        "PROBE window-start at=10.0s vmrss=2048kB vmhwm=2048kB threads=3",
        "PROBE window-end operations=5 ops_per_s=100 at=20.0s vmrss=4096kB vmhwm=4096kB threads=4",
    ])
    err = "\n".join([
        # before the window: not counted, however long
        "[INFO][gc][tid#1][5.000s] Epoch #1: Mutators pause time #1: 90000 microseconds.",
        "[INFO][gc][tid#1][11.000s] Epoch #2: Mark: 100 objects.",
        "[INFO][gc][tid#1][11.000s] Epoch #2: Mutators pause time #1: 1000 microseconds.",
        "[INFO][gc][tid#1][11.100s] Epoch #2: Mutators pause time #2: 12000 microseconds.",
        "[INFO][gcScheduler][tid#1][11.200s] Updated heap boundaries: alive 1048576, target 2, trigger 3",
        "[INFO][gc][tid#1][12.000s] Epoch #3: Mutators pause time #1: 2000 microseconds.",
        "[INFO][gc][tid#1][12.100s] Epoch #3: Mutators pause time #2: 3000 microseconds.",
    ])
    v = verdict(out, err, "selftest")
    # Four samples inside the window, each on its own: p99 is the 12 ms one, so the verdict is "no" —
    # and neither the 90 ms pause before the window nor summing an epoch may change that.
    assert v["pauses"] == 4 and v["epochs"] == 2, v
    assert v["p99_ms"] == 12.0 and not v["holds"], v
    assert v["epoch_sum_p99_ms"] == 13.0, v
    assert v["alive_mb"] == 1.0 and v["marked_median"] == 100, v
    try:
        verdict(out.replace("window-start at=10.0s", "window-start at=19.0s"), err, "empty")
    except SystemExit:
        pass
    else:
        raise AssertionError("an empty window must fail")
    print("selftest ok")


if __name__ == "__main__":
    if sys.argv[1:] == ["--selftest"]:
        selftest()
        sys.exit(0)
    args = sys.argv[1:]
    label = args[args.index("--label") + 1] if "--label" in args else ""
    v = verdict(open(args[0]).read(), open(args[1]).read(), label)
    print("| " + " | ".join(HEADER) + " |")
    print("|" + "---|" * len(HEADER))
    print("| " + " | ".join(row(v)) + " |")
