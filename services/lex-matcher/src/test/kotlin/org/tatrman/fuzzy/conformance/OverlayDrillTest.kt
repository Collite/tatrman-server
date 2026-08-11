// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.conformance

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.tatrman.fuzzy.config.AppConfig
import org.tatrman.fuzzy.config.LoaderSourceConfig
import org.tatrman.fuzzy.config.MetadataConfig
import org.tatrman.fuzzy.config.NlpConfig
import org.tatrman.fuzzy.config.TokenBasedConfig
import org.tatrman.fuzzy.core.AlgorithmType
import org.tatrman.fuzzy.core.Candidate
import org.tatrman.fuzzy.core.FuzzyMatcher
import org.tatrman.fuzzy.core.LookupQuery
import org.tatrman.fuzzy.core.SourceTag
import org.tatrman.fuzzy.core.StringRepository
import org.tatrman.fuzzy.core.TargetClass
import org.tatrman.fuzzy.loader.LoaderSource
import org.tatrman.fuzzy.loader.OverlayArchive
import org.tatrman.fuzzy.loader.OverlayArchiveSource
import org.tatrman.ttr.snapshot.SnapshotManifest
import org.tatrman.ttr.snapshot.SnapshotWriter
import java.nio.file.Files
import kotlin.io.path.writeBytes

/**
 * RV-P7.4 T5 — **the H2→H3 drill, tatrman-server's half.**
 *
 * `drill/overlay-final.json` is not a fixture somebody wrote. It is the **exact document
 * kantheon's `H2H3DrillSpec` produced** by running a real H2 conversation: the ladder exhausted,
 * the Golem asked once, user A pinned "prodejny", user B confirmed the same meaning, and two more
 * users refused a later menu outright. Those four turns are what the three rows below are made of.
 *
 * The drill has to be two halves because the estate learns in one repo and serves in another, and
 * no build crosses between them. So the halves hand over the thing the transport actually
 * carries — this document — and each proves its own end with real components. Together they are
 * the `plan.md` P7 gate:
 *
 *  1. *ask → confirm → overlay entry* — kantheon's half.
 *  2. **the SAME question binds at LEARNED with ZERO asks** — here.
 *  3. *a promotion candidate after a second distinct user* — kantheon's half (the `PROMOTION_CANDIDATE`
 *     status on the row below is that candidate, seen from this side).
 *  4. **negative-entry suppression** — here.
 *
 * ⚠ What this does **not** prove is the pod hop: a deployed Golem writing the file and a deployed
 * lex-matcher reading it. See the stage's `implementation/` record for what that needs.
 */
private const val TERM = "čerpacích stanic"

private const val LEARNED = "er.entity.store_sales"

private const val REFUSED_A = "er.entity.web_sales"

private const val REFUSED_B = "er.entity.catalog_sales"

class OverlayDrillTest :
    StringSpec({

        val document =
            OverlayDrillTest::class.java
                .getResourceAsStream("/drill/overlay-final.json")!!
                .bufferedReader()
                .readText()

        /**
         * The estate as it stands the moment before H3.
         *
         * **Nothing DECLARED for this term** — that is the whole premise: had the estate authored
         * `čerpacích stanic`, there would have been no ask, no feedback event and nothing to learn.
         *
         * But the estate does carry a **harvested METADATA label** that reads alike, on one of the
         * two entities users went on to refuse. That is not a contrivance — it is the ordinary
         * shape of the problem (a model label collides with a business phrase, so the term is
         * ambiguous and the Golem has to ask), and it is what makes the drill's step (4) provable
         * from here: without a rival on the table, a suppression has nothing to suppress.
         */
        val members =
            object : LoaderSource {
                override suspend fun loadNextCache(): Map<String, List<Candidate>> =
                    mapOf(
                        "db.dbo.dc.name" to
                            listOf(
                                Candidate.fromValues("dc-praha", "Praha"),
                                Candidate.fromValues("dc-brno", "Brno"),
                            ),
                        REFUSED_A to
                            listOf(
                                Candidate.vocabulary(
                                    "meta:web:cs:čerpací stanice",
                                    "čerpací stanice",
                                    REFUSED_A,
                                    SourceTag.METADATA,
                                    "TOKENS",
                                    TargetClass.MODEL_OBJECT,
                                ),
                            ),
                    )
            }

        fun cfg() =
            AppConfig(
                serverPort = 7108,
                grpcPort = 7208,
                grpcReflectionEnabled = false,
                refreshIntervalSeconds = 0,
                tokenBasedConfig = TokenBasedConfig(),
                nlp = NlpConfig(),
                loaderSource = LoaderSourceConfig(source = "static"),
                metadata = MetadataConfig(),
            )

        /** The estate, with or without the overlay the drill's first half produced. */
        fun estate(withOverlay: Boolean): StringRepository {
            val overlay =
                if (!withOverlay) {
                    org.tatrman.fuzzy.core.NoopOverlayStore
                } else {
                    val path = Files.createTempDirectory("rv-p7-drill").resolve("overlay.ttrsnap")
                    path.writeBytes(
                        SnapshotWriter.write(
                            SnapshotManifest(kind = OverlayArchive.KIND, producedBy = "rv-p7-drill"),
                            mapOf(OverlayArchive.OVERLAY to document),
                        ),
                    )
                    OverlayArchiveSource(path)
                }
            return StringRepository(cfg(), members, overlayStore = overlay).also {
                runBlocking { it.forceRefresh() }
            }
        }

        // ---- the premise: before the drill, this estate knew nothing ---------------------------

        "BEFORE — the estate offers only the metadata collision, and it is auto-bindable" {
            runBlocking {
                val hits = FuzzyMatcher(estate(withOverlay = false)).lookup(LookupQuery(TERM)).candidates

                withClue("nothing the users meant — the H2 ask was not a failure, it was the design") {
                    hits.none { it.targetRef == LEARNED } shouldBe true
                }
                withClue("and the collision is unopposed, so nothing marks it as the wrong answer") {
                    hits.single { it.targetRef == REFUSED_A }.autoBindable shouldBe true
                }
            }
        }

        // ---- H3: the same question, and no ask -------------------------------------------------

        "H3 — the SAME term now binds at LEARNED, with ZERO asks needed" {
            runBlocking {
                val repo = estate(withOverlay = true)
                val hits = FuzzyMatcher(repo).lookup(LookupQuery(TERM)).candidates

                val bound = hits.single { it.source == SourceTag.LEARNED && it.targetRef == LEARNED }
                withClue("the estate answers from what its own users taught it") {
                    bound.candidate shouldBe TERM
                    bound.provenance.producer shouldBe "fuzzy"
                }
                withClue("ZERO asks: one unsuppressed candidate for this term, so nothing is ambiguous") {
                    hits.count { it.source == SourceTag.LEARNED && it.autoBindable != false } shouldBe 1
                    bound.autoBindable shouldBe null
                }
            }
        }

        "H3 — the RV-39 tuple names the overlay the answer came from" {
            val repo = estate(withOverlay = true)

            withClue("traceable to a row in the Golem's rv_overlay_versions — v4, the drill's last mutation") {
                repo.layerVersions().overlayVersion shouldBe "4"
            }
        }

        "H3 — a user typing without diacritics gets the same answer" {
            runBlocking {
                // The property the widening bought. A consulted overlay keyed on the exact term
                // would have missed this, and the estate would have asked all over again.
                val hits = FuzzyMatcher(estate(withOverlay = true)).lookup(LookupQuery("cerpacich stanic")).candidates

                hits.any { it.source == SourceTag.LEARNED && it.targetRef == LEARNED } shouldBe true
            }
        }

        // ---- the negatives, proven live ---------------------------------------------------------

        // Step (4) of the gate, and the only place it can be proven: two users refused this
        // target in kantheon, and here is the effect, on a real candidate, in a real answer.
        "AFTER — the refused metadata rival comes back SUPPRESSED, offered but never auto-bound" {
            runBlocking {
                val repo = estate(withOverlay = true)
                val hits = FuzzyMatcher(repo).lookup(LookupQuery(TERM, maxCandidates = 20)).candidates

                val refused = hits.single { it.targetRef == REFUSED_A }
                withClue("two independent refusals in another repo, arriving as one flag on this row") {
                    refused.autoBindable shouldBe false
                }
                withClue("flagged, NOT deleted — a wrong negative must stay visible and recoverable (RV-2)") {
                    (refused.score > 0.0) shouldBe true
                    refused.source shouldBe SourceTag.METADATA
                }
                withClue("and the target the users actually meant is untouched") {
                    hits.single { it.targetRef == LEARNED }.autoBindable shouldBe null
                }
                withClue("both refusals travelled, including the one with no candidate to land on") {
                    repo
                        .overlay()
                        .consult(
                            org.tatrman.fuzzy.core
                                .OverlayRequest(TERM),
                        ).suppressedTargets shouldContainExactlyInAnyOrder setOf(REFUSED_A, REFUSED_B)
                }
            }
        }

        "the PROMOTION_CANDIDATE serves — candidacy is about the modeler, not about serving" {
            runBlocking {
                // The row is `PROMOTION_CANDIDATE` because two distinct users confirmed it. That
                // status is the queue's business; from here it is simply a servable learned alias,
                // and an estate whose vocabulary stopped working while a modeler thought about it
                // would be a bad trade.
                FuzzyMatcher(estate(withOverlay = true))
                    .match(TERM, null, AlgorithmType.TATRMAN, 10)
                    .any { it.source == SourceTag.LEARNED } shouldBe true
            }
        }
    })
