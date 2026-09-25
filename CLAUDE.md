# CLAUDE.md — kesh

A Redis-compatible (RESP2) in-memory store on Kotlin/Native, `linuxX64`, built with the portfolio's
sborka conventions and kore. **State: connection commands, strings, the four collection kinds, keys and the `SCAN` family
(B-01…B-10), `used_memory` and `maxmemory` (B-11) with Redis's eviction policies (B-12), active expiry
(B-13), snapshots with `SAVE` (B-14), `INFO`, probes and metrics on the HTTP port (B-15), the image, the chart and a drain that answers what
it read (B-16), the reference load measured (B-17), and kesh's own `epoll` transport that stopped the heap's runaway
under it (B-28), and Pub/Sub, with kompot's bus as its first consumer (B-27).** Read before writing code — the obvious design is wrong in several documented ways.

## Where to start a session

1. [docs/research/research-architecture.md](docs/research/research-architecture.md), **§0 first** —
   the ten places where research disagreed with the brief. The ones that change code:
   - **the keyspace is not a `HashMap`** (§1.3, D-12): the stdlib map rehashes everything in one
     call and cannot give `SCAN` its guarantee; `ByteArray` keys compare by identity;
   - **the transport is kesh's own `epoll` loop, not `ktor-network`** (D-31, B-28): kotlinx-io on
     Native does not pool its 8 KB segments, and ktor's channels fed the heap's runaway under load;
     the loop's thread is the store thread, so commands run where they are read;
   - **nothing is freed synchronously** (D-15): `DEL`/`UNLINK`/`FLUSHALL ASYNC` differ only in
     `used_memory`;
   - **the heap is measured before the keyspace is built** (D-17, B-19).
2. [backlog.md](backlog.md) — the queue and why it is ordered that way.
3. The layer document the task belongs to — `features/`, `api/`, `services/` — all on `main` since
   B-27 (see below).

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
merging. **Done on 2026-09-25, with B-27:** every layer document is on `main`, and *docs/layer-drafts*
is closed. A document for a feature not built yet is written in the item's own branch as
`status: draft` and made `active` by the item that implements it.

## The backlog loop merges its own branches

Decided by the owner on 2026-09-24. **The repository is public on GitHub, `youndie/kesh`, since
2026-09-25.** The loop pushes its branch `feat/b-<nn>-<slug>`, opens a pull request, and merges it
when the item is `done`, the acceptance was walked, CI is green on the branch's head, and the code
suites below passed on the build machine — CI runs the documentation gate only, not the code. It
merges fast-forward (rebasing first if `main` moved), then runs `make gate` and
`docs_check.py --on-main` on `main`. Until the push, work that exists only in a local branch does
not exist for the next session.

## Where things run

Builds, tests and the conformance oracle run on the Linux build machine (see the global agent
instructions for the wrapper), not on the Mac; the repository has a sync session there named
`kesh`. What CI will run for the code: `./gradlew ktlintCheck :resp:jvmTest :resp:linuxX64Test
:store:jvmTest :store:linuxX64Test :snapshot:jvmTest :snapshot:linuxX64Test :server:linuxX64Test :bench:jvmTest :bench:linuxX64Test
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
