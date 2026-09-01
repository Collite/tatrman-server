// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.pipeline.FrameRolePreps
import org.tatrman.resolver.pipeline.FrameRoles
import org.tatrman.resolver.v1.FrameRole
import org.tatrman.resolver.v1.TargetClass

/**
 * MS-P3·S3 — the `isMeasure` / `measureCapable` split (contracts §8.4, design.md §6.2, MS-R6).
 *
 * These are direct [FrameRoles.derive] cases rather than corpus fixtures: the corpus rework is
 * S4, and a rule change is easier to read as a table of shapes than as thirty-nine sentences.
 * The prepositions come from the real shipped tables — a hand-written prep set would let a rule
 * pass here and fail in production on the same word.
 *
 * The distinction being pinned, and it is the whole stage:
 *
 *  - **`measure`** IS the measure. R2 stamps it, and R3–R6 exempt it.
 *  - **`entity_with_measures`** HAS measures. R3–R6 exempt it too — *"prodeje podle prodejen"*
 *    groups by the branch, not by the sales — but R2 does NOT stamp it, because whether the
 *    question wants rows, a count or a measure value is the operator layer's reading to make
 *    (MS-R6, contracts §9), not a role this service can assert from the model alone.
 *  - **`entity`** and **`attribute`** are exempted from nothing: an entity with no declared
 *    measures groups and filters exactly as it always did.
 */
class FrameRolesMeasureTest :
    StringSpec({

        val preps = FrameRolePreps.shipped()

        fun rolesOf(
            parse: AnalyzeResponse,
            vararg mentions: FrameRoles.Input,
        ): Map<String, List<FrameRole>> = FrameRoles.derive(mentions.toList(), parse, "cs", preps)

        // "Zobraz tržby" — 0 Zobraz(VERB, root) 1 tržby(NOUN, nsubj)
        // The measure IS the nsubj candidate: R9 must still reach it.
        val bareMeasure =
            parse(
                tok("Zobraz", 0, 6, "zobrazit", "VERB", 0, "root"),
                tok("tržby", 7, 12, "tržby", "NOUN", 1, "nsubj"),
            )

        "R2 alive: a mention that binds a measure takes MEASURE" {
            val roles = rolesOf(bareMeasure, mention("m1", head = 1, kind = "measure"))
            roles.getValue("m1") shouldContain FrameRole.FRAME_ROLE_MEASURE
        }

        "R2 × R9: a measure that is the nsubj candidate takes SUBJECT alongside MEASURE" {
            // The two rules answer different questions and both answers stand — "Zobraz tržby" is
            // ABOUT the sales and the sales are ALSO the measure.
            val roles = rolesOf(bareMeasure, mention("m1", head = 1, kind = "measure"))
            roles.getValue("m1") shouldContain FrameRole.FRAME_ROLE_SUBJECT
            roles.getValue("m1") shouldContain FrameRole.FRAME_ROLE_MEASURE
        }

        "MS-R6: a measure-CAPABLE entity does NOT take MEASURE — the reading is the operator's" {
            val roles = rolesOf(bareMeasure, mention("m1", head = 1, kind = "entity_with_measures"))
            roles.getValue("m1") shouldNotContain FrameRole.FRAME_ROLE_MEASURE
            // it is still what the question is about
            roles.getValue("m1") shouldContain FrameRole.FRAME_ROLE_SUBJECT
        }

        // --- R3: `podle X` — GROUP-BY for a dimension, ORDER-BY for anything measure-capable ---

        // "prvních 10 prodejen podle tržby" — 0 prvních 1 10 2 prodejen(nsubj) 3 podle(case→4)
        // 4 tržby(NOUN, nmod)
        val orderBy =
            parse(
                tok("prvních", 0, 7, "první", "ADJ", 3, "amod"),
                tok("10", 8, 10, "10", "NUM", 3, "nummod"),
                tok("prodejen", 11, 19, "prodejna", "NOUN", 0, "nsubj"),
                tok("podle", 20, 25, "podle", "ADP", 5, "case"),
                tok("tržby", 26, 31, "tržby", "NOUN", 3, "nmod"),
            )

        listOf("measure", "entity_with_measures").forEach { kind ->
            "R3 exemption: `podle` over a $kind mention is NOT a grouping" {
                val roles = rolesOf(orderBy, mention("m1", head = 4, kind = kind))
                roles.getValue("m1") shouldNotContain FrameRole.FRAME_ROLE_GROUPING
            }
        }

        listOf("attribute", "entity").forEach { kind ->
            "R3 contrast: `podle` over a $kind mention IS a grouping" {
                val roles = rolesOf(orderBy, mention("m1", head = 4, kind = kind))
                roles.getValue("m1") shouldContain FrameRole.FRAME_ROLE_GROUPING
            }
        }

        // --- R4: a filter preposition ------------------------------------------------------

        // "prodeje za září" — 0 prodeje(nsubj) 1 za(case→2) 2 září(NOUN, obl)
        val filterPrep =
            parse(
                tok("prodeje", 0, 7, "prodej", "NOUN", 0, "nsubj"),
                tok("za", 8, 10, "za", "ADP", 3, "case"),
                tok("září", 11, 15, "září", "NOUN", 1, "obl"),
            )

        listOf("measure", "entity_with_measures").forEach { kind ->
            "R4 exemption: a filter preposition over a $kind mention is not a FILTER" {
                val roles = rolesOf(filterPrep, mention("m1", head = 2, kind = kind))
                roles.getValue("m1") shouldNotContain FrameRole.FRAME_ROLE_FILTER
            }
        }

        listOf("attribute", "entity").forEach { kind ->
            "R4 contrast: a filter preposition over a $kind mention IS a FILTER" {
                val roles = rolesOf(filterPrep, mention("m1", head = 2, kind = kind))
                roles.getValue("m1") shouldContain FrameRole.FRAME_ROLE_FILTER
            }
        }

        // --- R5: a compound premodifier ----------------------------------------------------

        // "webové tržby" as a compound: 0 webové(NOUN, compound→2) 1 tržby(NOUN, root)
        val compound =
            parse(
                tok("webové", 0, 6, "web", "NOUN", 2, "compound"),
                tok("tržby", 7, 12, "tržby", "NOUN", 0, "root"),
            )

        listOf("measure", "entity_with_measures").forEach { kind ->
            "R5 exemption: a compound $kind mention is not a FILTER" {
                val roles = rolesOf(compound, mention("m1", head = 0, kind = kind))
                roles.getValue("m1") shouldNotContain FrameRole.FRAME_ROLE_FILTER
            }
        }

        listOf("attribute", "entity").forEach { kind ->
            "R5 contrast: a compound $kind mention IS a FILTER" {
                val roles = rolesOf(compound, mention("m1", head = 0, kind = kind))
                roles.getValue("m1") shouldContain FrameRole.FRAME_ROLE_FILTER
            }
        }

        // --- R6: a bare oblique with no preposition ----------------------------------------

        // "vypiš tržby minulý týden" reduced: 0 vypiš(root) 1 tržby(NOUN, obl, no `case` child)
        val bareOblique =
            parse(
                tok("vypiš", 0, 5, "vypsat", "VERB", 0, "root"),
                tok("tržby", 6, 11, "tržby", "NOUN", 1, "obl"),
            )

        listOf("measure", "entity_with_measures").forEach { kind ->
            "R6 exemption: a bare oblique $kind mention is not a FILTER" {
                val roles = rolesOf(bareOblique, mention("m1", head = 1, kind = kind))
                roles.getValue("m1") shouldNotContain FrameRole.FRAME_ROLE_FILTER
            }
        }

        listOf("attribute", "entity").forEach { kind ->
            "R6 contrast: a bare oblique $kind mention IS a FILTER" {
                val roles = rolesOf(bareOblique, mention("m1", head = 1, kind = kind))
                roles.getValue("m1") shouldContain FrameRole.FRAME_ROLE_FILTER
            }
        }

        // --- R6' is NOT widened -------------------------------------------------------------

        "R6' is unchanged: it reads `attribute` exactly, not measure-capability" {
            // "loni" — an ADV/advmod with no nominal head. R6' fires for an attribute binding and
            // for nothing else; widening it to measure-capable kinds would make a temporal adverb
            // that happens to bind an entity into a filter, which is not what spike F-1 found.
            val advmod =
                parse(
                    tok("vypiš", 0, 5, "vypsat", "VERB", 0, "root"),
                    tok("loni", 6, 10, "loni", "ADV", 1, "advmod"),
                )
            rolesOf(advmod, mention("m1", head = 1, kind = "attribute"))
                .getValue("m1") shouldContain FrameRole.FRAME_ROLE_FILTER
            rolesOf(advmod, mention("m1", head = 1, kind = "entity_with_measures"))
                .getValue("m1") shouldNotContain FrameRole.FRAME_ROLE_FILTER
        }

        // --- the estate that declared nothing ------------------------------------------------

        "contracts §10: a blank kind is exempted from nothing — the pre-MS reading, unchanged" {
            rolesOf(orderBy, mention("m1", head = 4, kind = "")).getValue("m1") shouldContain
                FrameRole.FRAME_ROLE_GROUPING
            rolesOf(bareMeasure, mention("m1", head = 1, kind = "")).getValue("m1") shouldNotContain
                FrameRole.FRAME_ROLE_MEASURE
        }

        "kinds are read case-insensitively, as every other kind comparison in this file is" {
            rolesOf(bareMeasure, mention("m1", head = 1, kind = "MEASURE")).getValue("m1") shouldContain
                FrameRole.FRAME_ROLE_MEASURE
            rolesOf(orderBy, mention("m1", head = 4, kind = "Entity_With_Measures")).getValue("m1") shouldNotContain
                FrameRole.FRAME_ROLE_GROUPING
        }

        "R0 still wins: an operator carries no role whatever its kind says" {
            val roles =
                FrameRoles.derive(
                    listOf(
                        FrameRoles.Input(
                            id = "op",
                            charStart = 0,
                            headToken = 1,
                            targetClass = TargetClass.TARGET_CLASS_OPERATOR,
                            objectKind = "measure",
                            anchorsValue = false,
                        ),
                    ),
                    bareMeasure,
                    "cs",
                    preps,
                )
            roles.getValue("op") shouldBe emptyList()
        }
    }) {
    companion object {
        private fun tok(
            text: String,
            start: Int,
            end: Int,
            lemma: String,
            upos: String,
            depHead: Int,
            depRelation: String,
        ): Token =
            Token
                .newBuilder()
                .setText(text)
                .setCharStart(start)
                .setCharEnd(end)
                .setLemma(lemma)
                .setUpos(upos)
                .setDepHead(depHead)
                .setDepRelation(depRelation)
                .build()

        private fun parse(vararg tokens: Token): AnalyzeResponse =
            AnalyzeResponse.newBuilder().addAllTokens(tokens.toList()).build()

        /** A model-object mention at [head], carrying the declared [kind] and nothing else. */
        private fun mention(
            id: String,
            head: Int,
            kind: String,
            anchorsValue: Boolean = false,
        ): FrameRoles.Input =
            FrameRoles.Input(
                id = id,
                charStart = 0,
                headToken = head,
                targetClass = TargetClass.TARGET_CLASS_MODEL_OBJECT,
                objectKind = kind,
                anchorsValue = anchorsValue,
            )
    }
}
