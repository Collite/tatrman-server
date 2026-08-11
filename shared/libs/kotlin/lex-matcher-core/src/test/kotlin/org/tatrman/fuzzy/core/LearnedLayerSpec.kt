// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking

/**
 * RV-P7.3 T2 — **the overlay stops being a slot and becomes a layer.**
 *
 * `OverlayLayerTest` (RV-P1.4 T6) proves the seam is live and that a NEGATIVE entry survives every
 * path. This spec proves the half that P1.4 could not: a POSITIVE entry is now **loaded**, so it
 * is scored by the engine rather than by whatever number a store felt like returning — and the
 * suppression it lives beside now runs **before** the uniqueness margin, so a denied candidate
 * stops counting as a rival to the one the user actually meant.
 *
 * The two are one change. Loading the positives is what empties the consult of everything except
 * polarity, and an overlay that only says "not that one" is one the margin can safely be
 * recomputed after.
 */
private const val LEARNED_TERM = "tržba"

private const val TARGET = "md.measure.net"

/** An overlay with POSITIVE entries loaded as candidates and NEGATIVE ones consulted. */
private class FakeOverlay(
    private val version: String? = "7",
    private val positives: List<Pair<String, String>> = emptyList(),
    private val negatives: Map<String, Set<String>> = emptyMap(),
    private val targetClass: TargetClass? = TargetClass.MODEL_OBJECT,
) : OverlayStore {
    override fun version() = version

    override fun hash() = "overlay-$version"

    override suspend fun learned(): Map<String, List<Candidate>> =
        positives
            .groupBy { (_, ref) -> ref }
            .mapValues { (ref, rows) ->
                rows.map { (term, _) ->
                    // The shape the loader produces: no authored method (nobody authored a learned
                    // alias), the target ref as the category key, and a class it must state.
                    Candidate.vocabulary("learned:$ref:cs:$term", term, ref, SourceTag.LEARNED, null, targetClass)
                }
            }

    override suspend fun consult(request: OverlayRequest): OverlayVerdict =
        OverlayVerdict(negatives[TextNormalizer.canonical(request.term)] ?: emptySet())
}

/**
 * The repository contract the real [StringRepository] implements: the overlay's POSITIVE
 * candidates are **merged into the index** alongside the declared and member layers, so retrieval
 * and scoring never learn that a third layer exists.
 */
private fun repo(
    declared: List<Candidate> = emptyList(),
    store: OverlayStore = NoopOverlayStore,
): MatchRepository =
    runBlocking {
        val learned = store.learned()
        val all = declared + learned.values.flatten()
        // The declared layer's convention, which the overlay follows: the target ref IS the
        // category key (see `LexiconArchiveSource`).
        val byCategory = all.groupBy { (it.targetRef ?: "").lowercase() }
        object : MatchRepository {
            override fun getCandidates(category: String?) =
                if (category == null) all else byCategory[category.lowercase()] ?: emptyList()

            override fun getTokenIndex(category: String?) = TokenIndex(getCandidates(category))

            override fun getDistanceCache(category: String?) = DistanceCache()

            override fun getVocabulary(category: String?) = TokenVocabulary(getCandidates(category))

            override fun vocabularyVersion() = "v1"

            override fun layerVersions() = LayerVersions(overlayVersion = store.version())

            override fun overlay() = store

            override fun servesDeclaredLayer() = all.isNotEmpty()
        }
    }

private fun declaredRow(
    value: String,
    ref: String,
    method: String? = "TOKENS",
) = Candidate.vocabulary("lex:$ref:cs:$value", value, ref, SourceTag.DECLARED, method, TargetClass.MODEL_OBJECT)

class LearnedLayerSpec :
    StringSpec({

        // ---- (a) served at LEARNED, scored by the ENGINE ----------------------------------------

        "a learned alias serves tagged LEARNED, and its score is the engine's own" {
            runBlocking {
                val learned = FakeOverlay(positives = listOf(LEARNED_TERM to TARGET))
                val hit =
                    FuzzyMatcher(repo(store = learned))
                        .match(LEARNED_TERM, null, AlgorithmType.TATRMAN, 10)
                        .single()

                hit.source shouldBe SourceTag.LEARNED
                hit.targetRef shouldBe TARGET
                withClue("`producer=fuzzy` is the claim: this row went through retrieval and scoring") {
                    hit.provenance.producer shouldBe "fuzzy"
                    hit.provenance.method shouldBe "TATRMAN"
                }

                // The same value as a DECLARED row must score identically — the layer is evidence,
                // not a scoring rule (P1.4 T2). A store that invented its own number could not
                // make this true except by coincidence.
                val asDeclared =
                    FuzzyMatcher(repo(declared = listOf(declaredRow(LEARNED_TERM, TARGET))))
                        .match(LEARNED_TERM, null, AlgorithmType.TATRMAN, 10)
                        .single()
                hit.score shouldBe asDeclared.score
            }
        }

        // The property a *consulted* positive can never have, and the reason the seam was widened.
        "a learned alias matches fuzzily, because it is in the index like everything else" {
            runBlocking {
                val learned = FakeOverlay(positives = listOf(LEARNED_TERM to TARGET))
                val hits =
                    FuzzyMatcher(repo(store = learned))
                        // Diacritics stripped — the shape a user actually types.
                        .match("trzba", null, AlgorithmType.TATRMAN, 10)

                withClue("an exact-keyed consult would return nothing here") {
                    hits.single().targetRef shouldBe TARGET
                    hits.single().source shouldBe SourceTag.LEARNED
                }
            }
        }

        "a learned row states its class, so a class-scoped lookup can include it — or exclude it" {
            runBlocking {
                val learned = FakeOverlay(positives = listOf(LEARNED_TERM to TARGET))
                val matcher = FuzzyMatcher(repo(store = learned))

                matcher
                    .lookup(LookupQuery(LEARNED_TERM, targetClasses = setOf(TargetClass.MODEL_OBJECT)))
                    .candidates
                    .single()
                    .targetRef shouldBe TARGET
                withClue("a learned alias for a measure must not answer 'which operator is this?'") {
                    matcher
                        .lookup(LookupQuery(LEARNED_TERM, targetClasses = setOf(TargetClass.OPERATOR)))
                        .candidates
                        .isEmpty() shouldBe true
                }
            }
        }

        // ---- (b) layer precedence: both emerge, the classes distinguish them --------------------

        "a term both declared and learned yields BOTH candidates — the matcher hides no layer" {
            runBlocking {
                // The estate declares the term for one target; users taught it another.
                val declared = listOf(declaredRow("obrat", "md.measure.gross"))
                val learned = FakeOverlay(positives = listOf("obrat" to "md.measure.net"))

                val hits = FuzzyMatcher(repo(declared, learned)).match("obrat", null, AlgorithmType.TATRMAN, 10)

                hits.map { it.targetRef } shouldContainExactlyInAnyOrder
                    listOf("md.measure.gross", "md.measure.net")
                withClue("the LAYER is what tells them apart — the gate downstream orders the classes") {
                    hits.single { it.targetRef == "md.measure.gross" }.source shouldBe SourceTag.DECLARED
                    hits.single { it.targetRef == "md.measure.net" }.source shouldBe SourceTag.LEARNED
                }
            }
        }

        "an authored EXACT method does not gate the learned row beside it" {
            runBlocking {
                // P1.4's rule, re-proved now that the learned row goes through dispatch rather than
                // arriving after it: a learned entry has no author, so an authored method cannot
                // reject it. The declared EXACT row is still refused for the unaccented query.
                val declared = listOf(declaredRow("výroba", "md.vyroba", method = "EXACT"))
                val learned = FakeOverlay(positives = listOf("vyroba" to "md.vyroba"))

                val hits = FuzzyMatcher(repo(declared, learned)).match("vyroba", null, AlgorithmType.TATRMAN, 10)

                hits.none { it.source == SourceTag.DECLARED } shouldBe true
                hits.single().source shouldBe SourceTag.LEARNED
            }
        }

        // ---- (c) NEGATIVE suppresses its (term, ref) and only it --------------------------------

        "a NEGATIVE entry suppresses ITS target and leaves the others bindable" {
            runBlocking {
                val declared =
                    listOf(
                        declaredRow("čistý obrat", "md.measure.net"),
                        declaredRow("čistý obrat", "md.measure.gross"),
                    )
                val store = FakeOverlay(negatives = mapOf("čistý obrat" to setOf("md.measure.net")))

                val hits =
                    FuzzyMatcher(repo(declared, store))
                        .lookup(LookupQuery("čistý obrat"))
                        .candidates

                hits.single { it.targetRef == "md.measure.net" }.autoBindable shouldBe false
                withClue("suppression is per (term, ref) — a sibling target is untouched") {
                    hits.single { it.targetRef == "md.measure.gross" }.autoBindable shouldBe true
                }
                withClue("flagged, never removed: a wrong negative must stay visible and recoverable") {
                    hits.size shouldBe 2
                }
            }
        }

        "a NEGATIVE entry suppresses a LEARNED row too — the estate can unlearn" {
            runBlocking {
                val store =
                    FakeOverlay(
                        positives = listOf(LEARNED_TERM to TARGET),
                        negatives = mapOf(LEARNED_TERM to setOf(TARGET)),
                    )
                val hit =
                    FuzzyMatcher(repo(store = store))
                        .match(LEARNED_TERM, null, AlgorithmType.TATRMAN, 10)
                        .single()

                hit.source shouldBe SourceTag.LEARNED
                hit.autoBindable shouldBe false
            }
        }

        // ---- T3's ordering: suppression BEFORE the margin ---------------------------------------

        // The bug this stage exists to prevent, and the one place the list says it will live.
        "a suppressed candidate does not count as a rival when the margin is computed" {
            runBlocking {
                // Two declared TOKENS targets for one term: near-tied, so neither auto-binds.
                val declared =
                    listOf(
                        declaredRow("čerpací stanice", "md.station"),
                        declaredRow("čerpací stanice", "md.depot"),
                    )
                val matcher = FuzzyMatcher(repo(declared))
                val contested = matcher.lookup(LookupQuery("čerpací stanice")).candidates
                withClue("baseline: an unsuppressed tie is exactly what the margin exists to catch") {
                    contested.all { it.autoBindable == false } shouldBe true
                }

                // Now the estate has learned the term does not mean md.depot.
                val store = FakeOverlay(negatives = mapOf("čerpací stanice" to setOf("md.depot")))
                val hits = FuzzyMatcher(repo(declared, store)).lookup(LookupQuery("čerpací stanice")).candidates

                withClue("its only rival is denied, so the survivor is no longer ambiguous") {
                    hits.single { it.targetRef == "md.station" }.autoBindable shouldBe true
                }
                withClue("and the denied one stays denied — the recompute must not re-enable it") {
                    hits.single { it.targetRef == "md.depot" }.autoBindable shouldBe false
                }
            }
        }

        "the margin the survivor reports is measured without the suppressed rival" {
            runBlocking {
                val declared =
                    listOf(
                        declaredRow("čerpací stanice", "md.station"),
                        declaredRow("čerpací stanice", "md.depot"),
                    )
                val store = FakeOverlay(negatives = mapOf("čerpací stanice" to setOf("md.depot")))

                val hits = FuzzyMatcher(repo(declared, store)).lookup(LookupQuery("čerpací stanice")).candidates
                val survivor = hits.single { it.targetRef == "md.station" }

                withClue("unopposed ⇒ the gap is measured from zero, i.e. the row's own score") {
                    survivor.uniquenessMargin shouldBe survivor.score
                }
                withClue("the suppressed row still reports its own margin — it is offered, not hidden") {
                    hits.single { it.targetRef == "md.depot" }.uniquenessMargin shouldNotBe null
                }
            }
        }

        "suppression survives the BATCH-MATCH span path, where the margin is recomputed too" {
            runBlocking {
                val declared =
                    listOf(
                        declaredRow("čerpací stanice", "md.station"),
                        declaredRow("čerpací stanice", "md.depot"),
                    )
                val store = FakeOverlay(negatives = mapOf("čerpací stanice" to setOf("md.depot")))

                val out =
                    FuzzyMatcher(repo(declared, store))
                        .batchMatch(listOf(SpanQuery("čerpací stanice", emptyList(), 10)))
                val matches = out.results.single().matches

                matches.single { it.targetRef == "md.depot" }.autoBindable shouldBe false
                matches.single { it.targetRef == "md.station" }.autoBindable shouldBe true
            }
        }

        // ---- (d) the version rides every response -----------------------------------------------

        "the loaded overlay's version rides the tuple, and its absence is still absence" {
            val loaded = repo(store = FakeOverlay(version = "7"))
            loaded.layerVersions().overlayVersion shouldBe "7"

            withClue("a pre-P7 estate must keep parsing — absence is the contract, not an empty string") {
                repo().layerVersions().overlayVersion shouldBe null
            }
        }

        // ---- the no-overlay estate pays nothing -------------------------------------------------

        "with no overlay the results are untouched, margins and all" {
            runBlocking {
                val declared =
                    listOf(
                        declaredRow("čerpací stanice", "md.station"),
                        declaredRow("čerpací stanice", "md.depot"),
                    )
                val without = FuzzyMatcher(repo(declared)).lookup(LookupQuery("čerpací stanice")).candidates
                val withEmpty =
                    FuzzyMatcher(repo(declared, FakeOverlay(version = null)))
                        .lookup(LookupQuery("čerpací stanice"))
                        .candidates

                withClue("an empty verdict must not trigger the suppression-aware recompute") {
                    withEmpty shouldBe without
                }
            }
        }
    })
