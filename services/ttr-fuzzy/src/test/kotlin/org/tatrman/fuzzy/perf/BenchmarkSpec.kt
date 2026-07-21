// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.perf

import io.kotest.core.spec.style.StringSpec

/**
 * T6 — the perf artefact. Excluded from the default `test`; runs only under `-DincludePerf=true`:
 *
 *   ./gradlew :services:ttr-fuzzy:test -DincludePerf=true
 *
 * Builds the product-name-scale corpus ([PerfFixture.BENCH_PRODUCTS] products + customers), warms
 * up, then times each query class (10 measured rounds) and prints a markdown table of p50/p95 wall
 * time plus the mean engine seed-set size (the cost driver — 0 means the whole category is scored).
 * No hard assertions: the numbers are environment-relative; the printed table is the deliverable
 * that gets pasted into `baseline.md` (and re-run after each phase to show the speedup).
 */
class BenchmarkSpec :
    StringSpec({
        val includePerf = System.getProperty("includePerf") == "true"

        "TATRMAN benchmark — p50/p95 per query class".config(enabled = includePerf) {
            val benchProducts = CorpusGenerator.products(PerfFixture.BENCH_PRODUCTS, PerfFixture.CORPUS_SEED)
            val corpus =
                mapOf(
                    PerfFixture.PRODUCTS_CATEGORY to benchProducts,
                    PerfFixture.CUSTOMERS_CATEGORY to
                        CorpusGenerator.customers(PerfFixture.BENCH_CUSTOMERS, PerfFixture.CORPUS_SEED),
                )
            val fixture = PerfFixture.of(corpus)
            try {
                val queries = PerfFixture.benchQueries(benchProducts)
                val byClass = queries.groupBy { it.cls }

                // Warmup — let the JIT settle before measuring.
                repeat(3) { for (q in queries) fixture.matchTatrman(q.query, q.category, limit = 10) }

                val rows = StringBuilder()
                rows.appendLine("| query class | n | p50 ms | p95 ms | mean ms | mean seed (0=full-corpus) |")
                rows.appendLine("|---|---:|---:|---:|---:|---:|")

                for (cls in QueryClass.values()) {
                    val qs = byClass[cls] ?: continue
                    val timesMs = ArrayList<Double>(qs.size * 10)
                    repeat(10) {
                        for (q in qs) {
                            val t0 = System.nanoTime()
                            fixture.matchTatrman(q.query, q.category, limit = 10)
                            timesMs += (System.nanoTime() - t0) / 1_000_000.0
                        }
                    }
                    val meanSeed = qs.map { fixture.seedSize(it.query, it.category) }.average()
                    rows.appendLine(
                        "| %s | %d | %.2f | %.2f | %.2f | %.0f |".format(
                            cls.name,
                            qs.size,
                            percentile(timesMs, 50.0),
                            percentile(timesMs, 95.0),
                            timesMs.average(),
                            meanSeed,
                        ),
                    )
                }

                println(
                    buildString {
                        appendLine()
                        appendLine("### FZ benchmark — TATRMAN path")
                        appendLine(
                            "corpus: ${PerfFixture.BENCH_PRODUCTS} products + " +
                                "${PerfFixture.BENCH_CUSTOMERS} customers; " +
                                "${queries.size} queries; 3 warmup + 10 measured rounds",
                        )
                        append(rows)
                    },
                )
            } finally {
                fixture.close()
            }
        }
    })

/** Linear-interpolated percentile over an unsorted sample. */
private fun percentile(
    samples: List<Double>,
    p: Double,
): Double {
    if (samples.isEmpty()) return 0.0
    val sorted = samples.sorted()
    if (sorted.size == 1) return sorted[0]
    val rank = (p / 100.0) * (sorted.size - 1)
    val lo = rank.toInt()
    val hi = minOf(lo + 1, sorted.size - 1)
    val frac = rank - lo
    return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
}
