---
id: B-09
title: "Sorted sets with logarithmic rank and range"
status: done
priority: P1
size: L
stage: stage-2-types
epic: feature-sorted-sets
blocked_by: [B-05]
---

# B-09 — Sorted sets with logarithmic rank and range

**Feature:** [feature-sorted-sets](../features/feature-sorted-sets.md).

Leaderboards: 2 000 sorted sets of 1 000–100 000 members, ordered by (score, member bytes), with rank
and range queries logarithmic in the set's size.

- **The decision and its reason.** An order-statistic structure (a skiplist with spans, as Redis, or
  a balanced tree with subtree counts) plus a member → score index. The choice is the implementer's
  and is recorded in the feature document with its object count per member, because at ~10–20 M
  members (research §1.2) that count matters as much as the complexity.
- Small sorted sets packed, as B-06.

- AC: The scenarios of `feature-sorted-sets` pass; conformance is green, including score ties ordered by member bytes.
- AC: `ZRANK` on a 1 M-member set takes within 10× the time it takes on a 1 k-member one (a relative assertion, not an absolute one).

## Code anchors

| Module | Path |
|---|---|
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/zsets/` |
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/commands/SortedSetCommands.kt` |
| conformance | `conformance/scripts/zsets/` |

## Findings (2026-09-25)

- **The structure: Redis's skiplist with spans, 3.5 objects a member** (research D-23) — the first
  level in the node's fields, level arrays only on the quarter of nodes taller than one.
- **Acceptance.** The three scenarios are automated: score ties in `SortedSetCommandsTest`; the
  million-member board and the rank timing in `SortedSetScaleTest` (linuxX64) — `ZRANK` 5.8 µs on
  1 M members against 1.4 µs on 1 k, 4.2×, inside 10×. The board's ranks are checked against its
  own arithmetic; against the oracle, ranks are checked on a 300-member skiplist. `ZSetValueTest`
  holds both encodings to a sorted reference under 23 000 random writes. `conformance/run.sh`:
  15 scripts, 922 comparisons, all agree with Redis 7.2.16 — 250 of them new, all exact.
- **What the oracle found on the first run, beyond the code:** a packed Redis set answers `0` for
  a score of `-0` and a skiplist `-0` — the listpack stores an integral score as an integer. kesh
  now does the same. And `strtod` takes hexadecimal numbers; kesh does not, a known divergence
  already recorded for `INCRBYFLOAT`, and those lines are not in the script.
- **Numbers are read and printed three ways**, each Redis's own: `string2d` for a score, bare
  `strtod` for a range bound (an empty string is `0`), `d2string` for printing — 30 printed scores
  in the script agree, from `1e-310` to `-1.5e+300`.
- **Mutations, each caught by the oracle:** a rank one too high (also by the unit tests); member
  bytes compared signed (the `\xff` member's place); a negative `LIMIT` offset not selecting
  nothing.

Research: [research-architecture](../research/research-architecture.md).
