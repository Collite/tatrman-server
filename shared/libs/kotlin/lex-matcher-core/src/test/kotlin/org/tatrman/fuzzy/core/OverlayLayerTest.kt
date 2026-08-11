// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking

/**
 * RV-P1.4 T6 — the overlay slot: the third layer, defined and empty.
 *
 * The bar the task set was that the RV-P7 store "plugs in here without touching resolution code",
 * which is not something a no-op can demonstrate on its own. So these tests drive a **fake store**
 * that does what contracts §5 says an `OverlayEntry` can do, and assert the matcher already behaves
 * correctly for it.
 *
 * **Amended at RV-P7.3.** The prediction held for NEGATIVE entries — every suppression test below
 * is untouched, and the two that pin the *ordering* against `recomputeMargins` are the reason the
 * widening did not break anything. It did not hold for POSITIVE ones: `OverlayVerdict.additions`
 * let a store hand back rows nobody scored, and P7.3 T2(a) requires the engine's own
 * `in_class_score`, so positives now LOAD instead (see [OverlayStore.learned]). The four addition
 * tests that lived here moved to `LearnedLayerSpec` and got stronger there — they can now assert
 * a learned row scores identically to the same row declared, which a made-up number could only
 * match by coincidence.
 */
class OverlayLayerTest :
    StringSpec({

        /**
         * The test fake the task calls for — an in-memory overlay standing in for the RV-P7 store.
         * Deliberately shaped like the real one: entries are (term → target) with polarity, scoped
         * to this estate, and it reports a version.
         */
        class FakeOverlay(
            private val version: String? = "overlay-1",
            private val negatives: Map<String, Set<String>> = emptyMap(),
        ) : OverlayStore {
            var consultations = 0
                private set
            var lastRequest: OverlayRequest? = null
                private set

            override fun version() = version

            override suspend fun consult(request: OverlayRequest): OverlayVerdict {
                consultations++
                lastRequest = request
                return OverlayVerdict(negatives[TextNormalizer.canonical(request.term)] ?: emptySet())
            }
        }

        fun repo(
            candidates: List<Candidate>,
            store: OverlayStore = NoopOverlayStore,
        ): MatchRepository {
            val index = TokenIndex(candidates)
            return object : MatchRepository {
                override fun getCandidates(category: String?) = candidates

                override fun getTokenIndex(category: String?) = index

                override fun getDistanceCache(category: String?) = DistanceCache()

                override fun getVocabulary(category: String?) = TokenVocabulary(candidates)

                override fun vocabularyVersion() = "v1"

                override fun layerVersions() = LayerVersions(overlayVersion = store.version())

                override fun overlay() = store
            }
        }

        val declared =
            listOf(
                Candidate.vocabulary("t1", "čistý obrat", "md.net", SourceTag.DECLARED, "TOKENS"),
            )

        // ---- the empty slot --------------------------------------------------------------------

        "with the no-op store the tuple omits the overlay and results are untouched" {
            runBlocking {
                val repository = repo(declared)
                val hits = FuzzyMatcher(repository).match("čistý obrat", null, AlgorithmType.TATRMAN, 10)

                repository.layerVersions().overlayVersion.shouldBeNull()
                hits.single().source shouldBe SourceTag.DECLARED
                hits.single().autoBindable shouldBe true
            }
        }

        "the no-op store is still consulted — the seam is live, not bypassed" {
            // If the call site were conditional on "an overlay exists", RV-P7 would have to add it
            // back, which is exactly the resolution-code change T6 exists to avoid.
            val counting =
                object : OverlayStore by NoopOverlayStore {
                    var seen = 0

                    override suspend fun consult(request: OverlayRequest): OverlayVerdict {
                        seen++
                        return OverlayVerdict.EMPTY
                    }

                    // ⚠ `by NoopOverlayStore` delegates EVERY member, `pinned()` included — so
                    // without this the matcher pins the delegate and consults *that*, and this
                    // counter never moves. A trap worth naming rather than quietly working
                    // around: any decorator over an OverlayStore has to decide what its pin is,
                    // and "the thing I delegate to" is almost never the right answer.
                    override fun pinned(): OverlayStore = this
                }
            runBlocking {
                FuzzyMatcher(repo(declared, counting)).match("obrat", null, AlgorithmType.TATRMAN, 10)
                counting.seen shouldBe 1
            }
        }

        // ---- what the store is asked ------------------------------------------------------------

        "the store sees the query, the scope, and what the other layers found" {
            val store = FakeOverlay()
            runBlocking {
                FuzzyMatcher(repo(declared, store)).match("obrat", "md.net", AlgorithmType.TATRMAN, 10)

                val request = store.lastRequest!!
                request.term shouldBe "obrat"
                request.categories shouldContainExactly listOf("md.net")
                // Without the candidates in hand, a NEGATIVE entry cannot say "not that one".
                request.candidates.map { it.targetRef } shouldContainExactly listOf("md.net")
            }
        }

        // ---- NEGATIVE entries ------------------------------------------------------------------

        "a NEGATIVE entry flags a candidate unbindable — it does not delete it" {
            // RV-2: the lattice's job is to represent what is uncertain. Dropping the candidate
            // would also make a wrong negative both unrecoverable and invisible.
            val store = FakeOverlay(negatives = mapOf("čistý obrat" to setOf("md.net")))
            runBlocking {
                val hits = FuzzyMatcher(repo(declared, store)).match("čistý obrat", null, AlgorithmType.TATRMAN, 10)

                hits.single().targetRef shouldBe "md.net"
                hits.single().autoBindable shouldBe false
                // The score is the engine's, unchanged — the overlay states a fact, it does not rank.
                (hits.single().score > 0.0) shouldBe true
            }
        }

        "a NEGATIVE entry survives the LOOKUP path, where the margin is recomputed after the merge" {
            // The regression this exists to prevent. `lookup` re-asks the RV-32 margin over the
            // union of the requested categories, and that recompute derives `autoBindable` from the
            // margin ALONE — so running it after the overlay silently re-enabled auto-binding on a
            // target the estate had explicitly denied. Every path now orders the overlay last.
            //
            // Only `match()` was covered before, which is the one path with no re-margin, so the
            // suite could not see it.
            val store = FakeOverlay(negatives = mapOf("čistý obrat" to setOf("md.net")))
            runBlocking {
                val hits = FuzzyMatcher(repo(declared, store)).lookup(LookupQuery("čistý obrat")).candidates

                hits.single().targetRef shouldBe "md.net"
                hits.single().autoBindable shouldBe false
            }
        }

        "a NEGATIVE entry survives the BATCH-MATCH span path too" {
            val store = FakeOverlay(negatives = mapOf("čistý obrat" to setOf("md.net")))
            runBlocking {
                val out =
                    FuzzyMatcher(repo(declared, store))
                        .batchMatch(listOf(SpanQuery("čistý obrat", emptyList(), 10)))

                out.results
                    .single()
                    .matches
                    .single()
                    .autoBindable shouldBe false
            }
        }

        "the overlay is consulted ONCE per lookup, not once per requested category" {
            // A NEGATIVE entry is a statement about a candidate, so a store handed one category's
            // slice at a time could not make it — and once RV-P7 backs this with a real store, a
            // consult per category is a round trip per category.
            val store = FakeOverlay()
            runBlocking {
                FuzzyMatcher(repo(declared, store))
                    .lookup(LookupQuery("obrat", categories = listOf("md.net", "md.gross", "op:trend")))

                store.consultations shouldBe 1
                store.lastRequest!!.categories shouldContainExactly listOf("md.net", "md.gross", "op:trend")
            }
        }

        "a NEGATIVE entry for another target leaves this one bindable" {
            val store = FakeOverlay(negatives = mapOf("čistý obrat" to setOf("md.gross")))
            runBlocking {
                FuzzyMatcher(repo(declared, store))
                    .match("čistý obrat", null, AlgorithmType.TATRMAN, 10)
                    .single()
                    .autoBindable shouldBe true
            }
        }

        // ---- the version -----------------------------------------------------------------------

        "a present overlay reports its version into the tuple" {
            val repository = repo(declared, FakeOverlay(version = "overlay-42"))

            repository.layerVersions().overlayVersion shouldBe "overlay-42"
        }

        "a store may report no version, and that is absence, not an empty string" {
            val repository = repo(declared, FakeOverlay(version = null))

            repository.layerVersions().overlayVersion.shouldBeNull()
        }
    })
