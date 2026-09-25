package io.github.youndie.kesh.bench.load

import io.github.youndie.kesh.bench.HashEntry
import io.github.youndie.kesh.bench.ListEntry
import io.github.youndie.kesh.bench.Part
import io.github.youndie.kesh.bench.ReferenceDataset
import io.github.youndie.kesh.bench.SetEntry
import io.github.youndie.kesh.bench.SortedSetEntry
import io.github.youndie.kesh.bench.StringEntry

/**
 * The keys of the reference dataset, rebuilt from its seed — what the load addresses (B-17). A key
 * generator such as `memtier_benchmark` derives keys from a number; §5a's sessions are
 * `session:<uuid>`, so the load has to know the keys the dataset actually holds.
 *
 * With each key it keeps what a read needs to hit: one field of a profile (`HGET`), one member of a
 * tag set (`SISMEMBER`). Counters are kept although they expire two minutes after the load: `INCR`
 * re-creates them, which is what §5a's "constantly replaced" means.
 */
class KeyCatalogue(
    val sessions: List<ByteArray>,
    val profiles: List<ByteArray>,
    val profileFields: List<ByteArray>,
    val counters: List<ByteArray>,
    val feeds: List<ByteArray>,
    val tags: List<ByteArray>,
    val tagMembers: List<ByteArray>,
    val boards: List<ByteArray>,
    /** Members `u<id>` a leaderboard may hold: one per profile. */
    val boardMembers: Int,
) {
    companion object {
        fun of(
            seed: Long,
            scale: Double,
        ): KeyCatalogue {
            val sessions = ArrayList<ByteArray>()
            val profiles = ArrayList<ByteArray>()
            val fields = ArrayList<ByteArray>()
            val counters = ArrayList<ByteArray>()
            val feeds = ArrayList<ByteArray>()
            val tags = ArrayList<ByteArray>()
            val members = ArrayList<ByteArray>()
            val boards = ArrayList<ByteArray>()
            ReferenceDataset(seed, scale).entries().forEach { entry ->
                when (entry.part) {
                    Part.SESSIONS -> {
                        sessions += entry.key
                    }

                    Part.PROFILES -> {
                        profiles += entry.key
                        fields += (entry as HashEntry).fields.first().first
                    }

                    Part.COUNTERS -> {
                        counters += (entry as StringEntry).key
                    }

                    Part.FEEDS -> {
                        feeds += (entry as ListEntry).key
                    }

                    Part.TAGS -> {
                        tags += entry.key
                        members += (entry as SetEntry).members.first()
                    }

                    Part.LEADERBOARDS -> {
                        boards += (entry as SortedSetEntry).key
                    }
                }
            }
            return KeyCatalogue(sessions, profiles, fields, counters, feeds, tags, members, boards, profiles.size)
        }
    }
}
