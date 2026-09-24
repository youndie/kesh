---
id: feature-hashes
title: Hashes
type: feature
status: active
owner: unassigned
involved_services: [store, server, conformance]
client_entries: []
api: [endpoint-hashes]
tags: [data-types]
---

# Hashes

## 1. Overview

Objects with fields: profiles, carts, per-user settings. In the reference dataset, 2 M profiles of
8–20 fields each — which, stored one object per field and value, would be 32–80 M objects and
twice the live heap (research §1.2, B-19). A small hash is therefore one packed `ByteArray`, and
becomes a table where Redis 7.2 would make it one (research D-20).

## 2. Business rules

* `HSET` (several field–value pairs, answering how many were new), `HGET`, `HMGET`, `HDEL`,
  `HGETALL`, `HLEN`, `HEXISTS`, `HINCRBY`, `HINCRBYFLOAT`, `HKEYS`, `HVALS`. *Target* (B-10): `HSCAN`.
* A hash with no fields left is deleted; `HDEL` stops at the field that emptied it.
* `HINCRBY` on a field that is not an integer answers `-ERR hash value is not an integer`;
  `HINCRBYFLOAT` on one that is not a float answers `-ERR hash value is not a float`. The increment
  is read before the key, so a bad increment wins over `WRONGTYPE`, as in Redis.
* **Encoding.** Packed while it has at most 512 fields and every field and value is at most 64 bytes;
  a table from the write that breaks either, and for good. A write is checked before it happens: an
  `HSET` carrying more than 512 pairs, or any argument over 64 bytes, converts first — Redis's
  `hashTypeTryConversion`.
* The encoding is invisible except in reply order, and there it matches Redis: insertion order while
  packed, table order after — the same rule, so the same moment.
* *Target* (B-11): a write that would exceed `maxmemory` under `noeviction` answers `-OOM …`.

## 4. Code anchors

| Service | Code |
|---|---|
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/hashes/HashValue.kt` — the two encodings and the conversion |
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/commands/HashCommands.kt` — every hash command, following `t_hash.c` |
| conformance | `conformance/scripts/hashes/` — the commands, their refusals and the encoding boundary against Redis 7.2 |

## 5. Scenarios

Built in B-06. Beside these, the two hash scripts compare 128 replies with Redis 7.2, all agreeing.

### Scenario: Profile
* **When:** `HSET user:1001 name Ada plan pro visits 0`
* **Then:** `HINCRBY user:1001 visits 1` replies `:1`
* **And:** `HGETALL user:1001` returns the three pairs — in insertion order, byte for byte with Redis
* **Automated:** `store/src/commonTest/kotlin/io/github/youndie/kesh/store/HashCommandsTest.kt::a profile counts its visits`

### Scenario: Last field
* **Given:** a hash with one field
* **When:** `HDEL` removes it
* **Then:** `EXISTS` on the key replies `:0`
* **Automated:** `store/src/commonTest/kotlin/io/github/youndie/kesh/store/HashCommandsTest.kt::the key goes with its last field`

### Scenario: Encoding boundary
* **Given:** a hash one field below the packed threshold
* **When:** one field is added, then two are removed
* **Then:** every reply matches the oracle on both sides of the conversion
* **Automated:** `conformance/scripts/hashes/encoding.redis` — 512 fields compared exactly, then 513 and 511 as pairs; the conversion itself in `store/src/commonTest/kotlin/io/github/youndie/kesh/store/HashValueTest.kt::the 513th field converts and nothing converts back`

## 6. Out of scope

* Per-field expiry (`HEXPIRE` and friends, Redis 7.4) — not in the brief, and not in the oracle's
  version.
* `HSETNX`, `HMSET`, `HSTRLEN`, `HRANDFIELD` — Redis has them, the brief's §6 does not list them.

## 7. Quirks

* **`HINCRBYFLOAT` computes in `Double`**, as `INCRBYFLOAT` does ([feature-strings](feature-strings.md)
  quirks): Redis's `long double` answers differently where x86's extra bits round an error away.
* **`OBJECT ENCODING` does not exist** in kesh; the encoding shows only through reply order.

---

Why it is built this way, and what is still a hypothesis: [research-architecture](../research/research-architecture.md).
