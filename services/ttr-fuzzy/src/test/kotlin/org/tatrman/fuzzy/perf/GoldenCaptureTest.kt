// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.perf

import io.kotest.core.spec.style.StringSpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * T4 — regenerates `src/test/resources/perf/parity-goldens.json`. Disabled by default; runs only
 * under `-DregenGoldens=true`, then the JSON is committed. Every later phase is judged against it.
 *
 *   ./gradlew :services:ttr-fuzzy:test --tests '*GoldenCaptureTest*' -DregenGoldens=true
 */
class GoldenCaptureTest :
    StringSpec({
        val regen = System.getProperty("regenGoldens") == "true"

        "capture parity goldens".config(enabled = regen) {
            val fixture = PerfFixture.parity()
            try {
                val entries =
                    PerfFixture.parityQueries().map { q ->
                        val results = fixture.matchTatrman(q.query, q.category, limit = 10)
                        GoldenEntry(
                            queryId = q.id,
                            query = q.query,
                            category = q.category,
                            results = results.map { GoldenResult(it.candidateId, formatGoldenScore(it.score)) },
                        )
                    }
                val json = Json { prettyPrint = true }
                val out = resolveGoldenFile()
                out.parentFile.mkdirs()
                out.writeText(json.encodeToString(entries) + "\n")
                println("Wrote ${entries.size} golden entries to ${out.absolutePath}")
            } finally {
                fixture.close()
            }
        }
    })

/**
 * Resolves the source (not build) resource path so the regenerated JSON is committable. Gradle runs
 * the test with the module dir as the working directory, but we tolerate a repo-root cwd too.
 */
internal fun resolveGoldenFile(): File {
    val moduleDir =
        when {
            File("src/test/resources").isDirectory -> File(".")
            File("services/ttr-fuzzy/src/test/resources").isDirectory -> File("services/ttr-fuzzy")
            else -> File(".")
        }
    return File(moduleDir, "src/test/resources/perf/parity-goldens.json")
}
