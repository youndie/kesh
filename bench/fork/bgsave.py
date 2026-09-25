#!/usr/bin/env python3
"""B-25: BGSAVE, again and again, against a server under load (research R-6).

    bench/fork/bgsave.py <kesh pid> <port> <count> [hung seconds]

Asks for <count> background saves, one after another, and waits for each: every child must finish
with `rdb_last_bgsave_status:ok` and move `LASTSAVE`. A child still running after [hung seconds]
(default 120) is a hang — the lock-at-fork hazard R-6 names: its kernel stack and wait channel are
printed, it is killed and counted, and the run goes on.

While each save runs, the parent's and the child's memory is sampled from `/proc/<pid>/smaps_rollup`
every 50 ms: resident, proportional (Pss, which splits shared pages between the two) and private dirty
— the pages copy-on-write has already copied. Printed per save and as peaks at the end.
"""
import os
import socket
import sys
import time

pid, port, count = int(sys.argv[1]), int(sys.argv[2]), int(sys.argv[3])
hung_seconds = float(sys.argv[4]) if len(sys.argv) > 4 else 120.0

conn = socket.create_connection(("127.0.0.1", port))
reader = conn.makefile("rb")


def ask(*args):
    conn.sendall(b"".join([b"*%d\r\n" % len(args)] + [b"$%d\r\n%s\r\n" % (len(a), a.encode()) for a in args]))
    line = reader.readline()
    kind, rest = line[:1], line[1:-2].decode()
    if kind == b"$":
        body = reader.read(int(rest) + 2)[:-2].decode()
        return body
    return kind.decode() + rest


def persistence():
    return dict(line.split(":", 1) for line in ask("INFO", "persistence").split("\r\n") if ":" in line)


def children():
    try:
        with open(f"/proc/{pid}/task/{pid}/children") as f:
            direct = [int(c) for c in f.read().split()]
    except OSError:
        direct = []
    # The child is forked from the store thread, so it is that thread's child, not the main thread's.
    for task in os.listdir(f"/proc/{pid}/task"):
        try:
            with open(f"/proc/{pid}/task/{task}/children") as f:
                direct += [int(c) for c in f.read().split()]
        except OSError:
            pass
    return sorted(set(direct))


def memory(of):
    """MB of Rss, Pss and Private_Dirty, or None once the process is gone."""
    try:
        with open(f"/proc/{of}/smaps_rollup") as f:
            fields = dict(line.split(":", 1) for line in f if ":" in line and not line.startswith("0"))
    except OSError:
        return None

    def mb(name):
        return int(fields.get(name, "0 kB").split()[0]) // 1024

    return mb("Rss"), mb("Pss"), mb("Private_Dirty")


peaks = {"parent_rss": 0, "parent_private_dirty": 0, "child_rss": 0, "child_private_dirty": 0, "pss_sum": 0}
parent_before = memory(pid)
print(f"parent before the first save: rss {parent_before[0]} MB, pss {parent_before[1]} MB, private dirty {parent_before[2]} MB")
ok = failed = hung = 0
durations = []
for i in range(1, count + 1):
    before = ask("LASTSAVE")
    reply = ask("BGSAVE")
    if reply != "+Background saving started":
        print(f"save {i}: BGSAVE answered {reply!r}")
        failed += 1
        continue
    started = time.monotonic()
    save_peak = {"parent_rss": 0, "parent_private_dirty": 0, "child_rss": 0, "child_private_dirty": 0, "pss_sum": 0}
    while True:
        kids = children()
        parent = memory(pid)
        child = memory(kids[0]) if kids else None
        if parent:
            save_peak["parent_rss"] = max(save_peak["parent_rss"], parent[0])
            save_peak["parent_private_dirty"] = max(save_peak["parent_private_dirty"], parent[2])
        if child:
            save_peak["child_rss"] = max(save_peak["child_rss"], child[0])
            save_peak["child_private_dirty"] = max(save_peak["child_private_dirty"], child[2])
        if parent and child:
            save_peak["pss_sum"] = max(save_peak["pss_sum"], parent[1] + child[1])
        info = persistence()
        if info.get("rdb_bgsave_in_progress") == "0":
            break
        if time.monotonic() - started > hung_seconds:
            hung += 1
            for k in kids:
                for what in ("wchan", "stack", "status"):
                    try:
                        with open(f"/proc/{k}/{what}") as f:
                            text = f.read().strip()
                    except OSError as e:
                        text = str(e)
                    print(f"save {i}: HUNG child {k} {what}: {text[:2000]}")
                os.kill(k, 9)
            while persistence().get("rdb_bgsave_in_progress") != "0":
                time.sleep(0.05)
            break
        time.sleep(0.05)
    seconds = time.monotonic() - started
    info = persistence()
    after = ask("LASTSAVE")
    if info.get("rdb_last_bgsave_status") == "ok" and after != before:
        ok += 1
        durations.append(seconds)
    elif not (seconds > hung_seconds):
        failed += 1
        print(f"save {i}: status {info.get('rdb_last_bgsave_status')}, LASTSAVE {before} -> {after}")
    for k, v in save_peak.items():
        peaks[k] = max(peaks[k], v)
    if count <= 20 or i % 50 == 0:
        used = ask("INFO", "memory").split("used_memory:")[1].split("\r\n")[0]
        print(
            f"save {i}: {seconds:.2f} s; used_memory {int(used) // 1048576} MB; parent rss {save_peak['parent_rss']} MB, private dirty "
            f"{save_peak['parent_private_dirty']} MB; child rss {save_peak['child_rss']} MB, private dirty "
            f"{save_peak['child_private_dirty']} MB; pss of both {save_peak['pss_sum']} MB",
            flush=True,
        )
    # LASTSAVE is in seconds, set when the child is reaped: the next save must end in a later second
    # than this one did, so it starts after the second has turned.
    time.sleep(1.05 - time.time() % 1)

durations.sort()
median = durations[len(durations) // 2] if durations else 0
print(f"saves asked {count}: finished ok {ok}, failed {failed}, hung {hung}; median {median:.2f} s")
print(
    "peaks over the run: parent rss {parent_rss} MB, parent private dirty {parent_private_dirty} MB, child rss "
    "{child_rss} MB, child private dirty {child_private_dirty} MB, pss of both {pss_sum} MB".format(**peaks)
)
sys.exit(0 if ok == count else 1)
