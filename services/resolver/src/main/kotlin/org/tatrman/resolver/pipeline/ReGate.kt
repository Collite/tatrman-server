// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.tatrman.fuzzy.v1.LookupRequest
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.model.ResolverEntityType
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.v1.Attribution
import org.tatrman.resolver.v1.Binding
import org.tatrman.resolver.v1.GateRequest
import org.tatrman.resolver.v1.GateResponse
import org.tatrman.resolver.v1.Hypothesis
import org.tatrman.resolver.v1.HypothesisOutcome
import org.tatrman.resolver.v1.Mention
import org.tatrman.resolver.v1.RungLogEntry
import org.tatrman.resolver.v1.ValueFinding

/**
 * RV-P2.4 — **`resolve.gate:v1`**: the re-gate sibling tool (Q-13 RULED = B, ⚑RV-1).
 *
 * An LLM rung reads a lattice, forms a hypothesis — *"that `5010O1` is a typo for `501001`"* — and
 * hands it back. This is where it comes back. **Nothing here decides anything.** Every hypothesis
 * is turned into a lexicon question, the answer goes through the same [Binder] the broad pass and
 * the lookup rounds use, and only what survives becomes a binding. That is RV-7's
 * proposer-not-binder made structural rather than promised: there is no path through this file
 * that produces a `Binding` without the gate's consent, and `SingleBinderTest` greps for exactly
 * that.
 *
 * **The handler contains no matching logic, deliberately** (T3). It routes:
 *
 *  - a **correction** (`5010O1` → `501001`) re-asks the span's lookup with the corrected surface;
 *  - a **ref proposal** scopes that lookup to the proposed ref's own category — in the compiled
 *    lexicon a target ref IS the category key, so this needs no registry round trip — and then
 *    requires the winner to actually point at the proposed ref. A hypothesis that names a target
 *    and gets a different one back has not been confirmed, it has been contradicted;
 *  - an **attribution** with neither falls back to the anchoring already in the lattice, which is
 *    the same scope [RoundPlanner]'s first tier uses.
 *
 * If a fourth kind is ever wanted, it belongs in the components this orchestrates, not here.
 *
 * **Stateless** (T4). The request carries the lattice exactly as `Resolve` carries the text, so
 * the service holds no session and gating the same batch twice is the same call twice — which is
 * what the Golem loop's retry semantics need, and why the idempotency test is a real test rather
 * than a formality.
 */
object ReGate {
    /** The rung name this tool writes; the caller's proposing rung rides on each hypothesis. */
    const val ACTION: String = "regate"

    /** Why a hypothesis did not become a binding. Machine-readable; see `HypothesisOutcome.reason`. */
    object Reason {
        /** The lattice has no span at those offsets — the caller is gating against a stale lattice. */
        const val NO_SPAN = "NO_SPAN"

        /** The vocabulary has no such term at all. The H2 pre-learning case, and a correct answer. */
        const val NO_CANDIDATE = "NO_CANDIDATE"

        /** Found, and refused by the RV-14 class floor. */
        const val WEAK = "WEAK"

        /** Several distinct identities — refuse over guess (RS-26) rather than take the hypothesis' word. */
        const val AMBIGUOUS = "AMBIGUOUS"

        /** A candidate bound, but not the one the hypothesis proposed. Contradicted, not confirmed. */
        const val REF_MISMATCH = "REF_MISMATCH"
    }

    suspend fun run(
        request: GateRequest,
        fuzzy: FuzzyClient,
        entityTypes: List<ResolverEntityType>,
        thresholds: ResolverThresholds,
        snapshotHash: String,
        maxCandidates: Int,
    ): GateResponse {
        val lattice = request.lattice
        val mentions = lattice.mentionsList.associateBy { it.span.start to it.span.end }
        val values = lattice.valuesList.associateBy { it.span.start to it.span.end }
        val categoriesByRef = entityTypes.associate { it.ref to it.categories }

        val verified =
            coroutineScope {
                request.hypothesesList
                    .map { hypothesis ->
                        val key = hypothesis.span.start to hypothesis.span.end
                        val target = mentions[key] ?: values[key]
                        val categories = scopeFor(hypothesis, values[key], lattice, categoriesByRef)
                        hypothesis to
                            async {
                                if (target == null) {
                                    null
                                } else {
                                    runCatching {
                                        fuzzy.lookup(
                                            LookupRequest
                                                .newBuilder()
                                                .setTerm(surfaceOf(hypothesis, target))
                                                .addAllCategories(categories)
                                                .setMaxCandidates(maxCandidates)
                                                .build(),
                                        )
                                    }.getOrNull()?.candidatesList.orEmpty()
                                }
                            }
                    }.map { (hypothesis, deferred) -> hypothesis to deferred.await() }
            }

        val outcomes = mutableListOf<HypothesisOutcome>()
        val bindings = mutableListOf<Binding>()
        // Applied to a COPY of the caller's lattice, so `updated_gaps` describes the world after
        // gating without this tool ever having mutated anything the caller still holds.
        val mentionBindings = mutableMapOf<Pair<Int, Int>, MutableList<Binding>>()
        val valueBindings = mutableMapOf<Pair<Int, Int>, MutableList<Binding>>()

        for ((hypothesis, candidates) in verified) {
            val key = hypothesis.span.start to hypothesis.span.end
            val mention = mentions[key]
            val value = values[key]
            if (mention == null && value == null) {
                outcomes += outcome(hypothesis, Reason.NO_SPAN)
                continue
            }
            if (candidates.isNullOrEmpty()) {
                outcomes += outcome(hypothesis, Reason.NO_CANDIDATE)
                continue
            }

            // A mention is its own anchor phrase; a value is anchored exactly when the lattice
            // already said so. The hypothesis' own confidence is NOT evidence of anchoring — a
            // proposer that could talk itself into a higher evidence class is a proposer that binds.
            val anchored = mention != null || (value?.anchorMentionId?.isNotBlank() == true)
            val verdict = Binder.gate(candidates, anchorCandidate(hypothesis, anchored), thresholds)
            when {
                verdict is Binder.Ambiguous -> outcomes += outcome(hypothesis, Reason.AMBIGUOUS)
                verdict is Binder.Bind -> {
                    val binding = Bindings.of(verdict.winner, snapshotHash).withRung(hypothesis.proposingRung)
                    if (hypothesis.ref.isNotBlank() && !confirms(binding.ref, hypothesis.ref)) {
                        outcomes += outcome(hypothesis, Reason.REF_MISMATCH)
                    } else {
                        bindings += binding
                        outcomes += accepted(hypothesis, binding)
                        if (mention != null) {
                            mentionBindings.getOrPut(key) { mutableListOf() } += binding
                        } else {
                            valueBindings.getOrPut(key) { mutableListOf() } += binding
                        }
                    }
                }
                // NoBind with something rejected = the class floor refused it; with nothing
                // rejected the lookup answered and every row fell below the matcher's own floor.
                else ->
                    outcomes +=
                        outcome(hypothesis, if (verdict.rejected.isEmpty()) Reason.NO_CANDIDATE else Reason.WEAK)
            }
        }

        val updatedMentions = lattice.mentionsList.map { it.plus(mentionBindings[it.span.start to it.span.end]) }
        val updatedValues = lattice.valuesList.map { it.plus(valueBindings[it.span.start to it.span.end]) }
        val gaps =
            Gaps.assess(
                mentions = updatedMentions,
                values = updatedValues,
                // Ambiguity is re-derived from what THIS call decided; a span the caller's lattice
                // recorded as ambiguous stays ambiguous because nothing here bound it.
                ambiguousSpans =
                    lattice.gapsList
                        .filter { it.kind == org.tatrman.resolver.v1.GapKind.GAP_KIND_G2_AMBIGUOUS }
                        .map { it.span.start to it.span.end }
                        .toSet(),
                parse = lattice.parse,
                degraded = false,
            )

        return GateResponse
            .newBuilder()
            .addAllGatedBindings(bindings)
            .addAllUpdatedGaps(gaps)
            .addAllOutcomes(outcomes)
            .setRungLogEntry(
                RungLogEntry
                    .newBuilder()
                    .setRung(rungOf(request))
                    .setAction(ACTION)
                    .addAllHypotheses(request.hypothesesList)
                    .setBindingsAdded(bindings.size)
                    .setGapsOpen(gaps.size),
            ).build()
    }

    // --- helpers ------------------------------------------------------------

    /**
     * The rung this entry belongs to: whichever one proposed. One `gate` call is one
     * escalate→gate pair (RV-11), so a batch from two rungs is a caller error rather than a
     * shape to model; the first hypothesis names the pair.
     */
    private fun rungOf(request: GateRequest): String =
        request.hypothesesList
            .firstOrNull { it.proposingRung.isNotBlank() }
            ?.proposingRung
            .orEmpty()

    /** The corrected surface if the hypothesis offers one, else the span the lattice already has. */
    private fun surfaceOf(
        hypothesis: Hypothesis,
        target: Any,
    ): String =
        hypothesis.correction.ifBlank {
            when (target) {
                is Mention -> target.span.text
                is ValueFinding -> target.span.text
                else -> hypothesis.span.text
            }
        }

    /**
     * Where to look. A proposed ref scopes to its own category (in the compiled lexicon a target
     * ref IS the category key — the same fact `GroundingTriggers` relies on), widened to the
     * declaring entity's categories when the registry knows it, so a hypothesis naming an entity
     * still reaches that entity's member columns. With no ref, the lattice's own anchoring
     * supplies the scope — the same scope a P2.3 round would have used.
     */
    private fun scopeFor(
        hypothesis: Hypothesis,
        value: ValueFinding?,
        lattice: org.tatrman.resolver.v1.ResolutionState,
        categoriesByRef: Map<String, List<String>>,
    ): List<String> {
        if (hypothesis.ref.isNotBlank()) {
            val base = hypothesis.ref.substringBefore('#')
            return categoriesByRef[base] ?: listOf(base)
        }
        val anchorId = value?.anchorMentionId.orEmpty()
        if (anchorId.isBlank()) return emptyList()
        return lattice.mentionsList
            .firstOrNull { it.id == anchorId }
            ?.bindingsList
            ?.flatMap { categoriesByRef[it.ref] ?: listOf(it.ref) }
            ?.distinct()
            .orEmpty()
    }

    /**
     * A binding confirms a proposed ref when it IS that ref, or a member of it — `501001` on
     * `md.dimension.Account.code` arrives as `md.dimension.Account.code#501001`, and refusing that
     * would reject every correct member hypothesis there is.
     */
    private fun confirms(
        bound: String,
        proposed: String,
    ): Boolean = bound == proposed || bound.startsWith("$proposed#") || proposed.startsWith("$bound#")

    /**
     * The span as the gate needs to see it. Only [DomainSpanCandidate.anchored] is load-bearing
     * here — it is the one input to [EvidenceClasses] that does not come from the matcher row —
     * so the rest is filled from the hypothesis rather than invented.
     */
    private fun anchorCandidate(
        hypothesis: Hypothesis,
        anchored: Boolean,
    ): DomainSpanCandidate =
        DomainSpanCandidate(
            text = hypothesis.span.text,
            start = hypothesis.span.start,
            end = hypothesis.span.end,
            gatedEntityRefs = emptyList(),
            categories = emptyList(),
            anchored = anchored,
        )

    private fun outcome(
        hypothesis: Hypothesis,
        reason: String,
    ): HypothesisOutcome =
        HypothesisOutcome
            .newBuilder()
            .setHypothesis(hypothesis)
            .setAccepted(false)
            .setReason(reason)
            .build()

    private fun accepted(
        hypothesis: Hypothesis,
        binding: Binding,
    ): HypothesisOutcome =
        HypothesisOutcome
            .newBuilder()
            .setHypothesis(hypothesis)
            .setAccepted(true)
            .setBinding(binding)
            .build()

    private fun Binding.withRung(rung: String): Binding =
        if (rung.isBlank()) {
            this
        } else {
            toBuilder().setProducer(producer.toBuilder().setProposingRung(rung)).build()
        }

    private fun Mention.plus(added: List<Binding>?): Mention =
        if (added.isNullOrEmpty()) this else toBuilder().addAllBindings(added).build()

    private fun ValueFinding.plus(added: List<Binding>?): ValueFinding =
        if (added.isNullOrEmpty()) {
            this
        } else {
            toBuilder()
                .addAllAttributions(
                    added.map {
                        Attribution
                            .newBuilder()
                            .setAttributeRef(it.ref.substringBefore('#'))
                            .setBinding(it)
                            .build()
                    },
                ).build()
        }
}
