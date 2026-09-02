// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.pipeline.DomainSpanCandidate
import org.tatrman.resolver.pipeline.FrameRolePreps
import org.tatrman.resolver.pipeline.Slot
import org.tatrman.resolver.pipeline.SlotHint
import org.tatrman.resolver.pipeline.SlotHints

/**
 * MH T2 — the slot derivation, over the E-catalogue (design.md §1.4, contracts §8.2).
 *
 * Every parse here is hand-built and UD-plausible, not Stanza-verified — that is the recorded
 * risk (plan risk 5), and the P2 live drill is what settles it: any divergence between these
 * tokens and a real parse becomes a case HERE with the real tokens, never a change to the
 * estate's vocabulary to fit the rule.
 *
 * The catalogue is one word — `prodejna` / `stores` — claimed by two refs of different kinds:
 * `er.entity.store` (the six-row dimension) and `er.entity.store_sales` (the fact the Stores
 * channel vocabulary is pinned to). What the slot decides is which of the two the sentence is
 * asking for.
 */
class SlotHintsTest :
    StringSpec({

        val store = "er.entity.store"
        val storeSales = "er.entity.store_sales"
        val storeReturns = "er.entity.store_returns"
        val webSales = "er.entity.web_sales"

        val kinds =
            mapOf(
                store to "entity",
                storeSales to "entity_with_measures",
                storeReturns to "entity",
                webSales to "entity_with_measures",
            )
        val preps = FrameRolePreps.shipped()

        fun tok(
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

        fun parse(
            lang: String,
            vararg tokens: Token,
        ): AnalyzeResponse =
            AnalyzeResponse
                .newBuilder()
                .setLanguage(lang)
                .setDetectedLanguage(lang)
                .addAllTokens(tokens.toList())
                .build()

        fun anchor(
            text: String,
            head: Int,
            refs: List<String>,
            lemma: String = text.lowercase(),
        ) = DomainSpanCandidate(
            text = text,
            start = 0,
            end = text.length,
            gatedEntityRefs = refs,
            categories = emptyList(),
            anchored = true,
            origin = DomainSpanCandidate.Origin.ANCHOR_PHRASE,
            headToken = head,
            lemma = lemma,
        )

        fun stamp(
            p: AnalyzeResponse,
            candidates: List<DomainSpanCandidate>,
            lang: String = "cs",
            owners: Map<String, String> = emptyMap(),
        ) = SlotHints.stamp(p, candidates, kinds, owners, lang, preps)

        fun slotOf(
            p: AnalyzeResponse,
            candidates: List<DomainSpanCandidate>,
            index: Int = 0,
            lang: String = "cs",
            owners: Map<String, String> = emptyMap(),
        ) = stamp(p, candidates, lang, owners)[index].slot

        // ---- E1: Kolik máme prodejen? — the count head -----------------------------------------

        "E1 — a count quantifier makes the span a COUNT_HEAD" {
            val p =
                parse(
                    "cs",
                    tok("Kolik", 0, 5, "kolik", "DET", 3, "det:numgov"),
                    tok("máme", 6, 10, "mít", "VERB", 0, "root"),
                    tok("prodejen", 11, 19, "prodejna", "NOUN", 2, "obj"),
                    tok("?", 19, 20, "?", "PUNCT", 2, "punct"),
                )
            slotOf(p, listOf(anchor("prodejen", 2, listOf(store, storeSales)))) shouldBe SlotHint(Slot.COUNT_HEAD)
        }

        "E1' — the sibling analysis (kolik as an advmod of the verb) reads the same" {
            // Czech parses of one shape differ between models; the rule must not depend on which
            // of the two analyses the pipeline happened to get.
            val p =
                parse(
                    "cs",
                    tok("Kolik", 0, 5, "kolik", "ADV", 2, "advmod"),
                    tok("máme", 6, 10, "mít", "VERB", 0, "root"),
                    tok("prodejen", 11, 19, "prodejna", "NOUN", 2, "obj"),
                    tok("?", 19, 20, "?", "PUNCT", 2, "punct"),
                )
            slotOf(p, listOf(anchor("prodejen", 2, listOf(store, storeSales)))) shouldBe SlotHint(Slot.COUNT_HEAD)
        }

        // ---- E2: Tržby z prodejen za 2025 — a filter under a measure head ----------------------

        "E2 — a filter preposition under a measure-capable head carries the head's FACT" {
            val p =
                parse(
                    "cs",
                    tok("Tržby", 0, 5, "tržba", "NOUN", 0, "root"),
                    tok("z", 6, 7, "z", "ADP", 3, "case"),
                    tok("prodejen", 8, 16, "prodejna", "NOUN", 1, "nmod"),
                    tok("za", 17, 19, "za", "ADP", 5, "case"),
                    tok("2025", 20, 24, "2025", "NUM", 1, "nmod"),
                )
            val candidates =
                listOf(
                    anchor("Tržby", 0, listOf(storeSales), lemma = "tržba"),
                    anchor("prodejen", 2, listOf(store, storeSales), lemma = "prodejna"),
                )
            stamp(p, candidates)[1].slot shouldBe
                SlotHint(Slot.FILTER, headRefs = listOf(storeSales), headMeasureCapable = true)
        }

        // ---- E3: Tržby podle prodejen — the group-by axis ---------------------------------------

        "E3 — a grouping preposition makes the span a GROUP_BY, with the head fact" {
            val p =
                parse(
                    "cs",
                    tok("Tržby", 0, 5, "tržba", "NOUN", 0, "root"),
                    tok("podle", 6, 11, "podle", "ADP", 3, "case"),
                    tok("prodejen", 12, 20, "prodejna", "NOUN", 1, "nmod"),
                )
            val candidates =
                listOf(
                    anchor("Tržby", 0, listOf(storeSales), lemma = "tržba"),
                    anchor("prodejen", 2, listOf(store, storeSales), lemma = "prodejna"),
                )
            stamp(p, candidates)[1].slot shouldBe
                SlotHint(Slot.GROUP_BY, headRefs = listOf(storeSales), headMeasureCapable = true)
        }

        // ---- E5: Srovnej prodejny a web — coordination ------------------------------------------

        "E5 — a conj sibling makes BOTH spans COORD_WITH, each carrying the other's kinds" {
            val p =
                parse(
                    "cs",
                    tok("Srovnej", 0, 7, "srovnat", "VERB", 0, "root"),
                    tok("prodejny", 8, 16, "prodejna", "NOUN", 1, "obj"),
                    tok("a", 17, 18, "a", "CCONJ", 4, "cc"),
                    tok("web", 19, 22, "web", "NOUN", 2, "conj"),
                )
            val candidates =
                listOf(
                    anchor("prodejny", 1, listOf(store, storeSales), lemma = "prodejna"),
                    anchor("web", 3, listOf(webSales)),
                )
            val stamped = stamp(p, candidates)

            stamped[0].slot shouldBe
                SlotHint(Slot.COORD_WITH, coordSiblingKinds = setOf("entity_with_measures"))
            // The other direction: `web` is the conj CHILD, so it finds its sibling by walking up.
            stamped[1].slot shouldBe
                SlotHint(Slot.COORD_WITH, coordSiblingKinds = setOf("entity", "entity_with_measures"))
        }

        // ---- E6: Vratky z prodejen — the head is the WRONG fact for the channel term -----------

        "E6 — a filter under a NON-measure head still carries headRefs (T3 needs them)" {
            // This is the case that makes T2 alone a regression: the slot says "measure-ish", the
            // only measure-ish candidate is `store_sales`, and the clause is about returns. The
            // headRefs recorded here are what lets the reachability rule fix it.
            val p =
                parse(
                    "cs",
                    tok("Vratky", 0, 6, "vratka", "NOUN", 0, "root"),
                    tok("z", 7, 8, "z", "ADP", 3, "case"),
                    tok("prodejen", 9, 17, "prodejna", "NOUN", 1, "nmod"),
                )
            val candidates =
                listOf(
                    anchor("Vratky", 0, listOf(storeReturns), lemma = "vratka"),
                    anchor("prodejen", 2, listOf(store, storeSales), lemma = "prodejna"),
                )
            stamp(p, candidates)[1].slot shouldBe
                SlotHint(Slot.FILTER, headRefs = listOf(storeReturns), headMeasureCapable = false)
        }

        // ---- E9: the bare word ------------------------------------------------------------------

        "E9 — a one-word question has no dependency tree, so it has no slot" {
            // The single-word regression MS pinned (§8.5): this must keep ASKING, and the way it
            // keeps asking is that neither Binder rule has anything to fire on.
            val p = parse("cs", tok("prodejna", 0, 8, "prodejna", "NOUN", 0, "root"))
            slotOf(p, listOf(anchor("prodejna", 0, listOf(store, storeSales)))) shouldBe SlotHint.NONE
        }

        // ---- English ----------------------------------------------------------------------------

        "EN-1 — How many stores do we have? — the quantifier is a dependent of the head" {
            val p =
                parse(
                    "en",
                    tok("How", 0, 3, "how", "ADV", 2, "advmod"),
                    tok("many", 4, 8, "many", "ADJ", 3, "amod"),
                    tok("stores", 9, 15, "store", "NOUN", 6, "obj"),
                    tok("do", 16, 18, "do", "AUX", 6, "aux"),
                    tok("we", 19, 21, "we", "PRON", 6, "nsubj"),
                    tok("have", 22, 26, "have", "VERB", 0, "root"),
                    tok("?", 26, 27, "?", "PUNCT", 6, "punct"),
                )
            slotOf(
                p,
                listOf(anchor("stores", 2, listOf(store, storeSales), lemma = "store")),
                lang = "en",
            ) shouldBe SlotHint(Slot.COUNT_HEAD)
        }

        "EN-2 — Revenue by store" {
            val p =
                parse(
                    "en",
                    tok("Revenue", 0, 7, "revenue", "NOUN", 0, "root"),
                    tok("by", 8, 10, "by", "ADP", 3, "case"),
                    tok("store", 11, 16, "store", "NOUN", 1, "nmod"),
                )
            val candidates =
                listOf(
                    anchor("Revenue", 0, listOf(storeSales), lemma = "revenue"),
                    anchor("store", 2, listOf(store, storeSales)),
                )
            stamp(p, candidates, lang = "en")[1].slot shouldBe
                SlotHint(Slot.GROUP_BY, headRefs = listOf(storeSales), headMeasureCapable = true)
        }

        // ---- Precedence and edges (T6) ----------------------------------------------------------

        "a governed value outranks a filter preposition — the value is the stronger signal" {
            val p =
                parse(
                    "cs",
                    tok("Tržby", 0, 5, "tržba", "NOUN", 0, "root"),
                    tok("z", 6, 7, "z", "ADP", 3, "case"),
                    tok("prodejen", 8, 16, "prodejna", "NOUN", 1, "nmod"),
                    tok("Nashville", 17, 26, "Nashville", "PROPN", 3, "nmod"),
                )
            val candidates =
                listOf(
                    anchor("Tržby", 0, listOf(storeSales), lemma = "tržba"),
                    anchor("prodejen", 2, listOf(store, storeSales), lemma = "prodejna"),
                    DomainSpanCandidate(
                        text = "Nashville",
                        start = 17,
                        end = 26,
                        gatedEntityRefs = listOf(store),
                        categories = emptyList(),
                        anchored = false,
                        origin = DomainSpanCandidate.Origin.GOVERNED_VALUE,
                        headToken = 3,
                        anchorHeadToken = 2,
                    ),
                )
            stamp(p, candidates)[1].slot shouldBe SlotHint(Slot.GOVERNED_VALUE)
        }

        "coordination outranks a count head — 'kolik prodejen a webů' is still a comparison" {
            val p =
                parse(
                    "cs",
                    tok("Kolik", 0, 5, "kolik", "DET", 2, "det:numgov"),
                    tok("prodejen", 6, 14, "prodejna", "NOUN", 0, "root"),
                    tok("a", 15, 16, "a", "CCONJ", 4, "cc"),
                    tok("webů", 17, 21, "web", "NOUN", 2, "conj"),
                )
            slotOf(
                p,
                listOf(
                    anchor("prodejen", 1, listOf(store, storeSales), lemma = "prodejna"),
                    anchor("webů", 3, listOf(webSales), lemma = "web"),
                ),
            ).slot shouldBe Slot.COORD_WITH
        }

        "a bare nmod with no preposition is still a FILTER when it hangs off a mention" {
            // "tržby prodejen" — the genitive, no `case` child at all.
            val p =
                parse(
                    "cs",
                    tok("Tržby", 0, 5, "tržba", "NOUN", 0, "root"),
                    tok("prodejen", 6, 14, "prodejna", "NOUN", 1, "nmod"),
                )
            stamp(
                p,
                listOf(
                    anchor("Tržby", 0, listOf(storeSales), lemma = "tržba"),
                    anchor("prodejen", 1, listOf(store, storeSales), lemma = "prodejna"),
                ),
            )[1].slot shouldBe SlotHint(Slot.FILTER, headRefs = listOf(storeSales), headMeasureCapable = true)
        }

        "a bare nmod with NO head candidate is not a filter of anything" {
            val p =
                parse(
                    "cs",
                    tok("Nesmysl", 0, 7, "nesmysl", "NOUN", 0, "root"),
                    tok("prodejen", 8, 16, "prodejna", "NOUN", 1, "nmod"),
                )
            slotOf(p, listOf(anchor("prodejen", 1, listOf(store, storeSales), lemma = "prodejna"))) shouldBe
                SlotHint.NONE
        }

        "SUBJECT — a plain nsubj carries no kind preference, only the clause head" {
            val p =
                parse(
                    "cs",
                    tok("Prodejny", 0, 8, "prodejna", "NOUN", 2, "nsubj"),
                    tok("nebyly", 9, 15, "být", "AUX", 3, "cop"),
                    tok("ziskové", 16, 23, "ziskový", "ADJ", 0, "root"),
                )
            slotOf(p, listOf(anchor("Prodejny", 0, listOf(store, storeSales), lemma = "prodejna"))) shouldBe
                SlotHint(Slot.SUBJECT)
        }

        "the head walk stops after three hops" {
            // A chain of four nmods: the mention four levels down must NOT claim the far head,
            // because at that distance "the clause this span restricts" stops being true.
            fun chain(depth: Int): AnalyzeResponse =
                parse(
                    "cs",
                    tok("Tržby", 0, 5, "tržba", "NOUN", 0, "root"),
                    tok("a", 6, 7, "a", "NOUN", 1, "nmod"),
                    tok("b", 8, 9, "b", "NOUN", 2, "nmod"),
                    tok("c", 10, 11, "c", "NOUN", 3, "nmod"),
                    tok("prodejen", 12, 20, "prodejna", "NOUN", depth, "nmod"),
                )

            val head = anchor("Tržby", 0, listOf(storeSales), lemma = "tržba")
            // 3 hops up (prodejen → c → b → a? no: depHead 4 = token c, then b, then a, then Tržby)
            // depth 3 ⇒ governor is token 2 (`b`): b → a → Tržby is 3 hops. Found.
            stamp(chain(3), listOf(head, anchor("prodejen", 4, listOf(store), lemma = "prodejna")))[1]
                .slot.headRefs shouldBe listOf(storeSales)
            // depth 4 ⇒ governor is token 3 (`c`): c → b → a → Tržby is 4 hops. Not found.
            stamp(chain(4), listOf(head, anchor("prodejen", 4, listOf(store), lemma = "prodejna")))[1]
                .slot.headRefs shouldBe emptyList()
        }

        "a parse with tokens but no dependency tree yields no slots at all" {
            val p =
                parse(
                    "cs",
                    tok("Tržby", 0, 5, "tržba", "NOUN", 0, ""),
                    tok("prodejen", 6, 14, "prodejna", "NOUN", 0, ""),
                )
            stamp(
                p,
                listOf(
                    anchor("Tržby", 0, listOf(storeSales), lemma = "tržba"),
                    anchor("prodejen", 1, listOf(store, storeSales), lemma = "prodejna"),
                ),
            ).map { it.slot } shouldBe listOf(SlotHint.NONE, SlotHint.NONE)
        }

        "non-ANCHOR_PHRASE candidates and headless candidates keep NONE, and the list is unchanged" {
            val p =
                parse(
                    "cs",
                    tok("Tržby", 0, 5, "tržba", "NOUN", 0, "root"),
                    tok("podle", 6, 11, "podle", "ADP", 3, "case"),
                    tok("prodejen", 12, 20, "prodejna", "NOUN", 1, "nmod"),
                )
            val input =
                listOf(
                    anchor("Tržby", 0, listOf(storeSales), lemma = "tržba"),
                    anchor("prodejen", 2, listOf(store, storeSales), lemma = "prodejna"),
                    // A proper noun sitting on the same head as a mention: still a literal.
                    DomainSpanCandidate(
                        text = "prodejen",
                        start = 12,
                        end = 20,
                        gatedEntityRefs = listOf(store),
                        categories = emptyList(),
                        anchored = false,
                        origin = DomainSpanCandidate.Origin.PROPER_NOUN,
                        headToken = 2,
                    ),
                    // headToken = -1: what `ReGate.anchorCandidate` synthesises.
                    anchor("prodejen", -1, listOf(store, storeSales), lemma = "prodejna"),
                )

            val stamped = stamp(p, input)

            stamped.size shouldBe input.size
            stamped.map { it.text } shouldBe input.map { it.text }
            stamped[2].slot shouldBe SlotHint.NONE
            stamped[3].slot shouldBe SlotHint.NONE
        }

        "an empty count-heads table simply never fires the slot" {
            // A pre-MH `frame-roles.conf` override: everything else works, COUNT_HEAD does not.
            val preMh = FrameRolePreps(mapOf("cs" to setOf("podle")), mapOf("cs" to setOf("z")), "cs")
            val p =
                parse(
                    "cs",
                    tok("Kolik", 0, 5, "kolik", "DET", 3, "det:numgov"),
                    tok("máme", 6, 10, "mít", "VERB", 0, "root"),
                    tok("prodejen", 11, 19, "prodejna", "NOUN", 2, "obj"),
                )
            SlotHints
                .stamp(
                    p,
                    listOf(anchor("prodejen", 2, listOf(store, storeSales), lemma = "prodejna")),
                    kinds,
                    emptyMap(),
                    "cs",
                    preMh,
                )[0]
                .slot shouldBe SlotHint.NONE
        }

        "a measure head is reported as its OWNING fact, not as itself" {
            // "tržby z prodejen" where `tržby` bound the MEASURE rather than the fact: H is the
            // fact either way, which is what keeps the reachability rule's inputs uniform.
            val amount = "er.entity.store_sales.ext_sales_price"
            val p =
                parse(
                    "cs",
                    tok("Tržby", 0, 5, "tržba", "NOUN", 0, "root"),
                    tok("z", 6, 7, "z", "ADP", 3, "case"),
                    tok("prodejen", 8, 16, "prodejna", "NOUN", 1, "nmod"),
                )
            SlotHints
                .stamp(
                    p,
                    listOf(
                        anchor("Tržby", 0, listOf(amount), lemma = "tržba"),
                        anchor("prodejen", 2, listOf(store, storeSales), lemma = "prodejna"),
                    ),
                    kinds + (amount to "measure"),
                    mapOf(amount to storeSales),
                    "cs",
                    preps,
                )[1]
                .slot shouldBe SlotHint(Slot.FILTER, headRefs = listOf(storeSales), headMeasureCapable = true)
        }
    })
