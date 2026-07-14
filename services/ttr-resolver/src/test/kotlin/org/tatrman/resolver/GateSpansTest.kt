// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.FuzzyMatchResponse
import org.tatrman.fuzzy.v1.Provenance
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.resolver.model.ResolverEntityType
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.Bound
import org.tatrman.resolver.pipeline.Clarify
import org.tatrman.resolver.pipeline.DomainSpanCandidate
import org.tatrman.resolver.pipeline.GateSpans

/**
 * RG-P5.S1.T4 — gateSpans against a fake `BatchMatch` loaded with the Q-20
 * vocabulary shapes. Thresholds are the live ENTITIES_ONLY values.
 */
class GateSpansTest :
    StringSpec({

        val thresholds = ResolverThresholds.LIVE
        val product = ResolverEntityType("er.product", listOf("er.product"), emptyList())
        val branch = ResolverEntityType("er.branch", listOf("er.branch"), listOf("pobočka"))
        val qtypdok = ResolverEntityType("er.qtypdok", listOf("er.qtypdok.kod", "er.qtypdok.nazev"), listOf("doklad"))
        val qstred =
            ResolverEntityType("er.qstred_df", listOf("er.qstred_df.kod", "er.qstred_df.nazev"), listOf("středisko"))
        val qxxukazmu =
            ResolverEntityType("er.qxxukazmu", listOf("er.qxxukazmu.kod", "er.qxxukazmu.nazev"), listOf("ukazatel"))
        val allTypes = listOf(product, branch, qtypdok, qstred, qxxukazmu)

        "one BatchMatch: buildBatchRequest emits one positional SpanQuery per candidate" {
            val cands =
                listOf(
                    cand("Octavie", 22, 29, listOf("er.product")),
                    cand("pražských pobočkách", 32, 51, listOf("er.branch")),
                )
            val req = GateSpans.buildBatchRequest(cands, locale = "cs", perSpanLimit = 5)
            req.spansCount shouldBe 2
            req.getSpans(0).query shouldBe "Octavie"
            req.getSpans(0).categoriesList shouldContainExactly listOf("er.product")
            req.getSpans(1).query shouldBe "pražských pobočkách"
            req.locale shouldBe "cs"
        }

        "MEMBER hit → Domain binding carrying resolved_id" {
            val cands = listOf(cand("Octavie", 22, 29, listOf("er.product")))
            val resp = batch(fmr(fm("p-octavia", "Škoda Octavia", 0.97, "er.product", SourceTag.MEMBER)))
            val outcome = GateSpans.gate(cands, resp, allTypes, thresholds, emptyMap(), "snap-1")
            val bound = outcome.shouldBeInstanceOf<Bound>()
            val b = bound.bindings.single()
            b.resolvedId shouldBe "p-octavia"
            b.targetRef.shouldBeNull()
            b.entityTypeRef shouldBe "er.product"
            b.vocabularySource shouldBe "MEMBER"
            b.snapshotHash shouldBe "snap-1"
        }

        "VOCABULARY hit → target-ref binding, no resolved_id" {
            val cands = listOf(cand("pobočkách", 42, 51, listOf("er.branch")))
            val resp =
                batch(
                    fmr(
                        fm(
                            "term-pobocka",
                            "pobočka",
                            0.88,
                            "er.branch",
                            SourceTag.VOCABULARY,
                            targetRef = "er.branch#term-pobocka",
                        ),
                    ),
                )
            val outcome = GateSpans.gate(cands, resp, allTypes, thresholds, emptyMap(), "snap-1")
            val b = outcome.shouldBeInstanceOf<Bound>().bindings.single()
            b.targetRef shouldBe "er.branch#term-pobocka"
            b.resolvedId.shouldBeNull()
            b.entityTypeRef shouldBe "er.branch"
            b.vocabularySource shouldBe "VOCABULARY"
        }

        "below the bind floor (< 0.5) → no binding" {
            val cands = listOf(cand("roce", 0, 4, listOf("er.product")))
            val resp = batch(fmr(fm("x", "x", 0.40, "er.product", SourceTag.MEMBER)))
            val outcome = GateSpans.gate(cands, resp, allTypes, thresholds, emptyMap(), "snap-1")
            outcome.shouldBeInstanceOf<Bound>().bindings shouldBe emptyList()
        }

        "exact-match dominance: an exact code binds despite a lower near-name (code-vs-name)" {
            val cands = listOf(cand("FAP", 0, 3, listOf("er.qtypdok.kod", "er.qtypdok.nazev")))
            val resp =
                batch(
                    fmr(
                        fm("code-FAP", "FAP", 0.9999, "er.qtypdok.kod", SourceTag.MEMBER),
                        fm(
                            "name-fap",
                            "Faktura přijatá",
                            0.61,
                            "er.qtypdok.nazev",
                            SourceTag.VOCABULARY,
                            targetRef = "er.qtypdok.nazev",
                        ),
                    ),
                )
            val b =
                GateSpans
                    .gate(
                        cands,
                        resp,
                        allTypes,
                        thresholds,
                        emptyMap(),
                        "snap-1",
                    ).shouldBeInstanceOf<Bound>()
                    .bindings
                    .single()
            b.resolvedId shouldBe "code-FAP"
            b.entityTypeRef shouldBe "er.qtypdok"
        }

        "entity-identity dedup: the same resolved id via two spans collapses to one binding" {
            val cands =
                listOf(
                    cand("pobočka Praha", 0, 13, listOf("er.branch")),
                    cand("Praha pobočka", 20, 33, listOf("er.branch")),
                )
            val resp =
                batch(
                    fmr(fm("b-praha", "Pražská pobočka", 0.92, "er.branch", SourceTag.MEMBER)),
                    fmr(fm("b-praha", "Pražská pobočka", 0.90, "er.branch", SourceTag.MEMBER)),
                )
            val bound =
                GateSpans
                    .gate(
                        cands,
                        resp,
                        allTypes,
                        thresholds,
                        emptyMap(),
                        "snap-1",
                    ).shouldBeInstanceOf<Bound>()
            bound.bindings shouldHaveSize 1
            bound.bindings.single().resolvedId shouldBe "b-praha"
            bound.bindings.single().score shouldBe 0.92 // the higher-scoring span wins
        }

        "instance ambiguity → AwaitingClarification with the distinct contenders (capped at maxOptions)" {
            val cands = listOf(cand("DF", 0, 2, listOf("er.qstred_df.kod", "er.qstred_df.nazev")))
            val resp =
                batch(
                    fmr(
                        fm("df-adnak", "DF ADNAK", 0.72, "er.qstred_df.nazev", SourceTag.MEMBER),
                        fm("df-belus", "DF BELUS", 0.70, "er.qstred_df.nazev", SourceTag.MEMBER),
                    ),
                )
            val clarify =
                GateSpans
                    .gate(
                        cands,
                        resp,
                        allTypes,
                        thresholds,
                        emptyMap(),
                        "snap-1",
                    ).shouldBeInstanceOf<Clarify>()
            clarify.options.map { it.resolvedId } shouldContainExactlyInAnyOrder listOf("df-adnak", "df-belus")
            clarify.options.size shouldBe 2
        }

        "sibling-column: a MEMBER value on the NAZEV column also points at its KOD sibling" {
            val cands = listOf(cand("MAJETEK", 0, 7, listOf("er.qxxukazmu.kod", "er.qxxukazmu.nazev")))
            val siblings = mapOf("er.qxxukazmu.nazev" to listOf("er.qxxukazmu.kod"))
            val resp = batch(fmr(fm("majetek-1", "MAJETEK", 0.95, "er.qxxukazmu.nazev", SourceTag.MEMBER)))
            val b =
                GateSpans
                    .gate(
                        cands,
                        resp,
                        allTypes,
                        thresholds,
                        siblings,
                        "snap-1",
                    ).shouldBeInstanceOf<Bound>()
                    .bindings
                    .single()
            b.siblingRefs shouldContainExactly listOf("er.qxxukazmu.kod")
            b.entityTypeRef shouldBe "er.qxxukazmu"
        }
    }) {
    companion object {
        private fun cand(
            text: String,
            start: Int,
            end: Int,
            categories: List<String>,
        ) = DomainSpanCandidate(text, start, end, categories, categories, anchored = true)

        private fun fm(
            id: String,
            candidate: String,
            score: Double,
            category: String,
            source: SourceTag,
            targetRef: String = "",
            method: String = "TATRMAN",
        ): FuzzyMatch {
            val b =
                FuzzyMatch
                    .newBuilder()
                    .setCandidateId(id)
                    .setCandidate(candidate)
                    .setScore(score)
                    .setCategory(category)
                    .setSource(source)
                    .setProvenance(
                        Provenance
                            .newBuilder()
                            .setProducer("fuzzy")
                            .setMethod(method)
                            .setRawScore(score),
                    )
            if (targetRef.isNotBlank()) b.targetRef = targetRef
            return b.build()
        }

        private fun fmr(vararg matches: FuzzyMatch): FuzzyMatchResponse =
            FuzzyMatchResponse.newBuilder().addAllMatches(matches.toList()).build()

        private fun batch(vararg results: FuzzyMatchResponse): BatchMatchResponse =
            BatchMatchResponse.newBuilder().addAllResults(results.toList()).build()
    }
}
