// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.Provenance
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.model.ResolverEntityType
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.Bindings
import org.tatrman.resolver.pipeline.Bound
import org.tatrman.resolver.pipeline.DomainSpanCandidate
import org.tatrman.resolver.pipeline.FrameRolePreps
import org.tatrman.resolver.pipeline.GatedSpan
import org.tatrman.resolver.pipeline.LatticeAssembler
import org.tatrman.resolver.pipeline.UniversalBinding
import org.tatrman.resolver.v1.Binding
import org.tatrman.resolver.v1.FrameRole
import org.tatrman.resolver.v1.GapKind
import org.tatrman.resolver.v1.ResolutionState
import org.tatrman.resolver.v1.UniversalEntityType
import org.tatrman.fuzzy.v1.TargetClass as FuzzyTargetClass
import org.tatrman.resolver.v1.TargetClass as LatticeTargetClass

/**
 * RV-P1.6.T6 (RV-42) — **the trigger annotation inside the lattice**: what it does to a mention,
 * what it does to the grounded span that mention governs, and what it records.
 *
 * The question this closes is the one P1.6 could not answer while `ResolutionState` did not exist.
 * The kernels already know how to ask *"is this span mine?"* of their own slice (T4/T5); nobody
 * said *which* kernel to hand a span to. That is the narrowing, and it is a fact about the
 * MENTION — "the user said *roce*, so the year beside it is chrono's" — which is why it lives here
 * and not in the kernels.
 *
 * All three fixtures use the H1 shape (*"… v roce 2025 …"*) because the anchoring is a government
 * relation, and a relation needs a real parse to be read off.
 */
class GroundingLatticeTest :
    StringSpec({

        "a trigger annotates the mention BESIDE its model binding, and cannot outrank it" {
            val state = assemble(triggers = mapOf(ROCE_SPAN to listOf(chronoTrigger())))

            val roce = state.mentionsList.single { it.span.text == "roce" }
            roce.bindingsList.map { it.ref } shouldContainExactly
                listOf("md.dimension.Calendar.year", "ground:chrono")
            roce.bindingsList[1].targetClass shouldBe LatticeTargetClass.TARGET_CLASS_GROUNDING_TRIGGER
            // The model binding still leads, so frame-role derivation reads the model fact — a
            // grounding trigger must never be able to move a role. `roce` is an oblique with a
            // preposition, and stays the FILTER it was without the annotation.
            roce.frameRolesList shouldContainExactly listOf(FrameRole.FRAME_ROLE_FILTER)
            assemble().mentionsList.single { it.span.text == "roce" }.frameRolesList shouldBe roce.frameRolesList
        }

        "the trigger anchors the chrono call on the year it governs, and the rung log says so" {
            val state = assemble(triggers = mapOf(ROCE_SPAN to listOf(chronoTrigger())))

            val year = state.valuesList.single { it.span.text == "2025" }
            year.grounding.kernel shouldBe "nametag3" // who typed it
            year.grounding.ref shouldBe "ground:chrono" // which slice claimed the span it hangs off

            // The narrowing as an audit entry: chrono, on this mention, for this value. A caller
            // reads it instead of offering the whole question to all three kernels.
            val narrow = state.rungLogList.filter { it.action == LatticeAssembler.GROUND_NARROW_ACTION }
            narrow.map { it.rung } shouldContainExactly listOf(LatticeAssembler.CORE_RUNG)
            narrow.single().mentionIdsList shouldContainExactly listOf(mentionId(state, "roce"))
            narrow.single().valueIdsList shouldContainExactly listOf(valueId(state, "2025"))

            // …and the annotation is additive: the value the kernel will ground is untouched.
            year.grounding.normalizedValue shouldBe "2025"
            state.rungLogList.first().action shouldBe "annotate"
        }

        "a trigger whose kernel does not own the universal's class narrows nothing" {
            // `money` has vocabulary for the word beside a DATE. The universal layer says what the
            // span IS; the trigger says which kernel claimed the words next to it, and only when
            // the two agree has anything been narrowed.
            val state = assemble(triggers = mapOf(ROCE_SPAN to listOf(chronoTrigger(ref = "ground:money"))))

            state.valuesList
                .single { it.span.text == "2025" }
                .grounding.ref shouldBe ""
            // the mention is still annotated — money really does know that word — but the entry
            // names no value, because no grounding invocation was scoped by it
            val narrow = state.rungLogList.single { it.action == LatticeAssembler.GROUND_NARROW_ACTION }
            narrow.mentionIdsList shouldContainExactly listOf(mentionId(state, "roce"))
            narrow.valueIdsList.shouldBeEmpty()
        }

        "a trigger-carrying mention is not a G1 — the core DOES know what the word is about" {
            // `fiskální rok` binds no model object in this estate. Without the annotation that is
            // an unbound mention and the ladder would go and ask the user what it means; with it,
            // the core has already said the span is chrono's and chrono will ground it. G1 is
            // "nothing in this estate binds that word", and a slice entry IS something.
            val unbound = mention("roce", 29, 33, head = 5)
            val state =
                assemble(
                    gated = listOf(GatedSpan(unbound, emptyList(), ambiguous = false)),
                    triggers = mapOf(ROCE_SPAN to listOf(chronoTrigger())),
                )

            state.mentionsList
                .single()
                .bindingsList
                .map { it.ref } shouldContainExactly listOf("ground:chrono")
            state.gapsList.none { it.kind == GapKind.GAP_KIND_G1_UNBOUND } shouldBe true

            // and the control: the same mention with no trigger IS a G1
            val bare = assemble(gated = listOf(GatedSpan(unbound, emptyList(), ambiguous = false)))
            bare.gapsList.map { it.kind } shouldContainExactly listOf(GapKind.GAP_KIND_G1_UNBOUND)
        }

        "no dep parse, no anchoring: government is read, never guessed from adjacency" {
            val unparsed =
                PARSE
                    .toBuilder()
                    .clearTokens()
                    .addAllTokens(PARSE.tokensList.map { it.toBuilder().setDepHead(0).build() })
                    .build()
            val state = assemble(parse = unparsed, triggers = mapOf(ROCE_SPAN to listOf(chronoTrigger())))

            state.valuesList
                .single { it.span.text == "2025" }
                .grounding.ref shouldBe ""
            state.rungLogList
                .single { it.action == LatticeAssembler.GROUND_NARROW_ACTION }
                .valueIdsList
                .shouldBeEmpty()
        }

        "a FLOOR guess whose only hit is a trigger reaches the lattice — asking is not enough" {
            // GroundingTriggers.MENTION_ORIGINS includes NGRAM_FLOOR deliberately: in the
            // parse-less path every span is a floor guess, and a trigger hit there is a lexicon
            // fact rather than a syntactic one. But the assembler split the layers BEFORE merging
            // triggers, so a floor span with no MODEL contender was dropped and its trigger went
            // with it — the query went out, the answer came back, and the annotation was thrown
            // away for exactly the degraded estates the inclusion exists to help. Nothing failed,
            // because the trigger tests stopped at "was the span asked about?" (p1-6 review).
            val floor =
                mention("roce", 29, 33, head = 1)
                    .copy(origin = DomainSpanCandidate.Origin.NGRAM_FLOOR, anchored = false)
            val state =
                assemble(
                    gated = listOf(GatedSpan(floor, emptyList(), ambiguous = false)),
                    triggers = mapOf(ROCE_SPAN to listOf(chronoTrigger())),
                )

            state.mentionsList
                .single()
                .bindingsList
                .map { it.ref } shouldContainExactly listOf("ground:chrono")
            // and the narrowing is recorded, which is the whole point of keeping the span
            state.rungLogList.map { it.action } shouldContainExactly
                listOf("annotate", LatticeAssembler.GROUND_NARROW_ACTION)

            // the control, unchanged: a floor guess that hit NOTHING is still noise, and the
            // honest record of that situation is the G5 degrade gap, not an empty mention
            assemble(gated = listOf(GatedSpan(floor, emptyList(), ambiguous = false)))
                .mentionsList
                .shouldBeEmpty()
        }

        "an estate with no grounding vocabulary emits exactly what P2.1 emitted" {
            val state = assemble()

            state.mentionsList.flatMap { it.bindingsList }.none {
                it.targetClass == LatticeTargetClass.TARGET_CLASS_GROUNDING_TRIGGER
            } shouldBe true
            state.valuesList.all { it.grounding.ref.isEmpty() } shouldBe true
            state.rungLogList.map { it.action } shouldContainExactly listOf("annotate")
        }
    }) {
    companion object {
        private val ROCE_SPAN = 29 to 33

        /**
         * *"… v roce 2025 …"* as Stanza parses it — `2025` is a `nummod` of `roce` (token 5,
         * 1-based dep head 6). Char offsets match the H1 fixture so the two read alike.
         */
        private val PARSE: AnalyzeResponse =
            AnalyzeResponse
                .newBuilder()
                .setLanguage("cs")
                .addTokens(token("v", "v", "ADP", 27, 28, depHead = 2, rel = "case"))
                .addTokens(token("roce", "rok", "NOUN", 29, 33, depHead = 0, rel = "root"))
                .addTokens(token("2025", "2025", "NUM", 34, 38, depHead = 2, rel = "nummod"))
                .build()

        private fun token(
            text: String,
            lemma: String,
            upos: String,
            start: Int,
            end: Int,
            depHead: Int,
            rel: String,
        ): Token =
            Token
                .newBuilder()
                .setText(text)
                .setLemma(lemma)
                .setUpos(upos)
                .setCharStart(start)
                .setCharEnd(end)
                .setDepHead(depHead)
                .setDepRelation(rel)
                .build()

        private fun assemble(
            parse: AnalyzeResponse = PARSE,
            gated: List<GatedSpan> =
                listOf(
                    GatedSpan(
                        mention("roce", 29, 33, head = 1),
                        listOf(declaredYear()),
                        ambiguous = false,
                    ),
                ),
            triggers: Map<Pair<Int, Int>, List<Binding>> = emptyMap(),
        ): ResolutionState =
            LatticeAssembler.assemble(
                parse = parse,
                gate = Bound(emptyList(), 0.0, gated),
                ungatedMentions = emptyList(),
                universals =
                    listOf(
                        UniversalBinding(
                            start = 34,
                            end = 38,
                            text = "2025",
                            entityType = UniversalEntityType.DATE,
                            rawText = "2025",
                            normalizedValue = "2025",
                            sourceEngine = "nametag3",
                        ),
                    ),
                entityTypes =
                    listOf(
                        ResolverEntityType(
                            "md.dimension.Calendar.year",
                            listOf("md.dimension.Calendar.year"),
                            listOf("rok"),
                            "attribute",
                        ),
                    ),
                thresholds = ResolverThresholds.LIVE,
                snapshotHash = "snap-1",
                batch = BatchMatchResponse.getDefaultInstance(),
                lang = "cs",
                preps = FrameRolePreps.shipped(),
                triggers = triggers,
            )

        private fun mentionId(
            state: ResolutionState,
            text: String,
        ): String = state.mentionsList.single { it.span.text == text }.id

        private fun valueId(
            state: ResolutionState,
            text: String,
        ): String = state.valuesList.single { it.span.text == text }.id

        private fun mention(
            text: String,
            start: Int,
            end: Int,
            head: Int,
        ) = DomainSpanCandidate(
            text,
            start,
            end,
            listOf("md.dimension.Calendar.year"),
            listOf("md.dimension.Calendar.year"),
            anchored = true,
            origin = DomainSpanCandidate.Origin.ANCHOR_PHRASE,
            headToken = head,
            lemma = "rok",
        )

        private fun declaredYear(): FuzzyMatch =
            FuzzyMatch
                .newBuilder()
                .setCandidateId("lex:md.dimension.Calendar.year")
                .setCandidate("rok")
                .setScore(1.0)
                .setCategory("md.dimension.Calendar.year")
                .setSource(SourceTag.METADATA)
                .setTargetRef("md.dimension.Calendar.year")
                .setTargetClass(FuzzyTargetClass.TARGET_CLASS_MODEL_OBJECT)
                .setMatchMethod("EXACT")
                .setProvenance(Provenance.newBuilder().setProducer("lex-matcher").setMethod("TATRMAN"))
                .build()

        private fun chronoTrigger(ref: String = "ground:chrono"): Binding =
            Bindings.of(
                FuzzyMatch
                    .newBuilder()
                    .setCandidateId("lex:$ref")
                    .setCandidate(ref.substringAfter(':'))
                    .setScore(1.0)
                    .setCategory(ref)
                    .setSource(SourceTag.DECLARED)
                    .setTargetRef(ref)
                    .setTargetClass(FuzzyTargetClass.TARGET_CLASS_GROUNDING_TRIGGER)
                    .setMatchMethod("TYPOS(1)")
                    .setProvenance(Provenance.newBuilder().setProducer("lex-matcher").setMethod("TATRMAN"))
                    .build(),
                mention("roce", 29, 33, head = 1),
                ResolverThresholds.LIVE,
                "snap-1",
            )
    }
}
