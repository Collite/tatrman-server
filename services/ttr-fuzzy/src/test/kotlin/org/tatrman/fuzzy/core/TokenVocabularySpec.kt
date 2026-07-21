// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.ln

/** FZ-P2 T1 — the interned vocabulary surface, pinned before the retrieval layer consumes it. */
class TokenVocabularySpec :
    StringSpec({
        // Shared ("classic","gold","premium"), unique ("silver","zzz","noir"), diacritic ("Dvořák"→"dvorak").
        val candidates =
            listOf(
                Candidate.fromValues("A", "classic gold"), // 0
                Candidate.fromValues("B", "classic silver"), // 1
                Candidate.fromValues("C", "premium gold"), // 2
                Candidate.fromValues("D", "Dvořák classic"), // 3
                Candidate.fromValues("E", "unique zzz"), // 4
                Candidate.fromValues("F", "premium noir"), // 5
            )

        "(a) size == number of distinct folded surface∪lemma tokens" {
            val vocab = TokenVocabulary(candidates)
            val expected = candidates.flatMap { it.allTokenSet }.toSet()
            vocab.size shouldBe expected.size
            vocab.tokens.toSet() shouldBe expected
        }

        "(b) token ids are stable across rebuilds of the same corpus (sorted-vocab determinism)" {
            val v1 = TokenVocabulary(candidates)
            val v2 = TokenVocabulary(candidates)
            v1.idOf("classic") shouldBe v2.idOf("classic")
            v1.tokens.toList() shouldBe v2.tokens.toList()
            // Sorted ⇒ tokens are in ascending order.
            v1.tokens.toList() shouldBe v1.tokens.toList().sorted()
        }

        "(c) idf(id) matches the TokenIndex formula ln((N+1)/(df+1))+1 for a hand-computed df" {
            val vocab = TokenVocabulary(candidates)
            // "classic" ∈ {A,B,D} ⇒ df=3, N=6.
            val expected = ln((6 + 1.0) / (3 + 1.0)) + 1.0
            abs(vocab.idf(vocab.idOf("classic")) - expected) shouldBeLessThan 1e-12
            // … and it agrees with the legacy TokenIndex for the same token.
            abs(vocab.idf(vocab.idOf("classic")) - TokenIndex(candidates).idf("classic")) shouldBeLessThan 1e-12
        }

        "(d) postings(id) are exactly the candidate ordinals containing the token, ascending" {
            val vocab = TokenVocabulary(candidates)
            vocab.postings(vocab.idOf("classic")).toList() shouldBe listOf(0, 1, 3)
            vocab.postings(vocab.idOf("premium")).toList() shouldBe listOf(2, 5)
            vocab.postings(vocab.idOf("dvorak")).toList() shouldBe listOf(3) // diacritic folded
            vocab.postings(vocab.idOf("noir")).toList() shouldBe listOf(5)
        }

        "(e) unknown token ⇒ idOf == -1" {
            val vocab = TokenVocabulary(candidates)
            vocab.idOf("nonexistent") shouldBe -1
        }

        "length buckets group token ids by token length" {
            val vocab = TokenVocabulary(candidates)
            // "gold" (4), "noir" (4), "zzz" (3) …
            vocab.lengthBuckets[4]!!.map { vocab.tokens[it] }.toSet() shouldBe
                vocab.tokens.filter { it.length == 4 }.toSet()
        }
    })
