// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import com.google.protobuf.util.JsonFormat
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.fuzzy.v1.BatchMatchRequest
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.FuzzyMatchResponse
import org.tatrman.fuzzy.v1.FuzzyStatusResponse
import org.tatrman.fuzzy.v1.LayerVersions
import org.tatrman.nlp.v1.AnalyzeRequest
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.Capability
import org.tatrman.nlp.v1.NlpOp
import org.tatrman.nlp.v1.StatusResponse
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.client.NlpClient
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.registry.DeclaredVocabulary
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.registry.StubRegistrySource
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.Registry
import org.tatrman.resolver.v1.ResolveRequest
import java.io.File

/**
 * RV-P2.1.T2 — the lattice golden files. Three fixtures, each asserting the **whole**
 * emitted `ResolutionState` (contracts §1) as JSON, not a hand-picked field or two:
 *
 *  - **h1-cs** — the 0-LLM hero. Five mentions incl. the `op:show` OPERATOR binding, the
 *    account code attributed to `md.dimension.Account.code` because the user said *účtu*
 *    (the structural fix for issues.md §"Looking in wrong entity"), the year grounded,
 *    **zero gaps**.
 *  - **h1prime-cs** — the same question with a typo'd code. Identical mentions and identical
 *    frame roles — role derivation is structural and must not wobble because a lookup missed —
 *    plus one **G4_METHOD_MISS** at `disposition: UNRESOLVED`.
 *  - **h2-cs** — issues.md §"Not admitting not knowing the entity". The unknown SUBJECT is a
 *    **G1_UNBOUND** mention with zero bindings instead of a forced binding, and the
 *    unattributable LOCATION hint is a **G3_UNATTRIBUTED** value.
 *
 * Everything upstream is a fake: `nlp` replays a REAL cached Stanza parse (see
 * `lattice/PROVENANCE.md`) and `lex-matcher` answers per query text from the case file, so a
 * change in span-proposal order cannot silently re-shuffle a positional fixture.
 *
 * The golden covers the lattice **minus `parse`**: the parse slot is asserted separately to be
 * byte-identical to `ResolveResponse.parse`, which is the contract (the lattice is
 * self-contained because it travels alone in the RV-26 delegation payload) — inlining a
 * 13-token parse into three goldens would only hide the annotations they exist to pin.
 */
class LatticeGoldenTest :
    StringSpec({

        listOf("h1-cs", "h1prime-cs", "h2-cs").forEach { id ->
            "$id: the emitted ResolutionState matches its golden file" {
                val case = loadJson("/lattice/$id.case.json")
                val parse = parseOf(case)
                val registry = registryOf(case)
                val fuzzy = FakeFuzzy(case)

                val pipeline =
                    ResolverPipeline(
                        FakeNlp(parse),
                        fuzzy,
                        SnapshotRegistry(StubRegistrySource(DeclaredVocabulary(), ""), ResolverThresholds.LIVE),
                        emptyMap(),
                        ResumeTokenCodec(mapOf("k1" to ByteArray(32) { it.toByte() }), activeKeyId = "k1"),
                    )

                val request =
                    ResolveRequest
                        .newBuilder()
                        .setConversationId(id)
                        .setFresh(
                            FreshQuestion
                                .newBuilder()
                                .setText(case["text"]!!.jsonPrimitive.content)
                                .setLocale(case["locale"]!!.jsonPrimitive.content),
                        ).setRegistry(registry)
                        .build()

                val response = runBlocking { pipeline.resolve(request) }
                val printed = json.parseToJsonElement(printer.print(response)).jsonObject
                val lattice =
                    printed["resolutionState"]?.jsonObject
                        ?: error("no `resolutionState` on the response — the lattice is not emitted")

                // The lattice carries the parse, and it is the SAME parse (S-1 identity travels
                // with a detached lattice). Asserted here, excluded from the golden.
                lattice["parse"] shouldBe printed["parse"]

                val actual = JsonObject(lattice - "parse")
                val expected = loadJson("/lattice/$id.lattice.json")
                if (actual != expected) dumpActual(id, actual)
                actual shouldBe expected
            }
        }
    }) {
    private class FakeNlp(
        private val parse: AnalyzeResponse,
    ) : NlpClient {
        override suspend fun analyze(request: AnalyzeRequest): AnalyzeResponse = parse

        override suspend fun getStatus(): StatusResponse =
            StatusResponse
                .newBuilder()
                .setReady(true)
                .addCapabilities(capability(NlpOp.NER, "nametag3", "cnec2.0"))
                .addCapabilities(capability(NlpOp.DEP_PARSE, "stanza", "1.13.0"))
                .build()

        private fun capability(
            op: NlpOp,
            engine: String,
            version: String,
        ): Capability =
            Capability
                .newBuilder()
                .setLanguage("cs")
                .setOp(op)
                .setEngine(engine)
                .setModelVersion(version)
                .build()
    }

    /**
     * lex-matcher, answering **by query text** rather than by slot index: the fixture states
     * what the vocabulary knows, and stays valid when span proposal changes what it asks for.
     * An unknown query answers with zero candidates — which is what a real miss looks like.
     */
    private class FakeFuzzy(
        case: JsonObject,
    ) : FuzzyClient {
        private val matcher = case["matcher"]!!.jsonObject
        private val byQuery: Map<String, List<FuzzyMatch>> =
            matcher["byQuery"]!!.jsonObject.mapValues { (_, matches) ->
                matches.jsonArray.map { merge(it.jsonObject, FuzzyMatch.newBuilder()).build() }
            }
        private val layerVersions: LayerVersions =
            matcher["layerVersions"]?.let { merge(it.jsonObject, LayerVersions.newBuilder()).build() }
                ?: LayerVersions.getDefaultInstance()
        private val vocabularyVersion: String = matcher["vocabularyVersion"]?.jsonPrimitive?.content.orEmpty()

        override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse {
            val builder = BatchMatchResponse.newBuilder()
            for (span in request.spansList) {
                builder.addResults(
                    FuzzyMatchResponse
                        .newBuilder()
                        .addAllMatches(byQuery[span.query].orEmpty())
                        .setMatchedAlgorithm("TATRMAN")
                        .setVocabularyVersion(vocabularyVersion)
                        .setLayerVersions(layerVersions),
                )
            }
            return builder.build()
        }

        override suspend fun getStatus(): FuzzyStatusResponse = FuzzyStatusResponse.getDefaultInstance()
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val printer: JsonFormat.Printer = JsonFormat.printer().omittingInsignificantWhitespace()

        /**
         * Fixture inputs are parsed leniently on purpose: a case file describes a *future* estate
         * (it carries fields the wire may not have grown yet), and a mis-typed input can never pass
         * unnoticed — the golden is the whole output, so anything dropped shows up there.
         */
        private val parser: JsonFormat.Parser = JsonFormat.parser().ignoringUnknownFields()

        private fun <T : com.google.protobuf.Message.Builder> merge(
            source: JsonObject,
            builder: T,
        ): T {
            parser.merge(source.toString(), builder)
            return builder
        }

        private fun loadJson(resource: String): JsonObject =
            json
                .parseToJsonElement(
                    LatticeGoldenTest::class.java.getResource(resource)?.readText()
                        ?: error("missing test resource $resource"),
                ).jsonObject

        private fun parseOf(case: JsonObject): AnalyzeResponse =
            merge(
                loadJson("/lattice/${case["parseFile"]!!.jsonPrimitive.content}"),
                AnalyzeResponse.newBuilder(),
            ).build()

        private fun registryOf(case: JsonObject): Registry =
            merge(case["registry"]!!.jsonObject, Registry.newBuilder()).build()

        /** On a mismatch, write what the core actually emitted — a diffable file beats a stack trace. */
        private fun dumpActual(
            id: String,
            actual: JsonObject,
        ) {
            val dir = File("build/lattice-actual").apply { mkdirs() }
            val out = File(dir, "$id.lattice.json")
            out.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), actual) + "\n")
            println("lattice golden mismatch for $id — actual emission written to ${out.absolutePath}")
        }
    }
}
