// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking

/**
 * RV-P1.4 T6 — the overlay slot: the third layer, defined and empty.
 *
 * The bar the task sets is that the RV-P6 store "plugs in here without touching resolution code",
 * which is not something a no-op can demonstrate on its own. So these tests drive a **fake store**
 * that does everything contracts §5 says an `OverlayEntry` can do — POSITIVE additions, NEGATIVE
 * suppression, a version — and assert the matcher already behaves correctly for all of it. If any
 * of that needed a change in `FuzzyMatcher` at RV-P6, one of these would be failing now.
 */
class OverlayLayerTest :
    StringSpec({

        /**
         * The test fake the task calls for — an in-memory overlay standing in for the RV-P6 store.
         * Deliberately shaped like the real one: entries are (term → target) with polarity, scoped
         * to this estate, and it reports a version.
         */
        class FakeOverlay(
            private val version: String? = "overlay-1",
            private val positives: List<Triple<String, String, Double>> = emptyList(),
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
                val term = TextNormalizer.canonical(request.term)
                return OverlayVerdict(
                    additions =
                        positives
                            .filter { (t, _, _) -> TextNormalizer.canonical(t) == term }
                            .map { (t, target, score) ->
                                FuzzyMatchResult(
                                    candidateId = "learned:$target:$t",
                                    candidate = t,
                                    score = score,
                                    category = target,
                                    source = SourceTag.LEARNED,
                                    targetRef = target,
                                    provenance = Provenance("overlay", "LEARNED_ALIAS", score),
                                )
                            },
                    suppressedTargets = negatives[term] ?: emptySet(),
                )
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
            // If the call site were conditional on "an overlay exists", RV-P6 would have to add it
            // back, which is exactly the resolution-code change T6 exists to avoid.
            val counting =
                object : OverlayStore by NoopOverlayStore {
                    var seen = 0

                    override suspend fun consult(request: OverlayRequest): OverlayVerdict {
                        seen++
                        return OverlayVerdict.EMPTY
                    }
                }
            runBlocking {
                FuzzyMatcher(repo(declared, counting)).match("obrat", null, AlgorithmType.TATRMAN, 10)
                counting.seen shouldBe 1
            }
        }

        // ---- POSITIVE entries ------------------------------------------------------------------

        "a learned alias joins the answer, tagged LEARNED and ranked with everything else" {
            val store = FakeOverlay(positives = listOf(Triple("tržba", "md.net", 0.99)))
            runBlocking {
                val hits = FuzzyMatcher(repo(declared, store)).match("tržba", null, AlgorithmType.TATRMAN, 10)

                hits.first().source shouldBe SourceTag.LEARNED
                hits.first().targetRef shouldBe "md.net"
                hits.first().provenance.producer shouldBe "overlay"
            }
        }

        "the overlay is consulted AFTER dispatch, so a learned alias is not gated by an authored method" {
            // A learned entry has no author. Running it through the RV-32 gate would discard exactly
            // the aliases users taught the estate, on the grounds that the estate's authors never
            // wrote them down.
            val strict = listOf(Candidate.vocabulary("t1", "výroba", "md.vyroba", SourceTag.DECLARED, "EXACT"))
            val store = FakeOverlay(positives = listOf(Triple("vyroba", "md.vyroba", 0.9)))
            runBlocking {
                val hits = FuzzyMatcher(repo(strict, store)).match("vyroba", null, AlgorithmType.TATRMAN, 10)

                // The declared EXACT term is still refused for the unaccented query (T4)…
                hits.none { it.source == SourceTag.DECLARED } shouldBe true
                // …and the learned alias for the same target still lands.
                hits.single().source shouldBe SourceTag.LEARNED
            }
        }

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

        "a NEGATIVE entry for another target leaves this one bindable" {
            val store = FakeOverlay(negatives = mapOf("čistý obrat" to setOf("md.gross")))
            runBlocking {
                FuzzyMatcher(repo(declared, store))
                    .match("čistý obrat", null, AlgorithmType.TATRMAN, 10)
                    .single()
                    .autoBindable shouldBe true
            }
        }

        "both polarities in one verdict: the learned alias lands and the denied term is flagged" {
            val store =
                FakeOverlay(
                    positives = listOf(Triple("čistý obrat", "md.other", 0.97)),
                    negatives = mapOf("čistý obrat" to setOf("md.net")),
                )
            runBlocking {
                val hits = FuzzyMatcher(repo(declared, store)).match("čistý obrat", null, AlgorithmType.TATRMAN, 10)

                hits.map { it.targetRef } shouldContainExactlyInAnyOrder listOf("md.net", "md.other")
                hits.single { it.targetRef == "md.net" }.autoBindable shouldBe false
                hits.single { it.targetRef == "md.other" }.source shouldBe SourceTag.LEARNED
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

        // ---- bounds ----------------------------------------------------------------------------

        "additions respect the caller's limit" {
            val store =
                FakeOverlay(
                    positives =
                        listOf(
                            Triple("obrat", "md.a", 0.99),
                            Triple("obrat", "md.b", 0.98),
                            Triple("obrat", "md.c", 0.97),
                        ),
                )
            runBlocking {
                FuzzyMatcher(repo(declared, store)).match("obrat", null, AlgorithmType.TATRMAN, 2).size shouldBe 2
            }
        }
    })
