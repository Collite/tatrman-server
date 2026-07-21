// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

/** FZ-P2 T1 — the retrieval seam, specced against IndexFirstRetriever on a micro-corpus. */
class CandidateRetrieverSpec :
    StringSpec({
        // df: davidoff{0,1}=2, noir{0,2}=2 (rare); classic{1,3,4,5}=4, nescafe{2,3,4,5}=4 (common). N=6.
        val candidates =
            listOf(
                Candidate.fromValues("0", "davidoff noir"),
                Candidate.fromValues("1", "davidoff classic"),
                Candidate.fromValues("2", "nescafe noir"),
                Candidate.fromValues("3", "nescafe classic"),
                Candidate.fromValues("4", "nescafe classic"),
                Candidate.fromValues("5", "nescafe classic"),
            )
        val vocab = TokenVocabulary(candidates)
        val retriever =
            IndexFirstRetriever { cat -> if (cat == "db.nope.missing") TokenVocabulary(emptyList()) else vocab }

        // The seam returns Candidates best-first; these fixtures give each candidate an id equal to
        // its ordinal, so mapping back to the ordinal keeps the ranking assertions readable.
        fun ret(
            q: String,
            topN: Int = 10,
            cat: String? = null,
        ): List<Int> {
            val tokens = Candidate.tokenize(q)
            return retriever.retrieve(tokens, tokens, cat, topN).map { it.id.toInt() }
        }

        "(a) exact-subset query ranks the both-token candidate first" {
            ret("davidoff noir").first() shouldBe 0
        }

        "(b) a rarer matched token outranks a common matched token (IDF penalty visible)" {
            // Ordinal 0 matches rare 'noir'; ordinal 3 matches common 'classic'. 0 must rank ahead.
            val r = ret("noir classic")
            r.indexOf(0) shouldBe 0
            (r.indexOf(0) < r.indexOf(3)) shouldBe true
        }

        "(c) a query token matching nothing penalizes all candidates equally" {
            // 'zzz' resolves to nothing; only the two 'davidoff' candidates are touched, and they
            // score identically (davidoff matched + equal zzz penalty) ⇒ retrieved in ordinal order.
            ret("davidoff zzz") shouldBe listOf(0, 1)
        }

        "(d) an all-typo query still retrieves the true candidate via ED≤2 resolution" {
            // 'davidof' → 'davidoff' (ED 1); the both-token candidate is still found and ranks first.
            val r = ret("davidof noir")
            r shouldContain 0
            r.first() shouldBe 0
        }

        "(e) topN is respected" {
            ret("davidoff noir", topN = 1) shouldBe listOf(0)
        }

        "(f) determinism: repeated calls are identical; ties break by ascending ordinal" {
            ret("davidoff zzz") shouldBe ret("davidoff zzz")
            // 0 and 1 tie ⇒ ascending ordinal.
            ret("davidoff zzz") shouldBe listOf(0, 1)
        }

        "(g) explicit-unknown category ⇒ empty" {
            ret("davidoff noir", cat = "db.nope.missing") shouldBe emptyList()
        }
    })
