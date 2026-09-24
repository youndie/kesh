package io.github.youndie.kesh.store

import io.github.youndie.kesh.store.keyspace.Entry
import io.github.youndie.kesh.store.keyspace.Keyspace

/**
 * The one database (brief §3: `SELECT 0` only), with Redis's lazy expiry: a key whose time has passed
 * is deleted the moment anything looks it up, and is never returned (`redis/redis@7.2!/src/db.c` —
 * `expireIfNeeded`, `keyIsExpired`).
 *
 * Every command runs against one instant, [now], fixed when the command starts — Redis's
 * `commandTimeSnapshot` — so a command never sees a key alive in one step and gone in the next.
 * "Expired" is `now > expireAt`, strictly, as in `keyIsExpired`.
 */
class Db(
    seed: Int = 0,
) {
    val keyspace = Keyspace(seed)

    /** The command's instant, in milliseconds since the epoch. Set by the dispatcher per command. */
    var now: Long = 0

    /** Entries in the table, expired or not — what `DBSIZE` counts, as Redis's `dictSize` does. */
    val size: Int get() = keyspace.size

    /** The live entry for [key], deleting it first if it has expired. */
    fun lookup(key: ByteArray): Entry? {
        val entry = keyspace.get(key) ?: return null
        if (isExpired(entry)) {
            keyspace.remove(key)
            return null
        }
        return entry
    }

    fun isExpired(entry: Entry): Boolean = entry.expireAt != Entry.NO_EXPIRY && now > entry.expireAt

    /**
     * Sets [key] to [value]. The expiry is cleared unless [keepTtl] — Redis's `setKey` without
     * `SETKEY_KEEPTTL`, which is every `SET` without `KEEPTTL` and every `MSET` and `GETSET`.
     */
    fun set(
        key: ByteArray,
        value: Any,
        keepTtl: Boolean = false,
    ): Entry {
        val existing = lookup(key)
        if (existing != null) {
            existing.value = value
            if (!keepTtl) existing.expireAt = Entry.NO_EXPIRY
            return existing
        }
        return keyspace.put(key, value)
    }

    /** Removes [key] if it is live; `false` if it was absent or had expired (then it is gone too). */
    fun delete(key: ByteArray): Boolean = lookup(key) != null && keyspace.remove(key) != null

    fun clear() = keyspace.clear()
}
