// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.tatrman.fuzzy.config.AppConfig
import org.tatrman.fuzzy.config.LoaderSourceConfig
import org.tatrman.fuzzy.config.MetadataConfig
import org.tatrman.fuzzy.config.NlpConfig
import org.tatrman.fuzzy.config.TokenBasedConfig
import org.tatrman.fuzzy.loader.DeclaredValue
import org.tatrman.fuzzy.loader.DeclaredVocabulary
import org.tatrman.fuzzy.loader.DeclaredVocabularyEntry
import org.tatrman.fuzzy.loader.LoaderSource
import org.tatrman.fuzzy.loader.SnapshotVocabularySource

/**
 * RV-P1.4 T2 — layer resolution, written before the loader that fills the layers.
 *
 * RV-39 makes lex-matcher resolve three layers behind one query surface: the compiled
 * declared+metadata artifact, the member index, and the (empty until RV-P6) LEARNED overlay.
 *
 * The rule these tests exist to pin is that **lex-matcher does not pick a winner across layers**.
 * A term that is both an authored alias and a data value yields two candidates, each carrying its
 * own provenance, and the evidence-class gate in the resolver decides between them. Collapsing
 * them here would destroy the ambiguity the lattice is designed to represent (RV-2), and no
 * downstream layer could recover it.
 */
class LayerResolutionTest :
    StringSpec({

        fun cfg() =
            AppConfig(
                serverPort = 7111,
                grpcPort = 7211,
                grpcReflectionEnabled = false,
                refreshIntervalSeconds = 0,
                tokenBasedConfig = TokenBasedConfig(),
                nlp = NlpConfig(),
                loaderSource = LoaderSourceConfig(source = "static"),
                metadata = MetadataConfig(),
            )

        /** Member layer: data values, no authored method. */
        fun members(vararg entries: Pair<String, List<Candidate>>) =
            object : LoaderSource {
                override suspend fun loadNextCache(): Map<String, List<Candidate>> = mapOf(*entries)
            }

        /** The compiled-artifact layer, faked at the seam T3 will implement. */
        fun declared(
            artifactHash: String,
            vararg entries: DeclaredVocabularyEntry,
        ) = object : SnapshotVocabularySource {
            override suspend fun fetch() = DeclaredVocabulary(entries.toList())

            override fun hash() = artifactHash

            override fun artifactHash() = artifactHash
        }

        val artifact = "sha256:" + "aa".repeat(32)

        // ---- (a) declared layer only ----------------------------------------------------------

        "a term present only in the declared layer resolves with source DECLARED" {
            val repo =
                StringRepository(
                    cfg(),
                    members("region" to emptyList()),
                    snapshotSource =
                        declared(
                            artifact,
                            DeclaredVocabularyEntry(
                                category = "region",
                                targetRef = "er.entity.region",
                                values =
                                    listOf(
                                        DeclaredValue("t1", "středisko", SourceTag.DECLARED, "EXACT"),
                                    ),
                            ),
                        ),
                )
            runBlocking {
                repo.forceRefresh()
                val hits = FuzzyMatcher(repo).match("středisko", "region", AlgorithmType.TATRMAN, 10)

                hits.size shouldBe 1
                hits[0].source shouldBe SourceTag.DECLARED
                hits[0].targetRef shouldBe "er.entity.region"
                hits[0].matchMethod shouldBe "EXACT"
            }
        }

        "a harvested model label resolves with source METADATA, not DECLARED" {
            // The distinction the compiled artifact was built to keep: an author's file states an
            // intent, a model label is a byproduct, and the evidence gate ranks them differently.
            val repo =
                StringRepository(
                    cfg(),
                    members("region" to emptyList()),
                    snapshotSource =
                        declared(
                            artifact,
                            DeclaredVocabularyEntry(
                                category = "region",
                                targetRef = "er.entity.region",
                                values = listOf(DeclaredValue("m1", "Oblast", SourceTag.METADATA)),
                            ),
                        ),
                )
            runBlocking {
                repo.forceRefresh()
                val hits = FuzzyMatcher(repo).match("oblast", "region", AlgorithmType.TATRMAN, 10)

                hits.single().source shouldBe SourceTag.METADATA
                // Nobody authored a method for a harvested label.
                hits.single().matchMethod.shouldBeNull()
            }
        }

        // ---- (b) both layers, no winner picked ------------------------------------------------

        "a term in BOTH the declared and member layers returns both candidates, each with its own tags" {
            val repo =
                StringRepository(
                    cfg(),
                    members("region" to listOf(Candidate.fromValues("pk-42", "Praha"))),
                    snapshotSource =
                        declared(
                            artifact,
                            DeclaredVocabularyEntry(
                                category = "region",
                                targetRef = "er.entity.city",
                                values = listOf(DeclaredValue("t1", "Praha", SourceTag.DECLARED, "TYPOS(1)")),
                            ),
                        ),
                )
            runBlocking {
                repo.forceRefresh()
                val hits = FuzzyMatcher(repo).match("praha", "region", AlgorithmType.TATRMAN, 10)

                hits.map { it.source } shouldContainExactlyInAnyOrder
                    listOf(SourceTag.MEMBER, SourceTag.DECLARED)

                val member = hits.single { it.source == SourceTag.MEMBER }
                member.candidateId shouldBe "pk-42"
                member.targetRef.shouldBeNull()
                member.matchMethod.shouldBeNull()

                val decl = hits.single { it.source == SourceTag.DECLARED }
                decl.targetRef shouldBe "er.entity.city"
                decl.matchMethod shouldBe "TYPOS(1)"
            }
        }

        // ---- (c) the overlay slot is absent, not empty -----------------------------------------

        "with no overlay store, overlay_version is ABSENT and results are unchanged" {
            val repo =
                StringRepository(
                    cfg(),
                    members("region" to listOf(Candidate.fromValues("pk-42", "Praha"))),
                    snapshotSource =
                        declared(
                            artifact,
                            DeclaredVocabularyEntry(
                                category = "region",
                                targetRef = "er.entity.city",
                                values = listOf(DeclaredValue("t1", "Praha", SourceTag.DECLARED, "TYPOS(1)")),
                            ),
                        ),
                )
            runBlocking {
                repo.forceRefresh()

                // Absent — NOT the empty string. "No overlay exists" and "an overlay at version ''"
                // are different facts, and the tuple has to be able to say the first one.
                repo.layerVersions().overlayVersion.shouldBeNull()

                FuzzyMatcher(repo).match("praha", "region", AlgorithmType.TATRMAN, 10).size shouldBe 2
            }
        }

        // ---- (d) the tuple -------------------------------------------------------------------

        "the layer tuple carries the artifact hash and a version per member category" {
            val repo =
                StringRepository(
                    cfg(),
                    members(
                        "region" to listOf(Candidate.fromValues("pk-42", "Praha")),
                        "product" to listOf(Candidate.fromValues("pk-7", "Škoda")),
                    ),
                    snapshotSource =
                        declared(
                            artifact,
                            DeclaredVocabularyEntry(
                                category = "region",
                                targetRef = "er.entity.city",
                                values = listOf(DeclaredValue("t1", "Praha", SourceTag.DECLARED)),
                            ),
                        ),
                )
            runBlocking {
                repo.forceRefresh()
                val tuple = repo.layerVersions()

                tuple.lexiconArtifactHash shouldBe artifact
                tuple.memberIndexVersions.keys shouldContainExactlyInAnyOrder listOf("region", "product")
                tuple.overlayVersion.shouldBeNull()
            }
        }

        "a member category's version changes when its content does, and not otherwise" {
            // The old `vocabularyVersion` string bakes in the load timestamp, so it moves on every
            // refresh whether or not anything changed. The tuple exists to answer "did a layer
            // change?", so each component has to be content-derived or it answers nothing.
            var values = listOf(Candidate.fromValues("pk-42", "Praha"))
            val loader =
                object : LoaderSource {
                    override suspend fun loadNextCache(): Map<String, List<Candidate>> = mapOf("region" to values)
                }
            val repo = StringRepository(cfg(), loader)
            runBlocking {
                repo.forceRefresh()
                val first = repo.layerVersions().memberIndexVersions.getValue("region")

                repo.forceRefresh() // same content, later clock
                repo.layerVersions().memberIndexVersions.getValue("region") shouldBe first

                values = values + Candidate.fromValues("pk-43", "Brno")
                repo.forceRefresh()
                (repo.layerVersions().memberIndexVersions.getValue("region") == first) shouldBe false
            }
        }

        "with no declared layer at all, the artifact hash is empty and nothing else breaks" {
            val repo = StringRepository(cfg(), members("region" to listOf(Candidate.fromValues("pk-42", "Praha"))))
            runBlocking {
                repo.forceRefresh()
                val tuple = repo.layerVersions()

                tuple.lexiconArtifactHash shouldBe ""
                tuple.memberIndexVersions.keys shouldContainExactlyInAnyOrder listOf("region")
                tuple.overlayVersion.shouldBeNull()
            }
        }
    })
