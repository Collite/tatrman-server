// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import kotlinx.coroutines.runBlocking
import org.tatrman.fuzzy.config.AppConfig
import org.tatrman.fuzzy.config.LoaderSourceConfig
import org.tatrman.fuzzy.config.MetadataConfig
import org.tatrman.fuzzy.config.NlpConfig
import org.tatrman.fuzzy.config.TokenBasedConfig
import org.tatrman.fuzzy.loader.LoaderSource

/**
 * RG-P2.S2.T1 — `BatchMatch`: N spans × M categories in ONE call. Results are
 * positional to the spans, each carries the one call-consistent
 * `vocabulary_version`, and a span whose only category is explicit-but-unknown
 * gets an EMPTY slot (the per-slot leak guard, `RG-FUZ-002`).
 */
class BatchMatchTest :
    StringSpec({

        val loader =
            object : LoaderSource {
                override suspend fun loadNextCache(): Map<String, List<Candidate>> =
                    mapOf(
                        "product" to listOf(Candidate.fromValues("p-octavia", "Škoda Octavia")),
                        "measure-term" to
                            listOf(Candidate.vocabulary("t-trzba", "tržba", targetRef = "md.measure.net")),
                    )
            }
        val cfg =
            AppConfig(
                serverPort = 7103,
                grpcPort = 7203,
                grpcReflectionEnabled = false,
                // Manual-refresh mode (<=0): no background refresh loop. With a positive interval the
                // loop fires an immediate refresh on construction that races this test's forceRefresh,
                // re-stamping `vocabularyVersion` (loadedAtMs) between batchMatch's read and the
                // assertion below — a flake that surfaced on slower CI runners. The test drives refresh
                // explicitly via forceRefresh(), so it needs no background loop.
                refreshIntervalSeconds = 0,
                tokenBasedConfig = TokenBasedConfig(),
                nlp = NlpConfig(),
                loaderSource = LoaderSourceConfig(source = "static"),
                metadata = MetadataConfig(),
            )

        fun withMatcher(block: suspend (FuzzyMatcher, StringRepository) -> Unit) {
            val repo = StringRepository(cfg, loader, telemetry = null)
            try {
                runBlocking {
                    repo.forceRefresh()
                    block(FuzzyMatcher(repo), repo)
                }
            } finally {
                repo.close()
            }
        }

        "one BatchMatch resolves N spans positionally, each source-tagged" {
            withMatcher { m, _ ->
                val out =
                    m.batchMatch(
                        listOf(
                            SpanQuery("Škoda Octavia", listOf("product"), 5),
                            SpanQuery("tržba", listOf("measure-term"), 5),
                        ),
                    )
                out.results.size shouldBe 2 // positional to spans
                val product = out.results[0].matches.first()
                product.candidateId shouldBe "p-octavia"
                product.source shouldBe SourceTag.MEMBER
                val vocab = out.results[1].matches.first()
                vocab.candidateId shouldBe "t-trzba"
                vocab.source shouldBe SourceTag.VOCABULARY
                vocab.targetRef shouldBe "md.measure.net"
            }
        }

        "vocabulary_version is read once for the whole call and is non-blank" {
            withMatcher { m, repo ->
                val out = m.batchMatch(listOf(SpanQuery("tržba", listOf("measure-term"), 5)))
                out.vocabularyVersion.shouldNotBeBlank()
                out.vocabularyVersion shouldBe repo.vocabularyVersion()
            }
        }

        "a span whose only category is explicit-but-unknown gets an EMPTY slot (leak guard)" {
            withMatcher { m, _ ->
                val out =
                    m.batchMatch(
                        listOf(
                            SpanQuery("Škoda Octavia", listOf("product"), 5),
                            SpanQuery("Škoda Octavia", listOf("does-not-exist"), 5),
                        ),
                    )
                out.results[0]
                    .matches
                    .first()
                    .candidateId shouldBe "p-octavia"
                out.results[1].matches.shouldBeEmpty() // NOT a global fallback
            }
        }

        "RV-32: a span's uniqueness margin is re-asked over the categories it merged" {
            // RV-P1.4 T5. Per-category dispatch cannot see a rival in a sibling category — and the
            // compiled lexicon keys one category per target ref, so for a declared term EVERY rival
            // is in a sibling category. Without the re-margin after the merge, a span that asked
            // about two rival measures would report each as uniquely bindable.
            val rivals =
                object : LoaderSource {
                    override suspend fun loadNextCache(): Map<String, List<Candidate>> =
                        mapOf(
                            "md.net" to
                                listOf(
                                    Candidate.vocabulary(
                                        "t1",
                                        "čistý obrat",
                                        "md.net",
                                        SourceTag.DECLARED,
                                        "TOKENS",
                                    ),
                                ),
                            "md.gross" to
                                listOf(
                                    Candidate.vocabulary(
                                        "t2",
                                        "hrubý obrat",
                                        "md.gross",
                                        SourceTag.DECLARED,
                                        "TOKENS",
                                    ),
                                ),
                        )
                }
            val repo = StringRepository(cfg, rivals, telemetry = null)
            try {
                runBlocking {
                    repo.forceRefresh()
                    val out =
                        FuzzyMatcher(repo).batchMatch(
                            listOf(SpanQuery("obrat", listOf("md.net", "md.gross"), 5)),
                        )

                    val matches = out.results[0].matches
                    matches.size shouldBe 2
                    matches.forEach { it.autoBindable shouldBe false }
                }
            } finally {
                repo.close()
            }
        }

        "a span can match across multiple categories in one slot" {
            withMatcher { m, _ ->
                val out =
                    m.batchMatch(
                        listOf(SpanQuery("tržba", listOf("product", "measure-term"), 5)),
                    )
                // The measure-term wins for "tržba"; product contributes nothing but doesn't error.
                out.results[0]
                    .matches
                    .first()
                    .candidateId shouldBe "t-trzba"
            }
        }
    })
