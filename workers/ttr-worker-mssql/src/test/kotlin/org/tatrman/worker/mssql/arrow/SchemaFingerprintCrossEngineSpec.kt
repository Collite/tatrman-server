package org.tatrman.worker.mssql.arrow

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.ipc.ArrowStreamReader
import org.apache.arrow.vector.types.pojo.Schema
import java.io.File

/**
 * Fork Stage 3.4 T2 — cross-engine schema-fingerprint pin (Mssql / Arrow Java side).
 *
 * Mssql reads the shared reference Arrow IPC fixtures (`shared/testdata/fingerprints/`)
 * and recomputes the canonical fingerprint with its own (Arrow Java) implementation,
 * asserting equality with the pinned `fingerprints.json` — the same digests Polars
 * (pyarrow) and Charon (`Integrity.kt`) produce. Same algorithm, multiple
 * implementations, must agree; reading the same bytes, they all converge.
 */
class SchemaFingerprintCrossEngineSpec :
    StringSpec({

        val fixtureDir = locateFixtureDir()
        val expected = parseFingerprints(File(fixtureDir, "fingerprints.json").readText())

        expected.forEach { (fixture, digest) ->
            "Mssql canonical fingerprint matches the shared pin for $fixture" {
                ArrowIpcSerializer.fingerprintFor(schemaOf(File(fixtureDir, fixture))) shouldBe digest
            }
        }

        "reference.arrow matches Charon's pinned digest (cross-engine anchor)" {
            ArrowIpcSerializer.fingerprintFor(schemaOf(File(fixtureDir, "reference.arrow"))) shouldBe
                "69779ea65b0e127c59dc4f537bc33f62f08835c0098dbf313d61b35955fea7b8"
        }
    })

private fun schemaOf(file: File): Schema =
    RootAllocator(Long.MAX_VALUE).use { allocator ->
        file.inputStream().use { input ->
            ArrowStreamReader(input, allocator).use { reader -> reader.vectorSchemaRoot.schema }
        }
    }

/** Walk up from the module dir to the repo root and find shared/testdata/fingerprints. */
private fun locateFixtureDir(): File {
    var dir: File? = File(System.getProperty("user.dir"))
    while (dir != null) {
        val candidate = File(dir, "shared/testdata/fingerprints")
        if (candidate.isDirectory) return candidate
        dir = dir.parentFile
    }
    error("could not locate shared/testdata/fingerprints from ${System.getProperty("user.dir")}")
}

/** Minimal parse of the flat `{ "<file>.arrow": "<hex>" }` map — no JSON dep needed. */
private fun parseFingerprints(json: String): Map<String, String> =
    Regex("\"([^\"]+\\.arrow)\"\\s*:\\s*\"([0-9a-f]+)\"")
        .findAll(json)
        .associate { it.groupValues[1] to it.groupValues[2] }
