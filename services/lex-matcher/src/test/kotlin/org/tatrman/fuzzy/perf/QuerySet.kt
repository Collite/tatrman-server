// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.perf

import org.tatrman.fuzzy.core.Candidate
import kotlin.random.Random

/** The eight query shapes the harness benchmarks and pins goldens for. */
enum class QueryClass {
    EXACT_SUBSET,
    ONE_TYPO,
    ALL_TYPO,
    REORDERED,
    CROSS_CATEGORY,
    UNKNOWN_CATEGORY,
    SHORT,
    LONG,
}

/**
 * One benchmarked/pinned query. [category] is the target passed to the matcher
 * (`null` = deliberate cross-category span; an unknown string = leak-guard case).
 */
data class PerfQuery(
    val id: String,
    val query: String,
    val category: String?,
    val cls: QueryClass,
)

/**
 * Deterministic query set derived FROM a product corpus (seeded). Every phase is judged against
 * the goldens captured over this set, so it must be reproducible to the byte: the only entropy is
 * `Random(seed)`, and token selection breaks ties by (df, lexicographic) so it never depends on
 * hash-map iteration order.
 *
 * Per-class construction (mirrors the FZ-P0 task spec):
 *  - EXACT_SUBSET   — 2 of a candidate's rarest tokens, in the candidate's own order.
 *  - ONE_TYPO       — same, with one char of one token mutated.
 *  - ALL_TYPO       — same, with one char of EVERY token mutated (drives the empty-seed worst case).
 *  - REORDERED      — the 2 rare tokens reversed.
 *  - CROSS_CATEGORY — EXACT_SUBSET shape, `category = null` (spans all categories).
 *  - UNKNOWN_CATEGORY — EXACT_SUBSET shape, `category = "db.nope.missing"` (leak-guard: no index).
 *  - SHORT          — the single rarest token.
 *  - LONG           — 5 tokens from a candidate that has ≥5.
 */
object QuerySet {
    const val UNKNOWN_CATEGORY_KEY = "db.nope.missing"

    private const val PER_CLASS = 25

    /**
     * Builds the query set. [productCategory] is the category key the fixture registers products
     * under (attached to the category-bearing classes). Uses [seed] for all random choices.
     */
    fun build(
        products: List<Candidate>,
        productCategory: String,
        seed: Long,
    ): List<PerfQuery> {
        require(products.isNotEmpty()) { "QuerySet needs a non-empty product corpus" }
        val df = documentFrequencies(products)
        val rnd = Random(seed)
        val out = ArrayList<PerfQuery>(PER_CLASS * QueryClass.values().size)

        repeat(PER_CLASS) { i ->
            // EXACT_SUBSET / ONE_TYPO / REORDERED / SHORT share a rare-token pick per iteration draw.
            val exactTokens = rareTokensInOrder(pickWithTokens(products, 2, rnd), df, 2)
            out +=
                PerfQuery(
                    "EXACT_SUBSET-%02d".format(i),
                    exactTokens.joinToString(" "),
                    productCategory,
                    QueryClass.EXACT_SUBSET,
                )

            val oneTypoBase = rareTokensInOrder(pickWithTokens(products, 2, rnd), df, 2)
            out +=
                PerfQuery(
                    "ONE_TYPO-%02d".format(i),
                    mutateOne(oneTypoBase, rnd).joinToString(" "),
                    productCategory,
                    QueryClass.ONE_TYPO,
                )

            val allTypoBase = rareTokensInOrder(pickWithTokens(products, 2, rnd), df, 2)
            out +=
                PerfQuery(
                    "ALL_TYPO-%02d".format(i),
                    allTypoBase
                        .map {
                            mutateToken(it, rnd)
                        }.joinToString(" "),
                    productCategory,
                    QueryClass.ALL_TYPO,
                )

            val reorderBase = rareTokensInOrder(pickWithTokens(products, 2, rnd), df, 2)
            out +=
                PerfQuery(
                    "REORDERED-%02d".format(i),
                    reorderBase.reversed().joinToString(" "),
                    productCategory,
                    QueryClass.REORDERED,
                )

            val crossTokens = rareTokensInOrder(pickWithTokens(products, 2, rnd), df, 2)
            out +=
                PerfQuery(
                    "CROSS_CATEGORY-%02d".format(i),
                    crossTokens.joinToString(" "),
                    null,
                    QueryClass.CROSS_CATEGORY,
                )

            val unknownTokens = rareTokensInOrder(pickWithTokens(products, 2, rnd), df, 2)
            out +=
                PerfQuery(
                    "UNKNOWN_CATEGORY-%02d".format(i),
                    unknownTokens.joinToString(" "),
                    UNKNOWN_CATEGORY_KEY,
                    QueryClass.UNKNOWN_CATEGORY,
                )

            val shortToken = rareTokensInOrder(pickWithTokens(products, 1, rnd), df, 1)
            out += PerfQuery("SHORT-%02d".format(i), shortToken.joinToString(" "), productCategory, QueryClass.SHORT)

            val longTokens = pickWithTokens(products, 5, rnd).tokens.take(5)
            out += PerfQuery("LONG-%02d".format(i), longTokens.joinToString(" "), productCategory, QueryClass.LONG)
        }
        return out
    }

    /** Distinct-token document frequency over the product corpus (folded surface tokens). */
    private fun documentFrequencies(products: List<Candidate>): Map<String, Int> {
        val df = HashMap<String, Int>()
        for (c in products) {
            for (t in c.tokenSet) df[t] = (df[t] ?: 0) + 1
        }
        return df
    }

    /**
     * Picks a random candidate that has at least [minTokens] distinct tokens. The corpus always
     * contains such candidates (products carry ≥3 tokens; ≥5-token products exist because the
     * `bez kofeinu` form and non-empty packs push arity up), so the bounded scan always succeeds.
     */
    private fun pickWithTokens(
        products: List<Candidate>,
        minTokens: Int,
        rnd: Random,
    ): Candidate {
        repeat(64) {
            val c = products[rnd.nextInt(products.size)]
            if (c.tokens.size >= minTokens) return c
        }
        // Deterministic fallback: first candidate meeting the bar (keeps the set reproducible).
        return products.firstOrNull { it.tokens.size >= minTokens } ?: products.first()
    }

    /**
     * The [k] rarest tokens of [candidate], returned in the candidate's own token order.
     * Rarity = ascending document frequency; ties broken lexicographically so selection never
     * depends on set iteration order.
     */
    private fun rareTokensInOrder(
        candidate: Candidate,
        df: Map<String, Int>,
        k: Int,
    ): List<String> {
        val distinct = candidate.tokens.distinct()
        val rarest =
            distinct
                .sortedWith(compareBy({ df[it] ?: 0 }, { it }))
                .take(k)
                .toSet()
        return candidate.tokens.distinct().filter { it in rarest }
    }

    /** Mutates a random single token of [tokens] (one char change). */
    private fun mutateOne(
        tokens: List<String>,
        rnd: Random,
    ): List<String> {
        if (tokens.isEmpty()) return tokens
        val idx = rnd.nextInt(tokens.size)
        return tokens.mapIndexed { i, t -> if (i == idx) mutateToken(t, rnd) else t }
    }

    /**
     * One deterministic single-character edit of [token] (substitute / insert / delete), always
     * yielding a non-empty result. Substitution guarantees a *different* character so the token
     * genuinely changes.
     */
    private fun mutateToken(
        token: String,
        rnd: Random,
    ): String {
        if (token.isEmpty()) return token
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        // op: 0 substitute, 1 insert, 2 delete (delete only if length > 1)
        val op = if (token.length > 1) rnd.nextInt(3) else rnd.nextInt(2)
        return when (op) {
            0 -> {
                val pos = rnd.nextInt(token.length)
                var ch = alphabet[rnd.nextInt(alphabet.length)]
                if (ch == token[pos]) ch = alphabet[(alphabet.indexOf(ch) + 1) % alphabet.length]
                token.substring(0, pos) + ch + token.substring(pos + 1)
            }
            1 -> {
                val pos = rnd.nextInt(token.length + 1)
                val ch = alphabet[rnd.nextInt(alphabet.length)]
                token.substring(0, pos) + ch + token.substring(pos)
            }
            else -> {
                val pos = rnd.nextInt(token.length)
                token.substring(0, pos) + token.substring(pos + 1)
            }
        }
    }
}
