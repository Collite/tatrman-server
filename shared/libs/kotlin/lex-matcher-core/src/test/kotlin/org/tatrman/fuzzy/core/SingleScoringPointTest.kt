// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.walk

/**
 * RV-P3.0 T5(d) — **one** scoring point, and no caller routes around it.
 *
 * The same invariant `SingleBinderTest` holds in the resolver, for the same reason: the value of
 * "a declared profile decides how this row scores" is exactly the number of code paths that can
 * produce a score without asking it. `match`, `matchCascade`, `batchMatch` and `lookup` all funnel
 * through `FuzzyMatcher.runSingle`, which is where the dispatcher (and therefore [ProfileScorer])
 * runs — so this file asserts the four entry points agree, and then greps the sources to make sure
 * the fifth one nobody has written yet cannot quietly appear.
 */
class SingleScoringPointTest :
    StringSpec({

        val profile =
            MatchProfile(
                listOf(
                    NormRule(Norm.CANONICAL, exact = 1.00, typos = TyposRule(1, 0.05)),
                    NormRule(Norm.FOLDED, exact = 0.90),
                ),
            )

        val supplier =
            Candidate.vocabulary(
                id = "lex:er.receipt:cs:příjemka",
                value = "příjemka",
                targetRef = "er.receipt",
                source = SourceTag.DECLARED,
                matchMethod = "TYPOS(1)",
                targetClass = TargetClass.MODEL_OBJECT,
                matchProfile = profile,
            )

        val repo =
            object : MatchRepository {
                private val all = listOf(supplier)
                private val index = TokenIndex(all)

                override fun getCandidates(category: String?) = all

                override fun getTokenIndex(category: String?) = index

                override fun getDistanceCache(category: String?) = DistanceCache()

                override fun getVocabulary(category: String?) = TokenVocabulary(all)

                override fun vocabularyVersion() = "v1"

                override fun knownCategories() = setOf("er.receipt")

                override fun servesDeclaredLayer() = true
            }

        val matcher = FuzzyMatcher(repo)

        // `prijemka` is the diacritic-stripped form of a TWO-diacritic word: two edits, so the
        // canonical stratum's TYPOS(1) cannot reach it and ONLY the declared folded rule can — at
        // the author's own 0.90. A path that skipped the scorer would either miss the row entirely
        // or report the engine's number instead.
        val query = "prijemka"
        val declaredScore = 0.90

        "match() scores through the profile" {
            runBlocking {
                matcher.match(query, "er.receipt", AlgorithmType.TATRMAN, 5).single().score shouldBe declaredScore
            }
        }

        "matchCascade() scores through the profile" {
            runBlocking {
                matcher
                    .matchCascade(query, "er.receipt", listOf(CascadeStep(AlgorithmType.TATRMAN, 0.0)), 5)
                    .matches
                    .single()
                    .score shouldBe declaredScore
            }
        }

        "batchMatch() scores through the profile" {
            runBlocking {
                matcher
                    .batchMatch(listOf(SpanQuery(query, listOf("er.receipt"))))
                    .results
                    .single()
                    .matches
                    .single()
                    .score shouldBe declaredScore
            }
        }

        "lookup() scores through the profile" {
            runBlocking {
                matcher
                    .lookup(LookupQuery(term = query, categories = listOf("er.receipt")))
                    .candidates
                    .single()
                    .score shouldBe declaredScore
            }
        }

        "all four report the same winning (norm, algorithm) — one scorer, one answer" {
            runBlocking {
                val provenances =
                    listOf(
                        matcher.match(query, "er.receipt", AlgorithmType.TATRMAN, 5).single().provenance,
                        matcher
                            .matchCascade(query, "er.receipt", listOf(CascadeStep(AlgorithmType.TATRMAN, 0.0)), 5)
                            .matches
                            .single()
                            .provenance,
                        matcher
                            .batchMatch(listOf(SpanQuery(query, listOf("er.receipt"))))
                            .results
                            .single()
                            .matches
                            .single()
                            .provenance,
                        matcher
                            .lookup(LookupQuery(term = query, categories = listOf("er.receipt")))
                            .candidates
                            .single()
                            .provenance,
                    )

                provenances.map { it.norm }.toSet() shouldBe setOf("folded")
                provenances.map { it.algorithm }.toSet() shouldBe setOf("exact")
            }
        }

        // ---- the lemma stratum is opt-in, and so is its COST -------------------------------------

        /** Counts calls, and lemmatises to the folded surface exactly as [NoopLemmatizer] does. */
        class CountingLemmatizer : Lemmatizer {
            var calls: Int = 0

            override suspend fun lemmatize(tokens: Collection<String>): Map<String, String> {
                calls++
                return tokens.associateWith { TextNormalizer.fold(it) }
            }
        }

        "a profile with no `lemma` rule consults the lemmatiser ONCE — the token path's own call" {
            // In production the lemmatiser is `NlpLemmatizer`: one uncached gRPC BatchLemmatize per
            // call. Scoring used to ask for the query's lemma form unconditionally, which meant a
            // SECOND round-trip with the same tokens on every category of every query — including
            // on estates that author no profile at all, and for an axis nothing has authored yet.
            // The count is the assertion; a behavioural test cannot see the difference.
            val counting = CountingLemmatizer()
            runBlocking {
                FuzzyMatcher(repo, lemmatizer = counting).match(query, "er.receipt", AlgorithmType.TATRMAN, 5)
            }
            counting.calls shouldBe 1
        }

        "a profile that DOES declare `lemma` still gets its lemma form" {
            // The other half: the saving must not have been bought by breaking the stratum.
            val lemmaRow =
                Candidate.vocabulary(
                    id = "lex:er.receipt:cs:příjemka",
                    value = "příjemka",
                    targetRef = "er.receipt",
                    source = SourceTag.DECLARED,
                    matchProfile = MatchProfile(listOf(NormRule(Norm.LEMMA, exact = 0.80))),
                )
            val lemmaRepo =
                object : MatchRepository by repo {
                    private val all = listOf(lemmaRow)

                    override fun getCandidates(category: String?) = all

                    override fun getTokenIndex(category: String?) = TokenIndex(all)

                    override fun getVocabulary(category: String?) = TokenVocabulary(all)
                }

            val counting = CountingLemmatizer()
            runBlocking {
                val top =
                    FuzzyMatcher(lemmaRepo, lemmatizer = counting)
                        .match(query, "er.receipt", AlgorithmType.TATRMAN, 5)
                        .single()
                top.score shouldBe 0.80
                top.provenance.norm shouldBe "lemma"
            }
        }

        "the scorer is reachable from exactly ONE production file in this library" {
            // The grep half. A new entry point that scored rows itself would compile, pass every
            // behavioural test above, and quietly serve engine numbers for authored rows — so the
            // constraint that catches it has to be structural, not behavioural.
            @Suppress("DEPRECATION")
            val callers =
                Path
                    .of("src/main/kotlin")
                    .walk()
                    .filter { it.toString().endsWith(".kt") }
                    // Its own definition file is not a caller; every other one would be.
                    .filter { it.fileName.toString() != "ProfileScorer.kt" }
                    .filter { it.readText().contains("profileScorer.") || it.readText().contains("ProfileScorer(") }
                    .map { it.fileName.toString() }
                    .toList()

            callers shouldBe listOf("MethodDispatcher.kt")
        }
    })
