package org.tatrman.kantheon.kyklop.routing

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe

class ConsistentHashRingSpec :
    StringSpec({

        "empty ring returns null" {
            ConsistentHashRing<String>(nodes = emptyList()).nodeFor("anything") shouldBe null
        }

        "same key + same node set → same node across calls" {
            val ring =
                ConsistentHashRing(
                    listOf(
                        "node-a" to "A",
                        "node-b" to "B",
                        "node-c" to "C",
                    ),
                )
            val a = ring.nodeFor("session-42")
            val b = ring.nodeFor("session-42")
            val c = ring.nodeFor("session-42")
            (a == b && b == c) shouldBe true
        }

        "different keys distribute across nodes (not all on one)" {
            val ring =
                ConsistentHashRing(
                    listOf(
                        "node-a" to "A",
                        "node-b" to "B",
                        "node-c" to "C",
                    ),
                )
            val counts = mutableMapOf("A" to 0, "B" to 0, "C" to 0)
            for (i in 0 until 1000) {
                counts.merge(ring.nodeFor("session-$i")!!, 1, Int::plus)
            }
            // Loose check: every node got *some* traffic. The default 128 virtual nodes give
            // reasonable distribution, but we don't pin a tight band here.
            counts.values.forEach { (it > 100) shouldBe true }
        }

        "node removed → only keys that hashed to it move; the rest stay" {
            val before =
                ConsistentHashRing(
                    listOf(
                        "node-a" to "A",
                        "node-b" to "B",
                        "node-c" to "C",
                    ),
                )
            val after =
                ConsistentHashRing(
                    listOf(
                        "node-a" to "A",
                        "node-b" to "B",
                    ),
                )

            val keys = (0 until 1000).map { "k-$it" }
            val unchangedNotOnGone =
                keys.count { k ->
                    val pre = before.nodeFor(k)
                    val post = after.nodeFor(k)
                    pre != "C" && pre == post
                }
            val movedFromGone = keys.count { before.nodeFor(it) == "C" }
            val movedNotFromGone =
                keys.count { k -> before.nodeFor(k) != "C" && before.nodeFor(k) != after.nodeFor(k) }

            // Every key that *was* on the removed node moved.
            (movedFromGone > 0) shouldBe true
            // No key that *wasn't* on the removed node moved (that's the consistency guarantee).
            movedNotFromGone shouldBe 0
            unchangedNotOnGone shouldBe (keys.size - movedFromGone)
        }

        "node added → only a fraction of keys move (~1/N)" {
            val before =
                ConsistentHashRing(
                    listOf(
                        "node-a" to "A",
                        "node-b" to "B",
                    ),
                )
            val after =
                ConsistentHashRing(
                    listOf(
                        "node-a" to "A",
                        "node-b" to "B",
                        "node-c" to "C",
                    ),
                )

            val n = 1000
            val keys = (0 until n).map { "k-$it" }
            val moved = keys.count { k -> before.nodeFor(k) != after.nodeFor(k) }

            // Theory: with K nodes growing to K+1, about 1/(K+1) of keys move. K=2 → ~33% move.
            // We require < 55% as a loose upper bound (variance of SHA-256 distribution over
            // small N is non-trivial); the point of the test is "minimal reshuffling", not
            // "exactly 1/3".
            (moved.toDouble() / n) shouldBeLessThan 0.55
            // And of course every moved key now lands on the newcomer.
            keys.count { k -> before.nodeFor(k) != after.nodeFor(k) && after.nodeFor(k) != "C" } shouldBe 0
        }

        "ring is deterministic across separate instances (same inputs → same mapping)" {
            // Cold-start consistency: a fresh dispatcher with the same worker set picks the same
            // pod for a given session, no shared state needed.
            val r1 = ConsistentHashRing(listOf("a" to 1, "b" to 2, "c" to 3))
            val r2 = ConsistentHashRing(listOf("a" to 1, "b" to 2, "c" to 3))
            (0 until 100).forEach { i ->
                r1.nodeFor("key-$i") shouldBe r2.nodeFor("key-$i")
            }
        }

        "node order in the constructor doesn't affect the mapping" {
            val r1 = ConsistentHashRing(listOf("a" to "A", "b" to "B", "c" to "C"))
            val r2 = ConsistentHashRing(listOf("c" to "C", "a" to "A", "b" to "B"))
            (0 until 100).forEach { i ->
                r1.nodeFor("key-$i") shouldBe r2.nodeFor("key-$i")
            }
        }
    })
