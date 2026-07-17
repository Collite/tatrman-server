// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.NerEntity
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.model.ResolverEntityType
import org.tatrman.resolver.pipeline.DomainSpanCandidate
import org.tatrman.resolver.pipeline.SpanProposal

/**
 * RG-P5.S1.T3 — the anchored span proposal (Q-20 GO-WITH-FALLBACK, spike §5).
 * Builds the candidate set that gateSpans (T4) turns into one BatchMatch call.
 *
 * These fixtures are hand-authored dep parses (a real ttr-nlp `AnalyzeResponse`
 * shape) so the unit stays engine-free; the parity instrument (T6) exercises the
 * live corpora. The hero is the load-bearing case: `pražských pobočkách` must
 * propose as ONE multi-word candidate (the live single-head TODO is fixed here).
 */
class SpanProposalTest :
    StringSpec({

        // The declared registry the spike's anchored gating uses. `pobočka` is the
        // er.branch lexicon term (the anchor); `středisko` is the QSTRED_DF anchor.
        val branch =
            ResolverEntityType(ref = "er.branch", categories = listOf("er.branch"), anchors = listOf("pobočka"))
        val product = ResolverEntityType(ref = "er.product", categories = listOf("er.product"), anchors = emptyList())
        val qstred =
            ResolverEntityType(
                ref = "er.qstred_df",
                categories = listOf("er.qstred_df.kod", "er.qstred_df.nazev"),
                anchors = listOf("středisko"),
            )

        // "Kolik jsme utržili za Octavie v pražských pobočkách za poslední fiskální čtvrtletí?"
        // 0 Kolik 1 jsme 2 utržili(root) 3 za 4 Octavie(PROPN,obl) 5 v 6 pražských(amod→8)
        // 7 pobočkách(NOUN,obl,lemma pobočka) 8 za 9 poslední 10 fiskální 11 čtvrtletí 12 ?
        val hero =
            AnalyzeResponse
                .newBuilder()
                .addAllTokens(
                    listOf(
                        tok("Kolik", 0, 5, "kolik", "ADV", 3, "advmod"),
                        tok("jsme", 6, 10, "být", "AUX", 3, "aux"),
                        tok("utržili", 11, 18, "utržit", "VERB", 0, "root"),
                        tok("za", 19, 21, "za", "ADP", 5, "case"),
                        tok("Octavie", 22, 29, "Octavie", "PROPN", 3, "obl"),
                        tok("v", 30, 31, "v", "ADP", 8, "case"),
                        tok("pražských", 32, 41, "pražský", "ADJ", 8, "amod"),
                        tok("pobočkách", 42, 51, "pobočka", "NOUN", 3, "obl"),
                        tok("za", 52, 54, "za", "ADP", 12, "case"),
                        tok("poslední", 55, 63, "poslední", "ADJ", 12, "amod"),
                        tok("fiskální", 64, 72, "fiskální", "ADJ", 12, "amod"),
                        tok("čtvrtletí", 73, 82, "čtvrtletí", "NOUN", 3, "obl"),
                        tok("?", 82, 83, "?", "PUNCT", 3, "punct"),
                    ),
                ).addEntities(ner("poslední fiskální čtvrtletí", 55, 82, "DATE"))
                .build()

        "hero: `pražských pobočkách` proposes as ONE anchored multi-word candidate gated to er.branch" {
            val cands = SpanProposal.proposeDomainSpans(hero, listOf(branch, product))
            val branchCand = cands.single { it.text == "pražských pobočkách" }
            branchCand.anchored shouldBe true
            branchCand.gatedEntityRefs shouldBe listOf("er.branch")
            branchCand.start shouldBe 32
            branchCand.end shouldBe 51
        }

        "hero: `Octavie` proposes as an unanchored proper-noun candidate gated to all types" {
            val cands = SpanProposal.proposeDomainSpans(hero, listOf(branch, product))
            val octavie = cands.single { it.text == "Octavie" }
            octavie.anchored shouldBe false
            octavie.gatedEntityRefs shouldContainExactlyInAnyOrder listOf("er.branch", "er.product")
        }

        "hero: the universal DATE span `poslední fiskální čtvrtletí` produces no domain candidate" {
            val cands = SpanProposal.proposeDomainSpans(hero, listOf(branch, product))
            cands.any { it.text.contains("čtvrtletí") } shouldBe false
            // no candidate overlaps the excluded [55,82) DATE range
            cands.any { it.start < 82 && it.end > 55 }.shouldBeFalse()
        }

        "(c) a domain-eligible NER entity tagged as a common NOUN (cnec:op) still proposes" {
            // Live morphology tags a product name like `Octavie` NNFP4/NOUN (not PROPN), so the
            // anchored/PROPN paths miss it — but NameTag flags it `op` (object) → domain-eligible,
            // so path (c) admits it as a candidate gated against all declared types.
            val parse =
                AnalyzeResponse
                    .newBuilder()
                    .addAllTokens(
                        listOf(
                            tok("Kolik", 0, 5, "kolik", "ADV", 3, "advmod"),
                            tok("za", 6, 8, "za", "ADP", 3, "case"),
                            tok("Octavie", 9, 16, "Octavia", "NOUN", 0, "obl"),
                        ),
                    ).addEntities(
                        NerEntity
                            .newBuilder()
                            .setText("Octavie")
                            .setCharStart(9)
                            .setCharEnd(16)
                            .setLabel("MISC")
                            .setNormalizedValue("cnec:op")
                            .setSourceEngine("nametag3")
                            .build(),
                    ).build()
            val cands = SpanProposal.proposeDomainSpans(parse, listOf(branch, product))
            val octavie = cands.single { it.text == "Octavie" }
            octavie.anchored shouldBe false
            octavie.gatedEntityRefs shouldContainExactlyInAnyOrder listOf("er.branch", "er.product")
        }

        "(c) a universal NER entity (cnec:gu geo) is NOT proposed as a domain candidate" {
            val parse =
                AnalyzeResponse
                    .newBuilder()
                    .addAllTokens(listOf(tok("Praha", 0, 5, "Praha", "PROPN", 0, "root")))
                    .addEntities(
                        NerEntity
                            .newBuilder()
                            .setText("Praha")
                            .setCharStart(0)
                            .setCharEnd(5)
                            .setLabel("LOCATION")
                            .setNormalizedValue("cnec:gu")
                            .build(),
                    ).build()
            SpanProposal.proposeDomainSpans(parse, listOf(branch, product)).any { it.start < 5 && it.end > 0 } shouldBe
                false
        }

        "anchored value: `středisko` governing `DF ADNAK` proposes the value gated to QSTRED_DF only" {
            // "Zobraz středisko DF ADNAK" — 0 Zobraz(root) 1 středisko(obj,lemma) 2 DF(PROPN,flat→4)
            // 3 ADNAK(PROPN,nmod→2)
            val parse =
                AnalyzeResponse
                    .newBuilder()
                    .addAllTokens(
                        listOf(
                            tok("Zobraz", 0, 6, "zobrazit", "VERB", 0, "root"),
                            tok("středisko", 7, 16, "středisko", "NOUN", 1, "obj"),
                            tok("DF", 17, 19, "DF", "PROPN", 4, "flat"),
                            tok("ADNAK", 20, 25, "ADNAK", "PROPN", 2, "nmod"),
                        ),
                    ).build()
            val cands = SpanProposal.proposeDomainSpans(parse, listOf(qstred, branch))
            val value = cands.single { it.text == "DF ADNAK" }
            value.anchored shouldBe true
            value.gatedEntityRefs shouldBe listOf("er.qstred_df")
            value.categories shouldContainExactlyInAnyOrder listOf("er.qstred_df.kod", "er.qstred_df.nazev")
        }

        "no over-generation: a common noun that is neither an anchor nor a proper noun proposes nothing" {
            // "Zobraz záznamy" — `záznamy` is a NOUN, not a declared anchor, not PROPN.
            val parse =
                AnalyzeResponse
                    .newBuilder()
                    .addAllTokens(
                        listOf(
                            tok("Zobraz", 0, 6, "zobrazit", "VERB", 0, "root"),
                            tok("záznamy", 7, 14, "záznam", "NOUN", 1, "obj"),
                        ),
                    ).build()
            SpanProposal.proposeDomainSpans(parse, listOf(branch, product, qstred)) shouldBe emptyList()
        }

        "R4-γ floor: a parse-less (no dep) input still yields n-gram candidates gated to all types" {
            // Degraded language: tokens present, every dep_head = 0, no NER.
            val parse =
                AnalyzeResponse
                    .newBuilder()
                    .addAllTokens(
                        listOf(
                            tok("ukazatel", 0, 8, "", "", 0, ""),
                            tok("MAJETEK", 9, 16, "", "", 0, ""),
                        ),
                    ).build()
            val cands = SpanProposal.proposeDomainSpans(parse, listOf(branch, product, qstred))
            cands shouldNotBe emptyList<DomainSpanCandidate>()
            cands.map { it.text } shouldContain "MAJETEK"
            cands.all { !it.anchored } shouldBe true
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

        private fun ner(
            text: String,
            start: Int,
            end: Int,
            label: String,
        ): NerEntity =
            NerEntity
                .newBuilder()
                .setText(text)
                .setCharStart(start)
                .setCharEnd(end)
                .setLabel(label)
                .build()
    }
}
