// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import org.tatrman.text.Normalization

/**
 * Locale-independent text folding for matching.
 *
 * [fold] lower-cases, NFD-decomposes, and strips combining marks so that
 * diacritic-only differences ("Zákazník" / "zakaznik" / "ZAKAZNIKU") collapse to
 * the same form. Czech-first but harmless for other Latin scripts.
 *
 * This is the single normalization point for both candidate-side token building
 * (`Candidate.tokenize`) and query-side matching, so the token index, the
 * Levenshtein distances, and the standard algorithms all operate on folded text.
 *
 * RG-P0.S3 — the fold logic moved to the shared S-2 lib (`org.tatrman.text`) so
 * every matcher (fuzzy, the grounding kernel, meta.search) folds byte-identically.
 * This object stays as the fuzzy-side call site and delegates; the behaviour is
 * unchanged (see `TextNormalizerCharacterizationSpec`).
 */
object TextNormalizer {
    fun fold(input: String): String = Normalization.fold(input)
}
