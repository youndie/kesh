# CLAUDE.md — kesh

A Redis-compatible (RESP2) in-memory store on Kotlin/Native, `linuxX64`, built with the portfolio's
sborka conventions and kore. **State: connection commands, strings and keys (B-01…B-05); the other
value kinds, persistence and operations are ahead.** Read before writing code — the obvious design is wrong in several documented ways.

## Where to start a session

1. [docs/research/research-architecture.md](docs/research/research-architecture.md), **§0 first** —
   the ten places where research disagreed with the brief. The ones that change code:
   - **the keyspace is not a `HashMap`** (§1.3, D-12): the stdlib map rehashes everything in one
     call and cannot give `SCAN` its guarantee; `ByteArray` keys compare by identity;
   - **`ktor-network` on Native refuses descriptors ≥ 1024 and dies on `EINTR`** (§1.4, D-13, R-3):
     cap connections below it with Redis's error; never profile kesh with an in-process sampler;
     never install a `SIGCHLD` handler;
   - **nothing is freed synchronously** (D-15): `DEL`/`UNLINK`/`FLUSHALL ASYNC` differ only in
     `used_memory`;
   - **the heap is measured before the keyspace is built** (D-17, B-19).
2. [backlog.md](backlog.md) — the queue and why it is ordered that way.
3. The layer document the task belongs to — `features/`, `api/`, `services/` — in the branch
   *docs/layer-drafts* until it reaches `main` (see below).

## The rule that governs everything here

> **Redis is the oracle.** A command is done when the differential harness (`conformance`, B-04)
> gets the same bytes from kesh as from Redis 7.2 with `databases 1` — not when a unit test written
> from memory passes.

Error strings are copied from Redis's source (research §1.5 has the addresses), never retyped from
the brief or from memory. A normaliser in a conformance script (unordered, random, cursor, clock) is
declared on the line it applies to, never globally: it is also a way to hide a real difference.

## How layer documents reach `main`

`main` describes what exists. The feature, API and module documents are drafted in the branch
*docs/layer-drafts* with `status: draft`, together with the original brief under `research/`.
The pull request that implements an item brings the documents it makes true to `main` as
`status: active`: scenarios it implements get an `**Automated:**` line, the rest stay marked
*target*, and the anchors point at the code it added. The same pull request adds the `epic` field to
the backlog items of that feature, and removes the document from the drafts branch.

When the last document has moved, the drafts branch — and the brief in it — is closed without
merging.

## The backlog loop merges its own branches

Decided by the owner on 2026-09-24. There is no remote yet, so an item's "pull request" is its local
branch `feat/b-<nn>-<slug>`. When the item is `done`, the gate is green on the branch and the
acceptance was walked, the loop fast-forwards `main` to it (rebasing first if `main` moved), runs
`make gate` and `docs_check.py --on-main` on `main`, and then rebuilds *docs/layer-drafts* on top of
`main` without the documents the item moved. Once a remote exists, this becomes: push, open the pull
request, merge when green.

## Where things run

Builds, tests and the conformance oracle run on the Linux build machine (see the global agent
instructions for the wrapper), not on the Mac; the repository has a sync session there named
`kesh`. What CI will run for the code: `./gradlew ktlintCheck :resp:jvmTest :resp:linuxX64Test
:store:jvmTest :store:linuxX64Test :server:linuxX64Test :bench:jvmTest :bench:linuxX64Test
:conformance:jvmTest`. **The oracle** is
`conformance/run.sh` (needs Docker): it builds kesh, compares every script with Redis 7.2 and must
end with "all agree" — run it for any change to a command's reply. A mutation made with `sed` must be checked with `git diff` before its run is read: after the
formatter the pattern may not match, and the green run then tested the unchanged code (it happened
twice). **`ktlintFormat` runs on the Mac** (`LOCAL=1 ./gradlew ktlintFormat`): on the
synced copy its edits are reverted by the sync before anyone sees them. Latency and pause numbers are taken with **one subject per
host** and the load generator elsewhere (research §1.2, consequence 5); the build machine is not a
measurement host.

## Checks

`make check` is the gate and exactly what CI runs. `make fix` regenerates the backlog index after an
item changes. CI runs on `ubuntu-latest`, which works only while the repository is public — a private
repository in this account gets no hosted runners, and the workflow must move to the self-hosted
runner.

## Language

Code, comments, test names, commit messages and this documentation: English.
