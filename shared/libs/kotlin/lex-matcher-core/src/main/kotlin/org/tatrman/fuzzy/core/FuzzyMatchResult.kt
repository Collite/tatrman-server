// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

/**
 * How a candidate's category was sourced (contracts §2, RS-15).
 *
 * **Widened at RV-P1.4 (additive, J-v2).** RV's contracts §1 names four layers —
 * `DECLARED | METADATA | DATA | LEARNED` — where this enum had two. The compiled lexicon artifact
 * carries the RV value per row, and collapsing it into the old pair would throw away the
 * declared-vs-metadata distinction the artifact was built to keep: an author's file states an
 * intent, a model label is a byproduct, and the evidence-class gate downstream ranks them
 * differently.
 *
 * The mapping onto what was already here:
 *  - [MEMBER] **is** RV's `DATA` — member vocabulary read out of the data, `id` is a data PK.
 *    Not renamed: it is wire value 0 and the name is used throughout the loaders.
 *  - [VOCABULARY] is the pre-RV conflation of DECLARED and METADATA (it covered both lexicon
 *    terms and `valueLabels`). Kept for the fixture-stub source, which cannot tell them apart.
 *    Anything reading the compiled artifact emits [DECLARED] or [METADATA] instead.
 *  - [LEARNED] is the RV-P6 overlay. Nothing produces it yet.
 *
 * All of them flow through the same cascade with the same scoring — the layer is evidence, not a
 * filter, and lex-matcher never picks a winner across layers (that is the resolver's gate).
 */
enum class SourceTag {
    /** Data values — RV's `DATA`. The candidate `id` is a data PK (→ `resolved_id`). */
    MEMBER,

    /** Legacy: declared lexicon / `valueLabels`, undifferentiated. Superseded by the two below. */
    VOCABULARY,

    /** Authored in the `lexicon/` area or as TTR-M `def term` sugar. Carries a `targetRef`. */
    DECLARED,

    /** Harvested from model labels (`displayLabel`, `labelPlural`, `aliases`, `valueLabels`). */
    METADATA,

    /** The estate overlay (RV-P6). Never produced before that store exists. */
    LEARNED,
}

/** S-4 confidence provenance: which producer + method yielded the score. */
data class Provenance(
    val producer: String,
    val method: String,
    val rawScore: Double,
)

/**
 * RV-39 — the layer-version tuple, echoed on every response (S-1).
 *
 * Replaces nothing: the old opaque `vocabularyVersion` string stays alongside it (J-v2 additive).
 * The two answer different questions, and the old one answers its badly — it bakes in the member
 * load timestamp, so it changes on every refresh whether or not any vocabulary did. This tuple is
 * asked exactly one question, *did a layer change?*, and each component is content-derived.
 */
data class LayerVersions(
    /**
     * `CompiledLexicon.contentHash` of the loaded artifact — content of the entry table only, so
     * it does not move when the build clock does. Empty when no artifact is loaded.
     */
    val lexiconArtifactHash: String = "",
    /** category → the member index's version for that category. */
    val memberIndexVersions: Map<String, String> = emptyMap(),
    /** Absent (null) until the RV-P6 overlay store exists — absence is the contract, not `""`. */
    val overlayVersion: String? = null,
)

data class FuzzyMatchResult(
    val candidateId: String,
    val candidate: String,
    val score: Double,
    val category: String,
    // RG-P2 additive (response-side; the pinned MatchRequest is untouched):
    val source: SourceTag = SourceTag.MEMBER,
    val targetRef: String? = null,
    val provenance: Provenance = Provenance("fuzzy", "TATRMAN", score),
    /**
     * RV-32 — the **authored** match method (`EXACT` · `TOKENS` · `TYPOS(n)`), a different axis
     * from [Provenance.method], which is the algorithm that produced the score. Null for member
     * candidates: nobody authored a method for a data value.
     *
     * RV-P1.4 T2 carries it; **T4 honours it** (dispatch). Carrying it first is deliberate — the
     * value has to survive the loader and the cascade before the dispatcher can be trusted to read it.
     */
    val matchMethod: String? = null,
)
