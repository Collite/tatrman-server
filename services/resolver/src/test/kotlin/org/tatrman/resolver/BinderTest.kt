// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
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

        // --- MS-P3·S2 — the declared-containment collapse (contracts §8.3, design.md §10.2) ----

        val sales = "er.entity.sales"
        val amount = "er.entity.sales.amount_czk"
        val quantity = "er.entity.sales.quantity"
        val owners = mapOf(amount to sales, quantity to sales)

        "MS: an entity tied with its OWN attribute binds the attribute — one answer, two granularities" {
            val verdict =
                Binder.decide(
                    listOf(
                        declared(sales, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                        declared(amount, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                    ),
                    thresholds,
                    owners,
                )
            val bind = verdict.shouldBeInstanceOf<Binder.Bind>()
            bind.winner.match.targetRef shouldBe amount
            bind.admitted.map { it.match.targetRef } shouldContainExactly listOf(amount)
            // nothing is silently dropped: the owner is nameable in the rung log
            bind.rejected.map { it.match.targetRef } shouldContainExactly listOf(sales)
        }

        "MS: with no declared owners the same input stays Ambiguous (pre-v3 estates unchanged)" {
            val verdict =
                Binder.decide(
                    listOf(
                        declared(sales, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                        declared(amount, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                    ),
                    thresholds,
                )
            verdict.shouldBeInstanceOf<Binder.Ambiguous>().admitted.size shouldBe 2
        }

        "MS: two attributes of the SAME entity are still a genuine tie — no sibling collapse" {
            val verdict =
                Binder.decide(
                    listOf(
                        declared(amount, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                        declared(quantity, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                    ),
                    thresholds,
                    owners,
                )
            verdict.shouldBeInstanceOf<Binder.Ambiguous>().admitted.map {
                it.match.targetRef
            } shouldContainExactlyInAnyOrder
                listOf(amount, quantity)
        }

        "MS: an entity and an UNRELATED entity's attribute are still a genuine tie" {
            val verdict =
                Binder.decide(
                    listOf(
                        declared("er.entity.branch", 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                        declared(amount, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                    ),
                    thresholds,
                    owners,
                )
            verdict.shouldBeInstanceOf<Binder.Ambiguous>().admitted.size shouldBe 2
        }

        "MS: a WEAK owner row is rejected BEFORE the collapse ever sees it" {
            val verdict =
                Binder.decide(
                    listOf(
                        declared(sales, 0.99, EvidenceClass.EVIDENCE_CLASS_WEAK),
                        declared(amount, 0.72, EvidenceClass.EVIDENCE_CLASS_ANCHORED_FUZZY_STRONG),
                    ),
                    thresholds,
                    owners,
                )
            val bind = verdict.shouldBeInstanceOf<Binder.Bind>()
            bind.winner.match.targetRef shouldBe amount
            // the WEAK row is refused by RV-14, with its class intact — not by the collapse
            bind.rejected.single().evidenceClass shouldBe EvidenceClass.EVIDENCE_CLASS_WEAK
        }

        "MS: the collapse lives INSIDE the top class — a higher-class entity still wins outright" {
            val verdict =
                Binder.decide(
                    listOf(
                        declared(sales, 0.80, EvidenceClass.EVIDENCE_CLASS_EXACT),
                        declared(amount, 0.99, EvidenceClass.EVIDENCE_CLASS_ANCHORED_FUZZY_STRONG),
                    ),
                    thresholds,
                    owners,
                )
            // RV-14: the top class wins outright, and the attribute is not in it. No cross-class
            // rule crept in with the collapse.
            verdict
                .shouldBeInstanceOf<Binder.Bind>()
                .winner.match.targetRef shouldBe sales
        }

        "MS: a MEMBER identity is never collapsed, even when it carries an owned target ref" {
            // `M:` rows are data values, not model objects. This member row's targetRef IS the
            // entity the attribute declares as its owner — so without the `V:`-only guard the
            // collapse would delete a data value on the strength of a model relation and bind the
            // attribute alone. Drop the guard and this case turns into a Bind.
            val verdict =
                Binder.decide(
                    listOf(
                        member("row-in-sales", sales, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                        declared(amount, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                    ),
                    thresholds,
                    owners,
                )
            verdict.shouldBeInstanceOf<Binder.Ambiguous>().admitted.size shouldBe 2
        }

        "MS: out-of-band owners are not resurrected by the collapse" {
            // The entity is outside the tie band, so it is already rejected when the collapse runs
            // — and the attribute binds on its own, exactly as it did before MS.
            val verdict =
                Binder.decide(
                    listOf(
                        declared(amount, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                        declared(sales, 0.10, EvidenceClass.EVIDENCE_CLASS_EXACT),
                    ),
                    thresholds,
                    owners,
                )
            val bind = verdict.shouldBeInstanceOf<Binder.Bind>()
            bind.winner.match.targetRef shouldBe amount
            bind.rejected.map { it.match.targetRef } shouldContainExactly listOf(sales)
        }

        // --- review-084 F3 — malformed containment, the two shapes that empty the survivors ----

        "MS: a containment CYCLE declines the collapse instead of throwing" {
            // `owners` is data this service did not produce. A cycle collapses every identity and
            // leaves nothing to bind; the rule declines and the ordinary tie check answers, which
            // for two distinct identities in the band is what it always was — a refusal.
            val verdict =
                Binder.decide(
                    listOf(
                        declared(sales, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                        declared(amount, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                    ),
                    thresholds,
                    mapOf(sales to amount, amount to sales),
                )
            val ambiguous = verdict.shouldBeInstanceOf<Binder.Ambiguous>()
            ambiguous.admitted.map { it.match.targetRef } shouldContainExactlyInAnyOrder listOf(sales, amount)
            // and nothing was moved to `rejected` by a collapse that did not happen
            ambiguous.rejected.shouldBeEmpty()
        }

        "MS: a ref declared as its OWN owner still binds — never a clarification with one option" {
            // The degenerate half of the same guard, and the one that used to be wrong: returning
            // `Ambiguous` from the empty-survivors branch made `Ambiguous` reachable with a single
            // admitted candidate, and `GateSpans.outcomeOf` renders any ambiguous span by offering
            // its contenders. One row in, one option out — the exact question the collapse exists
            // to remove, produced from malformed data instead of from good data.
            val verdict =
                Binder.decide(
                    listOf(declared(amount, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT)),
                    thresholds,
                    mapOf(amount to amount),
                )
            verdict
                .shouldBeInstanceOf<Binder.Bind>()
                .winner.match.targetRef shouldBe amount
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
        /** A MEMBER row that nonetheless carries a target ref — a data value inside an object. */
        private fun member(
            id: String,
            targetRef: String,
            score: Double,
            evidenceClass: EvidenceClass,
        ): Binder.ClassedMatch =
            Binder.ClassedMatch(
                FuzzyMatch
                    .newBuilder()
                    .setCandidateId(id)
                    .setCandidate(id)
                    .setScore(score)
                    .setTargetRef(targetRef)
                    .setCategory(targetRef)
                    .setSource(SourceTag.MEMBER)
                    .build(),
                evidenceClass,
            )

        /** A DECLARED row for a model object — identity `V:targetRef`, the collapse's unit. */
        private fun declared(
            targetRef: String,
            score: Double,
            evidenceClass: EvidenceClass,
        ): Binder.ClassedMatch =
            Binder.ClassedMatch(
                FuzzyMatch
                    .newBuilder()
                    .setCandidateId("lex:$targetRef")
                    .setCandidate(targetRef)
                    .setScore(score)
                    .setTargetRef(targetRef)
                    .setCategory(targetRef)
                    .setSource(SourceTag.DECLARED)
                    .build(),
                evidenceClass,
            )

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
