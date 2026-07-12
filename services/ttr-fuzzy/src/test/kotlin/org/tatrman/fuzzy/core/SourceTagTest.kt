// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.tatrman.fuzzy.config.AppConfig
import org.tatrman.fuzzy.config.LoaderSourceConfig
import org.tatrman.fuzzy.config.MetadataConfig
import org.tatrman.fuzzy.config.NlpConfig
import org.tatrman.fuzzy.config.TokenBasedConfig
import org.tatrman.fuzzy.loader.LoaderSource

/**
 * RG-P2.S1.T2 — source-tagged categories (contracts §2). A category loaded from
 * MEMBER data yields `Candidate{source=MEMBER, id=<pk>, target_ref=absent}`; a
 * category loaded from declared VOCABULARY yields `{source=VOCABULARY,
 * target_ref=<lexicon target>, id=<entry id>}`. Both flow through the SAME
 * cascade with the same scoring + S-4 provenance.
 */
class SourceTagTest :
    StringSpec({

        val loader =
            object : LoaderSource {
                override suspend fun loadNextCache(): Map<String, List<Candidate>> =
                    mapOf(
                        // MEMBER: product values keyed by data PK.
                        "product" to listOf(Candidate.fromValues("p-octavia", "Škoda Octavia")),
                        // VOCABULARY: a declared lexicon term pointing at an md target.
                        "measure-term" to
                            listOf(Candidate.vocabulary("t-trzba", "tržba", targetRef = "md.measure.net")),
                    )
            }

        val cfg =
            AppConfig(
                serverPort = 7103,
                grpcPort = 7203,
                grpcReflectionEnabled = false,
                refreshIntervalSeconds = 3600,
                tokenBasedConfig = TokenBasedConfig(),
                nlp = NlpConfig(),
                loaderSource = LoaderSourceConfig(source = "static"),
                metadata = MetadataConfig(),
            )

        fun withMatcher(block: suspend (FuzzyMatcher) -> Unit) {
            val repo = StringRepository(cfg, loader, telemetry = null)
            try {
                runBlocking {
                    repo.forceRefresh()
                    block(FuzzyMatcher(repo))
                }
            } finally {
                repo.close()
            }
        }

        "a MEMBER category yields a MEMBER candidate with a data id and no target_ref" {
            withMatcher { m ->
                val top = m.match("Škoda Octavia", "product", AlgorithmType.TATRMAN, 5).first()
                top.candidateId shouldBe "p-octavia"
                top.source shouldBe SourceTag.MEMBER
                top.targetRef.shouldBeNull()
            }
        }

        "a VOCABULARY category yields a VOCABULARY candidate carrying its lexicon target_ref" {
            withMatcher { m ->
                val top = m.match("tržba", "measure-term", AlgorithmType.TATRMAN, 5).first()
                top.candidateId shouldBe "t-trzba"
                top.source shouldBe SourceTag.VOCABULARY
                top.targetRef shouldBe "md.measure.net"
            }
        }

        "both sources flow through the same cascade + carry S-4 provenance" {
            withMatcher { m ->
                val member = m.match("Škoda Octavia", "product", AlgorithmType.TATRMAN, 5).first()
                val vocab = m.match("tržba", "measure-term", AlgorithmType.TATRMAN, 5).first()
                for (r in listOf(member, vocab)) {
                    r.provenance.producer shouldBe "fuzzy"
                    r.provenance.method shouldBe "TATRMAN"
                    r.provenance.rawScore shouldBe r.score
                }
            }
        }
    })
