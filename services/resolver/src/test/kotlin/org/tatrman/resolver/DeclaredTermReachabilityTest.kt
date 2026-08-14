// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.model.ResolverEntityType
import org.tatrman.resolver.pipeline.MentionLayer
import org.tatrman.resolver.pipeline.SpanProposal

/**
 * **Two ways a correctly-declared term could not reach the lattice**, found by reading a live
 * lattice against the estate's own lexicon.
 *
 * Both are matcher-side: in each case the estate authored the term correctly and *nothing in the
 * pipeline could ever produce the span it is keyed on*. They are independent of whether the
 * declared vocabulary is fed at all — the first one reproduces with an EMPTY registry, which is
 * why it went unnoticed while the registry was empty.
 *
 *  - **(1) a proper-noun modifier ate its own phrase.** `Marketplace revenue` never formed,
 *    because `SpanProposal`'s PROPN branch claims `Marketplace` as a candidate of its own and
 *    `MentionLayer` then refuses to fold a *claimed* token into a neighbour's phrase. The head
 *    noun was left alone, and a bare measure word is ambiguous on any estate that declines to
 *    declare bare measure words. **Lowercase the same word and it binds** — the common-noun
 *    tagging skips the PROPN branch entirely.
 *  - **(2) a multi-word anchor could not be matched.** The anchor index was keyed on a single
 *    folded token, so `by month` — or any two-word declared term — could not be a key any token
 *    matches. An estate can author it exactly right and nothing will ever see it.
 */
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

class DeclaredTermReachabilityTest :
    StringSpec({

        // ── (1) the proper-noun modifier ──────────────────────────────────────────────

        // "Why did Marketplace revenue drop in 2025?" — the live parse, verbatim.
        // 0 Why(advmod→5) 1 did(aux→5) 2 Marketplace(PROPN,compound→4) 3 revenue(NOUN,nsubj→5)
        // 4 drop(VERB,root) 5 in(case→7) 6 2025(NUM,obl→5) 7 ?
        val capitalised =
            parse(
                tok("Why", 0, 3, "why", "ADV", 5, "advmod"),
                tok("did", 4, 7, "do", "AUX", 5, "aux"),
                tok("Marketplace", 8, 19, "Marketplace", "PROPN", 4, "compound"),
                tok("revenue", 20, 27, "revenue", "NOUN", 5, "nsubj"),
                tok("drop", 28, 32, "drop", "VERB", 0, "root"),
                tok("in", 33, 35, "in", "ADP", 7, "case"),
                tok("2025", 36, 40, "2025", "NUM", 5, "obl"),
                tok("?", 40, 41, "?", "PUNCT", 5, "punct"),
            )

        // The same question with the channel written as a common noun — the phrasing that
        // already worked. Kept as the CONTROL: the fix must make the two agree, not make the
        // capitalised one merely different.
        val lowercased =
            parse(
                tok("Why", 0, 3, "why", "ADV", 5, "advmod"),
                tok("did", 4, 7, "do", "AUX", 5, "aux"),
                tok("marketplace", 8, 19, "marketplace", "NOUN", 4, "compound"),
                tok("revenue", 20, 27, "revenue", "NOUN", 5, "nsubj"),
                tok("drop", 28, 32, "drop", "VERB", 0, "root"),
                tok("in", 33, 35, "in", "ADP", 7, "case"),
                tok("2025", 36, 40, "2025", "NUM", 5, "obl"),
                tok("?", 40, 41, "?", "PUNCT", 5, "punct"),
            )

        fun mentions(p: AnalyzeResponse): List<String> {
            val gated = SpanProposal.proposeDomainSpans(p, emptyList())
            return MentionLayer.propose(p, gated).map { it.text }
        }

        "⚑ a PROPN compound modifier no longer eats the phrase it modifies" {
            withClue("the whole term is what the estate declares; the head noun alone is ambiguous") {
                mentions(capitalised) shouldContain "Marketplace revenue"
            }
        }

        "capitalisation does not change what reaches the gate" {
            // The defect in one assertion. ⚑ Compared over BOTH layers, not just mentions: the
            // two-layer model (RV-2) routes the same word differently by tag — a proper noun is a
            // span-proposal candidate, a common noun is a leftover mention — and both reach the
            // one BatchMatch. Comparing mentions alone would assert that the layers agree, which
            // they are designed not to. What must agree is the set of SPANS the estate gets asked
            // about, and before the fix that set was missing `Marketplace revenue` entirely.
            fun asked(p: AnalyzeResponse): List<String> {
                val gated = SpanProposal.proposeDomainSpans(p, emptyList())
                return (gated + MentionLayer.propose(p, gated)).map { it.text.lowercase() }.distinct()
            }
            asked(capitalised) shouldContainExactlyInAnyOrder asked(lowercased)
        }

        "the proper noun SURVIVES as its own candidate — it is still a filter value" {
            // The narrow fix must not be "stop proposing proper nouns". `Marketplace` names the
            // channel and is a legitimate value; losing it would trade one binding for another.
            SpanProposal
                .proposeDomainSpans(capitalised, emptyList())
                .map { it.text } shouldContain "Marketplace"
        }

        "a standalone proper noun is untouched — no phrase to be part of" {
            // 0 Show(VERB,root) 1 Memphis(PROPN,obj→1)
            val p =
                parse(
                    tok("Show", 0, 4, "show", "VERB", 0, "root"),
                    tok("Memphis", 5, 12, "Memphis", "PROPN", 1, "obj"),
                )
            SpanProposal.proposeDomainSpans(p, emptyList()).map { it.text } shouldContain "Memphis"
        }

        // ── (2) multi-word anchors ────────────────────────────────────────────────────

        val dateDim =
            ResolverEntityType(
                ref = "er.entity.date_dim.month",
                categories = listOf("er.entity.date_dim.month"),
                // Authored as a PHRASE on purpose: a bare `month` is already a chrono grounding
                // trigger, and declaring it twice would put two classes in competition on one span.
                anchors = listOf("by month", "monthly"),
            )
        val sales =
            ResolverEntityType(
                ref = "er.entity.catalog_sales.ext_sales_price",
                categories = listOf("er.entity.catalog_sales.ext_sales_price"),
                anchors = listOf("marketplace revenue"),
            )

        // "Show me marketplace revenue by month" —
        // 0 Show(root) 1 me(iobj→1) 2 marketplace(NOUN,compound→4) 3 revenue(NOUN,obj→1)
        // 4 by(ADP,case→6) 5 month(NOUN,nmod→4)
        val byMonth =
            parse(
                tok("Show", 0, 4, "show", "VERB", 0, "root"),
                tok("me", 5, 7, "I", "PRON", 1, "iobj"),
                tok("marketplace", 8, 19, "marketplace", "NOUN", 4, "compound"),
                tok("revenue", 20, 27, "revenue", "NOUN", 1, "obj"),
                tok("by", 28, 30, "by", "ADP", 6, "case"),
                tok("month", 31, 36, "month", "NOUN", 4, "nmod"),
            )

        "⚑ a TWO-WORD anchor is matched, and proposes the span it actually covers" {
            val cands = SpanProposal.proposeDomainSpans(byMonth, listOf(dateDim, sales))
            val grain = cands.single { it.text == "by month" }

            grain.anchored shouldBe true
            grain.gatedEntityRefs shouldBe listOf("er.entity.date_dim.month")
            withClue("the span must cover BOTH tokens — a phrase anchor that proposes one word is the old bug") {
                grain.start shouldBe 28
                grain.end shouldBe 36
            }
        }

        "a two-word anchor crossing a preposition is why this is not a phrase-relation fix" {
            // `by` attaches with `case`, which is not a phrase relation and must not become one:
            // adding it would drag prepositions into every leftover mention. The anchor index is
            // the right place, because the estate has NAMED this exact word sequence.
            MentionLayer
                .propose(byMonth, SpanProposal.proposeDomainSpans(byMonth, listOf(dateDim, sales)))
                .map { it.text }
                .forEach {
                    withClue("leftover phrases must not start swallowing prepositions") {
                        it.startsWith("by ") shouldBe
                            false
                    }
                }
        }

        "the longest anchor wins when two overlap" {
            val overlapping =
                ResolverEntityType(
                    ref = "er.entity.date_dim.month",
                    categories = listOf("er.entity.date_dim.month"),
                    anchors = listOf("by month", "by month end"),
                )
            val p =
                parse(
                    tok("by", 0, 2, "by", "ADP", 3, "case"),
                    tok("month", 3, 8, "month", "NOUN", 0, "root"),
                    tok("end", 9, 12, "end", "NOUN", 2, "compound"),
                )
            SpanProposal
                .proposeDomainSpans(p, listOf(overlapping))
                .filter { it.anchored }
                .map { it.text } shouldContain "by month end"
        }

        "a single-word anchor still behaves exactly as before" {
            // The regression guard. Q-20's anchored path is the precision path, and this change
            // must not widen it: one-word anchors take the same route they always did.
            val branch =
                ResolverEntityType(ref = "er.branch", categories = listOf("er.branch"), anchors = listOf("pobočka"))
            val p =
                parse(
                    tok("v", 0, 1, "v", "ADP", 3, "case"),
                    tok("pražských", 2, 11, "pražský", "ADJ", 3, "amod"),
                    tok("pobočkách", 12, 21, "pobočka", "NOUN", 0, "root"),
                )
            val cands = SpanProposal.proposeDomainSpans(p, listOf(branch))
            val branchCand = cands.single { it.text == "pražských pobočkách" }
            branchCand.anchored shouldBe true
            branchCand.gatedEntityRefs shouldBe listOf("er.branch")
        }

        "an anchor phrase whose words are not contiguous does NOT match" {
            // "revenue by marketplace" is not "marketplace revenue". A bag-of-words anchor would
            // bind the wrong object confidently, which is the failure this whole layer avoids.
            val p =
                parse(
                    tok("revenue", 0, 7, "revenue", "NOUN", 0, "root"),
                    tok("by", 8, 10, "by", "ADP", 3, "case"),
                    tok("marketplace", 11, 22, "marketplace", "NOUN", 1, "nmod"),
                )
            SpanProposal
                .proposeDomainSpans(p, listOf(sales))
                .none { it.anchored && it.text == "marketplace revenue" } shouldBe true
        }
    })
