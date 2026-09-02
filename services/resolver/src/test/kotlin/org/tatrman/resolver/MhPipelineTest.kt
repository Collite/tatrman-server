// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.tatrman.fuzzy.v1.BatchMatchRequest
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.FuzzyMatchResponse
import org.tatrman.fuzzy.v1.FuzzyStatusResponse
import org.tatrman.fuzzy.v1.Provenance
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.nlp.v1.AnalyzeRequest
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.Capability
import org.tatrman.nlp.v1.NlpOp
import org.tatrman.nlp.v1.StatusResponse
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.client.NlpClient
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.registry.DeclaredVocabulary
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.registry.StubRegistrySource
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.resolver.v1.EntityType
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.Reach
import org.tatrman.resolver.v1.Registry
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ResolveResponse

/**
 * MH — the E-catalogue (design.md §1.4) through the WHOLE pipeline.
 *
 * `BinderTest` proves the two rules in isolation and `SlotHintsTest` proves the derivation; this
 * proves they meet — parse → span proposal → slot stamping → one BatchMatch → the gate — over the
 * hartland collision with the bare word RESTORED in the channel vocabulary. That restoration is
 * the point: it is what ✅MH-D6 will do to the estate in MH-P2, and this is where the outcome of
 * doing it is pinned before the estate takes the risk.
 *
 * The registry arrives through the per-request override, carrying the same three facts the
 * archive carries — `object_kind`, `owner_ref`, `reached_from` — so both channels are exercised
 * by the feature's own tests rather than only the archive one.
 *
 * ⚠ The parses are hand-built and UD-plausible, not Stanza-verified (plan risk 5). MH-P2's live
 * drill is what settles them; a divergence becomes a case here with the real tokens.
 */
class MhPipelineTest :
    StringSpec({

        "E1 — `Kolik máme prodejen?` binds the store DIMENSION" {
            val state =
                resolve(
                    "Kolik máme prodejen?",
                    tok("Kolik", 0, 5, "kolik", "DET", 3, "det:numgov"),
                    tok("máme", 6, 10, "mít", "VERB", 0, "root"),
                    tok("prodejen", 11, 19, "prodejna", "NOUN", 2, "obj"),
                    tok("?", 19, 20, "?", "PUNCT", 2, "punct"),
                )

            val mention = state.mentionsList.single { it.span.text == "prodejen" }
            mention.bindingsList.map { it.ref } shouldContainExactly listOf(STORE)
            mention.bindingsList
                .single()
                .equivalentsList shouldBe emptyList()
        }

        "E2 — `Tržby z prodejen` binds the dimension AND records the channel as equal" {
            val state =
                resolve(
                    "Tržby z prodejen za 2025",
                    tok("Tržby", 0, 5, "tržba", "NOUN", 0, "root"),
                    tok("z", 6, 7, "z", "ADP", 3, "case"),
                    tok("prodejen", 8, 16, "prodejna", "NOUN", 1, "nmod"),
                    tok("za", 17, 19, "za", "ADP", 5, "case"),
                    tok("2025", 20, 24, "2025", "NUM", 1, "nmod"),
                )

            state.mentionsList
                .single { it.span.text == "Tržby" }
                .bindingsList
                .map { it.ref } shouldContainExactly listOf(STORE_SALES)

            // The money shot: the dimension binds, and the answer can still say that on THIS
            // model the Stores-channel reading selects the same rows.
            val prodejen = state.mentionsList.single { it.span.text == "prodejen" }
            prodejen.bindingsList.map { it.ref } shouldContainExactly listOf(STORE)
            prodejen.bindingsList
                .single()
                .equivalentsList
                .map { it.ref to it.rule } shouldContainExactly listOf(STORE_SALES to "reach-equal")
        }

        "E3 — `Tržby podle prodejen` groups by the dimension" {
            val state =
                resolve(
                    "Tržby podle prodejen",
                    tok("Tržby", 0, 5, "tržba", "NOUN", 0, "root"),
                    tok("podle", 6, 11, "podle", "ADP", 3, "case"),
                    tok("prodejen", 12, 20, "prodejna", "NOUN", 1, "nmod"),
                )

            state.mentionsList
                .single { it.span.text == "prodejen" }
                .bindingsList
                .map { it.ref } shouldContainExactly listOf(STORE)
        }

        "E5 — `Srovnej prodejny a web` compares CHANNEL with channel" {
            val state =
                resolve(
                    "Srovnej prodejny a web",
                    tok("Srovnej", 0, 7, "srovnat", "VERB", 0, "root"),
                    tok("prodejny", 8, 16, "prodejna", "NOUN", 1, "obj"),
                    tok("a", 17, 18, "a", "CCONJ", 4, "cc"),
                    tok("web", 19, 22, "web", "NOUN", 2, "conj"),
                )

            // The comparison axis has to be the same species on both sides — six store rows
            // against one channel is the wrong shape, and it is the one T1 alone cannot avoid.
            state.mentionsList
                .single { it.span.text == "prodejny" }
                .bindingsList
                .map { it.ref } shouldContainExactly listOf(STORE_SALES)
            state.mentionsList
                .single { it.span.text == "web" }
                .bindingsList
                .map { it.ref } shouldContainExactly listOf(WEB_SALES)
        }

        "E6 — `Vratky z prodejen` binds the dimension, NOT the sales fact" {
            val state =
                resolve(
                    "Vratky z prodejen",
                    tok("Vratky", 0, 6, "vratka", "NOUN", 0, "root"),
                    tok("z", 7, 8, "z", "ADP", 3, "case"),
                    tok("prodejen", 9, 17, "prodejna", "NOUN", 1, "nmod"),
                )

            // The regression T2 would have shipped alone: the channel term is pinned to
            // store_sales and the clause is about returns, so the fact reading is the WRONG fact.
            val prodejen = state.mentionsList.single { it.span.text == "prodejen" }
            prodejen.bindingsList.map { it.ref } shouldContainExactly listOf(STORE)
            // …and nothing is claimed equal here: rule 3 fired, not rule 2.
            prodejen.bindingsList
                .single()
                .equivalentsList shouldBe emptyList()
        }

        "E9 — the bare word still ASKS, and the two options carry their SPECIES" {
            val response =
                resolveResponse(
                    "prodejna",
                    tok("prodejna", 0, 8, "prodejna", "NOUN", 0, "root"),
                )

            // The single-word regression MS pinned (§8.5): no slot, no rule, no silent bind.
            response.hasAwaiting() shouldBe true
            val options = response.awaiting.optionsList
            // A one-word question has no dependency tree, so the span comes from the n-gram floor
            // (R4-γ) and is gated against EVERY declared type — the pre-MH reading, unchanged.
            options.map { it.targetRef } shouldContainAll listOf(STORE, STORE_SALES)
            // What MH adds to the refusal: a question a human can answer, because each option
            // now says what SPECIES it is.
            options
                .filter { it.targetRef in listOf(STORE, STORE_SALES) }
                .map { it.objectKind } shouldContainExactlyInAnyOrder listOf("entity", "entity_with_measures")
            options.map { it.span.text }.distinct() shouldContainExactly listOf("prodejna")
        }

        "EN-1 — `How many stores do we have?` binds the dimension" {
            val state =
                resolve(
                    "How many stores do we have?",
                    lang = "en",
                    tokens =
                        arrayOf(
                            tok("How", 0, 3, "how", "ADV", 2, "advmod"),
                            tok("many", 4, 8, "many", "ADJ", 3, "amod"),
                            tok("stores", 9, 15, "store", "NOUN", 6, "obj"),
                            tok("do", 16, 18, "do", "AUX", 6, "aux"),
                            tok("we", 19, 21, "we", "PRON", 6, "nsubj"),
                            tok("have", 22, 26, "have", "VERB", 0, "root"),
                            tok("?", 26, 27, "?", "PUNCT", 6, "punct"),
                        ),
                )

            // The span is the whole nominal phrase — `many` is an `amod` of `stores`, and span
            // proposal takes the subtree, not the bare head.
            state.mentionsList
                .single { it.span.text == "many stores" }
                .bindingsList
                .map { it.ref } shouldContainExactly listOf(STORE)
        }

        "EN-2 — `Revenue by store` groups by the dimension" {
            val state =
                resolve(
                    "Revenue by store",
                    lang = "en",
                    tokens =
                        arrayOf(
                            tok("Revenue", 0, 7, "revenue", "NOUN", 0, "root"),
                            tok("by", 8, 10, "by", "ADP", 3, "case"),
                            tok("store", 11, 16, "store", "NOUN", 1, "nmod"),
                        ),
                )

            state.mentionsList
                .single { it.span.text == "store" }
                .bindingsList
                .map { it.ref } shouldContainExactly listOf(STORE)
        }

        "a registry with NO kinds and NO reach reproduces the pre-MH answer: E1 asks" {
            // The compatibility claim, end to end: an estate on a pre-MH archive (or one that
            // declared no mention facet) gets exactly the G2 it got before, from the same parse.
            val response =
                resolveResponse(
                    "Kolik máme prodejen?",
                    tok("Kolik", 0, 5, "kolik", "DET", 3, "det:numgov"),
                    tok("máme", 6, 10, "mít", "VERB", 0, "root"),
                    tok("prodejen", 11, 19, "prodejna", "NOUN", 2, "obj"),
                    tok("?", 19, 20, "?", "PUNCT", 2, "punct"),
                    registry = PLAIN_REGISTRY,
                )

            response.hasAwaiting() shouldBe true
            response.awaiting.optionsList
                .map { it.targetRef } shouldContainAll listOf(STORE, STORE_SALES)
            response.awaiting.optionsList
                .map { it.objectKind }
                .distinct() shouldContainExactly listOf("")
        }
    }) {
    private companion object {
        private const val STORE = "er.entity.store"
        private const val STORE_SALES = "er.entity.store_sales"
        private const val STORE_RETURNS = "er.entity.store_returns"
        private const val WEB_SALES = "er.entity.web_sales"

        /**
         * The hartland registry with ✅MH-D6 ALREADY APPLIED: `prodejna` / `stores` are anchors of
         * the channel term AND of the dimension, which is the collision the whole effort is about.
         */
        private val MH_REGISTRY: Registry =
            Registry
                .newBuilder()
                .addEntityTypes(
                    EntityType
                        .newBuilder()
                        .setRef(STORE)
                        .addCategories(STORE)
                        .addAnchors("prodejna")
                        .addAnchors("store")
                        .setObjectKind("entity")
                        .addReachedFrom(Reach.newBuilder().setFactRef(STORE_RETURNS).setMandatory(true))
                        .addReachedFrom(Reach.newBuilder().setFactRef(STORE_SALES).setMandatory(true)),
                ).addEntityTypes(
                    EntityType
                        .newBuilder()
                        .setRef(STORE_SALES)
                        .addCategories(STORE_SALES)
                        .addAnchors("tržba")
                        .addAnchors("revenue")
                        .addAnchors("prodejna")
                        .addAnchors("store")
                        .setObjectKind("entity_with_measures"),
                ).addEntityTypes(
                    EntityType
                        .newBuilder()
                        .setRef(STORE_RETURNS)
                        .addCategories(STORE_RETURNS)
                        .addAnchors("vratka")
                        .setObjectKind("entity"),
                ).addEntityTypes(
                    EntityType
                        .newBuilder()
                        .setRef(WEB_SALES)
                        .addCategories(WEB_SALES)
                        .addAnchors("web")
                        .setObjectKind("entity_with_measures"),
                ).addLocales("cs")
                .addLocales("en")
                .setSnapshotHash("snap-mh")
                .build()

        /** The same vocabulary with every MH fact stripped — a pre-MH estate. */
        private val PLAIN_REGISTRY: Registry =
            MH_REGISTRY
                .toBuilder()
                .clearEntityTypes()
                .addAllEntityTypes(
                    MH_REGISTRY.entityTypesList.map {
                        it
                            .toBuilder()
                            .clearObjectKind()
                            .clearReachedFrom()
                            .build()
                    },
                ).build()

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

        private fun resolveResponse(
            text: String,
            vararg tokens: Token,
            lang: String = "cs",
            registry: Registry = MH_REGISTRY,
        ): ResolveResponse {
            val parse =
                AnalyzeResponse
                    .newBuilder()
                    .setLanguage(lang)
                    .setDetectedLanguage(lang)
                    .setTraceId("mh")
                    .addAllTokens(tokens.toList())
                    .build()
            val pipeline =
                ResolverPipeline(
                    FakeNlp(parse, lang),
                    AnchorFuzzy(),
                    SnapshotRegistry(StubRegistrySource(DeclaredVocabulary(), ""), ResolverThresholds.LIVE),
                    emptyMap(),
                    ResumeTokenCodec(mapOf("k1" to ByteArray(32) { it.toByte() }), activeKeyId = "k1"),
                )
            val request =
                ResolveRequest
                    .newBuilder()
                    .setConversationId("mh")
                    .setFresh(FreshQuestion.newBuilder().setText(text).setLocale(lang))
                    .setRegistry(registry)
                    .build()
            return runBlocking { pipeline.resolve(request) }
        }

        private fun resolve(
            text: String,
            vararg tokens: Token,
            lang: String = "cs",
            registry: Registry = MH_REGISTRY,
        ) = resolveResponse(text, *tokens, lang = lang, registry = registry).resolutionState

        private fun resolve(
            text: String,
            lang: String,
            tokens: Array<Token>,
        ) = resolveResponse(text, *tokens, lang = lang).resolutionState

        /**
         * A matcher that answers every span with one EXACT DECLARED row per category it was asked
         * about — i.e. the vocabulary contains exactly what the registry says it does. That makes
         * the homonym a real tie at the top of the top class, which is the input the Binder's two
         * rules exist to decide.
         */
        private class AnchorFuzzy : FuzzyClient {
            override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse {
                val builder = BatchMatchResponse.newBuilder()
                for (span in request.spansList) {
                    val matches =
                        span.categoriesList.map { ref ->
                            FuzzyMatch
                                .newBuilder()
                                .setCandidateId("lex:$ref")
                                .setCandidate(span.query)
                                .setScore(1.0)
                                .setCategory(ref)
                                .setTargetRef(ref)
                                .setSource(SourceTag.DECLARED)
                                .setMatchMethod("EXACT")
                                .setProvenance(
                                    Provenance
                                        .newBuilder()
                                        .setProducer("fuzzy")
                                        .setMethod("TATRMAN")
                                        .setRawScore(1.0),
                                ).build()
                        }
                    builder.addResults(FuzzyMatchResponse.newBuilder().addAllMatches(matches))
                }
                return builder.build()
            }

            override suspend fun getStatus(): FuzzyStatusResponse = FuzzyStatusResponse.getDefaultInstance()
        }

        private class FakeNlp(
            private val parse: AnalyzeResponse,
            private val lang: String,
        ) : NlpClient {
            override suspend fun analyze(request: AnalyzeRequest): AnalyzeResponse = parse

            override suspend fun getStatus(): StatusResponse =
                StatusResponse
                    .newBuilder()
                    .setReady(true)
                    .addCapabilities(
                        Capability
                            .newBuilder()
                            .setLanguage(lang)
                            .setOp(NlpOp.NER)
                            .setEngine("nametag3")
                            .setModelVersion("cnec2.0"),
                    ).addCapabilities(
                        Capability
                            .newBuilder()
                            .setLanguage(lang)
                            .setOp(NlpOp.DEP_PARSE)
                            .setEngine("udpipe")
                            .setModelVersion("pdt-2.5"),
                    ).build()
        }
    }
}
