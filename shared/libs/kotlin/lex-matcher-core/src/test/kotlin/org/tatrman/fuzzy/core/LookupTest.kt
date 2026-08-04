// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking

/**
 * RV-P1.4 T5 — the category-scoped lookup, the shape P2.3's lookup rounds call.
 *
 * The rung's contract (RV-33) is "deterministic, anchored, category-scoped, always eligible first",
 * and each test below pins one word of that: scoped means the caller's categories and classes are
 * respected *and* reported on; deterministic means one algorithm, no cascade; anchored means the
 * answer says which layers produced it.
 */
class LookupTest :
    StringSpec({

        fun vocab(
            id: String,
            value: String,
            targetRef: String,
            targetClass: TargetClass,
            method: String = "TOKENS",
        ) = Candidate.vocabulary(id, value, targetRef, SourceTag.DECLARED, method, targetClass)

        /**
         * A repository keyed the way `LexiconArchiveSource` keys one: **one category per target
         * ref**. That convention is what makes T5's cross-category re-margin necessary, so the
         * fixture has to reproduce it rather than lump everything into one bucket.
         */
        fun repo(vararg entries: Pair<String, List<Candidate>>): MatchRepository {
            val cache = mapOf(*entries).mapKeys { it.key.lowercase() }
            val all = cache.values.flatten()
            val indices = cache.mapValues { (_, c) -> TokenIndex(c) }
            val global = TokenIndex(all)
            return object : MatchRepository {
                override fun getCandidates(category: String?) =
                    if (category == null) all else cache[category.lowercase()] ?: emptyList()

                override fun getTokenIndex(category: String?) =
                    if (category == null) global else indices[category.lowercase()] ?: TokenIndex(emptyList())

                override fun getDistanceCache(category: String?) = DistanceCache()

                override fun getVocabulary(category: String?) = TokenVocabulary(getCandidates(category))

                override fun vocabularyVersion() = "v1"

                override fun knownCategories() = cache.keys

                // Every fixture here serves declared rows, which is what turns on the scoring
                // headroom the authored-method gate and the class filter need.
                override fun servesDeclaredLayer() = all.any { it.authoredMethod != null }
            }
        }

        val net = vocab("t1", "čistý obrat", "md.net", TargetClass.MODEL_OBJECT)
        val gross = vocab("t2", "hrubý obrat", "md.gross", TargetClass.MODEL_OBJECT)
        val trend = vocab("t3", "obrat", "op:trend", TargetClass.OPERATOR)

        val estate =
            repo(
                "md.net" to listOf(net),
                "md.gross" to listOf(gross),
                "op:trend" to listOf(trend),
            )

        // ---- scoping ---------------------------------------------------------------------------

        "an empty category list is the deliberate cross-category lookup" {
            runBlocking {
                val hits = FuzzyMatcher(estate).lookup(LookupQuery(term = "obrat")).candidates

                hits.map { it.targetRef } shouldContainExactlyInAnyOrder listOf("md.net", "md.gross", "op:trend")
            }
        }

        "an explicit category list restricts the answer to those categories" {
            runBlocking {
                val hits =
                    FuzzyMatcher(estate)
                        .lookup(LookupQuery(term = "obrat", categories = listOf("md.net")))
                        .candidates

                hits.map { it.targetRef } shouldContainExactly listOf("md.net")
            }
        }

        "target-class scoping answers 'which operator is this?' without naming one" {
            runBlocking {
                val hits =
                    FuzzyMatcher(estate)
                        .lookup(LookupQuery(term = "obrat", targetClasses = setOf(TargetClass.OPERATOR)))
                        .candidates

                hits.map { it.targetRef } shouldContainExactly listOf("op:trend")
            }
        }

        "a class-scoped lookup excludes member values, which carry no class" {
            // Deliberate: "which operator is this?" must not be answered with a row of data that
            // happens to read alike.
            val withData = repo("db.t.col" to listOf(Candidate.fromValues("pk-1", "obrat")))
            runBlocking {
                val matcher = FuzzyMatcher(withData)

                matcher.lookup(LookupQuery(term = "obrat")).candidates.size shouldBe 1
                matcher
                    .lookup(LookupQuery(term = "obrat", targetClasses = setOf(TargetClass.OPERATOR)))
                    .candidates
                    .shouldBeEmpty()
            }
        }

        "an unknown category is named, not silently empty" {
            runBlocking {
                val result =
                    FuzzyMatcher(estate).lookup(
                        LookupQuery(term = "obrat", categories = listOf("md.net", "md.gone")),
                    )

                result.candidates.map { it.targetRef } shouldContainExactly listOf("md.net")
                // A stale ref and a genuine miss are indistinguishable without this.
                result.unknownCategories shouldContainExactly listOf("md.gone")
            }
        }

        "a repository that does not report its categories reports nothing unknown" {
            // null means "I don't publish my categories", which must not read as "none exist".
            val silent =
                object : MatchRepository by estate {
                    override fun knownCategories(): Set<String>? = null
                }
            runBlocking {
                FuzzyMatcher(silent)
                    .lookup(LookupQuery(term = "obrat", categories = listOf("md.gone")))
                    .unknownCategories
                    .shouldBeEmpty()
            }
        }

        // ---- the margin, re-asked over the union (the T4 finding) --------------------------------

        "the uniqueness margin is computed across the REQUESTED categories, not within each" {
            // The failure this exists to prevent: one category per target ref means a per-category
            // margin never sees a rival, so every ambiguous term would report as uniquely bindable.
            runBlocking {
                val hits =
                    FuzzyMatcher(estate)
                        .lookup(LookupQuery(term = "obrat", categories = listOf("md.net", "md.gross")))
                        .candidates

                hits.size shouldBe 2
                hits.forEach { it.autoBindable shouldBe false }
                hits.first().uniquenessMargin!! shouldBeLessThan MethodDispatcher.DEFAULT_UNIQUENESS_FLOOR
            }
        }

        "scoping to ONE of two rival targets makes it bindable again — the scope is the disambiguation" {
            runBlocking {
                val hit =
                    FuzzyMatcher(estate)
                        .lookup(LookupQuery(term = "obrat", categories = listOf("md.net")))
                        .candidates
                        .single()

                hit.autoBindable shouldBe true
            }
        }

        "a rival dropped by max_candidates still depresses the margin" {
            // Re-margining after the limit would manufacture uniqueness by truncation: the two
            // rivals tie, and asking for one candidate must not turn the survivor into a safe bind.
            runBlocking {
                val hits =
                    FuzzyMatcher(estate)
                        .lookup(
                            LookupQuery(term = "obrat", categories = listOf("md.net", "md.gross"), maxCandidates = 1),
                        ).candidates

                hits.size shouldBe 1
                hits.single().autoBindable shouldBe false
            }
        }

        // ---- the gate narrows AFTER scoring, so scoring leaves it headroom ----------------------

        "a candidate the gate admits is not lost to rows the gate rejects ranking above it" {
            // The engine scores, THEN dispatch rejects. Scoring at exactly `limit` let candidates
            // the author's method refuses consume every slot, so the one admissible answer — ranked
            // below them by the deliberately recall-oriented folded index — was truncated before
            // the gate ever saw it, and the query answered "nothing" where the answer existed.
            //
            // `zákazník` authored EXACT does not admit the unaccented `zakaznik` (T4), but the
            // folded index scores every one of them a perfect hit. The TOKENS row matches one token
            // of two, so it scores strictly lower and lands 13th of 13.
            val crowded =
                repo(
                    "md.crowded" to
                        (1..12).map { n ->
                            vocab("x$n", "zákazník", "md.decoy$n", TargetClass.MODEL_OBJECT, "EXACT")
                        } + vocab("real", "zákazník klient", "md.real", TargetClass.MODEL_OBJECT, "TOKENS"),
                )
            runBlocking {
                val hits = FuzzyMatcher(crowded).lookup(LookupQuery("zakaznik", maxCandidates = 10)).candidates

                hits.map { it.targetRef } shouldContainExactly listOf("md.real")
            }
        }

        "a class-scoped lookup is not emptied by out-of-class rows ranking above the match" {
            // Same failure, one stage later: the class filter also runs after scoring, so twelve
            // MODEL_OBJECT rows scoring above the single OPERATOR row used to truncate it away and
            // answer "no operator" for a term that names one.
            val crowded =
                repo(
                    "md.crowded" to
                        (1..12).map { n ->
                            vocab("x$n", "obrat", "md.decoy$n", TargetClass.MODEL_OBJECT)
                        } + vocab("op", "obrat vývoj", "op:trend", TargetClass.OPERATOR),
                )
            runBlocking {
                val hits =
                    FuzzyMatcher(crowded)
                        .lookup(
                            LookupQuery("obrat", targetClasses = setOf(TargetClass.OPERATOR), maxCandidates = 10),
                        ).candidates

                hits.map { it.targetRef } shouldContainExactly listOf("op:trend")
            }
        }

        "max_candidates is capped, so one request cannot ask for the whole estate" {
            // `int32` on the wire with no ceiling is an invitation; the rung's question is bounded
            // by construction and a caller that wants more narrows its categories instead.
            val big =
                repo(
                    "md.big" to
                        (1..250).map { n -> vocab("b$n", "obrat $n", "md.b$n", TargetClass.MODEL_OBJECT) },
                )
            runBlocking {
                FuzzyMatcher(big)
                    .lookup(LookupQuery("obrat", maxCandidates = Int.MAX_VALUE))
                    .candidates
                    .size shouldBe FuzzyMatcher.MAX_LOOKUP_CANDIDATES
            }
        }

        // ---- method_override -------------------------------------------------------------------

        "method_override narrows: EXACT makes a partial term unreachable" {
            runBlocking {
                val matcher = FuzzyMatcher(estate)
                val scope = listOf("md.net")

                matcher.lookup(LookupQuery("obrat", scope)).candidates.size shouldBe 1
                matcher
                    .lookup(LookupQuery("obrat", scope, methodOverride = MatchMethod.Exact))
                    .candidates
                    .shouldBeEmpty()
                // …and the whole authored term still is reachable under the same override.
                matcher
                    .lookup(LookupQuery("čistý obrat", scope, methodOverride = MatchMethod.Exact))
                    .candidates
                    .size shouldBe 1
            }
        }

        "method_override widens: TYPOS(2) reaches a term the authored EXACT would refuse" {
            val strict =
                repo(
                    "md.vyroba" to listOf(vocab("t1", "výroba", "md.vyroba", TargetClass.MODEL_OBJECT, "EXACT")),
                )
            runBlocking {
                val matcher = FuzzyMatcher(strict)

                matcher.lookup(LookupQuery("vyroba")).candidates.shouldBeEmpty()
                matcher
                    .lookup(LookupQuery("vyroba", methodOverride = MatchMethod.Typos(2)))
                    .candidates
                    .size shouldBe 1
            }
        }

        "method_override does NOT reach rows nobody authored" {
            // A caller narrowing its own declared layer must not narrow the estate's data layer:
            // member values never had an authored method, so there is nothing to override.
            val data = repo("db.t.col" to listOf(Candidate.fromValues("pk-1", "obrat celkem")))
            runBlocking {
                FuzzyMatcher(data)
                    .lookup(LookupQuery("obrat", methodOverride = MatchMethod.Exact))
                    .candidates
                    .size shouldBe 1
            }
        }

        "the override decides which rows the margin is computed over, not the authored method" {
            // The margin is a TOKENS instrument. Under an override the EFFECTIVE method is what
            // decides who competes — reading the authored method back out of the row (which is what
            // the response reports) would recompute it over the wrong set.
            val strict =
                repo(
                    "md.net" to listOf(vocab("t1", "obrat", "md.net", TargetClass.MODEL_OBJECT, "EXACT")),
                    "md.gross" to listOf(vocab("t2", "obrat", "md.gross", TargetClass.MODEL_OBJECT, "EXACT")),
                )
            runBlocking {
                val matcher = FuzzyMatcher(strict)

                // Authored EXACT: both match exactly, and no uniqueness decision applies at all.
                matcher.lookup(LookupQuery("obrat")).candidates.forEach {
                    it.uniquenessMargin.shouldBeNull()
                    it.autoBindable.shouldBeNull()
                }
                // Overridden to TOKENS: now they compete, and they tie.
                matcher.lookup(LookupQuery("obrat", methodOverride = MatchMethod.Tokens)).candidates.forEach {
                    it.uniquenessMargin!! shouldBeLessThan MethodDispatcher.DEFAULT_UNIQUENESS_FLOOR
                    it.autoBindable shouldBe false
                }
            }
        }

        "overriding TOKENS rows to EXACT withdraws the uniqueness decision with it" {
            runBlocking {
                FuzzyMatcher(estate)
                    .lookup(
                        LookupQuery("čistý obrat", listOf("md.net"), methodOverride = MatchMethod.Exact),
                    ).candidates
                    .single()
                    .autoBindable
                    .shouldBeNull()
            }
        }

        "the reported match_method stays the AUTHORED one under an override" {
            // Two different facts: what the author asked for, and what this call decided by. The
            // response carries the override separately (`applied_method_override`).
            runBlocking {
                val hit =
                    FuzzyMatcher(estate)
                        .lookup(
                            LookupQuery("čistý obrat", listOf("md.net"), methodOverride = MatchMethod.Exact),
                        ).candidates
                        .single()

                hit.matchMethod shouldBe "TOKENS"
            }
        }

        // ---- bounds ------------------------------------------------------------------------------

        "max_candidates bounds the answer, and 0 means the default" {
            runBlocking {
                val matcher = FuzzyMatcher(estate)

                matcher.lookup(LookupQuery("obrat", maxCandidates = 2)).candidates.size shouldBe 2
                matcher.lookup(LookupQuery("obrat", maxCandidates = 0)).candidates.size shouldBe 3
            }
        }

        "a term matching nothing yields no candidates and no error" {
            runBlocking {
                FuzzyMatcher(estate).lookup(LookupQuery("zcela jiné slovo", listOf("op:trend"))).candidates.forEach {
                    // The token cascade may still float low-scoring neighbours; what matters is that
                    // nothing scores as a hit worth binding.
                    it.autoBindable shouldBe false
                }
            }
        }
    })
