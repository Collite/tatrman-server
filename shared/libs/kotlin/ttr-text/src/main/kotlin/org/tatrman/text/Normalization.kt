// SPDX-License-Identifier: Apache-2.0
package org.tatrman.text

import java.text.Normalizer

/**
 * S-2 — the one normalization spec (contracts §6, invariant #3).
 *
 * `fold(text) = lowercase -> NFD -> strip combining marks`. Every matcher in the
 * understanding layer (ttr-fuzzy, the grounding kernel, meta.search) folds through
 * THIS function, byte-identically — determinism and cross-service parity depend on
 * it. The golden vectors in `NormalizationSpec` are the fixture.
 *
 * Placement decision (RG-P0.S3.T2): the fold lives in a standalone `ttr-text`
 * shared lib rather than inside `ttr-grounding-core`. Contracts §6 leaves the
 * physical home to P0; `ttr-grounding-core` does not exist yet (RG-P3 creates it)
 * and the fold has consumers outside grounding (fuzzy today, meta.search later),
 * so a dependency-free leaf lib is the right home. `ttr-grounding-core` will depend
 * on `ttr-text`, not the reverse.
 */
object Normalization {
    private val COMBINING_MARKS = Regex("\\p{Mn}+")

    /** Fold [input] to its canonical match form: lower-case, NFD-decompose, strip combining marks. */
    fun fold(input: String): String =
        Normalizer
            .normalize(input.lowercase(), Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
}
