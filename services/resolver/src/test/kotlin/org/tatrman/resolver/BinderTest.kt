// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.Binder
import org.tatrman.resolver.v1.EvidenceClass

/**
 * RV-P2.2.T1 — the evidence-class gate, tested as the pure decision it is.
 *
 * The class order is `EXACT > DECLARED_ALIAS > LEARNED_ALIAS > ANCHORED_FUZZY_STRONG >
 * UNANCHORED_FUZZY_STRONG > WEAK` (RV-14), and the one rule that makes it a lexicographic order
 * rather than a tie-breaker is asserted here from several directions: **a numeric score compares
 * only WITHIN a class**. A 0.99 in a lower class does not beat a 0.62 in a higher one, and no
 * score whatsoever lifts a WEAK candidate into a binding.
 *
 * These cases feed [Binder.decide] pre-classified candidates on purpose — deriving the class from
 * a matcher row is a separate question with its own list (T2/T4, `EvidenceClassesTest`), and
 * mixing the two would make an ordering bug look like a derivation bug.
 */
class BinderTest :
    StringSpec({

        val thresholds = ResolverThresholds.LIVE

        "(a) a unique top class binds, and everything below it is rejected rather than recorded" {
            val verdict =
                Binder.decide(
                    listOf(
                        classed("acct-501001", 0.62, EvidenceClass.EVIDENCE_CLASS_DECLARED_ALIAS),
                        classed("cc-5au5001", 0.99, EvidenceClass.EVIDENCE_CLASS_UNANCHORED_FUZZY_STRONG),
                    ),
                    thresholds,
                )
            val bind = verdict.shouldBeInstanceOf<Binder.Bind>()
            bind.winner.match.candidateId shouldBe "acct-501001"
            bind.admitted.map { it.match.candidateId } shouldContainExactly listOf("acct-501001")
            bind.rejected.map { it.match.candidateId } shouldContainExactly listOf("cc-5au5001")
        }

        "(b) two candidates in the SAME class within the tie band → ambiguous, nothing binds" {
            val verdict =
                Binder.decide(
                    listOf(
                        classed("df-adnak", 0.72, EvidenceClass.EVIDENCE_CLASS_ANCHORED_FUZZY_STRONG),
                        classed("df-belus", 0.70, EvidenceClass.EVIDENCE_CLASS_ANCHORED_FUZZY_STRONG),
                    ),
                    thresholds,
                )
            val ambiguous = verdict.shouldBeInstanceOf<Binder.Ambiguous>()
            ambiguous.admitted.map { it.match.candidateId } shouldContainExactlyInAnyOrder
                listOf("df-adnak", "df-belus")
        }

        "same class but OUTSIDE the tie band: the score decides, because they are comparable" {
            val verdict =
                Binder.decide(
                    listOf(
                        classed("df-adnak", 0.95, EvidenceClass.EVIDENCE_CLASS_ANCHORED_FUZZY_STRONG),
                        classed("df-belus", 0.71, EvidenceClass.EVIDENCE_CLASS_ANCHORED_FUZZY_STRONG),
                    ),
                    thresholds,
                )
            verdict
                .shouldBeInstanceOf<Binder.Bind>()
                .winner.match.candidateId shouldBe "df-adnak"
        }

        "(c) a higher class beats a higher score in a lower class — 0.99 UNANCHORED loses to 0.62 DECLARED" {
            val verdict =
                Binder.decide(
                    listOf(
                        classed("near-name", 0.99, EvidenceClass.EVIDENCE_CLASS_UNANCHORED_FUZZY_STRONG),
                        classed("declared", 0.62, EvidenceClass.EVIDENCE_CLASS_DECLARED_ALIAS),
                    ),
                    thresholds,
                )
            verdict
                .shouldBeInstanceOf<Binder.Bind>()
                .winner.match.candidateId shouldBe "declared"
        }

        "(d) WEAK never binds, whatever its score — even alone, even at 1.0" {
            val verdict =
                Binder.decide(
                    listOf(classed("garbage", 1.0, EvidenceClass.EVIDENCE_CLASS_WEAK)),
                    thresholds,
                )
            val noBind = verdict.shouldBeInstanceOf<Binder.NoBind>()
            noBind.admitted shouldBe emptyList()
            noBind.rejected.map { it.match.candidateId } shouldContainExactly listOf("garbage")
        }

        "(d′) a whole field of WEAK candidates is a G1/G3, not a clarification to offer the user" {
            val verdict =
                Binder.decide(
                    listOf(
                        classed("5au-5001", 0.667, EvidenceClass.EVIDENCE_CLASS_WEAK),
                        classed("7ax-0800", 0.500, EvidenceClass.EVIDENCE_CLASS_WEAK),
                    ),
                    thresholds,
                )
            verdict.shouldBeInstanceOf<Binder.NoBind>().admitted shouldBe emptyList()
        }

        "(e) LEARNED_ALIAS sits below DECLARED_ALIAS: only lexicon promotion buys the higher class" {
            val verdict =
                Binder.decide(
                    listOf(
                        classed("learned", 0.98, EvidenceClass.EVIDENCE_CLASS_LEARNED_ALIAS),
                        classed("declared", 0.80, EvidenceClass.EVIDENCE_CLASS_DECLARED_ALIAS),
                    ),
                    thresholds,
                )
            verdict
                .shouldBeInstanceOf<Binder.Bind>()
                .winner.match.candidateId shouldBe "declared"
        }

        "(f) an empty candidate set is a NoBind — the gate reports nothing, the gap layer types it" {
            Binder.decide(emptyList(), thresholds).shouldBeInstanceOf<Binder.NoBind>().rejected shouldBe emptyList()
        }

        "two exact rows with DIFFERENT identities are a genuine tie — refuse over guess (RS-26)" {
            val verdict =
                Binder.decide(
                    listOf(
                        classed("fap-doklad", 0.9999, EvidenceClass.EVIDENCE_CLASS_EXACT),
                        classed("fap-ukazatel", 0.9999, EvidenceClass.EVIDENCE_CLASS_EXACT),
                    ),
                    thresholds,
                )
            verdict.shouldBeInstanceOf<Binder.Ambiguous>().admitted.size shouldBe 2
        }

        "the SAME identity reached twice is one binding, not an ambiguity" {
            val verdict =
                Binder.decide(
                    listOf(
                        classed("b-praha", 0.92, EvidenceClass.EVIDENCE_CLASS_EXACT),
                        classed("b-praha", 0.90, EvidenceClass.EVIDENCE_CLASS_EXACT),
                    ),
                    thresholds,
                )
            val bind = verdict.shouldBeInstanceOf<Binder.Bind>()
            bind.admitted.size shouldBe 1
            // the stronger of the two speaks for the identity
            bind.winner.match.score shouldBe 0.92
        }

        "UNSPECIFIED never outranks a real class, though proto3 gives it the zero value" {
            val verdict =
                Binder.decide(
                    listOf(
                        classed("unknown", 1.0, EvidenceClass.EVIDENCE_CLASS_UNSPECIFIED),
                        classed("anchored", 0.75, EvidenceClass.EVIDENCE_CLASS_ANCHORED_FUZZY_STRONG),
                    ),
                    thresholds,
                )
            verdict
                .shouldBeInstanceOf<Binder.Bind>()
                .winner.match.candidateId shouldBe "anchored"
        }
    }) {
    private companion object {
        /** A member row at [score], already carrying the class the gate is being asked to order by. */
        private fun classed(
            id: String,
            score: Double,
            evidenceClass: EvidenceClass,
        ): Binder.ClassedMatch =
            Binder.ClassedMatch(
                FuzzyMatch
                    .newBuilder()
                    .setCandidateId(id)
                    .setCandidate(id)
                    .setScore(score)
                    .setCategory("md.dimension.Account.code")
                    .setSource(SourceTag.MEMBER)
                    .build(),
                evidenceClass,
            )
    }
}
