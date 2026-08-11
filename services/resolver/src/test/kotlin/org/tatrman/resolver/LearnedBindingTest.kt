// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.LayerVersions
import org.tatrman.fuzzy.v1.Provenance
import org.tatrman.fuzzy.v1.SourceTag as FuzzySourceTag
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.Binder
import org.tatrman.resolver.pipeline.Bindings
import org.tatrman.resolver.pipeline.DomainSpanCandidate
import org.tatrman.resolver.v1.EvidenceClass
import org.tatrman.resolver.v1.SourceTag

/**
 * RV-P7.3 T5 — **the LEARNED class, exercised with real learned candidates for the first time.**
 *
 * `EVIDENCE_CLASS_LEARNED_ALIAS` has been in the enum since RV-P2 and its ordering has been tested
 * since `BinderTest` (e) — but always against a hand-stamped class, because until RV-P7.3 nothing
 * could produce a `SOURCE_TAG_LEARNED` row. Now the overlay does, so the passthrough is checked
 * end to end: a row lex-matcher tags LEARNED arrives as a binding at LEARNED_ALIAS with its
 * provenance intact, and the RV-14 order (`EXACT > DECLARED_ALIAS > LEARNED_ALIAS > …`) decides
 * against real rivals rather than fabricated ones.
 *
 * The passthrough itself is deliberately unremarkable — no branch in the resolver knows the overlay
 * exists. That is the claim: **the layer is evidence, not a special case.**
 */
private val THRESHOLDS = ResolverThresholds.LIVE

private fun mention(text: String) =
    DomainSpanCandidate(
        text,
        0,
        text.length,
        listOf("md.x"),
        listOf("md.x"),
        anchored = true,
        origin = DomainSpanCandidate.Origin.ANCHOR_PHRASE,
        lemma = text,
    )

/** A row exactly as `OverlayArchiveSource` produces it and lex-matcher serialises it. */
private fun learned(
    targetRef: String,
    score: Double,
    autoBindable: Boolean = true,
): FuzzyMatch =
    FuzzyMatch
        .newBuilder()
        .setCandidateId("learned:$targetRef:cs:tržba")
        .setCandidate("tržba")
        .setScore(score)
        .setCategory(targetRef)
        .setSource(FuzzySourceTag.LEARNED)
        .setTargetRef(targetRef)
        // No authored method: nobody wrote a learned alias down, which is what makes it learned.
        .setAutoBindable(autoBindable)
        .setProvenance(Provenance.newBuilder().setProducer("lex-matcher").setMethod("TATRMAN"))
        .build()

private fun declared(
    targetRef: String,
    method: String,
    score: Double,
): FuzzyMatch =
    FuzzyMatch
        .newBuilder()
        .setCandidateId("lex:$targetRef")
        .setCandidate("čistý obrat")
        .setScore(score)
        .setCategory(targetRef)
        .setSource(FuzzySourceTag.DECLARED)
        .setTargetRef(targetRef)
        .setMatchMethod(method)
        .setProvenance(Provenance.newBuilder().setProducer("lex-matcher").setMethod("TATRMAN"))
        .build()

class LearnedBindingTest :
    StringSpec({

        "an overlay row binds at LEARNED_ALIAS, with source=LEARNED and full provenance" {
            val verdict =
                Binder
                    .gate(listOf(learned("md.measure.net", score = 0.96)), mention("tržba"), THRESHOLDS)
                    .shouldBeInstanceOf<Binder.Bind>()
            val binding = Bindings.of(verdict.winner, "snap-learned")

            binding.evidenceClass shouldBe EvidenceClass.EVIDENCE_CLASS_LEARNED_ALIAS
            binding.source shouldBe SourceTag.SOURCE_TAG_LEARNED
            binding.ref shouldBe "md.measure.net"
            withClue("the class carries the confidence; the number only compares within it (RV-14)") {
                binding.inClassScore shouldBe 0.96
            }
            withClue("a learned alias has no author, so it reports no method rather than guessing one") {
                binding.method shouldBe org.tatrman.resolver.v1.MatchMethod.MATCH_METHOD_UNSPECIFIED
            }
        }

        // RV-14's order, decided for the first time between a real declared row and a real learned
        // one. The bar is deliberate: promotion into `lexicon/` is what buys the higher class, and
        // an estate must not be able to out-vote its own modelers by answering enough asks.
        "a DECLARED rival beats a better-scoring LEARNED one — only promotion buys the higher class" {
            val verdict =
                Binder
                    .gate(
                        listOf(
                            learned("md.measure.net", score = 0.99),
                            declared("md.measure.gross", "TOKENS", score = 0.80),
                        ),
                        mention("čistý obrat"),
                        THRESHOLDS,
                    ).shouldBeInstanceOf<Binder.Bind>()

            withClue("0.99 loses to 0.80 because the classes are not comparable by number") {
                verdict.winner.evidenceClass shouldBe EvidenceClass.EVIDENCE_CLASS_DECLARED_ALIAS
                Bindings.of(verdict.winner, "s").ref shouldBe "md.measure.gross"
            }
        }

        // The other side of the same order. Note the rival has to be a MEMBER row to be a *fuzzy*
        // one at all: a METADATA row is declared-tier (`!isMember ⇒ DECLARED_ALIAS`), because a
        // model label is still the estate's own word for the thing, harvested rather than authored.
        "a LEARNED row beats a fuzzy data hit — a user's confirmation outranks a resemblance" {
            val verdict =
                Binder
                    .gate(
                        listOf(
                            learned("md.measure.net", score = 0.70),
                            FuzzyMatch
                                .newBuilder()
                                .setCandidateId("m-1")
                                .setCandidate("Tržná")
                                .setScore(0.98)
                                .setCategory("er.branch.name")
                                .setSource(FuzzySourceTag.MEMBER)
                                .setProvenance(Provenance.newBuilder().setProducer("lex-matcher").setMethod("TATRMAN"))
                                .build(),
                        ),
                        mention("tržba"),
                        THRESHOLDS,
                    ).shouldBeInstanceOf<Binder.Bind>()

            withClue("0.70 beats 0.98: a data value that merely looks alike is weaker evidence") {
                verdict.winner.evidenceClass shouldBe EvidenceClass.EVIDENCE_CLASS_LEARNED_ALIAS
                Bindings.of(verdict.winner, "s").ref shouldBe "md.measure.net"
            }
        }

        // The far end of a suppression that began as a NEGATIVE overlay entry in another repo.
        // The gate does not need to know that: `auto_bindable=false` classes the row WEAK, and
        // RV-14's WEAK never binds whatever its score. P1.4's reason-agnostic ruling, paying off.
        "a suppressed LEARNED row is classed WEAK — offered, never bound, however well it scores" {
            val verdict =
                Binder
                    .gate(
                        listOf(learned("md.measure.net", score = 0.99, autoBindable = false)),
                        mention("tržba"),
                        THRESHOLDS,
                    ).shouldBeInstanceOf<Binder.NoBind>()

            withClue("still in the lattice as a rejected contender — a wrong negative stays visible (RV-2)") {
                verdict.rejected
                    .single()
                    .match.targetRef shouldBe "md.measure.net"
                verdict.rejected.single().evidenceClass shouldBe EvidenceClass.EVIDENCE_CLASS_WEAK
            }
        }

        "the overlay version completes the RV-39 tuple, and absence still parses" {
            val withOverlay =
                LayerVersions
                    .newBuilder()
                    .setLexiconArtifactHash("sha256:abc")
                    .setOverlayVersion("42")
                    .build()
            withOverlay.hasOverlayVersion() shouldBe true
            withOverlay.overlayVersion shouldBe "42"

            withClue("a pre-P7 estate sends no overlay_version, and that must remain readable") {
                LayerVersions
                    .newBuilder()
                    .setLexiconArtifactHash("sha256:abc")
                    .build()
                    .hasOverlayVersion() shouldBe false
            }
        }
    })
