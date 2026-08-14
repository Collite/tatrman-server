// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.registry

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.ttr.lexicon.LexiconArea
import org.tatrman.ttr.lexicon.LexiconDataFile
import org.tatrman.ttr.lexicon.LexiconLoad
import org.tatrman.ttr.lexicon.LexiconArchive
import org.tatrman.ttr.lexicon.LexiconValidator
import org.tatrman.ttr.lexicon.TargetClass
import org.tatrman.ttr.lexicon.compile.LexiconCompiler
import org.tatrman.ttr.lexicon.compile.LexiconPacker
import org.tatrman.ttr.lexicon.compile.LexiconSources
import org.tatrman.ttr.lexicon.compile.ModelRefIndex
import org.tatrman.ttr.snapshot.SnapshotManifest
import org.tatrman.ttr.snapshot.SnapshotReadResult
import org.tatrman.ttr.snapshot.SnapshotReader
import org.tatrman.ttr.snapshot.SnapshotWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeBytes

/**
 * Q-7 (Bora, 2026-08-14) — **the registry is fed from the compiled lexicon archive**, the same
 * file lex-matcher mounts. RS-24's *"one channel, two consumers … off the SAME snapshot
 * identity"*, made real on the second consumer.
 *
 * The fixture is packed by the **real `LexiconPacker`** (test scope only), exactly as
 * `LexiconArchiveSourceTest` does on the lex-matcher side — a change to the producer's layout
 * breaks this test rather than silently diverging from it. That matters more here than usual:
 * the two readers are conformant *by contract* until the RO-13 shared lib exists, so the only
 * thing holding them together is that both are held to the same producer.
 */
class LexiconArchiveRegistrySourceTest :
    StringSpec({

        val modelHash = "sha256:" + "cd".repeat(32)

        /** Compiles + packs a lexicon exactly as the RV-P1.2 build does, and writes it to disk. */
        fun writeArchive(
            dir: Path,
            yaml: String,
            classOf: (String) -> TargetClass?,
            name: String = "lexicon.tar.zst",
            producer: String = "test",
        ): Path {
            val file =
                LexiconValidator
                    .loadDataFile(yaml, "aliases/er.lex.yaml")
                    .shouldBeInstanceOf<LexiconLoad.Ok<LexiconDataFile>>()
                    .value
            val result =
                LexiconCompiler.compile(
                    LexiconSources(area = LexiconArea(listOf(file), emptyList())),
                    ModelRefIndex { ref -> classOf(ref) },
                    modelHash,
                    "2026-08-14T00:00:00Z",
                )
            return dir.resolve(name).also { it.writeBytes(LexiconPacker.pack(result, modelHash, producer).bytes) }
        }

        val objectsOnly = { ref: String ->
            when (ref) {
                "er.entity.catalog_sales", "er.entity.catalog_sales.ext_sales_price" -> TargetClass.MODEL_OBJECT
                else -> null
            }
        }

        val aliases =
            """
            schema: ttr-lexicon/v1
            defaults: { lang: en }
            entries:
              - terms:
                  - { text: "marketplace" }
                  - { text: "marketplace revenue", method: TOKENS }
                target: er.entity.catalog_sales
              - terms: [ { text: "revenues", method: TYPOS(1) } ]
                target: er.entity.catalog_sales.ext_sales_price
            """.trimIndent()

        "⚠ only MODEL_OBJECT rows become anchors — members and grounding triggers do not" {
            // A decision, not a filter, and the test that makes it a decision. `anchors` are the
            // words naming a model OBJECT that Q-20's anchored proposal ties subtrees to.
            //  - a MEMBER is the value layer (RV-2). Every member literal becoming an anchor token
            //    would fragment ordinary noun phrases, because `anchorTokens` blocks a word from
            //    being folded into a sibling's phrase.
            //  - a GROUNDING_TRIGGER already has its own annotation path.
            //  - an OPERATOR is tatrman-server#58's territory: Q-20 cut over-generation 33 → 0, and
            //    widening proposal as a side effect of a plumbing change is how that gets lost.
            val mixed =
                """
                schema: ttr-lexicon/v1
                defaults: { lang: en }
                entries:
                  - terms: [ { text: "marketplace" } ]
                    target: er.entity.catalog_sales
                  - terms: [ { text: "month" } ]
                    target: ground:chrono
                """.trimIndent()
            val dir = Files.createTempDirectory("resolver-lex")
            val source = LexiconArchiveRegistrySource(writeArchive(dir, mixed, objectsOnly))

            runBlocking {
                val vocab = source.fetch()
                withMessage("a grounding trigger must not become an anchor") {
                    vocab.entries.map { it.targetRef } shouldContainExactlyInAnyOrder listOf("er.entity.catalog_sales")
                }
            }
        }

        "an archive projects into declared vocabulary keyed by target ref" {
            val dir = Files.createTempDirectory("resolver-lex")
            val source = LexiconArchiveRegistrySource(writeArchive(dir, aliases, objectsOnly))

            runBlocking {
                val vocab = source.fetch()

                vocab.entries.map { it.category } shouldContainExactlyInAnyOrder
                    listOf("er.entity.catalog_sales", "er.entity.catalog_sales.ext_sales_price")
                // category == targetRef is what `SnapshotRegistry.project` keys on. If these ever
                // diverge the registry silently produces one entity type per (category, ref) pair.
                vocab.entries.forEach { it.category shouldBe it.targetRef }

                vocab.entries
                    .single { it.targetRef == "er.entity.catalog_sales" }
                    .values
                    .map { it.value } shouldContainExactlyInAnyOrder listOf("marketplace", "marketplace revenue")
            }
        }

        "the projection reaches the registry as anchors" {
            val dir = Files.createTempDirectory("resolver-lex")
            val registry =
                SnapshotRegistry(
                    LexiconArchiveRegistrySource(writeArchive(dir, aliases, objectsOnly)),
                    ResolverThresholds.LIVE,
                )

            runBlocking {
                val types = registry.current().entityTypes

                types.map { it.ref } shouldContainExactlyInAnyOrder
                    listOf("er.entity.catalog_sales", "er.entity.catalog_sales.ext_sales_price")
                types
                    .single { it.ref == "er.entity.catalog_sales" }
                    .anchors shouldContainExactlyInAnyOrder listOf("marketplace", "marketplace revenue")
            }
        }

        "⚠ objectKind stays BLANK — the archive states the class, not the kind" {
            // Pinned deliberately, because it is the half of tatrman-server#59 this change does
            // NOT deliver. `CompiledEntry` carries a TargetClass (MODEL_OBJECT | MEMBER |
            // OPERATOR | GROUNDING_TRIGGER); the resolver's `objectKind` wants
            // measure|dimension|attribute|entity|operator, which is a MODEL fact and lives on the
            // other side (veles `meta.v1`'s `kind`/`semantics_kind`). Blank ⇒ FrameRoles R2 still
            // does not fire. A future change that starts deriving a kind from the ref's prefix
            // here would be a second, quietly divergent rule — this test is the tripwire.
            val dir = Files.createTempDirectory("resolver-lex")
            val registry =
                SnapshotRegistry(
                    LexiconArchiveRegistrySource(writeArchive(dir, aliases, objectsOnly)),
                    ResolverThresholds.LIVE,
                )

            runBlocking { registry.current().entityTypes.forEach { it.objectKind shouldBe "" } }
        }

        "the hash is the ARCHIVE id — it moves when the file moves, and a re-read is not a re-parse" {
            val dir = Files.createTempDirectory("resolver-lex")
            val path = writeArchive(dir, aliases, objectsOnly)
            val source = LexiconArchiveRegistrySource(path)

            val first = source.hash()
            first shouldNotBe ""
            source.hash() shouldBe first

            // Repack with an extra term: same targets, different bytes.
            writeArchive(
                dir,
                aliases.replace(
                    """- terms: [ { text: "revenues", method: TYPOS(1) } ]""",
                    """- terms: [ { text: "revenues", method: TYPOS(1) }, { text: "sales", method: TOKENS } ]""",
                ),
                objectsOnly,
            )
            val second = source.hash()
            withMessage("a stale hash means the resolver serves a vocabulary the estate has replaced") {
                second shouldNotBe first
            }
            // ⚑ And the ARCHIVE id, not the lexicon's content hash. A repack with byte-identical
            // *content* still produces a different file, and the registry cache keys on this — so
            // a hash that only tracked content would let a re-delivered archive go unnoticed.
            // Mutation-checked: swapping this for `lexicon.contentHash` passes without this case.
            val samePath = Files.createTempDirectory("resolver-lex-repack")
            val once =
                LexiconArchiveRegistrySource(
                    writeArchive(samePath, aliases, objectsOnly, producer = "build-1"),
                ).hash()
            val twice =
                LexiconArchiveRegistrySource(
                    writeArchive(samePath, aliases, objectsOnly, producer = "build-2"),
                ).hash()
            withMessage("same terms, different archive — the id must move") { twice shouldNotBe once }
            runBlocking {
                source
                    .fetch()
                    .entries
                    .flatMap { it.values }
                    .map { it.value } shouldContainExactlyInAnyOrder
                    listOf("marketplace", "marketplace revenue", "revenues", "sales")
            }
        }

        "an ABSENT archive is empty and stable — not a crash, and not a reload every tick" {
            val source = LexiconArchiveRegistrySource(Path.of("/nonexistent/lexicon.tar.zst"))

            source.hash() shouldBe ""
            source.hash() shouldBe ""
            runBlocking { source.fetch().entries shouldBe emptyList() }
        }

        "unreadable BYTES are empty, not a crash" {
            val dir = Files.createTempDirectory("resolver-lex")
            val bogus = dir.resolve("lexicon.tar.zst")
            Files.write(bogus, byteArrayOf(1, 2, 3, 4))

            runBlocking { LexiconArchiveRegistrySource(bogus).fetch().entries shouldBe emptyList() }
        }

        "a WRONG-KIND archive is empty rather than silently 'the estate declared nothing'" {
            // ⚑ A REAL archive with a REAL lexicon payload, relabelled `kind: models`. Junk bytes
            // would not test this — they fail at the reader, never reaching the kind gate, and a
            // version of this test that used them passed with the gate deleted (mutation-checked).
            // The failure mode this guards is the one the whole issue is about: a model snapshot
            // pointed at the lexicon slot loads as an empty vocabulary and reads as "the estate
            // authored nothing".
            val dir = Files.createTempDirectory("resolver-lex")
            val good = LexiconArchiveRegistrySource(writeArchive(dir, aliases, objectsOnly, name = "good.tar.zst"))
            good.hash() shouldNotBe ""

            val payload =
                SnapshotReader
                    .read(
                        dir.resolve("good.tar.zst").let {
                            java.nio.file.Files
                                .readAllBytes(it)
                        },
                    ).shouldBeInstanceOf<SnapshotReadResult.Ok>()
                    .contents.docs
                    .getValue(LexiconArchive.LEXICON)
            val mislabelled =
                SnapshotWriter.write(
                    SnapshotManifest(kind = "models", producedBy = "test"),
                    mapOf(LexiconArchive.LEXICON to payload),
                )
            val path = dir.resolve("lexicon.tar.zst").also { it.writeBytes(mislabelled) }

            val source = LexiconArchiveRegistrySource(path)
            source.hash() shouldBe ""
            runBlocking { source.fetch().entries shouldBe emptyList() }
        }
    })

private fun withMessage(
    clue: String,
    block: () -> Unit,
) = io.kotest.assertions.withClue(clue, block)
