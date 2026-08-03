// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
 * RV-P1.4 T4 — the dispatcher through the real wiring, not called directly.
 *
 * `MethodDispatcherTest` (lex-matcher-core) pins the rules; this pins that a live query actually
 * reaches them: repository → cascade → dispatch. The distinction matters because the engine
 * *would* return every candidate here on score alone — the folded index makes `vyroba` an exact
 * token hit for `výroba` — so each assertion below fails if the gate is bypassed anywhere on the
 * path.
 */
class MethodDispatchWiringTest :
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

        fun members(vararg entries: Pair<String, List<Candidate>>) =
            object : LoaderSource {
                override suspend fun loadNextCache(): Map<String, List<Candidate>> = mapOf(*entries)
            }

        fun declared(vararg entries: DeclaredVocabularyEntry) =
            object : SnapshotVocabularySource {
                override suspend fun fetch() = DeclaredVocabulary(entries.toList())

                override fun hash() = "sha256:" + "bb".repeat(32)
            }

        fun repo(vararg entries: DeclaredVocabularyEntry) =
            StringRepository(cfg(), members("m" to emptyList()), snapshotSource = declared(*entries))

        fun entry(
            targetRef: String,
            vararg values: DeclaredValue,
            // Category == target ref, which is what `LexiconArchiveSource` produces: one category
            // per target. Cross-target ambiguity therefore only shows up on the deliberate
            // cross-category (null) lookup — which is exactly the P2.3 lookup round the margin is
            // for, where the core asks "what does this word mean?" without naming a target.
        ) = DeclaredVocabularyEntry(category = targetRef, targetRef = targetRef, values = values.toList())

        "an EXACT term is not reachable by a diacritic-stripped query, even though the index folds" {
            val repository = repo(entry("md.vyroba", DeclaredValue("t1", "výroba", SourceTag.DECLARED, "EXACT")))
            runBlocking {
                repository.forceRefresh()
                val matcher = FuzzyMatcher(repository)

                // The term is reachable as written…
                matcher.match("výroba", null, AlgorithmType.TATRMAN, 10).size shouldBe 1
                // …and not as an unaccented approximation, which is a TYPOS decision the author
                // declined. Without the gate the folded token index scores this a perfect hit.
                matcher.match("vyroba", null, AlgorithmType.TATRMAN, 10).shouldBeEmpty()
            }
        }

        "the same term declared TYPOS(1) IS reachable that way — the author's n is the difference" {
            val repository = repo(entry("md.vyroba", DeclaredValue("t1", "výroba", SourceTag.DECLARED, "TYPOS(1)")))
            runBlocking {
                repository.forceRefresh()
                FuzzyMatcher(repository).match("vyroba", null, AlgorithmType.TATRMAN, 10).size shouldBe 1
            }
        }

        "an ambiguous TOKENS query returns both targets, flagged not auto-bindable" {
            val repository =
                repo(
                    entry("md.net", DeclaredValue("t1", "čistý obrat", SourceTag.DECLARED, "TOKENS")),
                    entry("md.gross", DeclaredValue("t2", "hrubý obrat", SourceTag.DECLARED, "TOKENS")),
                )
            runBlocking {
                repository.forceRefresh()
                val hits = FuzzyMatcher(repository).match("obrat", null, AlgorithmType.TATRMAN, 10)

                hits.size shouldBe 2
                // Both scored alike on the shared token, so neither identifies a target on its own.
                hits.forEach { it.autoBindable shouldBe false }
                hits.forEach { it.uniquenessMargin shouldNotBe null }
            }
        }

        "an unambiguous TOKENS query is auto-bindable and carries the gap it won by" {
            val repository =
                repo(
                    entry("md.net", DeclaredValue("t1", "čistý obrat", SourceTag.DECLARED, "TOKENS")),
                    entry("md.hr", DeclaredValue("t2", "počet zaměstnanců", SourceTag.DECLARED, "TOKENS")),
                )
            runBlocking {
                repository.forceRefresh()
                val hits = FuzzyMatcher(repository).match("čistý obrat", null, AlgorithmType.TATRMAN, 10)

                val winner = hits.first { it.targetRef == "md.net" }
                winner.autoBindable shouldBe true
                (winner.uniquenessMargin!! > 0.0) shouldBe true
            }
        }

        "a member-only estate is untouched — no methods, no margins, no gate" {
            // T7's guarantee, asserted on the path that would violate it.
            val repository = StringRepository(cfg(), members("m" to listOf(Candidate.fromValues("pk-1", "Praha"))))
            runBlocking {
                repository.forceRefresh()
                val hit = FuzzyMatcher(repository).match("praha", "m", AlgorithmType.TATRMAN, 10).single()

                hit.matchMethod.shouldBeNull()
                hit.uniquenessMargin.shouldBeNull()
                hit.autoBindable.shouldBeNull()
            }
        }
    })
