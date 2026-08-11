// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.loader

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.tatrman.fuzzy.core.OverlayRequest
import org.tatrman.fuzzy.core.SourceTag
import org.tatrman.fuzzy.core.TargetClass
import org.tatrman.ttr.snapshot.SnapshotManifest
import org.tatrman.ttr.snapshot.SnapshotWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeBytes

/**
 * RV-P7.3 T3/T4 — the overlay reader, held to the archive the Golem actually writes.
 *
 * The fixtures are packed with the real `SnapshotWriter`, for the same reason
 * `LexiconArchiveSourceTest` packs its own with the real `LexiconPacker`: a reader tested against
 * a hand-rolled copy of a layout is a reader tested against its author's assumptions.
 *
 * The tests that matter most here are the ones about **not** serving: an overlay is estate memory,
 * and both directions of getting it wrong are expensive — serving a retired entry re-teaches a
 * mistake, refusing to load a valid one wipes an estate's vocabulary from its point of view.
 */
private const val ESTATE = "golem-hartland"

private fun entry(
    term: String,
    ref: String,
    polarity: String = "POSITIVE",
    status: String = "ACTIVE",
    targetClass: String = "MODEL_OBJECT",
) = OverlayDocEntry(
    term = term,
    lang = "cs",
    targetRef = ref,
    polarity = polarity,
    status = status,
    targetClass = targetClass,
)

private fun archiveBytes(
    version: Long,
    entries: List<OverlayDocEntry>,
    kind: String = OverlayArchive.KIND,
    schema: String = OverlayArchive.SCHEMA,
): ByteArray =
    SnapshotWriter.write(
        SnapshotManifest(kind = kind, producedBy = "golem 0.9.9"),
        mapOf(
            OverlayArchive.OVERLAY to
                OverlayArchive.JSON.encodeToString(
                    OverlayDoc(schema = schema, estateId = ESTATE, version = version, entries = entries),
                ),
        ),
    )

private fun write(
    dir: Path,
    version: Long,
    entries: List<OverlayDocEntry>,
    kind: String = OverlayArchive.KIND,
    schema: String = OverlayArchive.SCHEMA,
): Path {
    val path = dir.resolve("overlay.ttrsnap")
    path.writeBytes(archiveBytes(version, entries, kind, schema))
    return path
}

class OverlayArchiveSourceTest :
    StringSpec({

        "a POSITIVE ACTIVE entry loads as a LEARNED candidate under its target ref" {
            runBlocking {
                val dir = Files.createTempDirectory("overlay")
                val source = OverlayArchiveSource(write(dir, 3, listOf(entry("tržba", "md.measure.net"))))
                source.hash()

                val loaded = source.learned()
                loaded.keys shouldContainExactly listOf("md.measure.net")
                val candidate = loaded.getValue("md.measure.net").single()
                candidate.value shouldBe "tržba"
                candidate.source shouldBe SourceTag.LEARNED
                candidate.targetClass shouldBe TargetClass.MODEL_OBJECT
                withClue("nobody authored a learned alias — an authored method would gate it out") {
                    candidate.matchMethod.shouldBeNull()
                }
            }
        }

        // T2(e) — the defence against a lagging transport.
        "an INVALIDATED entry is exported but NOT served — status is re-checked, not trusted" {
            runBlocking {
                val dir = Files.createTempDirectory("overlay")
                val source =
                    OverlayArchiveSource(
                        write(
                            dir,
                            4,
                            listOf(
                                entry("tržba", "md.measure.net"),
                                entry("obrat", "md.measure.gone", status = "INVALIDATED"),
                            ),
                        ),
                    )
                source.hash()

                withClue("RV-20 retired it at the snapshot build; a stale reader must not revive it") {
                    source.learned().keys shouldContainExactly listOf("md.measure.net")
                }
            }
        }

        "a PROPOSED entry is not served either — it is recorded, not yet believed" {
            runBlocking {
                val dir = Files.createTempDirectory("overlay")
                val source = OverlayArchiveSource(write(dir, 1, listOf(entry("x", "md.a", status = "PROPOSED"))))
                source.hash()

                source.learned().shouldBeEmpty()
            }
        }

        "a PROMOTION_CANDIDATE still serves — candidacy is about the modeler, not about serving" {
            runBlocking {
                val dir = Files.createTempDirectory("overlay")
                val source =
                    OverlayArchiveSource(write(dir, 2, listOf(entry("x", "md.a", status = "PROMOTION_CANDIDATE"))))
                source.hash()

                source.learned().keys shouldContainExactly listOf("md.a")
            }
        }

        "an ACTIVE NEGATIVE suppresses its ref; a PROPOSED one suppresses nothing" {
            runBlocking {
                val dir = Files.createTempDirectory("overlay")
                val source =
                    OverlayArchiveSource(
                        write(
                            dir,
                            5,
                            listOf(
                                entry("čistý obrat", "md.measure.net", polarity = "NEGATIVE"),
                                entry("čistý obrat", "md.measure.gross", polarity = "NEGATIVE", status = "PROPOSED"),
                            ),
                        ),
                    )
                source.hash()

                val verdict = source.consult(OverlayRequest("čistý obrat"))
                withClue("⚑ RV-P7.2: a negative activates on the SECOND refusal, not the first") {
                    verdict.suppressedTargets shouldContainExactly setOf("md.measure.net")
                }
            }
        }

        "the term is matched canonically, so the query's spelling does not decide" {
            runBlocking {
                val dir = Files.createTempDirectory("overlay")
                val source =
                    OverlayArchiveSource(write(dir, 1, listOf(entry("Čistý Obrat", "md.net", polarity = "NEGATIVE"))))
                source.hash()

                source.consult(OverlayRequest("čistý obrat")).suppressedTargets shouldContainExactly setOf("md.net")
            }
        }

        // ---- the two versions, and which answers which question --------------------------------

        "version() is the STORE's version and hash() is the archive's content id" {
            val dir = Files.createTempDirectory("overlay")
            val source = OverlayArchiveSource(write(dir, 42, listOf(entry("x", "md.a"))))

            withClue("a pure accessor: nothing is loaded until the refresh clock reads the file") {
                source.version().shouldBeNull()
            }
            val id = source.hash()
            source.version() shouldBe "42"
            withClue("the content id is the snapshot's, spelled the way every other one is") {
                id.startsWith("sha256:") shouldBe true
            }
        }

        // T4 — determinism. `SnapshotWriter` is byte-deterministic, which is what makes the content
        // id usable as a clock at all: an unchanged overlay must not look like a new one.
        "the same overlay content produces the same archive id, exported twice" {
            val once = archiveBytes(7, listOf(entry("tržba", "md.net"), entry("obrat", "md.gross")))
            val twice = archiveBytes(7, listOf(entry("tržba", "md.net"), entry("obrat", "md.gross")))

            once.toList() shouldBe twice.toList()
        }

        "a version bump alone changes the id, so the matcher reloads" {
            val before = archiveBytes(7, listOf(entry("tržba", "md.net")))
            val after = archiveBytes(8, listOf(entry("tržba", "md.net")))

            withClue("otherwise an invalidation-only build would never reach a matcher") {
                (before.toList() == after.toList()) shouldBe false
            }
        }

        "a re-read of unchanged bytes does not re-parse — the clock is a comparison" {
            runBlocking {
                val dir = Files.createTempDirectory("overlay")
                val source = OverlayArchiveSource(write(dir, 1, listOf(entry("x", "md.a"))))
                val first = source.hash()
                val loadedOnce = source.learned()

                source.hash() shouldBe first
                withClue("same instances back: the parse is cached on the archive id") {
                    (source.learned() === loadedOnce) shouldBe true
                }
            }
        }

        "a new export swaps the whole overlay atomically — version and content together" {
            runBlocking {
                val dir = Files.createTempDirectory("overlay")
                val path = write(dir, 1, listOf(entry("tržba", "md.net")))
                val source = OverlayArchiveSource(path)
                source.hash()
                source.version() shouldBe "1"

                path.writeBytes(archiveBytes(2, listOf(entry("tržba", "md.net"), entry("obrat", "md.gross"))))
                source.hash()

                withClue("a matcher must never see version 2 with version 1's entries") {
                    source.version() shouldBe "2"
                    source.learned().keys shouldContainExactlyInAnyOrder listOf("md.net", "md.gross")
                }
            }
        }

        // ---- everything that can go wrong, and none of it fatal ---------------------------------

        "no archive at all is a supported deployment, not a failure" {
            runBlocking {
                val source = OverlayArchiveSource(Path.of("/nonexistent/overlay.ttrsnap"))

                source.hash() shouldBe ""
                source.version().shouldBeNull()
                source.learned().shouldBeEmpty()
                source.consult(OverlayRequest("anything")).isEmpty shouldBe true
            }
        }

        "an archive of the wrong KIND is refused, and the loaded overlay survives it" {
            runBlocking {
                val dir = Files.createTempDirectory("overlay")
                val path = write(dir, 1, listOf(entry("tržba", "md.net")))
                val source = OverlayArchiveSource(path)
                source.hash()

                // Someone points the overlay slot at a model snapshot.
                path.writeBytes(archiveBytes(9, listOf(entry("wrong", "md.wrong")), kind = "models"))
                source.hash()

                withClue("keeping the last good overlay beats serving a model snapshot as vocabulary") {
                    source.version() shouldBe "1"
                    source.learned().keys shouldContainExactly listOf("md.net")
                }
            }
        }

        "an archive from a FUTURE schema is refused rather than half-understood" {
            runBlocking {
                val dir = Files.createTempDirectory("overlay")
                val source =
                    OverlayArchiveSource(
                        write(dir, 1, listOf(entry("tržba", "md.net")), schema = "rv-overlay/v2"),
                    )
                source.hash()

                withClue("reading v2 fields with v1 meanings is worse than serving no overlay") {
                    source.version().shouldBeNull()
                    source.learned().shouldBeEmpty()
                }
            }
        }

        "a corrupt archive keeps whatever was loaded and never throws" {
            runBlocking {
                val dir = Files.createTempDirectory("overlay")
                val path = write(dir, 3, listOf(entry("tržba", "md.net")))
                val source = OverlayArchiveSource(path)
                source.hash()

                path.writeBytes("not an archive".toByteArray())
                source.hash()

                source.version() shouldBe "3"
                source.learned().keys shouldContainExactly listOf("md.net")
            }
        }

        "an entry with a target class this build does not know is dropped, not defaulted" {
            runBlocking {
                val dir = Files.createTempDirectory("overlay")
                val source =
                    OverlayArchiveSource(
                        write(
                            dir,
                            1,
                            listOf(
                                entry("tržba", "md.net"),
                                entry("něco", "md.future", targetClass = "TIME_AXIS"),
                            ),
                        ),
                    )
                source.hash()

                withClue("defaulting would file a learned alias under a class it may not belong to") {
                    source.learned().keys shouldContainExactly listOf("md.net")
                }
            }
        }

        "op: and ground: targets load like any other alias (RV-35/42)" {
            runBlocking {
                val dir = Files.createTempDirectory("overlay")
                val source =
                    OverlayArchiveSource(
                        write(
                            dir,
                            1,
                            listOf(
                                entry("vývoj", "op:trend", targetClass = "OPERATOR"),
                                entry("loni", "ground:chrono", targetClass = "GROUNDING_TRIGGER"),
                            ),
                        ),
                    )
                source.hash()

                source
                    .learned()
                    .getValue("op:trend")
                    .single()
                    .targetClass shouldBe TargetClass.OPERATOR
                source
                    .learned()
                    .getValue("ground:chrono")
                    .single()
                    .targetClass shouldBe
                    TargetClass.GROUNDING_TRIGGER
            }
        }
    })
