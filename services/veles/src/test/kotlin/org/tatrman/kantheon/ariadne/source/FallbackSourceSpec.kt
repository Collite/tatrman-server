package org.tatrman.kantheon.ariadne.source

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import kotlin.io.path.writeText

/**
 * FallbackSource serves the primary when it loads a usable model, else the fallback — never both.
 * Mirrors the GitHub-primary / bundled-resources-fallback wiring without touching the network.
 */
class FallbackSourceSpec :
    StringSpec({

        // A real, usable source: a temp dir with one valid `.ttr` table, read via FileBasedSource.
        fun usableSource(id: String): ModelSource {
            val dir = Files.createTempDirectory("fb-src-")
            dir.toFile().deleteOnExit()
            dir.resolve("db.ttr").writeText(
                """
                model db schema dbo

                def table customers {
                    primaryKey: ["id"]
                    columns: [
                        def column id { type: int, isKey: true }
                    ]
                }
                """.trimIndent(),
            )
            return FileBasedSource(sourceId = id, priority = 100, storage = LocalFsStorage(id = id, rootPath = dir))
        }

        "uses the primary when it loads a usable model" {
            val snapshot = FallbackSource("github-model", usableSource("primary"), usableSource("bundled")).load()

            snapshot.sourceId shouldBe "primary"
            snapshot.tables.size shouldBe 1
            // Primary succeeded — no degraded-mode warning injected.
            snapshot.warnings.filter { it.file == "<fallback>" }.shouldBeEmpty()
        }

        "falls back when the primary throws (clone / auth / network failure)" {
            val exploding = ModelSource { throw RuntimeException("clone failed: 403") }
            val snapshot = FallbackSource("github-model", exploding, usableSource("bundled")).load()

            snapshot.sourceId shouldBe "bundled"
            snapshot.tables.size shouldBe 1
            val degraded = snapshot.warnings.filter { it.file == "<fallback>" }
            degraded shouldHaveSize 1
            degraded.first().message shouldContain "github-model"
            degraded.first().message shouldContain "bundled"
        }

        "falls back when the primary loads an empty (unusable) model" {
            val empty = ModelSource { SourceSnapshot(sourceId = "primary", priority = 100, version = "v-empty") }
            val snapshot = FallbackSource("github-model", empty, usableSource("bundled")).load()

            snapshot.sourceId shouldBe "bundled"
            snapshot.warnings.filter { it.file == "<fallback>" } shouldHaveSize 1
        }
    })
