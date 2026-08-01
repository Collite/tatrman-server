// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * RG-P2.S2.T5 — the two-clock refresh discipline. (a) member data swaps
 * atomically and a loader failure preserves the previous cache; (b) declared
 * vocabulary reloads ONLY when its snapshot hash changes; (c) `vocabulary_version`
 * reflects the declared snapshot hash. `refreshIntervalSeconds = 0` = manual
 * mode (no background loop) so the counts are deterministic.
 */
class RefreshDisciplineTest :
    StringSpec({

        fun cfg() =
            AppConfig(
                serverPort = 7103,
                grpcPort = 7203,
                grpcReflectionEnabled = false,
                refreshIntervalSeconds = 0,
                tokenBasedConfig = TokenBasedConfig(),
                nlp = NlpConfig(),
                loaderSource = LoaderSourceConfig(source = "static"),
                metadata = MetadataConfig(),
            )

        "member cache swaps atomically; a loader failure preserves the previous cache" {
            val calls = AtomicInteger(0)
            val loader =
                object : LoaderSource {
                    override suspend fun loadNextCache(): Map<String, List<Candidate>>? =
                        if (calls.getAndIncrement() == 0) {
                            mapOf("product" to listOf(Candidate.fromValues("p1", "Škoda Octavia")))
                        } else {
                            null // subsequent loads fail
                        }
                }
            val repo = StringRepository(cfg(), loader)
            try {
                runBlocking {
                    repo.forceRefresh()
                    repo.getCandidates("product").size shouldBe 1
                    repo.forceRefresh() // loader now fails
                    repo.getCandidates("product").size shouldBe 1 // preserved, not cleared
                }
            } finally {
                repo.close()
            }
        }

        "declared vocabulary reloads only when the snapshot hash changes" {
            val fetches = AtomicInteger(0)
            var currentHash = "v1"
            val snapshot =
                object : SnapshotVocabularySource {
                    override suspend fun fetch(): DeclaredVocabulary {
                        fetches.incrementAndGet()
                        return DeclaredVocabulary(
                            listOf(
                                DeclaredVocabularyEntry(
                                    "md.measure.net",
                                    "md.measure.net",
                                    listOf(DeclaredValue("trzba", "tržba")),
                                ),
                            ),
                        )
                    }

                    override fun hash(): String = currentHash
                }
            val loader =
                object : LoaderSource {
                    override suspend fun loadNextCache() = mapOf("product" to listOf(Candidate.fromValues("p1", "x")))
                }
            val repo = StringRepository(cfg(), loader, snapshotSource = snapshot)
            try {
                runBlocking {
                    repo.forceRefresh()
                    fetches.get() shouldBe 1 // first load fetches declared vocab
                    repo.forceRefresh()
                    fetches.get() shouldBe 1 // same hash → NOT re-fetched (second clock idle)
                    currentHash = "v2"
                    repo.forceRefresh()
                    fetches.get() shouldBe 2 // hash changed → re-fetched
                }
            } finally {
                repo.close()
            }
        }

        "vocabulary_version reflects the declared snapshot hash and changes with it" {
            var currentHash = "snap-abc"
            val snapshot =
                object : SnapshotVocabularySource {
                    override suspend fun fetch() =
                        DeclaredVocabulary(
                            listOf(
                                DeclaredVocabularyEntry(
                                    "er.branch",
                                    "er.branch",
                                    listOf(DeclaredValue("t", "pobočka")),
                                ),
                            ),
                        )

                    override fun hash(): String = currentHash
                }
            val loader =
                object : LoaderSource {
                    override suspend fun loadNextCache() = mapOf("product" to listOf(Candidate.fromValues("p1", "x")))
                }
            val repo = StringRepository(cfg(), loader, snapshotSource = snapshot)
            try {
                runBlocking {
                    repo.forceRefresh()
                    val v1 = repo.vocabularyVersion()
                    v1 shouldContain "snap-abc"
                    // declared vocab loaded as a VOCABULARY category
                    repo.getCandidates("er.branch").single().source shouldBe SourceTag.VOCABULARY

                    currentHash = "snap-xyz"
                    repo.forceRefresh()
                    val v2 = repo.vocabularyVersion()
                    v2 shouldContain "snap-xyz"
                    (v1 == v2) shouldBe false
                }
            } finally {
                repo.close()
            }
        }
    })
