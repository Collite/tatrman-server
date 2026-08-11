// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.tatrman.fuzzy.config.AppConfig
import org.tatrman.fuzzy.config.LexiconConfig
import org.tatrman.fuzzy.config.LoaderSourceConfig
import org.tatrman.fuzzy.config.MetadataConfig
import org.tatrman.fuzzy.config.NlpConfig
import org.tatrman.fuzzy.config.TokenBasedConfig
import org.tatrman.fuzzy.loader.LoaderSource

/**
 * RV-P7.3 T3/T4 — the overlay through the **real repository**: the third clock, the merge into the
 * one index, and the version that rides every response.
 *
 * `OverlayArchiveSourceTest` proves the reader; `LearnedLayerSpec` proves the matcher. This is the
 * seam between them — the part where a learned candidate has to actually be in the index a query
 * searches, at a version the tuple can name, and where the two must move together.
 */
private const val LEARNED = "tržba"

private const val TARGET = "md.measure.net"

/** One overlay's content, frozen — what a real store's `pinned()` hands back. */
private class Frozen(
    private val at: Pair<Long, List<Pair<String, String>>>?,
) : OverlayStore {
    override fun version(): String? = at?.first?.toString()

    override fun hash(): String = at?.let { "overlay-${it.first}" } ?: ""

    override suspend fun learned(): Map<String, List<Candidate>> =
        at
            ?.second
            ?.groupBy { (_, ref) -> ref }
            ?.mapValues { (ref, rows) ->
                rows.map { (term, _) ->
                    Candidate.vocabulary(
                        "learned:$ref:cs:$term",
                        term,
                        ref,
                        SourceTag.LEARNED,
                        null,
                        TargetClass.MODEL_OBJECT,
                    )
                }
            } ?: emptyMap()

    override suspend fun consult(request: OverlayRequest) = OverlayVerdict.EMPTY

    override fun pinned(): OverlayStore = this
}

/**
 * A fake overlay whose content the test moves, to drive the third clock.
 *
 * It implements [OverlayStore.pinned] properly rather than taking the `= this` default, and that
 * is not fake-plumbing — it is the contract. The default is only correct for a store that cannot
 * change beneath a reader; **this one can**, exactly like the real `OverlayArchiveSource`, so
 * taking the default would make it a fake that models the one property under test wrongly.
 */
private class MovableOverlay : OverlayStore {
    @Volatile
    var current: Pair<Long, List<Pair<String, String>>>? = null

    @Volatile
    var learnedCalls = 0

    override fun version(): String? = current?.first?.toString()

    override fun hash(): String = current?.let { "overlay-${it.first}" } ?: ""

    override suspend fun learned(): Map<String, List<Candidate>> {
        learnedCalls++
        return Frozen(current).learned()
    }

    override suspend fun consult(request: OverlayRequest) = OverlayVerdict.EMPTY

    override fun pinned(): OverlayStore = Frozen(current)
}

private class Members(
    private val rows: Map<String, List<Candidate>>,
) : LoaderSource {
    override suspend fun loadNextCache(): Map<String, List<Candidate>> = rows
}

private fun cfg() =
    AppConfig(
        serverPort = 7113,
        grpcPort = 7213,
        grpcReflectionEnabled = false,
        refreshIntervalSeconds = 0,
        tokenBasedConfig = TokenBasedConfig(),
        nlp = NlpConfig(),
        loaderSource = LoaderSourceConfig(source = "static"),
        metadata = MetadataConfig(),
        lexicon = LexiconConfig(),
    )

class OverlayServingTest :
    StringSpec({

        "a learned entry reaches the index and answers a query, tagged LEARNED" {
            runBlocking {
                val overlay = MovableOverlay().apply { current = 1L to listOf(LEARNED to TARGET) }
                val repo = StringRepository(cfg(), Members(emptyMap()), overlayStore = overlay)
                repo.forceRefresh()

                val hit = FuzzyMatcher(repo).match(LEARNED, null, AlgorithmType.TATRMAN, 10).single()

                hit.source shouldBe SourceTag.LEARNED
                hit.targetRef shouldBe TARGET
                withClue("the index is the point: it is retrieved and scored, not injected after") {
                    hit.provenance.producer shouldBe "fuzzy"
                }
            }
        }

        "the overlay version rides the tuple, and its absence still means absence" {
            runBlocking {
                val overlay = MovableOverlay().apply { current = 9L to listOf(LEARNED to TARGET) }
                val withOverlay = StringRepository(cfg(), Members(emptyMap()), overlayStore = overlay)
                withOverlay.forceRefresh()
                withOverlay.layerVersions().overlayVersion shouldBe "9"

                val without = StringRepository(cfg(), Members(emptyMap()))
                without.forceRefresh()
                withClue("a pre-P7 estate parses unchanged — absence is the contract, not an empty string") {
                    without.layerVersions().overlayVersion.shouldBeNull()
                }
            }
        }

        // The third clock: same discipline the declared layer has had since P1.4 T5.
        "an unchanged overlay is not re-fetched, and a changed one is" {
            runBlocking {
                val overlay = MovableOverlay().apply { current = 1L to listOf(LEARNED to TARGET) }
                val repo = StringRepository(cfg(), Members(emptyMap()), overlayStore = overlay)

                repo.forceRefresh()
                repo.forceRefresh()
                repo.forceRefresh()
                withClue("three member refreshes, one overlay load — the clocks are separate") {
                    overlay.learnedCalls shouldBe 1
                }

                overlay.current = 2L to listOf(LEARNED to TARGET, "obrat" to "md.measure.gross")
                repo.forceRefresh()
                overlay.learnedCalls shouldBe 2
                repo.layerVersions().overlayVersion shouldBe "2"
            }
        }

        "a reload replaces the learned layer rather than accumulating it" {
            runBlocking {
                val overlay = MovableOverlay().apply { current = 1L to listOf(LEARNED to TARGET) }
                val repo = StringRepository(cfg(), Members(emptyMap()), overlayStore = overlay)
                repo.forceRefresh()

                // The estate's snapshot build invalidated the entry; the next export omits it.
                overlay.current = 2L to emptyList()
                repo.forceRefresh()

                withClue("an entry RV-20 retired must actually stop answering") {
                    FuzzyMatcher(repo).match(LEARNED, null, AlgorithmType.TATRMAN, 10).isEmpty() shouldBe true
                }
            }
        }

        // T4's version pinning, at the level where it can actually be observed.
        "the served overlay and the tuple's version are published together" {
            runBlocking {
                val overlay = MovableOverlay().apply { current = 1L to listOf(LEARNED to TARGET) }
                val repo = StringRepository(cfg(), Members(emptyMap()), overlayStore = overlay)
                repo.forceRefresh()

                // The Golem exports a new overlay. The store now KNOWS about version 2, but no
                // refresh has run, so nothing built from it has been published.
                overlay.current = 2L to listOf("obrat" to "md.measure.gross")

                withClue("the tuple must name the overlay that answers, not the one that exists") {
                    repo.layerVersions().overlayVersion shouldBe "1"
                    FuzzyMatcher(repo).match(LEARNED, null, AlgorithmType.TATRMAN, 10).single().targetRef shouldBe
                        TARGET
                }

                repo.forceRefresh()
                repo.layerVersions().overlayVersion shouldBe "2"
            }
        }

        "a reload between two spans of one batch cannot mix overlays" {
            runBlocking {
                val overlay = MovableOverlay().apply { current = 1L to listOf(LEARNED to TARGET) }
                val repo = StringRepository(cfg(), Members(emptyMap()), overlayStore = overlay)
                repo.forceRefresh()
                val pinned = repo.overlay()

                // A refresh lands mid-flight.
                overlay.current = 2L to emptyList()
                repo.forceRefresh()

                withClue("the pin a request took is answered from, whatever the store has moved on to") {
                    pinned.version() shouldBe "1"
                    pinned.learned().keys.single() shouldBe TARGET
                }
            }
        }

        "a member-only estate is untouched — no overlay, no widening, no version" {
            runBlocking {
                val members = mapOf("city" to listOf(Candidate.fromValues("1", "Praha")))
                val repo = StringRepository(cfg(), Members(members))
                repo.forceRefresh()

                repo.servesDeclaredLayer() shouldBe false
                repo.layerVersions().overlayVersion.shouldBeNull()
                FuzzyMatcher(repo).match("Praha", null, AlgorithmType.TATRMAN, 10).single().source shouldBe
                    SourceTag.MEMBER
            }
        }

        "an overlay alone widens the scoring window, because its rows can be narrowed after scoring" {
            runBlocking {
                val overlay = MovableOverlay().apply { current = 1L to listOf(LEARNED to TARGET) }
                val repo = StringRepository(cfg(), Members(emptyMap()), overlayStore = overlay)
                repo.forceRefresh()

                withClue("a learned row carries a target class, so T5's filter can reject it") {
                    repo.servesDeclaredLayer() shouldBe true
                }
            }
        }
    })
