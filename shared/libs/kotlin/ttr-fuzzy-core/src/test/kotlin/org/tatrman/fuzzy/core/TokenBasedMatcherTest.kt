// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class TokenBasedMatcherTest :
    StringSpec({
        val testCandidates =
            listOf(
                Candidate.fromValues("1", "Hello World"),
                Candidate.fromValues("2", "Hello There"),
                Candidate.fromValues("3", "Goodbye World"),
                Candidate.fromValues("4", "World Hello"),
                Candidate.fromValues("5", "Hello"),
                Candidate.fromValues("6", "Completely Different"),
            )

        fun createMatcher(candidates: List<Candidate> = testCandidates): TokenBasedMatcher =
            TokenBasedMatcher(
                candidates = candidates,
                tokenIndex = TokenIndex(candidates),
                distanceCache = DistanceCache(),
            )

        "should return exact match for identical query" {
            val matcher = createMatcher(testCandidates)
            val results = matcher.match("Hello World", 10)

            results.firstOrNull()?.first?.value shouldBe "Hello World"
            (results.firstOrNull()?.second ?: 0.0) shouldBeGreaterThan 0.99
        }

        "should find matches with subset of tokens" {
            val matcher = createMatcher(testCandidates)
            val results = matcher.match("Hello", 10)

            results.firstOrNull()?.second shouldBe 1.0
            results.any { it.first.value == "Hello" } shouldBe true
        }

        "should find fuzzy matches when exact tokens not present" {
            val matcher = createMatcher(testCandidates)
            val results = matcher.match("Hello Universe", 10)

            results.firstOrNull() shouldNotBe null
            (results.firstOrNull()?.second ?: 0.0) shouldBeGreaterThan 0.0
        }

        "should give higher score to closer matches" {
            val matcher = createMatcher(testCandidates)
            val results = matcher.match("Hello World", 10)

            val helloWorldScore = results.find { it.first.value == "Hello World" }?.second ?: 0.0
            val completelyDifferentScore = results.find { it.first.value == "Completely Different" }?.second ?: 0.0

            helloWorldScore shouldBeGreaterThan completelyDifferentScore
        }

        "should apply order bonus for correct token order" {
            val matcher = createMatcher(testCandidates)
            val results = matcher.match("Hello World", 10)

            val helloWorldScore = results.find { it.first.value == "Hello World" }?.second ?: 0.0
            val worldHelloScore = results.find { it.first.value == "World Hello" }?.second ?: 0.0

            helloWorldScore shouldBeGreaterThan worldHelloScore
        }

        "should handle empty query" {
            val matcher = createMatcher(testCandidates)
            val results = matcher.match("", 10)

            results shouldBe emptyList()
        }

        "should handle query with only whitespace" {
            val matcher = createMatcher(testCandidates)
            val results = matcher.match("   ", 10)

            results shouldBe emptyList()
        }

        "should respect limit parameter" {
            val matcher = createMatcher(testCandidates)
            val results = matcher.match("Hello", 2)

            results.size shouldBe 2
        }

        "should work with single token candidates" {
            val singleTokenCandidates =
                listOf(
                    Candidate.fromValues("1", "Hello"),
                    Candidate.fromValues("2", "World"),
                )

            val matcher = createMatcher(singleTokenCandidates)
            val results = matcher.match("Hello", 10)

            results.firstOrNull()?.second shouldBe 1.0
        }

        "should handle case insensitivity" {
            val matcher = createMatcher(testCandidates)
            val resultsLower = matcher.match("hello world", 10)
            val resultsUpper = matcher.match("HELLO WORLD", 10)

            resultsLower.firstOrNull()?.second shouldBe resultsUpper.firstOrNull()?.second
        }

        "should match Czech queries regardless of diacritics and case" {
            val czechCandidates =
                listOf(
                    Candidate.fromValues("1", "Zákazník"),
                    Candidate.fromValues("2", "Příjmení zákazníka"),
                    Candidate.fromValues("3", "Dodavatel"),
                )
            val matcher = createMatcher(czechCandidates)

            fun top(query: String): Pair<Candidate, Double>? = matcher.match(query, 10).firstOrNull()

            // diacritic-stripped, lowercased, uppercased — all hit "Zákazník" as the top result
            top("zakaznik")?.first?.value shouldBe "Zákazník"
            top("ZÁKAZNÍK")?.first?.value shouldBe "Zákazník"
            top("Zákazník")?.first?.value shouldBe "Zákazník"

            // a diacritic-only difference is an exact match (score 1.0) after folding
            (top("zakaznik")?.second ?: 0.0) shouldBe 1.0

            // multi-token, diacritic-stripped
            top("prijmeni zakaznika")?.first?.value shouldBe "Příjmení zákazníka"
        }

        "should match an inflected query against a candidate's lemma tokens (Stage B)" {
            // candidate carries surface tokens ["zakaznik"] and the same lemma; an inflected query
            // "zákazníků" lemmatised to "zakaznik" must land an exact lemma match (score 1.0).
            val candidates =
                listOf(
                    Candidate.withLemmas(
                        "1",
                        "Zákazník",
                        surfaceTokens = listOf("zakaznik"),
                        lemmaTokens = listOf("zakaznik"),
                    ),
                    Candidate.withLemmas(
                        "2",
                        "Dodavatel",
                        surfaceTokens = listOf("dodavatel"),
                        lemmaTokens = listOf("dodavatel"),
                    ),
                )
            val matcher = createMatcher(candidates)

            // query surface "zakazniku" (folded), lemma "zakaznik" → exact lemma hit
            val r = matcher.match(listOf("zakazniku"), listOf("zakaznik"), 10)
            r.firstOrNull()?.first?.value shouldBe "Zákazník"
            r.firstOrNull()?.second shouldBe 1.0

            // the dual-axis max never regresses a surface-only match: a diacritic-stripped exact
            // surface phrase still scores via the surface axis even if the lemma axis differs
            val candWithDifferentLemma =
                Candidate.withLemmas("3", "Mladá", surfaceTokens = listOf("mlada"), lemmaTokens = listOf("mlady"))
            val m2 = createMatcher(listOf(candWithDifferentLemma))
            // user typed "mlada" (no diacritics), lemmatiser didn't map it → surface "mlada", lemma "mlada"
            m2.match(listOf("mlada"), listOf("mlada"), 10).firstOrNull()?.second shouldBe 1.0
        }
    })

class DistanceCacheTest :
    StringSpec({
        "should cache and return distances" {
            val cache = DistanceCache()

            val distance1 = cache.getOrCompute("hello", "hello") { 0.0 }
            val distance2 = cache.getOrCompute("hello", "hello") { 0.0 }

            distance1 shouldBe 0.0
            distance2 shouldBe 0.0
            cache.size() shouldBe 1
        }

        "should compute different distances for different pairs" {
            val cache = DistanceCache()

            val distance1 = cache.getOrCompute("hello", "hallo") { 1.0 }
            val distance2 = cache.getOrCompute("hello", "world") { 5.0 }

            distance1 shouldBe 1.0
            distance2 shouldBe 5.0
            cache.size() shouldBe 2
        }

        // FZ-P1 T3 — the cache no longer folds case; callers MUST pass already-folded tokens
        // (which every production caller does). Distinct-case inputs are therefore distinct keys.
        "keys tokens verbatim (callers fold; no case-folding in the cache)" {
            val cache = DistanceCache()

            val distance1 = cache.getOrCompute("Hello", "hello") { 0.0 }
            val distance2 = cache.getOrCompute("HELLO", "hello") { 3.0 }

            distance1 shouldBe 0.0
            distance2 shouldBe 3.0
            cache.size() shouldBe 2
        }

        // FZ-P1 T3 — a full cache stops storing (compute-without-store) instead of wiping wholesale,
        // so entries already cached are never lost on the hot path.
        "when full, keeps computing without evicting existing entries" {
            val cache = DistanceCache(maxSize = 2)

            cache.getOrCompute("a", "b") { 1.0 }
            cache.getOrCompute("c", "d") { 2.0 }
            // Cache is full (2); a new pair is computed but not stored, and existing entries survive.
            val fresh = cache.getOrCompute("e", "f") { 9.0 }

            fresh shouldBe 9.0
            cache.size() shouldBe 2
            // The first pair is still memoised (no wholesale clear).
            cache.getOrCompute("a", "b") { 99.0 } shouldBe 1.0
        }
    })

class TokenIndexTest :
    StringSpec({
        val candidates =
            listOf(
                Candidate.fromValues("1", "Hello World"),
                Candidate.fromValues("2", "Hello There"),
                Candidate.fromValues("3", "Goodbye World"),
            )

        "should find candidates with exact token" {
            val index = TokenIndex(candidates)
            val results = index.findCandidatesWithExactToken("hello")

            results shouldBe listOf("1", "2")
        }

        "should find candidates with any token" {
            val index = TokenIndex(candidates)
            val results = index.findCandidatesWithAnyToken(listOf("hello", "goodbye"))

            results shouldBe setOf("1", "2", "3")
        }

        "should return empty for unknown token" {
            val index = TokenIndex(candidates)
            val results = index.findCandidatesWithExactToken("unknown")

            results shouldBe emptyList()
        }

        "should get candidate by id" {
            val index = TokenIndex(candidates)
            val candidate = index.getCandidateById("1")

            candidate?.value shouldBe "Hello World"
        }

        "should return null for unknown id" {
            val index = TokenIndex(candidates)
            val candidate = index.getCandidateById("999")

            candidate shouldBe null
        }
    })

class CandidateTest :
    StringSpec({
        "should tokenize value correctly" {
            val candidate = Candidate.fromValues("1", "Hello World")

            candidate.tokens shouldBe listOf("hello", "world")
            candidate.tokenSet shouldBe setOf("hello", "world")
        }

        "should handle empty value" {
            val candidate = Candidate.fromValues("1", "")

            candidate.tokens shouldBe emptyList()
            candidate.tokenSet shouldBe emptySet()
        }

        "should handle value with multiple spaces" {
            val candidate = Candidate.fromValues("1", "Hello   World")

            candidate.tokens shouldBe listOf("hello", "world")
        }

        "should handle value with tabs" {
            val candidate = Candidate.fromValues("1", "Hello\tWorld")

            candidate.tokens shouldBe listOf("hello", "world")
        }

        "should deduplicate tokens in tokenSet but keep order in tokens" {
            val candidate = Candidate.fromValues("1", "hello world hello")

            candidate.tokens shouldBe listOf("hello", "world", "hello")
            candidate.tokenSet shouldBe setOf("hello", "world")
        }

        "should fold Czech diacritics and case when tokenizing" {
            Candidate.fromValues("1", "Zákazník").tokens shouldBe listOf("zakaznik")
            Candidate.fromValues("2", "PŘÍJMENÍ Zákazníka").tokens shouldBe listOf("prijmeni", "zakaznika")
            // candidates that differ only by diacritics/case produce the same token set
            Candidate.fromValues("3", "žluťoučký kůň").tokenSet shouldBe
                Candidate.fromValues("4", "ZLUTOUCKY KUN").tokenSet
        }
    })

class TextNormalizerTest :
    StringSpec({
        "fold lowercases" {
            TextNormalizer.fold("Hello World") shouldBe "hello world"
        }

        "fold strips Czech diacritics" {
            TextNormalizer.fold("Příliš žluťoučký kůň úpěl ďábelské ódy") shouldBe
                "prilis zlutoucky kun upel dabelske ody"
        }

        "fold collapses diacritic-only and case-only differences to the same string" {
            TextNormalizer.fold("ZÁKAZNÍKŮ") shouldBe TextNormalizer.fold("zakazniku")
        }

        "fold leaves plain ASCII unchanged apart from case" {
            TextNormalizer.fold("abc-123 XYZ") shouldBe "abc-123 xyz"
        }
    })
