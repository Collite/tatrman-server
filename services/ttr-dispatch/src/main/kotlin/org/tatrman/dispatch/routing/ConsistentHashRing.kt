// SPDX-License-Identifier: Apache-2.0
package org.tatrman.dispatch.routing

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.TreeMap

/**
 * DF-D03 / Phase 06 C2 — small consistent-hash ring used by the dispatcher to pin a routing key
 * (typically `session_id`) to a worker pod *deterministically* across calls, *consistently*
 * across pod join/leave events:
 *
 *   - Same key + same node set → same node (across processes; the ring depends only on the
 *     node identifier string, no random salt).
 *   - Node leaves → only keys that previously hashed to it move; the rest stay.
 *   - Node joins → only keys that fall into the new node's hash range move (~N/K of them for
 *     uniform virtual-node distribution); the rest stay.
 *
 * Implementation: SHA-256 over `"$node#$replica"` → first 8 bytes as a long; insert each replica
 * at its hash position in a `TreeMap<Long, T>`; lookup walks `ceilingEntry` (with wrap-around at
 * Long.MAX_VALUE). `replicasPerNode = 128` gives reasonable distribution for the small node
 * counts we deal with (1–10 workers per role); the ring is rebuilt on every call (cheap because
 * node sets are tiny), so callers don't need to coordinate updates with the registry.
 */
internal class ConsistentHashRing<T>(
    private val nodes: List<Pair<String, T>>,
    private val replicasPerNode: Int = DEFAULT_REPLICAS,
) {
    private val ring: TreeMap<Long, T> =
        TreeMap<Long, T>().apply {
            for ((id, value) in nodes) {
                for (replica in 0 until replicasPerNode) {
                    put(hash("$id#$replica"), value)
                }
            }
        }

    /** Returns the node a [key] maps to, or `null` when the ring is empty. */
    fun nodeFor(key: String): T? {
        if (ring.isEmpty()) return null
        val h = hash(key)
        val entry = ring.ceilingEntry(h) ?: ring.firstEntry()
        return entry.value
    }

    fun isEmpty(): Boolean = ring.isEmpty()

    private fun hash(input: String): Long {
        // SHA-256 is overkill for distribution-quality and not for security here; we want a
        // stable, uniform 64-bit avalanche over arbitrary input strings without pulling in
        // Guava/MurmurHash3 just for this.
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return ByteBuffer.wrap(bytes, 0, 8).long
    }

    companion object {
        const val DEFAULT_REPLICAS: Int = 128
    }
}
