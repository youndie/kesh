---
id: B-07
title: "Lists"
status: done
priority: P1
size: M
stage: stage-2-types
epic: feature-lists
blocked_by: [B-05]
---

# B-07 — Lists

**Feature:** [feature-lists](../features/feature-lists.md).

Feeds are 300 k lists of 20–200 item ids. Push and pop at both ends, negative indexes, `LTRIM`.

- **The decision and its reason.** A list of packed chunks (Redis's quicklist idea): each chunk one
  `ByteArray` holding several items. It keeps the object count per list proportional to chunks, not
  items, which is research §1.2's lever.
- Rejected: `ArrayDeque<ByteArray>` — one object per item.

- AC: The scenarios of `feature-lists` pass; conformance is green.
- AC: An empty list is deleted.

## Code anchors

| Module | Path |
|---|---|
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/lists/` |
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/commands/ListCommands.kt` |
| conformance | `conformance/scripts/lists/` |

## Findings (2026-09-25)

- **Chunks of 8 KiB, Redis's `list-max-listpack-size -2`** (research D-21). Unlike a hash, a list's
  encoding cannot show in a reply, so nothing forced the number; Redis's is the one with a record.
  The packed layout moved out of `HashValue` into `store/…/packed/Packed.kt`, shared by both.
- **Acceptance.** Both scenarios automated (`ListCommandsTest`); `ListValueTest` covers the chunks —
  2 000 items over several chunks, head pushes, cuts from both ends, `LREM` in both directions and
  across chunks, an oversized item, a split after `LSET`. `conformance/run.sh`: 13 scripts, 542
  comparisons, all agree with Redis 7.2.16, 142 of them new, every one exact.
- **Mutations, each caught:** the chunk walk skipping an item after a chunk boundary (the oracle's
  `LRANGE long 0 -1`, and `ListValueTest`); a pop treating a count of 0 as negative (the oracle's
  `RPOP l 0`); `LINDEX` reading its index before its key (the oracle's `LINDEX nokey x`). The last two
  only the oracle sees — the order of checks is exactly what a unit test written from memory misses.
- **Seen on the way:** a handler that throws closes its connection and logs `kesh: connection failed`
  — not the server (feature-lists quirks).

Research: [research-architecture](../research/research-architecture.md).
