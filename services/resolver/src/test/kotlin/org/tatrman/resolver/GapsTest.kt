// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.pipeline.Gaps
import org.tatrman.resolver.v1.Binding
import org.tatrman.resolver.v1.Disposition
import org.tatrman.resolver.v1.FrameRole
import org.tatrman.resolver.v1.GapKind
import org.tatrman.resolver.v1.GapRecord
import org.tatrman.resolver.v1.Grounding
import org.tatrman.resolver.v1.Mention
import org.tatrman.resolver.v1.Span
import org.tatrman.resolver.v1.TargetClass
import org.tatrman.resolver.v1.ValueFinding
import org.tatrman.resolver.v1.ValueKind

/**
 * RV-P2.1.T6 — every gap kind, constructed from a crafted input. The kind is not decoration:
 * contracts §3 gives each one its own rung list and its own ask rule, so getting the kind wrong
 * sends the ladder down the wrong path.
 */
class GapsTest :
    StringSpec({

        "G1_UNBOUND — a mention nothing bound, carrying the role that makes it worth asking about" {
            val gaps =
                Gaps.assess(
                    mentions = listOf(mention("m1", 18, 34, "čerpacích stanic", FrameRole.FRAME_ROLE_SUBJECT)),
                    values = emptyList(),
                    ambiguousSpans = emptySet(),
                    parse = AnalyzeResponse.getDefaultInstance(),
                    degraded = false,
                )
            val gap = gaps.single()
            gap.kind shouldBe GapKind.GAP_KIND_G1_UNBOUND
            gap.mentionId shouldBe "m1"
            gap.frameRolesList shouldContainExactly listOf(FrameRole.FRAME_ROLE_SUBJECT)
            gap.disposition shouldBe Disposition.DISPOSITION_UNRESOLVED
        }

        "G2_AMBIGUOUS — several candidates, none dominant; the lattice keeps them all" {
            val ambiguous =
                mention("m1", 0, 9, "středisko")
                    .toBuilder()
                    .addBindings(binding("er.qstred_df"))
                    .addBindings(binding("er.qxxukazmu"))
                    .build()
            val gaps =
                Gaps.assess(
                    mentions = listOf(ambiguous),
                    values = emptyList(),
                    ambiguousSpans = setOf(0 to 9),
                    parse = AnalyzeResponse.getDefaultInstance(),
                    degraded = false,
                )
            gaps.single().kind shouldBe GapKind.GAP_KIND_G2_AMBIGUOUS
            // the bindings are NOT dropped — refusing to choose is not refusing to report
            ambiguous.bindingsCount shouldBe 2
        }

        "G3_UNATTRIBUTED — a hint nothing scoped and nothing claimed (issues.md's `Praze`)" {
            val gaps =
                Gaps.assess(
                    mentions = emptyList(),
                    values = listOf(grounded("v1", 37, 42, "Praze", "LOCATION")),
                    ambiguousSpans = emptySet(),
                    parse = AnalyzeResponse.getDefaultInstance(),
                    degraded = false,
                )
            val gap = gaps.single()
            gap.kind shouldBe GapKind.GAP_KIND_G3_UNATTRIBUTED
            gap.valueId shouldBe "v1"
            gap.mentionId shouldBe ""
        }

        "G4_METHOD_MISS — the SAME value, once a mention scoped it: a known axis that missed" {
            val anchored =
                literal("v1", 20, 26, "5010O1")
                    .toBuilder()
                    .setAnchorMentionId("m1")
                    .build()
            val gaps =
                Gaps.assess(
                    mentions = listOf(mention("m1", 15, 19, "účtu", FrameRole.FRAME_ROLE_FILTER, bound = true)),
                    values = listOf(anchored),
                    ambiguousSpans = emptySet(),
                    parse = AnalyzeResponse.getDefaultInstance(),
                    degraded = false,
                )
            val gap = gaps.single()
            gap.kind shouldBe GapKind.GAP_KIND_G4_METHOD_MISS
            gap.valueId shouldBe "v1"
            // a value gap inherits its anchor's roles: that is what tells the ask policy whether
            // the missed code sits on the load-bearing axis
            gap.frameRolesList shouldContainExactly listOf(FrameRole.FRAME_ROLE_FILTER)
        }

        "G5_NLP_DARK — the capability floor is a gap about the whole question, and it DEGRADES" {
            val parse =
                AnalyzeResponse
                    .newBuilder()
                    .addTokens(
                        Token
                            .newBuilder()
                            .setText("Zobraz")
                            .setCharStart(0)
                            .setCharEnd(6),
                    ).addTokens(
                        Token
                            .newBuilder()
                            .setText("tržby")
                            .setCharStart(7)
                            .setCharEnd(12),
                    ).build()
            val gaps =
                Gaps.assess(
                    mentions = emptyList(),
                    values = emptyList(),
                    ambiguousSpans = emptySet(),
                    parse = parse,
                    degraded = true,
                )
            val gap = gaps.single()
            gap.kind shouldBe GapKind.GAP_KIND_G5_NLP_DARK
            gap.span.start shouldBe 0
            gap.span.end shouldBe 12
            // the answer still goes out — with the banner. That is a different promise from
            // UNRESOLVED, and the disposition is where the difference lives.
            gap.disposition shouldBe Disposition.DISPOSITION_DEGRADED
        }

        "G6_INCOHERENT — expressible, and deliberately never produced by a deterministic core" {
            val record =
                GapRecord
                    .newBuilder()
                    .setKind(GapKind.GAP_KIND_G6_INCOHERENT)
                    .setSpan(Span.newBuilder().setStart(0).setEnd(12))
                    .setDisposition(Disposition.DISPOSITION_UNRESOLVED)
                    .build()
            GapRecord.parseFrom(record.toByteArray()).kind shouldBe GapKind.GAP_KIND_G6_INCOHERENT

            // "the question does not cohere" is a judgement about meaning; contracts §3 routes it
            // to the `capable` rung. A core that claimed to detect it would be guessing.
            val everything =
                Gaps.assess(
                    mentions = listOf(mention("m1", 0, 6, "Zobraz"), mention("m2", 7, 12, "tržby", bound = true)),
                    values = listOf(literal("v1", 13, 15, "10"), grounded("v2", 16, 21, "Praze", "LOCATION")),
                    ambiguousSpans = setOf(0 to 6),
                    parse = AnalyzeResponse.getDefaultInstance(),
                    degraded = true,
                )
            everything.none { it.kind == GapKind.GAP_KIND_G6_INCOHERENT } shouldBe true
        }

        "a literal an OPERATOR scoped is that operator's argument, not an unattributed value" {
            val operator =
                mention("m1", 7, 14, "prvních")
                    .toBuilder()
                    .addBindings(binding("op:top-n", TargetClass.TARGET_CLASS_OPERATOR))
                    .build()
            val gaps =
                Gaps.assess(
                    mentions = listOf(operator),
                    values = listOf(literal("v1", 15, 17, "10").toBuilder().setAnchorMentionId("m1").build()),
                    ambiguousSpans = emptySet(),
                    parse = AnalyzeResponse.getDefaultInstance(),
                    degraded = false,
                )
            gaps shouldBe emptyList()
        }

        "a date grounds itself; a place does not" {
            val gaps =
                Gaps.assess(
                    mentions = emptyList(),
                    values =
                        listOf(
                            grounded("v1", 34, 38, "2025", "DATE"),
                            grounded("v2", 40, 45, "Praze", "LOCATION"),
                        ),
                    ambiguousSpans = emptySet(),
                    parse = AnalyzeResponse.getDefaultInstance(),
                    degraded = false,
                )
            // a date IS the value the planner will use; a place is a hint until something
            // attributes it to an attribute of the model
            gaps.map { it.valueId } shouldContainExactly listOf("v2")
        }

        "gaps come out in span order, whatever order they were found in" {
            val gaps =
                Gaps.assess(
                    mentions = listOf(mention("m1", 50, 60, "položky")),
                    values = listOf(grounded("v1", 10, 15, "Praze", "LOCATION")),
                    ambiguousSpans = emptySet(),
                    parse = AnalyzeResponse.getDefaultInstance(),
                    degraded = false,
                )
            gaps.map { it.span.start } shouldContainExactly listOf(10, 50)
        }
    }) {
    companion object {
        private fun mention(
            id: String,
            start: Int,
            end: Int,
            text: String,
            vararg roles: FrameRole,
            bound: Boolean = false,
        ): Mention {
            val builder =
                Mention
                    .newBuilder()
                    .setId(id)
                    .setSpan(
                        Span
                            .newBuilder()
                            .setStart(start)
                            .setEnd(end)
                            .setText(text),
                    ).addAllFrameRoles(roles.toList())
            if (bound) builder.addBindings(binding("md.x"))
            return builder.build()
        }

        private fun binding(
            ref: String,
            targetClass: TargetClass = TargetClass.TARGET_CLASS_MODEL_OBJECT,
        ): Binding =
            Binding
                .newBuilder()
                .setRef(ref)
                .setTargetClass(targetClass)
                .build()

        private fun literal(
            id: String,
            start: Int,
            end: Int,
            text: String,
        ): ValueFinding =
            ValueFinding
                .newBuilder()
                .setId(id)
                .setSpan(
                    Span
                        .newBuilder()
                        .setStart(start)
                        .setEnd(end)
                        .setText(text),
                ).setKind(ValueKind.VALUE_KIND_LITERAL)
                .build()

        private fun grounded(
            id: String,
            start: Int,
            end: Int,
            text: String,
            kind: String,
        ): ValueFinding =
            ValueFinding
                .newBuilder()
                .setId(id)
                .setSpan(
                    Span
                        .newBuilder()
                        .setStart(start)
                        .setEnd(end)
                        .setText(text),
                ).setKind(ValueKind.VALUE_KIND_GROUNDED)
                .setGrounding(Grounding.newBuilder().setKernel("nametag3").setKind(kind))
                .build()
    }
}
