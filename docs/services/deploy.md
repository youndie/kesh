---
id: deploy
title: deploy — container image and Helm chart
type: service
status: active
module: deploy
tech_stack: [Docker, Helm]
owner: unassigned
depends_on: [server]
publishes: [container image, Helm chart]
---

# deploy

## 1. Responsibility

The image the binary ships in and the chart that runs it: one StatefulSet replica with a persistent
volume for snapshots, probes wired to the HTTP port, a memory limit and a grace period (B-16).

**Deliberately does not:** run more than one replica (no replication or cluster in v1 — brief §2), or
choose any sizing value by hand. Not published to a registry yet: the image is built where it runs,
and environments are not decided (brief §7).

## 2a. Code anchors

| File | What is there |
|---|---|
| `deploy/Dockerfile` | the image: sborka's reference two-stage file (`:server:writeNativeDockerfile`), plus kesh's `KESH_DIR`, ports and non-root user |
| `.dockerignore` | the build context, trimmed of build output |
| `deploy/chart/values.yaml` | the one sizing input, `maxmemory`, and the measurements the arithmetic starts from, each with its source |
| `deploy/chart/templates/_helpers.tpl` | the arithmetic: SAVE and load seconds, drain, grace period, startup budget, memory limit |
| `deploy/chart/templates/statefulset.yaml` | the StatefulSet; each derived value carries its derivation as a comment in the rendered manifest |
| `bench/drain/run.sh`, `bench/drain/checker.py` | the graceful-stop scenario in a kind cluster, repeated |

## 3. How it is built

**Three values are derived, and the rendered manifest says from what**, as a comment next to each —
from `maxmemory` and `values.measured`:

* **`terminationGracePeriodSeconds`** = kore's announce wait (5 s) + the drain stage + kore's three
  release groups (9 s) + 2 s to exit. The drain stage is kesh's 5 s for connections plus **twice** the
  measured `SAVE` time of a full dataset — 3.9 s per GiB of `used_memory` (B-14's 2.3 s per million
  keys at B-11's 593 bytes per key) — twice because it was measured on the build machine, not a
  reference host (research Q-3). kesh is told both numbers (`KESH_SHUTDOWN_DRAIN_SECONDS`,
  `KESH_TERMINATION_GRACE_SECONDS`), and kore refuses at startup a plan that does not fit.
* **The startup probe's budget** = twice a full load (10.1 s per GiB, B-14) + 10 s.
* **The memory limit** = `maxmemory` × 3.3 (resident over `used_memory` at its peak under the
  reference load, B-28) × 1.2 for a day's drift + 64 MiB for the empty process. **The 1.2 is a placeholder** until B-18
  measures the drift, and `values.yaml` says so. Until B-28 the ratio was B-11's 2.8, taken without
  traffic; under traffic the old transport reached 5.3 × and an OOM kill (research R-8). The Kotlin/Native runtime does not see the limit;
  kesh checks `maxmemory` against it through kore at the same 3.3, which the chart passes as
  `KESH_RESIDENT_PEAK_RATIO_TENTHS` (B-26): a `maxmemory` the limit cannot hold does not start.
  **The limit does not budget a `BGSAVE`** (B-29, the owner's decision): under load its child needs
  about 1.4 × `used_memory` beyond the server's own — 4.6 × in all at 1/8 (`bench/reports/b-25/`) —
  and the OOM killer takes the larger process. Nothing in kesh issues one; the save on stop is a
  `SAVE`. A `BGSAVE` in a pod near its limit is the operator's to size for.

For `maxmemory` 1 GiB that is a 30 s grace period, a 32 s startup budget and a 4.0 GiB limit.

## 5. Infrastructure and deploy

* **Image:** `docker build -f deploy/Dockerfile .` from the repository's root: Gradle builds the
  release binary in the first stage, and it runs on `distroless/cc` as user 65532 — 47 MB. The first
  build downloads the Kotlin/Native toolchain into a cache mount.
* **Chart:** `deploy/chart/`, one replica, the volume at `/data` (`KESH_DIR`), `fsGroup` 65532,
  a read-only root file system.
* **Health:** `/health/started`, `/health/ready`, `/health/live` on 8080 — see
  [endpoint-http](../api/endpoint-http.md). Readiness every 2 s with one failure, inside kore's 5 s
  announce wait. Metrics are annotated for a Prometheus scrape.
* **Environments:** not decided (brief §7).

## 8. Quirks

* **A replica count above one fails rendering**, not deploys two independent stores behind one
  service that clients would treat as one.
* **The memory limit is expensive by measurement, not by caution**: 4.0 GiB for 1 GiB of data is
  B-28's 3.3 under load with B-18's placeholder drift. Lowering it without a new measurement turns the
  next load peak into an OOM kill.
* **Buildx warns about `FROM --platform=linux/amd64`** being constant. It is deliberate: the image
  wants the `linuxX64` binary whatever host builds it.
