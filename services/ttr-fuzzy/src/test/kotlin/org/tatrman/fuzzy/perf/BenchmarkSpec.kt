// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.perf

import io.kotest.core.spec.style.StringSpec
import org.tatrman.fuzzy.core.Candidate
import org.tatrman.fuzzy.core.RetrievalMode
import org.tatrman.fuzzy.core.TokenVocabulary
import org.tatrman.fuzzy.core.VocabularyResolver

/**
 * T6 — the perf artefact. Excluded from the default `test`; runs only under `-DincludePerf=true`:
 *
 *   ./gradlew :services:ttr-fuzzy:test -DincludePerf=true
 *
 * Builds the product-name-scale corpus ([PerfFixture.BENCH_PRODUCTS] products + customers), warms
 * up, then times each query class (10 measured rounds) under BOTH retrieval modes (legacy vs
 * index-first) and prints markdown tables of p50/p95 wall time. Plus a resolver-only micro-bench
 * (FZ-P2·A T6). No hard assertions: numbers are environment-relative; the tables are the deliverable
 * pasted into `baseline.md`.
 */
class BenchmarkSpec :
    StringSpec({
        val includePerf = System.getProperty("includePerf") == "true"

        "TATRMAN benchmark — legacy vs index-first".config(enabled = includePerf) {
            val benchProducts = CorpusGenerator.products(PerfFixture.BENCH_PRODUCTS, PerfFixture.CORPUS_SEED)
            val corpus =
                mapOf(
                    PerfFixture.PRODUCTS_CATEGORY to benchProducts,
                    PerfFixture.CUSTOMERS_CATEGORY to
                        CorpusGenerator.customers(PerfFixture.BENCH_CUSTOMERS, PerfFixture.CORPUS_SEED),
                )
            val queries = PerfFixture.benchQueries(benchProducts)
            val byClass = queries.groupBy { it.cls }

            suspend fun benchmark(mode: RetrievalMode): String {
                val fixture = PerfFixture.of(corpus, mode)
                try {
                    repeat(3) { for (q in queries) fixture.matchTatrman(q.query, q.category, limit = 10) }
                    val rows = StringBuilder()
                    rows.appendLine("| query class | n | p50 ms | p95 ms | mean ms |")
                    rows.appendLine("|---|---:|---:|---:|---:|")
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
                        rows.appendLine(
                            "| %s | %d | %.2f | %.2f | %.2f |".format(
                                cls.name,
                                qs.size,
                                percentile(timesMs, 50.0),
                                percentile(timesMs, 95.0),
                                timesMs.average(),
                            ),
                        )
                    }
                    return rows.toString()
                } finally {
                    fixture.close()
                }
            }

            val legacyTable = benchmark(RetrievalMode.LEGACY)
            val indexFirstTable = benchmark(RetrievalMode.INDEX_FIRST)

            // Resolver micro-bench (FZ-P2·A T6): resolve every ALL_TYPO token against the products vocab.
            val resolverTable =
                run {
                    val vocab = TokenVocabulary(benchProducts)
                    val allTypoTokens =
                        (byClass[QueryClass.ALL_TYPO] ?: emptyList())
                            .flatMap { Candidate.tokenize(it.query) }
                    repeat(3) {
                        val r = VocabularyResolver(vocab)
                        allTypoTokens.forEach { r.resolve(it) }
                    }
                    val timesMs = ArrayList<Double>(allTypoTokens.size * 10)
                    repeat(10) {
                        val r = VocabularyResolver(vocab)
                        for (tok in allTypoTokens) {
                            val t0 = System.nanoTime()
                            r.resolve(tok)
                            timesMs += (System.nanoTime() - t0) / 1_000_000.0
                        }
                    }
                    "vocabulary size=${vocab.size}; ${allTypoTokens.size} all-typo tokens resolved; " +
                        "p50=%.3f ms  p95=%.3f ms".format(percentile(timesMs, 50.0), percentile(timesMs, 95.0))
                }

            println(
                buildString {
                    appendLine()
                    appendLine("### FZ benchmark — TATRMAN path (legacy vs index-first)")
                    appendLine(
                        "corpus: ${PerfFixture.BENCH_PRODUCTS} products + ${PerfFixture.BENCH_CUSTOMERS} customers; " +
                            "${queries.size} queries; 3 warmup + 10 measured rounds",
                    )
                    appendLine()
                    appendLine("#### legacy")
                    append(legacyTable)
                    appendLine()
                    appendLine("#### index-first")
                    append(indexFirstTable)
                    appendLine()
                    appendLine("#### resolver micro-bench")
                    appendLine(resolverTable)
                },
            )
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
