// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.conformance

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.fuzzy.config.AppConfig
import org.tatrman.fuzzy.config.LoaderSourceConfig
import org.tatrman.fuzzy.config.MetadataConfig
import org.tatrman.fuzzy.config.NlpConfig
import org.tatrman.fuzzy.config.TokenBasedConfig
import org.tatrman.fuzzy.core.Candidate
import org.tatrman.fuzzy.core.FuzzyMatcher
import org.tatrman.fuzzy.core.LookupQuery
import org.tatrman.fuzzy.core.SourceTag
import org.tatrman.fuzzy.core.StringRepository
import org.tatrman.fuzzy.loader.LexiconArchiveSource
import org.tatrman.fuzzy.loader.LoaderSource
import org.tatrman.fuzzy.loader.OverlayArchive
import org.tatrman.fuzzy.loader.OverlayArchiveSource
import org.tatrman.fuzzy.loader.OverlayDoc
import org.tatrman.fuzzy.loader.OverlayDocEntry
import org.tatrman.ttr.lexicon.LexiconArea
import org.tatrman.ttr.lexicon.LexiconDataFile
import org.tatrman.ttr.lexicon.LexiconLoad
import org.tatrman.ttr.lexicon.LexiconValidator
import org.tatrman.ttr.lexicon.SkillDef
import org.tatrman.ttr.lexicon.TargetClass
import org.tatrman.ttr.lexicon.compile.LexiconCompiler
import org.tatrman.ttr.lexicon.compile.LexiconPacker
import org.tatrman.ttr.lexicon.compile.LexiconSources
import org.tatrman.ttr.lexicon.compile.ModelRefIndex
import org.tatrman.ttr.snapshot.SnapshotManifest
import org.tatrman.ttr.snapshot.SnapshotWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeBytes

/**
 * RV-P7.3 T4 — the hartland_cz corpus with a **LEARNED overlay** on top of its declared layer.
 *
 * ### ✅ GATING since RV-P7.4, on the criterion this file recorded at P7.3
 *
 * It shipped NON-gating, with the promotion criterion written here: *once H2→H3 runs end to end
 * (ask → confirm → entry → the same question binds at LEARNED_ALIAS with zero asks), this corpus
 * stops being a statement about the reader and becomes a statement about the loop.* RV-P7.4's
 * drill met it — `OverlayDrillTest` next door is fed by `drill/overlay-final.json`, which is the
 * exact document kantheon's `H2H3DrillSpec` produced from a real conversation rather than a
 * fixture anyone wrote. So both joined `just conformance` and `corpus-hashes.sha256` together.
 *
 * ⚠ **What is still NOT proven, and is not what this gate is for:** the pod hop — a deployed Golem
 * writing the archive and a deployed lex-matcher reading it. That needs images, a resolver on the
 * estate and the transport medium ruled; see the stage's `implementation/` record.
 *
 * ### What it is
 *
 * The declared half is the **same hash-pinned authored fixture** the gating `LexiconConformanceTest`
 * uses (`aliases.lex.yaml` + `skills/trend.md`), compiled by the same RV-P1.2 toolchain — so the
 * two layers are this estate's real ones and layer precedence is asked on a term the estate really
 * declares. The overlay half is packed by the same `SnapshotWriter` the Golem exports with, so a
 * red case means the serving chain broke rather than that a fixture drifted.
 *
 * Hermetic: no live services, no Golem, no network.
 */
class OverlayConformanceTest :
    StringSpec({

        val root = "/conformance/hartland-cz"
        val modelHash = "sha256:" + "cf".repeat(32)
        val json = Json { ignoreUnknownKeys = true }

        fun resource(path: String): String =
            OverlayConformanceTest::class.java
                .getResourceAsStream(path)
                ?.bufferedReader()
                ?.readText()
                ?: error("missing conformance resource: $path")

        fun lines(file: String) =
            resource("$root/$file")
                .lineSequence()
                .filter { it.isNotBlank() }
                .map { json.parseToJsonElement(it) as JsonObject }
                .toList()

        val declaredLines = lines("cases.jsonl")
        val overlayLines = lines("overlay-cases.jsonl")

        val modelRefs =
            declaredLines
                .first { it.containsKey("_model_refs") }["_model_refs"]!!
                .jsonArray
                .map { it.jsonPrimitive.content }
                .toSet()

        val overlaySpec = overlayLines.first { it.containsKey("_overlay") }
        val overlayVersion = overlaySpec["_overlay_version"]!!.jsonPrimitive.int.toLong()
        val overlayEntries =
            overlaySpec["_overlay"]!!.jsonArray.map { row ->
                val o = row.jsonObject
                OverlayDocEntry(
                    term = o["term"]!!.jsonPrimitive.content,
                    lang = o["lang"]!!.jsonPrimitive.content,
                    targetRef = o["target_ref"]!!.jsonPrimitive.content,
                    polarity = o["polarity"]!!.jsonPrimitive.content,
                    status = o["status"]!!.jsonPrimitive.content,
                    targetClass = o["target_class"]!!.jsonPrimitive.content,
                    distinctUsers = o["distinct_users"]!!.jsonPrimitive.int,
                    conflicted = o["conflicted"]!!.jsonPrimitive.boolean,
                )
            }
        val cases = overlayLines.filter { it.containsKey("query") }

        /** The same member layer the gating corpus runs on — a three-layer estate, as deployed. */
        val members =
            object : LoaderSource {
                override suspend fun loadNextCache(): Map<String, List<Candidate>> =
                    mapOf(
                        "db.dbo.dc.name" to
                            listOf(
                                Candidate.fromValues("dc-brno", "Brno"),
                                Candidate.fromValues("dc-praha", "Praha"),
                                Candidate.fromValues("dc-ostrava", "Ostrava"),
                            ),
                    )
            }

        fun cfg() =
            AppConfig(
                serverPort = 7107,
                grpcPort = 7207,
                grpcReflectionEnabled = false,
                refreshIntervalSeconds = 0,
                tokenBasedConfig = TokenBasedConfig(),
                nlp = NlpConfig(),
                loaderSource = LoaderSourceConfig(source = "static"),
                metadata = MetadataConfig(),
            )

        /**
         * The estate's declared layer, compiled from the SAME authored files the gating corpus
         * pins. The three toolchain calls are repeated rather than shared with
         * `LexiconConformanceTest`: that is one rule invoked twice, not two rules — the thing that
         * can actually drift is the fixture, and the fixture is shared and hash-pinned.
         */
        val compiled by lazy {
            val aliases =
                LexiconValidator
                    .loadDataFile(resource("$root/aliases.lex.yaml"), "aliases.lex.yaml")
                    .shouldBeInstanceOf<LexiconLoad.Ok<LexiconDataFile>>()
                    .value
            val trend =
                LexiconValidator
                    .loadSkillFile(resource("$root/skills/trend.md"), "skills/trend.md")
                    .shouldBeInstanceOf<LexiconLoad.Ok<SkillDef>>()
                    .value
            LexiconCompiler.compile(
                LexiconSources(area = LexiconArea(listOf(aliases), listOf(trend))),
                ModelRefIndex { ref -> if (ref in modelRefs) TargetClass.MODEL_OBJECT else null },
                modelHash,
                "2026-08-03T00:00:00Z",
            )
        }

        fun packLexicon(dir: Path): Path =
            dir.resolve("hartland-cz.tar.zst").also {
                it.writeBytes(LexiconPacker.pack(compiled, modelHash, "conformance").bytes)
            }

        /** Packed exactly as the Golem's exporter does — same writer, same kind, same document. */
        fun packOverlay(dir: Path): Path =
            dir.resolve("overlay.ttrsnap").also {
                it.writeBytes(
                    SnapshotWriter.write(
                        SnapshotManifest(kind = OverlayArchive.KIND, producedBy = "conformance"),
                        mapOf(
                            OverlayArchive.OVERLAY to
                                OverlayArchive.JSON.encodeToString(
                                    OverlayDoc(
                                        estateId = "golem-hartland",
                                        version = overlayVersion,
                                        entries = overlayEntries,
                                    ),
                                ),
                        ),
                    ),
                )
            }

        fun estate(withOverlay: Boolean = true): StringRepository {
            val dir = Files.createTempDirectory("hartland-cz-overlay")
            return StringRepository(
                cfg(),
                members,
                snapshotSource = LexiconArchiveSource(packLexicon(dir)),
                overlayStore =
                    if (withOverlay) {
                        OverlayArchiveSource(
                            packOverlay(dir),
                        )
                    } else {
                        org.tatrman.fuzzy.core.NoopOverlayStore
                    },
            ).also { runBlocking { it.forceRefresh() } }
        }

        // ---- the corpus ------------------------------------------------------------------------

        cases.forEach { case ->
            val query = case["query"]!!.jsonPrimitive.content
            val cls = case["class"]!!.jsonPrimitive.content

            "[$cls] $query" {
                val repo = estate()
                try {
                    runBlocking {
                        val hits = FuzzyMatcher(repo).lookup(LookupQuery(term = query, maxCandidates = 10)).candidates

                        case["expect_not_learned"]?.let { absent ->
                            // The OVERLAY contributes nothing for this ref. Deliberately layer-
                            // precise rather than "the ref is unreachable": the declared layer may
                            // legitimately answer for the same target, and asserting plain absence
                            // would then be a claim about the wrong layer — passing or failing for
                            // reasons that have nothing to do with the entry under test.
                            hits.none {
                                it.targetRef == absent.jsonPrimitive.content && it.source == SourceTag.LEARNED
                            } shouldBe true
                        }

                        case["expect_targets"]?.let { expected ->
                            // Layer precedence: BOTH emerge. lex-matcher never picks a winner
                            // across layers — the resolver's evidence-class gate does. Containment,
                            // not equality: an unscoped lookup is recall-oriented by construction
                            // and legitimately returns everything token-similar.
                            val refs = expected.jsonArray.map { it.jsonPrimitive.content }
                            hits.mapNotNull { it.targetRef }.distinct().containsAll(refs) shouldBe true
                        }

                        case["expect_source_of"]?.let { sources ->
                            sources.jsonObject.forEach { (ref, source) ->
                                hits.first { it.targetRef == ref }.source.name shouldBe
                                    source.jsonPrimitive.content
                            }
                        }

                        case["expect_top_target"]?.let { top ->
                            hits.first().targetRef shouldBe top.jsonPrimitive.content
                        }
                        case["expect_source"]?.let { hits.first().source.name shouldBe it.jsonPrimitive.content }
                        case["expect_class"]?.let { hits.first().targetClass?.name shouldBe it.jsonPrimitive.content }
                        case["expect_auto_bindable"]?.let {
                            hits.first().autoBindable shouldBe it.jsonPrimitive.boolean
                        }
                    }
                } finally {
                    repo.close()
                }
            }
        }

        // ---- the tuple, per run ------------------------------------------------------------------

        "the layer tuple now names all three layers, the overlay included" {
            val repo = estate()
            try {
                val tuple = repo.layerVersions()
                tuple.lexiconArtifactHash shouldBe compiled.lexicon.contentHash
                tuple.memberIndexVersions.keys shouldContainExactlyInAnyOrder listOf("db.dbo.dc.name")
                withClue("the store's own version, traceable to a row in the Golem's rv_overlay_versions") {
                    tuple.overlayVersion shouldBe overlayVersion.toString()
                }
            } finally {
                repo.close()
            }
        }

        "the same estate without an overlay is the pre-P7 service, tuple and all" {
            val with = estate(withOverlay = true)
            val without = estate(withOverlay = false)
            try {
                runBlocking {
                    without.layerVersions().overlayVersion.shouldBeNull()
                    withClue("every learned term simply is not there") {
                        FuzzyMatcher(without)
                            .lookup(LookupQuery("čerpací stanice", maxCandidates = 10))
                            .candidates
                            .none { it.targetRef == "md.dimension.Store" } shouldBe true
                    }
                    withClue("and the declared layer answers identically either way") {
                        val a = FuzzyMatcher(with).lookup(LookupQuery("tržby", maxCandidates = 5)).candidates.first()
                        val b = FuzzyMatcher(without).lookup(LookupQuery("tržby", maxCandidates = 5)).candidates.first()
                        a.targetRef shouldBe b.targetRef
                        a.score shouldBe b.score
                    }
                }
            } finally {
                with.close()
                without.close()
            }
        }
    })
