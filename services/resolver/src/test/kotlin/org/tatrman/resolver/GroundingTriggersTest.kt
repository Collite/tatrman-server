// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.FuzzyMatchResponse
import org.tatrman.fuzzy.v1.Provenance
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.fuzzy.v1.TargetClass
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.DomainSpanCandidate
import org.tatrman.resolver.pipeline.GroundingTriggers
import org.tatrman.resolver.v1.MatchMethod
import org.tatrman.resolver.v1.UniversalEntityType
import org.tatrman.ttr.lexicon.LexiconValidator
import org.tatrman.resolver.v1.TargetClass as LatticeTargetClass

/**
 * RV-P1.6.T6 (RV-42) — the grounding-trigger question, asked and read.
 *
 * `LatticeGoldenTest` pins what a trigger looks like in a whole emitted lattice; this pins the
 * mechanism underneath, including the parts no fixture exercises — the vocabulary the core is
 * allowed to ask about, which spans it asks about, and what it does with an answer it did not
 * expect.
 */
class GroundingTriggersTest :
    StringSpec({

        val thresholds = ResolverThresholds.LIVE

        "the askable kernels are the producer's closed vocabulary, not a copy of it" {
            // Read from `ttr-lexicon` — the artifact the estate's lexicon was validated and
            // compiled against — so a fourth kernel becomes askable the moment the producer
            // declares one. RG-LEX-012 rejects anything outside this set at authoring time, which
            // is what makes a fixed list here honest rather than a guess.
            GroundingTriggers.CATEGORIES shouldContainExactly
                LexiconValidator.GROUNDING_KINDS
                    .sorted()
                    .map { "ground:$it" }
            GroundingTriggers.CATEGORIES.all { it.startsWith(LexiconValidator.GROUND_PREFIX) } shouldBe true
        }

        "only the mention layer is asked, once per span" {
            val ucet = mention("účtu", 15, 19, head = 2)
            // the SAME span reached through a second entity type — one anchor word may be owned by
            // two model objects, and a trigger is about the surface, not about who scoped it
            val ucetAgain = ucet.copy(gatedEntityRefs = listOf("md.dimension.Ledger"))
            val code = literal("501001", 20, 26)
            val leftover = mention("stanic", 30, 36, head = 5).copy(gatedEntityRefs = emptyList())

            val spans = GroundingTriggers.spansOf(listOf(ucet, ucetAgain, code), listOf(leftover))

            spans.map { it.text } shouldContainExactly listOf("účtu", "stanic")
        }

        "a floor guess is still asked: a trigger is a lexicon fact, not a syntactic one" {
            val floor =
                mention("roce", 29, 33).copy(origin = DomainSpanCandidate.Origin.NGRAM_FLOOR, anchored = false)
            GroundingTriggers.spansOf(listOf(floor), emptyList()).map { it.text } shouldContainExactly listOf("roce")
        }

        "every query is class-scoped to the grounding categories, in span order" {
            val spans = listOf(mention("roce", 29, 33), mention("období", 45, 51))
            val queries = GroundingTriggers.queries(spans, perSpanLimit = 20)

            queries.map { it.query } shouldContainExactly listOf("roce", "období")
            queries.forEach {
                it.categoriesList shouldContainExactly GroundingTriggers.CATEGORIES
                it.limit shouldBe 20
            }
        }

        "the answer is read from the TRAILING slots, past the gate's own" {
            val gateSpans = listOf(mention("roce", 29, 33), literal("2025", 34, 38))
            val triggerSpans = listOf(mention("roce", 29, 33))
            val response =
                BatchMatchResponse
                    .newBuilder()
                    // the gate's two slots — a trigger read that started at 0 would take these
                    .addResults(matches(declaredTrigger("ground:money", 1.0)))
                    .addResults(FuzzyMatchResponse.getDefaultInstance())
                    .addResults(matches(declaredTrigger("ground:chrono", 1.0)))
                    .build()

            val triggers =
                GroundingTriggers.collect(triggerSpans, response, gateSpans.size, thresholds, "snap-1")

            triggers.getValue(29 to 33).map { it.ref } shouldContainExactly listOf("ground:chrono")
        }

        "one binding per slice, the strongest — several terms of one kernel are one assertion" {
            val span = mention("roce", 29, 33)
            val response =
                BatchMatchResponse
                    .newBuilder()
                    .addResults(
                        matches(
                            // "rok" and "roce" both fire within TYPOS(1) of the surface form
                            declaredTrigger("ground:chrono", 0.9, candidateId = "lex:ground:chrono:rok"),
                            declaredTrigger("ground:chrono", 1.0, candidateId = "lex:ground:chrono:roce"),
                            declaredTrigger("ground:money", 0.7),
                        ),
                    ).build()

            val bindings = GroundingTriggers.collect(listOf(span), response, 0, thresholds, "snap-1").getValue(29 to 33)

            bindings.map { it.ref } shouldContainExactly listOf("ground:chrono", "ground:money")
            bindings.first().inClassScore shouldBe 1.0
            bindings.first().targetClass shouldBe LatticeTargetClass.TARGET_CLASS_GROUNDING_TRIGGER
            // the authored RV-32 method survives the trip, parameter and all
            bindings.first().method shouldBe MatchMethod.MATCH_METHOD_TYPOS
            bindings.first().maxDistance shouldBe 1
        }

        "a trigger below the bind floor is not evidence of anything" {
            val span = mention("rok?", 29, 33)
            val response =
                BatchMatchResponse
                    .newBuilder()
                    .addResults(matches(declaredTrigger("ground:chrono", thresholds.bind - 0.01)))
                    .build()

            GroundingTriggers.collect(listOf(span), response, 0, thresholds, "snap-1") shouldBe emptyMap()
        }

        "a row that is not a grounding trigger cannot ride in through a trigger slot" {
            // The slot asks for `ground:` categories only, so this should not happen — and if a
            // matcher ever answers wider, a model object must not become a kernel annotation.
            val span = mention("roce", 29, 33)
            val response =
                BatchMatchResponse
                    .newBuilder()
                    .addResults(
                        matches(
                            FuzzyMatch
                                .newBuilder()
                                .setCandidateId("lex:md.dimension.Calendar.year")
                                .setCandidate("rok")
                                .setScore(1.0)
                                .setCategory("md.dimension.Calendar.year")
                                .setSource(SourceTag.DECLARED)
                                .setTargetRef("md.dimension.Calendar.year")
                                .setTargetClass(TargetClass.TARGET_CLASS_MODEL_OBJECT)
                                .build(),
                        ),
                    ).build()

            GroundingTriggers.collect(listOf(span), response, 0, thresholds, "snap-1") shouldBe emptyMap()
        }

        "a matcher that answered short degrades to 'no trigger', it does not take the resolve down" {
            val spans = listOf(mention("roce", 29, 33), mention("období", 45, 51))
            val short =
                BatchMatchResponse
                    .newBuilder()
                    .addResults(
                        matches(declaredTrigger("ground:chrono", 1.0)),
                    ).build()

            val triggers = GroundingTriggers.collect(spans, short, 0, thresholds, "snap-1")

            triggers.keys shouldContainExactly listOf(29 to 33)
        }

        "no spans, no queries and no answers to read" {
            GroundingTriggers.queries(emptyList(), 20).shouldBeEmpty()
            GroundingTriggers.collect(
                emptyList(),
                BatchMatchResponse.getDefaultInstance(),
                0,
                thresholds,
                "s",
            ) shouldBe emptyMap()
        }

        "a kernel name is read off a ref, never invented" {
            GroundingTriggers.kindOf("ground:chrono") shouldBe "chrono"
            // RG-LEX-012 rejects this at authoring time; if one ever reaches the wire it names no
            // kernel, and saying so beats passing "weather" on to a caller that would try to call it
            GroundingTriggers.kindOf("ground:weather") shouldBe ""
            GroundingTriggers.kindOf("op:show") shouldBe ""
            GroundingTriggers.kindOf("md.dimension.Account") shouldBe ""
        }

        "the universal classes map to the kernel that owns them, and only those" {
            GroundingTriggers.kernelOf(UniversalEntityType.DATE) shouldBe "chrono"
            GroundingTriggers.kernelOf(UniversalEntityType.MONEY) shouldBe "money"
            GroundingTriggers.kernelOf(UniversalEntityType.LOCATION) shouldBe "geo"
            // no grounding service claims a person or an organisation — a trigger beside one
            // narrows nothing, which is different from narrowing to a kernel that does not exist
            GroundingTriggers.kernelOf(UniversalEntityType.PERSON) shouldBe ""
            GroundingTriggers.kernelOf(UniversalEntityType.ORGANIZATION) shouldBe ""
            GroundingTriggers.kernelOf(UniversalEntityType.MISC) shouldBe ""
        }
    }) {
    companion object {
        private fun mention(
            text: String,
            start: Int,
            end: Int,
            head: Int = -1,
        ) = DomainSpanCandidate(
            text,
            start,
            end,
            listOf("md.dimension.Account"),
            listOf("md.dimension.Account"),
            anchored = true,
            origin = DomainSpanCandidate.Origin.ANCHOR_PHRASE,
            headToken = head,
            lemma = text,
        )

        private fun literal(
            text: String,
            start: Int,
            end: Int,
        ) = DomainSpanCandidate(
            text,
            start,
            end,
            listOf("md.dimension.Account"),
            listOf("md.dimension.Account"),
            anchored = true,
            origin = DomainSpanCandidate.Origin.LITERAL,
        )

        private fun matches(vararg found: FuzzyMatch): FuzzyMatchResponse =
            FuzzyMatchResponse.newBuilder().addAllMatches(found.toList()).build()

        private fun declaredTrigger(
            ref: String,
            score: Double,
            candidateId: String = "lex:$ref",
        ): FuzzyMatch =
            FuzzyMatch
                .newBuilder()
                .setCandidateId(candidateId)
                .setCandidate(ref.substringAfter(':'))
                .setScore(score)
                .setCategory(ref)
                .setSource(SourceTag.DECLARED)
                .setTargetRef(ref)
                .setTargetClass(TargetClass.TARGET_CLASS_GROUNDING_TRIGGER)
                .setMatchMethod("TYPOS(1)")
                .setProvenance(Provenance.newBuilder().setProducer("lex-matcher").setMethod("TATRMAN"))
                .build()
    }
}
