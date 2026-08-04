// SPDX-License-Identifier: Apache-2.0
package org.tatrman.text

import java.text.Normalizer

/**
 * S-2 — the one normalization spec (contracts §6, invariant #3).
 *
 * `fold(text) = lowercase -> NFD -> strip combining marks`. Every matcher in the
 * understanding layer (lex-matcher, the grounding kernel, meta.search) folds through
 * THIS function, byte-identically — determinism and cross-service parity depend on
 * it. The golden vectors in `NormalizationSpec` are the fixture.
 *
 * Placement decision (RG-P0.S3.T2): the fold lives in a standalone `text`
 * shared lib rather than inside `grounding-core`. Contracts §6 leaves the
 * physical home to P0; `grounding-core` does not exist yet (RG-P3 creates it)
 * and the fold has consumers outside grounding (fuzzy today, meta.search later),
 * so a dependency-free leaf lib is the right home. `grounding-core` will depend
 * on `text`, not the reverse.
 */
object Normalization {
    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val WHITESPACE = Regex("\\s+")

    /** Fold [input] to its canonical match form: lower-case, NFD-decompose, strip combining marks. */
    fun fold(input: String): String =
        Normalizer
            .normalize(input.lowercase(), Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")

    /**
     * RV-P1.4 T4 — the **authored** form: NFC → trim → collapse inner whitespace → lower-case,
     * with **diacritics preserved**. A different question from [fold], and both are needed.
     *
     * [fold] is the *index key*: it deliberately erases diacritics so a user typing `zakaznik`
     * reaches `zákazník`. This is the *stored term*: it is what the author wrote, modulo casing and
     * spacing. `EXACT` dispatch has to compare on THIS one — on the folded form `vyroba` would
     * EXACT-match `výroba`, which is a `TYPOS` decision the author never made.
     *
     * Byte-identical to the toolchain's `org.tatrman.ttr.lexicon.TermNormalizer.normalize`, which
     * produces the term strings inside a compiled lexicon archive. The two live in different repos
     * (this one depends on the toolchain, never the reverse) so they cannot literally be one
     * function; `TermNormalizerParitySpec` in lex-matcher holds them to each other on every build,
     * which turns "two copies that could drift" into "two copies CI proves identical".
     */
    fun canonical(input: String): String =
        Normalizer
            .normalize(input, Normalizer.Form.NFC)
            .trim()
            .replace(WHITESPACE, " ")
            .lowercase()
}
