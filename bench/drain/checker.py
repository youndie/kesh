"""The graceful-stop scenario's client side (B-16), stdlib only, run in a pod next to kesh.

N connections each send a pipeline of four `INCR <counter>` and read the four replies before the
next four, until the server closes the connection. Per connection it records the whole replies and
the bytes left over when the connection ended — a truncated reply if not zero. It prints one JSON
line when every connection has ended. The ledger — whether the counter the server saved equals the
replies received — is checked by run.sh after the restart.
"""
import json
import socket
import sys
import threading
import time

host, port, counter = sys.argv[1], int(sys.argv[2]), sys.argv[3]
connections = int(sys.argv[4]) if len(sys.argv) > 4 else 200

command = f"*2\r\n$4\r\nINCR\r\n${len(counter)}\r\n{counter}\r\n".encode() * 4
results = []
lock = threading.Lock()
started = threading.Barrier(connections + 1)


def client():
    replies, leftover, error = 0, 0, None
    sock = socket.create_connection((host, port), timeout=60)
    started.wait()
    pending = b""
    try:
        while True:
            sock.sendall(command)
            owed = 4
            while owed > 0:
                data = sock.recv(65536)
                if not data:
                    raise EOFError
                pending += data
                while b"\r\n" in pending:
                    line, pending = pending.split(b"\r\n", 1)
                    if not line.startswith(b":"):
                        raise ValueError(f"not an integer reply: {line[:40]!r}")
                    replies += 1
                    owed -= 1
    except (EOFError, ConnectionResetError, BrokenPipeError):
        pass
    except Exception as e:  # reported, not swallowed: it is the run's result
        error = repr(e)
    leftover = len(pending)
    sock.close()
    with lock:
        results.append((replies, leftover, error))


threads = [threading.Thread(target=client) for _ in range(connections)]
for t in threads:
    t.start()
started.wait()
print("loaded", flush=True)
began = time.time()
for t in threads:
    t.join()
print(json.dumps({
    "connections": len(results),
    "replies": sum(r[0] for r in results),
    "truncated_connections": sum(1 for r in results if r[1] > 0),
    "leftover_bytes": sum(r[1] for r in results),
    "errors": [r[2] for r in results if r[2]][:5],
    "seconds": round(time.time() - began, 1),
}), flush=True)
