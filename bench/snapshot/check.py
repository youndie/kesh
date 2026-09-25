#!/usr/bin/env python3
"""B-14's scenarios through the running server, and the numbers research R-5 needs.

    python3 bench/snapshot/check.py <scale> <workdir>

Round trip: the reference dataset at <scale> loaded through `redis-cli --pipe`, SAVE, stop, start;
DBSIZE, 10 000 sampled keys (read in full, unordered replies sorted) and every sorted set's ZCARD
compared. Torn save: a second SAVE killed with SIGKILL a moment in; the next start must load the first
snapshot. Expired: a key saved after its time must not come back. Prints SAVE time, snapshot size,
load time and resident memory, from kesh's own log lines.
"""
import os, random, re, signal, socket, subprocess, sys, time

SCALE, WORK = sys.argv[1], sys.argv[2]
PORT = 16397
KESH = os.path.abspath("server/build/bin/linuxX64/releaseExecutable/kesh.kexe")
DATASET = os.path.abspath("bench/build/bin/linuxX64/releaseExecutable/kesh-dataset.kexe")
os.makedirs(WORK, exist_ok=True)
SNAP = os.path.join(WORK, "dump.kesh")


class Client:
    def __init__(self):
        self.s = socket.create_connection(("127.0.0.1", PORT))
        self.f = self.s.makefile("rb")

    def cmd(self, *args):
        out = b"*%d\r\n" % len(args) + b"".join(b"$%d\r\n%s\r\n" % (len(a), a) for a in (x if isinstance(x, bytes) else str(x).encode() for x in args))
        self.s.sendall(out)
        return self.read()

    def read(self):
        line = self.f.readline()[:-2]
        t, rest = line[:1], line[1:]
        if t in (b"+", b"-"): return rest.decode()
        if t == b":": return int(rest)
        if t == b"$":
            n = int(rest)
            if n < 0: return None
            data = self.f.read(n + 2)[:-2]
            return data
        if t == b"*":
            n = int(rest)
            return None if n < 0 else [self.read() for _ in range(n)]
        raise ValueError(line)


def start(log):
    env = dict(os.environ, KESH_PORT=str(PORT), KESH_HTTP_PORT="off", KESH_DIR=WORK)
    p = subprocess.Popen([KESH], env=env, stdout=open(log, "w"), stderr=subprocess.STDOUT)
    for _ in range(3000):
        if p.poll() is not None: raise SystemExit(f"kesh exited {p.returncode}: {open(log).read()[-400:]}")
        try:
            socket.create_connection(("127.0.0.1", PORT), timeout=0.2).close()
            return p
        except OSError:
            time.sleep(0.05)
    raise SystemExit("kesh did not start")


def stop(p):
    p.send_signal(signal.SIGINT)
    p.wait(60)


def rss(p):
    for line in open(f"/proc/{p.pid}/status"):
        if line.startswith("VmRSS"): return int(line.split()[1]) // 1024


def state(c, keys):
    out = {}
    for k in keys:
        t = c.cmd("TYPE", k)
        if t == "string": v = c.cmd("GET", k)
        elif t == "hash": v = sorted(zip(*[iter(c.cmd("HGETALL", k))] * 2))
        elif t == "list": v = c.cmd("LRANGE", k, 0, -1)
        elif t == "set": v = sorted(c.cmd("SMEMBERS", k))
        elif t == "zset": v = c.cmd("ZRANGE", k, 0, -1, "WITHSCORES")
        else: v = None
        out[k] = (t, v, c.cmd("PTTL", k) > 0)
    return out


SAVED = r"saved .* in (\d+) ms"
LOADED = r"loaded .* in (\d+) ms"


def log_number(log, pattern):
    m = re.findall(pattern, open(log).read())
    return m[-1] if m else None


for f in os.listdir(WORK):
    os.remove(os.path.join(WORK, f))
print(f"scale {SCALE}, kesh {subprocess.check_output(['md5sum', KESH]).decode()[:8]}")

# Round trip.
p = start(f"{WORK}/kesh1.log")
gen = subprocess.Popen([DATASET, "--seed", "42", "--scale", SCALE, "--out", "-"], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
subprocess.run(["docker", "run", "--rm", "-i", "--network", "host", "redis:7.2", "redis-cli", "-p", str(PORT), "--pipe"], stdin=gen.stdout, stdout=subprocess.DEVNULL, check=True)
gen.wait()
c = Client()
size_before = c.cmd("DBSIZE")
keys, cursor = [], b"0"
while True:
    cursor, batch = c.cmd("SCAN", cursor, "COUNT", 1000)
    keys += batch
    if cursor == b"0": break
random.seed(7)
sample = random.sample(keys, min(10000, len(keys)))
boards = [k for k in keys if k.startswith(b"board:")]
before = state(c, sample)
zcards = {k: c.cmd("ZCARD", k) for k in boards}
t0 = time.monotonic()
assert c.cmd("SAVE") == "OK"
save_wall = time.monotonic() - t0
rss_before = rss(p)
stop(p)
snap_bytes = os.path.getsize(SNAP)
p = start(f"{WORK}/kesh2.log")
c = Client()
size_after = c.cmd("DBSIZE")
after = state(c, sample)
zcards_after = {k: c.cmd("ZCARD", k) for k in boards}
rss_after = rss(p)
diff = [k for k in sample if before[k] != after[k]]
print(f"round trip: DBSIZE {size_before} -> {size_after}; {len(sample)} sampled keys, {len(diff)} differ; "
      f"{len(boards)} sorted sets, {sum(1 for k in boards if zcards[k] != zcards_after[k])} ZCARDs differ")
save_ms = log_number(f"{WORK}/kesh1.log", SAVED)
load_ms = log_number(f"{WORK}/kesh2.log", LOADED)
print(f"SAVE {save_ms} ms (wall {save_wall * 1000:.0f} ms), snapshot {snap_bytes / 1048576:.0f} MB; "
      f"load {load_ms} ms; RSS {rss_before} MB before stop, {rss_after} MB after load")

# Torn save: change one key, SAVE, kill -9 a moment in.
c.cmd("SET", "torn:marker", "new")
c.s.sendall(b"*1\r\n$4\r\nSAVE\r\n")
time.sleep(float(os.environ.get("KILL_AFTER", "0.2")))
p.send_signal(signal.SIGKILL)
p.wait()
leftovers = [f for f in os.listdir(WORK) if f.startswith("temp-")]
p = start(f"{WORK}/kesh3.log")
c = Client()
print(f"torn save: killed during SAVE; temporary files left {leftovers}; restart DBSIZE {c.cmd('DBSIZE')} "
      f"(the first snapshot held {size_after}), torn:marker {c.cmd('EXISTS', 'torn:marker')}")

# Expired keys are not saved.
c.cmd("SET", "expired:k", "v", "PX", 10)
time.sleep(0.02)
c.cmd("SAVE")
stop(p)
p = start(f"{WORK}/kesh4.log")
c = Client()
print(f"expired: EXISTS expired:k after SAVE and restart = {c.cmd('EXISTS', 'expired:k')}")
stop(p)
