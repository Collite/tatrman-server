// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.loader

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.tatrman.fuzzy.core.OverlayRequest
import org.tatrman.fuzzy.core.SourceTag
import org.tatrman.fuzzy.core.TargetClass
import org.tatrman.ttr.snapshot.SnapshotManifest
import org.tatrman.ttr.snapshot.SnapshotWriter
import java.nio.file.Files
import kotlin.io.path.writeBytes

/**
 * RV-P7.3 — **the cross-repo pin, read side.**
 *
 * `overlay/overlay-golden.json` is checked into BOTH this repo and `kantheon`, byte for byte.
 * There it is what the Golem's `OverlayExport` must produce; here it is what this reader must make
 * of it. Two repos, one file, no compiler between them — the export and the load are only held
 * together by this, so a field rename on either side that nothing else notices fails here.
 *
 * ⚠ **If this test goes red, do not edit the golden alone.** Copy the new document from kantheon in
 * the same change. A regenerated golden with a stale twin is precisely the silent breakage the file
 * exists to prevent — an estate goes on learning and nothing serves any of it.
 */
class OverlayGoldenTest :
    StringSpec({

        val golden =
            OverlayGoldenTest::class.java
                .getResourceAsStream("/overlay/overlay-golden.json")!!
                .bufferedReader()
                .readText()

        fun source(): OverlayArchiveSource {
            val path = Files.createTempDirectory("overlay-golden").resolve("overlay.ttrsnap")
            path.writeBytes(
                SnapshotWriter.write(
                    SnapshotManifest(kind = OverlayArchive.KIND, producedBy = "golden"),
                    mapOf(OverlayArchive.OVERLAY to golden),
                ),
            )
            return OverlayArchiveSource(path).also { it.hash() }
        }

        "the Golem's exported document parses into exactly the overlay it describes" {
            runBlocking {
                val store = source()

                store.version() shouldBe "7"

                val learned = store.learned()
                withClue("one of each namespace, and the NEGATIVE entry is not among the candidates") {
                    learned.keys shouldContainExactlyInAnyOrder
                        listOf(
                            "md.dimension.Store",
                            "op:trend",
                            "ground:chrono",
                            "er.entity.branch.name#42",
                        )
                }

                // RV-38 — the class the producer STATED, carried through unchanged.
                learned.getValue("md.dimension.Store").single().let {
                    it.value shouldBe "čerpací stanice"
                    it.source shouldBe SourceTag.LEARNED
                    it.targetClass shouldBe TargetClass.MODEL_OBJECT
                }
                learned.getValue("op:trend").single().targetClass shouldBe TargetClass.OPERATOR
                learned.getValue("ground:chrono").single().targetClass shouldBe TargetClass.GROUNDING_TRIGGER
                learned.getValue("er.entity.branch.name#42").single().targetClass shouldBe TargetClass.MEMBER

                withClue("the NEGATIVE entry became a suppression, on the term it was recorded for") {
                    store.consult(OverlayRequest("distribuční centrum")).suppressedTargets shouldBe
                        setOf("md.dimension.DistributionCentre")
                }
            }
        }

        "every field name the document uses is one this build reads" {
            // Catches the failure a shape test cannot: a renamed field that decodes to its DEFAULT
            // instead of failing. `ignoreUnknownKeys` is deliberate (a newer Golem may add fields
            // and must not cost an estate its vocabulary) — which is exactly why the names this
            // build depends on have to be pinned explicitly rather than assumed to have arrived.
            val required =
                listOf(
                    "\"schema\"",
                    "\"estate_id\"",
                    "\"version\"",
                    "\"entries\"",
                    "\"term\"",
                    "\"lang\"",
                    "\"target_ref\"",
                    "\"polarity\"",
                    "\"status\"",
                    "\"target_class\"",
                )
            required.forEach { field ->
                withClue("$field missing from the golden — the producer renamed something") {
                    golden.contains(field) shouldBe true
                }
            }
        }
    })
