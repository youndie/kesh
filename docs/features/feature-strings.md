---
id: feature-strings
title: Strings and counters
type: feature
status: active
owner: unassigned
involved_services: [store, server, conformance]
client_entries: []
api: [endpoint-strings]
tags: [data-types]
---

# Strings and counters

## 1. Overview

The most used type: cached values, session blobs, counters. In the reference dataset, 10 M sessions
and 3 M rate counters are strings (research appendix A).

## 2. Business rules

* `SET` supports `EX`, `PX`, `EXAT`, `PXAT`, `NX`, `XX`, `KEEPTTL` and `GET` (and `NX` with `GET`, as
  Redis 7.0 does). A `SET` without `KEEPTTL` clears an existing expiry.
* `INCR`, `INCRBY`, `DECR` and `DECRBY` work on values that parse as a signed 64-bit integer.
  Otherwise the reply is `-ERR value is not an integer or out of range`. Overflow is refused with
  `-ERR increment or decrement would overflow` and the value is unchanged. `DECRBY` by
  `-9223372036854775808` is refused with `-ERR decrement would overflow` — a second string, because
  negating that increment overflows before the addition does (research §1.5).
* Any string command on a key of another type answers
  `-WRONGTYPE Operation against a key holding the wrong kind of value`.
* `INCRBYFLOAT` prints its result as Redis does — fixed-point, trimmed — but computes in `Double`
  where Redis on x86-64 uses `long double`; see the quirk below.
* *Target* (B-11): a write that would exceed `maxmemory` under `noeviction` answers `-OOM …`.

## 4. Code anchors

| Service | Code |
|---|---|
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/commands/StringCommands.kt` — every string command, following `t_string.c` |
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/RedisFloat.kt` — `INCRBYFLOAT`'s numbers |
| conformance | `conformance/scripts/strings/` — every command and refusal here against Redis 7.2 |

## 5. Scenarios

Built in B-05; *Wrong type* since B-06 brought a second value kind. Beside these, the
three string scripts compare 150 replies with Redis 7.2 byte for byte, all agreeing.

### Scenario: Set and get
* **Given:** an empty keyspace
* **When:** `SET user:1001:name Ada` then `GET user:1001:name`
* **Then:** the reply is `$3\r\nAda\r\n`
* **Automated:** `store/src/commonTest/kotlin/io/github/youndie/kesh/store/CommandsTest.kt::set and get`

### Scenario: Expiring set
* **When:** `SET session:42 x PX 100`
* **Then:** `GET session:42` returns `x`
* **And:** after 150 ms it returns the null bulk string
* **Automated:** `store/src/commonTest/kotlin/io/github/youndie/kesh/store/CommandsTest.kt::an expiring set is gone once its time has passed and not a millisecond before` — at 100 ms and at 101 ms, on a clock moved by hand

### Scenario: Counter overflow
* **Given:** `SET c 9223372036854775807`
* **When:** `INCR c`
* **Then:** the reply is `-ERR increment or decrement would overflow` and `GET c` is still `9223372036854775807`
* **Automated:** `store/src/commonTest/kotlin/io/github/youndie/kesh/store/CommandsTest.kt::counter overflow leaves the value unchanged`

### Scenario: Decrement by the minimum
* **Given:** `SET c 0`
* **When:** `DECRBY c -9223372036854775808`
* **Then:** the reply is `-ERR decrement would overflow`
* **Automated:** `store/src/commonTest/kotlin/io/github/youndie/kesh/store/CommandsTest.kt::counter overflow leaves the value unchanged`

### Scenario: Wrong type
* **Given:** a hash at `h`
* **When:** `GET h`
* **Then:** the reply is `-WRONGTYPE Operation against a key holding the wrong kind of value`
* **Automated:** `store/src/commonTest/kotlin/io/github/youndie/kesh/store/HashCommandsTest.kt::a string command on a hash answers WRONGTYPE`; against Redis in `conformance/scripts/hashes/commands.redis`

## 6. Out of scope

* `LCS`, `SUBSTR`, bit operations (`SETBIT`, `BITCOUNT`, …) — not in the brief's §6.

## 7. Quirks

* **`INCRBYFLOAT` and x86's `long double`.** `10.5 + 0.1` answers `10.6` on both; `0.1 + 0.2` answers
  `0.3` from Redis on x86-64 and `0.30000000000000004` from kesh, which computes in `Double` — Kotlin
  has no 80-bit type. Redis itself answers the second on platforms whose `long double` is a `double`.
  The conformance script compares only the cases where the two agree, and says so.
* **Hexadecimal floats** (`0x1p3`), which Redis's `strtold` accepts, are refused as not a valid float.

---

Why it is built this way, and what is still a hypothesis: [research-architecture](../research/research-architecture.md).
