// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

/**
 * The outcome of gating the proposed spans against the vocabulary (RG-P5.S1.T4).
 * A resolve is EITHER fully bound OR awaiting clarification (the proto `outcome`
 * oneof) — instance ambiguity anywhere forces [Clarify] (refuse-over-guess, RS-26).
 */
sealed interface GateOutcome

/** All spans bound with no unresolved instance ambiguity. */
data class Bound(
    val bindings: List<DomainBinding>,
    val confidence: Double,
) : GateOutcome

/** At least one span was ambiguous among distinct instances — ask, don't guess. */
data class Clarify(
    val options: List<ClarificationOption>,
) : GateOutcome

/**
 * One resolved domain binding (internal model; T5 maps it to the `EntityBinding`
 * proto). MEMBER hits carry [resolvedId] (a data PK → instance-determinate);
 * VOCABULARY hits carry [targetRef] (a declared lexicon target). [siblingRefs] is
 * the Q-20 sibling-column expansion — a value match on a KOD/NAZEV column also
 * points at its sibling column (a catalog lookup, not inference).
 */
data class DomainBinding(
    val span: DomainSpanCandidate,
    val entityTypeRef: String,
    val rawText: String,
    val vocabularySource: String, // "MEMBER" | "VOCABULARY"
    val resolvedId: String?,
    val resolvedLabel: String,
    val targetRef: String?,
    val siblingRefs: List<String>,
    val score: Double,
    val algorithm: String,
    val snapshotHash: String,
)

/** One clarification option (internal model; T5 maps it to the `Option` proto). */
data class ClarificationOption(
    val id: String,
    val label: String,
    val resolvedId: String?,
    val targetRef: String?,
)

/**
 * The sibling-column catalog (Q-20): a column category → the sibling categories a
 * value match should also point at (KOD ↔ NAZEV). Snapshot-fed in S2; injected here
 * so gate stays a pure function.
 */
typealias SiblingCatalog = Map<String, List<String>>
