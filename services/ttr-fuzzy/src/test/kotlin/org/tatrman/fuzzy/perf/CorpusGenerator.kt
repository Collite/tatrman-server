// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.perf

import org.tatrman.fuzzy.core.Candidate
import kotlin.random.Random

/**
 * Deterministic, seeded ERP-shaped corpus generator for the FZ perf/parity harness.
 *
 * Ports the shape from the 2026-07-21 performance review's `bench_fuzzy.py`:
 *  - products = `"{brand} {line} {form} {size} {pack}"` drawn from fixed word lists,
 *    with blank parts dropped (forms/packs include an empty entry so name arity varies);
 *  - customers = `"{surname} {street} {city}"` from Czech-flavoured lists *with diacritics*
 *    (Novák, Dvořák, Průmyslová, Plzeň …) so [org.tatrman.fuzzy.core.TextNormalizer] folding
 *    is exercised by the golden corpus.
 *
 * Determinism is the whole point (a flaky golden poisons the effort): the only entropy source
 * is `kotlin.random.Random(seed)` — no wall-clock, no default `Random`. The same seed yields a
 * byte-identical list. Ids are `P000001…` / `C000001…` (1-based, matching the review's `P%06d`
 * shape but 1-indexed so id ordinals are human-friendly).
 */
object CorpusGenerator {
    // Product word lists — ported verbatim from bench_fuzzy.py (heavy token reuse: brands, sizes,
    // packs recur across thousands of rows, which is exactly what makes exact-token seeding blow up).
    private val brands =
        listOf(
            "nescafe",
            "jacobs",
            "tchibo",
            "lavazza",
            "segafredo",
            "douwe",
            "davidoff",
            "illy",
            "kenco",
            "maxwell",
            "folgers",
            "dallmayr",
            "melitta",
            "eduscho",
            "bravos",
            "marila",
            "jihlavanka",
            "standard",
            "velta",
            "fortuna",
        )
    private val lines =
        listOf(
            "classic",
            "gold",
            "crema",
            "espresso",
            "intense",
            "mild",
            "strong",
            "decaf",
            "original",
            "premium",
            "selection",
            "tradition",
            "barista",
            "velvet",
            "noir",
            "aroma",
            "balance",
            "harmony",
            "delicate",
            "royal",
        )

    // forms & packs carry an empty entry (dropped when joining) so product name arity ranges 3–5 tokens.
    private val forms =
        listOf("instant", "mleta", "zrnkova", "kapsle", "pods", "sticks", "3v1", "2v1", "bez kofeinu", "")
    private val sizes =
        listOf("50g", "100g", "200g", "250g", "500g", "1kg", "75g", "150g", "10x2g", "20x2g")
    private val packs =
        listOf("24x50", "12x100", "6x500", "10x200", "48x50", "24x100", "36x75", "8x250", "")

    // Customer word lists — Czech-flavoured, WITH diacritics, so NFD-folding is exercised.
    private val surnames =
        listOf(
            "Novák",
            "Svoboda",
            "Novotný",
            "Dvořák",
            "Černý",
            "Procházka",
            "Kučera",
            "Veselý",
            "Horák",
            "Němec",
            "Pokorný",
            "Marek",
            "Král",
            "Růžička",
            "Beneš",
            "Fiala",
            "Sedláček",
            "Doležal",
            "Zeman",
            "Kolář",
        )
    private val streets =
        listOf(
            "Průmyslová",
            "Nádražní",
            "Hlavní",
            "Zahradní",
            "Školní",
            "Polní",
            "Krátká",
            "Dlouhá",
            "Lipová",
            "Květná",
            "Nová",
            "Sadová",
            "Riegrova",
            "Palackého",
            "Komenského",
            "Husova",
        )
    private val cities =
        listOf(
            "Praha",
            "Brno",
            "Ostrava",
            "Plzeň",
            "Liberec",
            "Olomouc",
            "České Budějovice",
            "Hradec Králové",
            "Ústí nad Labem",
            "Pardubice",
            "Zlín",
            "Havířov",
            "Kladno",
            "Most",
            "Opava",
            "Frýdek-Místek",
        )

    /**
     * [n] product candidates. Mirrors `bench_fuzzy.py:make_corpus`: draw one word from each of
     * brand/line/form/size/pack, drop blanks, join with a single space. Ids `P000001…`.
     */
    fun products(
        n: Int,
        seed: Long,
    ): List<Candidate> {
        val rnd = Random(seed)
        return List(n) { i ->
            val parts =
                listOf(
                    brands.random(rnd),
                    lines.random(rnd),
                    forms.random(rnd),
                    sizes.random(rnd),
                    packs.random(rnd),
                ).filter { it.isNotEmpty() }
            Candidate.fromValues("P%06d".format(i + 1), parts.joinToString(" "))
        }
    }

    /**
     * [n] customer candidates: `"{surname} {street} {city}"` (all three always present).
     * Ids `C000001…`. The diacritics guarantee the folded token index and the parity goldens
     * exercise the NFD-strip path, not just ASCII.
     */
    fun customers(
        n: Int,
        seed: Long,
    ): List<Candidate> {
        val rnd = Random(seed)
        return List(n) { i ->
            val value =
                listOf(
                    surnames.random(rnd),
                    streets.random(rnd),
                    cities.random(rnd),
                ).joinToString(" ")
            Candidate.fromValues("C%06d".format(i + 1), value)
        }
    }
}
