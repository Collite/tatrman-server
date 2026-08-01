// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
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
import org.tatrman.resolver.registry.DeclaredValue
import org.tatrman.resolver.registry.DeclaredVocabulary
import org.tatrman.resolver.registry.DeclaredVocabularyEntry
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.registry.StubRegistrySource
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.ResolveRequest

/**
 * RG-P5.S2.T5 — RS-25 capability degrade. The resolver is the first consumer that
 * must VISIBLY branch on the capability matrix. Two cases:
 *  - no cs NER but dep parse present ⇒ universal hints thin out, the domain path
 *    binds unaffected, `degraded=false`;
 *  - an unsupported language ⇒ fold+fuzzy floor, every binding `degraded=true`
 *    with RG-RES-001, and the `capabilities` echo matches what backed the resolve.
 */
class DegradeTest :
    StringSpec({

        val registry =
            SnapshotRegistry(
                StubRegistrySource(
                    DeclaredVocabulary(
                        entries =
                            listOf(
                                DeclaredVocabularyEntry(
                                    "er.item",
                                    "er.item",
                                    listOf(DeclaredValue("i-majetek", "MAJETEK")),
                                ),
                            ),
                    ),
                    "snap-degrade",
                ),
                ResolverThresholds.LIVE,
            )
        val codec = ResumeTokenCodec(mapOf("k1" to ByteArray(32) { it.toByte() }), "k1")

        "no cs NER (dep present): universals thin out, the domain path binds, degraded=false" {
            // Parse HAS dep structure, NO NER entities. Matrix advertises DEP_PARSE only.
            val parse =
                AnalyzeResponse
                    .newBuilder()
                    .setLanguage("cs")
                    .setDetectedLanguage("cs")
                    .addAllTokens(
                        listOf(
                            tok("Zobraz", 0, 6, "zobrazit", "VERB", 0, "root"),
                            tok("MAJETEK", 7, 14, "MAJETEK", "PROPN", 1, "obj"),
                        ),
                    ).build()
            val status =
                StatusResponse
                    .newBuilder()
                    .setReady(true)
                    .addCapabilities(
                        Capability
                            .newBuilder()
                            .setLanguage("cs")
                            .setOp(NlpOp.DEP_PARSE)
                            .setEngine("udpipe"),
                    ).build()
            val fuzzy = fuzzyReturning(memberMatch("i-majetek", "MAJETEK", 0.95, "er.item"))
            val pipeline = ResolverPipeline(FakeNlp(parse, status), fuzzy, registry, emptyMap(), codec)

            val resp = runBlocking { pipeline.resolve(fresh("Zobraz MAJETEK")) }

            resp.capabilities.csNer.shouldBeFalse() // NER unavailable → thin universals
            resp.capabilities.depParse.shouldBeTrue()
            resp.capabilities.degraded.shouldBeFalse() // domain path is dep/n-gram based — unaffected
            val bindings = resp.resolution.bindingsList
            bindings.count { it.hasUniversal() } shouldBe 0
            val domain = bindings.single { it.hasDomain() }
            domain.domain.resolvedId shouldBe "i-majetek"
            domain.degraded.shouldBeFalse()
        }

        "unsupported language: fold+fuzzy floor, every binding degraded=true + RG-RES-001" {
            // No dep, no NER, empty capability matrix for the language → the floor.
            val parse =
                AnalyzeResponse
                    .newBuilder()
                    .setLanguage("sk")
                    .setDetectedLanguage("sk")
                    .addAllTokens(
                        listOf(
                            tok("MAJETEK", 0, 7, "", "", 0, ""),
                        ),
                    ).build()
            val status = StatusResponse.newBuilder().setReady(true).build() // no capabilities for sk
            val fuzzy = fuzzyReturning(memberMatch("i-majetek", "MAJETEK", 0.95, "er.item"))
            val pipeline = ResolverPipeline(FakeNlp(parse, status), fuzzy, registry, emptyMap(), codec)

            val resp = runBlocking { pipeline.resolve(fresh("MAJETEK")) }

            resp.capabilities.degraded.shouldBeTrue()
            resp.capabilities.csNer.shouldBeFalse()
            resp.capabilities.depParse.shouldBeFalse()
            resp.capabilities.degradedReasonsList shouldHaveSize 1
            resp.capabilities.degradedReasonsList
                .single()
                .contains("degraded")
                .shouldBeTrue()
            // the floor still binds via fuzzy, but every binding is flagged degraded.
            val domain = resp.resolution.bindingsList.single { it.hasDomain() }
            domain.degraded.shouldBeTrue()
        }
    }) {
    private class FakeNlp(
        private val parse: AnalyzeResponse,
        private val status: StatusResponse,
    ) : NlpClient {
        override suspend fun analyze(request: AnalyzeRequest): AnalyzeResponse = parse

        override suspend fun getStatus(): StatusResponse = status
    }

    companion object {
        private fun fresh(text: String) =
            ResolveRequest
                .newBuilder()
                .setConversationId(
                    "c",
                ).setFresh(FreshQuestion.newBuilder().setText(text).setLocale(""))
                .build()

        private fun fuzzyReturning(vararg matches: FuzzyMatch): FuzzyClient =
            object : FuzzyClient {
                override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse {
                    // Return the same match set for EVERY requested span (positional).
                    val builder = BatchMatchResponse.newBuilder()
                    repeat(request.spansCount) {
                        builder.addResults(FuzzyMatchResponse.newBuilder().addAllMatches(matches.toList()))
                    }
                    return builder.build()
                }

                override suspend fun getStatus(): FuzzyStatusResponse = FuzzyStatusResponse.getDefaultInstance()
            }

        private fun memberMatch(
            id: String,
            candidate: String,
            score: Double,
            category: String,
        ): FuzzyMatch =
            FuzzyMatch
                .newBuilder()
                .setCandidateId(id)
                .setCandidate(candidate)
                .setScore(score)
                .setCategory(category)
                .setSource(SourceTag.MEMBER)
                .setProvenance(
                    Provenance
                        .newBuilder()
                        .setProducer("fuzzy")
                        .setMethod("TATRMAN")
                        .setRawScore(score),
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
                .setText(
                    text,
                ).setCharStart(
                    start,
                ).setCharEnd(end)
                .setLemma(lemma)
                .setUpos(upos)
                .setDepHead(depHead)
                .setDepRelation(depRelation)
                .build()
    }
}
