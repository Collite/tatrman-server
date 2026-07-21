// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.math.abs

/** FZ-P2 T4 — the vocabulary resolver, pinned before the retriever consumes it. */
class VocabularyResolverSpec :
    StringSpec({
        val candidates =
            listOf(
                Candidate.fromValues("A", "classic gold"),
                Candidate.fromValues("B", "premium silver"),
                Candidate.fromValues("C", "espresso intense"),
                Candidate.fromValues("D", "harmony balance"),
                Candidate.fromValues("E", "nescafe original"),
                Candidate.fromValues("F", "tchibo barista"),
            )
        val vocab = TokenVocabulary(candidates)

        fun resolver() = VocabularyResolver(vocab)

        "(a) exact vocab hit ⇒ single entry, distance 0, quality 1.0, weight = idf" {
            val r = resolver().resolve("classic")
            r.size shouldBe 1
            r[0].tokenId shouldBe vocab.idOf("classic")
            r[0].distance shouldBe 0
            r[0].quality shouldBe 1.0
            abs(r[0].weight - vocab.idf(vocab.idOf("classic"))) shouldBeLessThan 1e-12
        }

        "(b) one-typo token 'clasic' ⇒ contains 'classic' at distance 1, quality 1 - 1/7" {
            val r = resolver().resolve("clasic")
            val hit = r.firstOrNull { it.tokenId == vocab.idOf("classic") }
            (hit != null) shouldBe true
            hit!!.distance shouldBe 1
            abs(hit.quality - (1.0 - 1.0 / 7.0)) shouldBeLessThan 1e-12
        }

        "(c) an ED-2 hit is found; an ED-3 token is absent" {
            // "clssc" → "classic": insert a,i and… actually ED("clssc","classic")=2 (insert 'a','i' ... )
            // Use a controlled pair: "gxld" is ED-2 from "gold" (sub x→o? no). Build explicitly:
            // "gld" vs "gold" = ED 1; "glld" vs "gold" = ED 1; "gxxd" vs "gold" = ED 2.
            val ed2 = resolver().resolve("gxxd")
            (ed2.any { it.tokenId == vocab.idOf("gold") }) shouldBe true
            ed2.first { it.tokenId == vocab.idOf("gold") }.distance shouldBe 2

            // "gxxxd" vs "gold" = ED 3 ⇒ must NOT resolve to gold.
            val ed3 = resolver().resolve("gxxxd")
            (ed3.any { it.tokenId == vocab.idOf("gold") }) shouldBe false
        }

        "(d) a token nothing is within ED 2 of resolves to nothing" {
            resolver().resolve("zzzzzzzz").shouldBeEmpty()
        }

        "(e) results are sorted by distance then tokenId (determinism)" {
            // 'clasic' is ED-1 from 'classic'; check the whole list is (distance, tokenId)-ordered.
            val r = resolver().resolve("clasic")
            r shouldBe r.sortedWith(compareBy({ it.distance }, { it.tokenId }))
        }

        "(f) length-bucket pruning still finds a match whose length differs by exactly 2" {
            // "espress" (7) → "espresso" (8) is ED 1 (length diff 1); build a length-diff-2 case:
            // "harmony" (7) vs "harmony" (7) is ED 2 (transpose = 2 subs); use "harmon" (6) vs "harmony" (7)?
            // Cleaner: "premiummm" (9) vs "premium" (7): length diff 2, ED 2 (delete 2 m's).
            val r = resolver().resolve("premiummm")
            (r.any { it.tokenId == vocab.idOf("premium") }) shouldBe true
        }
    })
