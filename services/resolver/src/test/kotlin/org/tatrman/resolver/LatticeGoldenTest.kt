// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import com.google.protobuf.util.JsonFormat
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import org.tatrman.resolver.registry.DeclaredValue
import org.tatrman.resolver.registry.DeclaredVocabularyEntry
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
 * RV-P1.6.T6 extends the first two with the **grounding-trigger annotation** (RV-42): *roce* and
 * *období* each carry a `ground:chrono` binding beside their model binding — the overlap is the
 * lattice's normal state, not a competition — and the trigger on *roce* is what anchors the chrono
 * call on `2025`. h2 is the negative: its estate ships no `ground:` rows and nothing is annotated.
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

        // ---- MS-P2·S2 T5 — the no-op guard, and the lattice P3 starts from -------------------
        //
        // ⚑ The task list expected `objectKind` to appear on the mention and nothing else to
        // move. Neither half is what happens. `Mention` has no `object_kind` field — the kind is
        // consumed by `FrameRoles.Input` and never reaches the wire — so a kind cannot show up as
        // itself. What it shows up as is a frame role, and the role is R2: *a measure IS the
        // measure*. **R2 comes alive here, in P2·S2, not in P3.** It was never dead code; it was
        // live code that had never been given a kind, which is precisely what tatrman-server#69
        // says. P3 changes competition and exemptions on top of a lattice that already has R2.
        //
        // ⛑ But "a frame role is the only effect" is FALSE as a general claim (review-083 F2),
        // and the first version of this guard made it from one case. A kind is read in a second
        // place: `SpanProposal` skips governed-value proposal for an anchor whose kind is in
        // `VALUELESS_OBJECT_KINDS = {operator, measure}`, and emitting a governed value marks its
        // tokens COVERED — so a kind can change the mention SET, not just its roles. `h2-cs`
        // shows exactly that, and it is pinned below rather than left as folklore.
        //
        // What saves the no-op claim is the scope: `operator` never reaches the snapshot channel
        // (the archive's `targets` map holds entity/attribute entries and `fetch()` filters to
        // `TargetClass.MODEL_OBJECT`, so an `op:` ref is never given a kind). Restricted to the
        // four kinds MS can actually produce, the corpus is unchanged outside `frameRoles` — and
        // that is the property asserted over ALL four cases, not one.
        //
        // ⚠ It stays a corpus result, not a theorem: `measure` IS in that set, so an estate whose
        // measure anchor governs a nominal child would see its spans change too. No case here has
        // that shape. That belongs in the MS-P4·T3 chain drill.

        // Contracts §5 — the four values `MentionKinds` can put on the snapshot channel.
        val msProducibleKinds = setOf("measure", "attribute", "entity", "entity_with_measures")

        // Rebuild a case's proto registry as a DeclaredVocabulary, the way the archive reader now
        // produces one: one entry per (targetRef, category), anchors as values, kinds per
        // `kindOf`. Feeding it through `StubRegistrySource` — with NO per-request override — is
        // what makes this the SNAPSHOT channel rather than the one the goldens below exercise.
        fun latticeWith(
            id: String,
            kindOf: (String) -> String,
        ): JsonObject {
            val case = loadJson("/lattice/$id.case.json")
            val declared = registryOf(case)
            val vocabulary =
                DeclaredVocabulary(
                    entries =
                        declared.entityTypesList.flatMap { t ->
                            t.categoriesList.mapIndexed { i, category ->
                                DeclaredVocabularyEntry(
                                    category = category,
                                    targetRef = t.ref,
                                    // Anchors hang off the first category only; `project`
                                    // distincts the union, so repeating them would be a no-op
                                    // that hid a duplication bug.
                                    values =
                                        if (i == 0) {
                                            t.anchorsList.map { DeclaredValue(id = "lex:${t.ref}:$it", value = it) }
                                        } else {
                                            emptyList()
                                        },
                                    objectKind = kindOf(t.objectKind),
                                )
                            }
                        },
                    locales = declared.localesList.toList(),
                )
            val pipeline =
                ResolverPipeline(
                    FakeNlp(parseOf(case)),
                    FakeFuzzy(case),
                    SnapshotRegistry(
                        StubRegistrySource(vocabulary, "snap-ms"),
                        ResolverThresholds.LIVE,
                        configLocales = declared.localesList.toList(),
                    ),
                    emptyMap(),
                    ResumeTokenCodec(mapOf("k1" to ByteArray(32) { it.toByte() }), activeKeyId = "k1"),
                )
            val request =
                ResolveRequest
                    .newBuilder()
                    .setConversationId("$id-ms")
                    .setFresh(
                        FreshQuestion
                            .newBuilder()
                            .setText(case["text"]!!.jsonPrimitive.content)
                            .setLocale(case["locale"]!!.jsonPrimitive.content),
                    ).build()
            val response = runBlocking { pipeline.resolve(request) }
            val printed = json.parseToJsonElement(printer.print(response)).jsonObject
            val lattice = printed["resolutionState"]!!.jsonObject
            return JsonObject(withoutDurations(lattice) - "parse")
        }

        val noKinds: (String) -> String = { "" }
        val msKindsOnly: (String) -> String = { if (it in msProducibleKinds) it else "" }
        val everyKind: (String) -> String = { it }

        listOf("h1-cs", "h1prime-cs", "h2-cs", "h5-cs").forEach { id ->
            "$id: MS-producible kinds move frame roles and nothing else" {
                // The property that generalises to MS's channel, asserted on the whole corpus
                // rather than on the one case that happens to show R2.
                stripFrameRoles(latticeWith(id, msKindsOnly)) shouldBe stripFrameRoles(latticeWith(id, noKinds))
            }
        }

        "MS: a registry carrying kinds wakes R2 — h1-cs is the case that shows it" {
            val withKinds = latticeWith("h1-cs", msKindsOnly)
            val without = latticeWith("h1-cs", noKinds)

            // The roles DID move, or the corpus assertion above would be vacuous.
            withKinds shouldNotBe without

            // The mention bound to `md.measure.cost` is the one that moves, and it moves by
            // GAINING the measure role — the R2 the estate's declaration finally supplies.
            // `md.dimension.Account` does not: its kind is not one MS produces, so it arrives
            // blank, and a blank kind fires no rule. R2 keys on the kind, never on syntax.
            mentionsOf(withKinds)[1]["frameRoles"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe
                listOf("FRAME_ROLE_SUBJECT", "FRAME_ROLE_MEASURE")
            mentionsOf(without)[1]["frameRoles"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe
                listOf("FRAME_ROLE_SUBJECT")

            // Every OTHER mention keeps the roles it had — the change is one mention wide.
            mentionsOf(withKinds).indices.filter { it != 1 }.forEach { i ->
                mentionsOf(withKinds)[i]["frameRoles"] shouldBe mentionsOf(without)[i]["frameRoles"]
            }
        }

        "⛑ a kind is read in TWO places — an operator kind changes the mention SET, not its roles" {
            // review-083 F2, pinned so the no-op claim above can never quietly widen. `op:show`
            // is the root of h2's parse and governs the rest of the question; with a blank kind
            // it proposes every noun under it as one of its values, and those tokens are then
            // COVERED, so `čerpacích stanic` never becomes a mention of its own. Give it its
            // kind and `SpanProposal` skips that emission — the question gains a mention.
            //
            // MS cannot cause this today: `targets` never describes an `op:` ref, so the
            // snapshot channel cannot deliver `operator`. The per-request override can, and
            // does, which is why this is asserted rather than assumed.
            mentionsOf(latticeWith("h2-cs", everyKind)).size shouldBe 4
            mentionsOf(latticeWith("h2-cs", noKinds)).size shouldBe 3
            // ⚠ `measure` is in the same set as `operator` (SpanProposal.VALUELESS_OBJECT_KINDS),
            // so the exemption becomes reachable from MS's OWN channel the day an estate has a
            // measure anchor governing a nominal child. No corpus case has that shape, which is
            // why this test exists on the operator half instead.
        }

        listOf("h1-cs", "h1prime-cs", "h2-cs", "h5-cs").forEach { id ->
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

                val actual = JsonObject(withoutDurations(lattice) - "parse")
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
     *
     * A row is returned only when the slot ASKED for its category (RV-P1.6.T6). The real matcher
     * is category-scoped per slot — an explicit-but-unknown category contributes nothing, it does
     * not fall back to the global index — and the core now asks two different questions about one
     * span ("which model object is this?" and "is this span a grounding kernel's?"), in two slots
     * with different categories. A fake that ignored `categories` would answer both with both.
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
                val scoped = span.categoriesList.toSet()
                builder.addResults(
                    FuzzyMatchResponse
                        .newBuilder()
                        .addAllMatches(
                            byQuery[span.query].orEmpty().filter { scoped.isEmpty() || it.category in scoped },
                        ).setMatchedAlgorithm("TATRMAN")
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

        /**
         * Strip `elapsedMs` from every rung-log entry (RV-P2.5.T6).
         *
         * A golden asserts the ANNOTATION, and how long a round took is not part of it — the same
         * reason `parse` is excluded above. This was found the honest way: the P2.3 rounds write a
         * real duration, the h1′ golden was promoted on a run where it rounded to 0, and the first
         * slower run put a `2` there and failed. A wall-clock number inside a byte-compared fixture
         * is a flake with a delay fuse, so it comes out here rather than being rounded, zeroed, or
         * left to fail one CI run in ten.
         */
        private fun mentionsOf(lattice: JsonObject): List<JsonObject> =
            lattice["mentions"]!!.jsonArray.map { it.jsonObject }

        /** The same lattice with every `frameRoles` key removed, at any depth. */
        private fun stripFrameRoles(
            element: kotlinx.serialization.json.JsonElement,
        ): kotlinx.serialization.json.JsonElement =
            when (element) {
                is JsonObject ->
                    JsonObject(
                        element.entries
                            .filterNot { it.key == "frameRoles" }
                            .associate { it.key to stripFrameRoles(it.value) },
                    )
                is JsonArray -> JsonArray(element.map { stripFrameRoles(it) })
                else -> element
            }

        private fun withoutDurations(lattice: JsonObject): JsonObject =
            JsonObject(
                lattice.toMutableMap().also { map ->
                    val log = lattice["rungLog"]?.jsonArray ?: return@also
                    map["rungLog"] = JsonArray(log.map { JsonObject(it.jsonObject - "elapsedMs") })
                },
            )

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
