// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.NerEntity
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.model.ResolverEntityType
import org.tatrman.text.Normalization.fold

/**
 * `proposeDomainSpans` — the Q-20 GO-WITH-FALLBACK core (spike §5). Naive
 * all-spans × fuzzy over-generates (P=0.5, 33 spurious binds); this proposes a
 * domain span ONLY where the dep parse ties a content subtree to a declared
 * entity-type anchor, gating it against that entity's vocabulary alone. That
 * recovered P=1.0 and killed over-generation (33→0) with ZERO LLM.
 *
 * Deterministic candidate sources:
 *   (a) **anchored subtrees** — for each declared anchor word found in the parse,
 *       the anchor's own nominal phrase (`pražských pobočkách` as ONE candidate)
 *       plus each nominal/proper-noun argument it governs (`středisko DF ADNAK`
 *       → the value `DF ADNAK`), gated against THAT entity only. Precision path.
 *   (b) **proper-noun arguments** — PROPN runs not already anchored and not
 *       universal-tagged, gated against ALL declared types. Admits data values
 *       like `Octavie` without re-admitting common-noun junk (the 33 spurious in
 *       config B were common nouns: `záznamy`, `roce`, `vývoj nákladů`).
 *   (c) **domain-eligible NER entities** — a NER span the classifier does NOT type
 *       as universal (CNEC objects/institutions: `op` products, `if` orgs) is a
 *       domain candidate gated against ALL declared types, EVEN when the POS tagger
 *       calls it a common NOUN (so (a)/(b) miss it). Live morphology tags a product
 *       name like `Octavie` NNFP4/NOUN while NameTag flags it `op`; fuzzy is the filter.
 *   (d) **n-gram floor (R4-γ)** — only when there is no dep parse (degraded
 *       language): content n-grams (1..[MAX_NGRAM]) over non-stopword,
 *       non-universal tokens, gated against ALL types.
 *
 * Universal-typed NER spans (person/geo/time/number) are removed before domain
 * gating (spike §1). Institutions/objects stay domain-eligible and are actively
 * proposed by (c) — a domain value like `DF ADNAK` is `io`-tagged, so NER is not the
 * domain filter; fuzzy is.
 *
 * RV-P2.1 adds one source and one exclusion, both needed by the lattice:
 *
 *   (e) **literal runs** — a run of code/number tokens (`501001`, `5010O1`, `10`), scoped
 *       to the categories of the nearest MENTION beside it and to nothing else. This is
 *       the deterministic half of RV-33's anchored lookup and the structural fix for
 *       issues.md §"Looking in wrong entity": `501001` is searched in the *account*
 *       because the question said *účtu*, not offered to every fuzzy column in the estate.
 *       A literal with no mention beside it is NOT proposed — an unscoped code search is
 *       exactly the over-generation Q-20 removed.
 *
 *   **An anchor word is nobody else's modifier and nobody else's value.** A declared
 *       anchor is a mention in its own right, so it is excluded from another anchor's
 *       phrase hull and from its governed arguments. Without this, Stanza's tagging of the
 *       Czech imperative (`Zobraz` comes back NOUN/`amod` under the measure — P0.2 report)
 *       silently swallows the operator word into `Zobraz náklady`, and `účtu` — governed by
 *       the root — is gated against the *measure's* categories instead of its own.
 */
object SpanProposal {
    private const val MAX_NGRAM = 3

    /** How far from a literal a mention may sit and still scope it (in tokens). */
    private const val MAX_ANCHOR_DISTANCE = 3

    /** UPOS tags whose tokens are literals: codes, numbers, symbols. */
    private val LITERAL_UPOS = setOf("NUM", "SYM")

    /** Object kinds with no member vocabulary — nothing they govern is a value of theirs. */
    private val VALUELESS_OBJECT_KINDS = setOf("operator", "measure")

    /** Anchor-phrase pre-modifiers folded into the anchor noun's own candidate. */
    private val ANCHOR_PHRASE_RELATIONS = setOf("amod", "compound", "flat", "flat:name", "det", "nummod")

    /** Relations by which an anchor governs a separate value argument. */
    private val GOVERNED_VALUE_RELATIONS = setOf("nmod", "appos", "obj", "obl", "dep", "conj", "flat")

    /** Multi-word run relations that glue a proper-noun phrase together. */
    private val PROPN_RUN_RELATIONS = setOf("flat", "flat:name", "compound", "nmod", "appos")

    private val NOMINAL_UPOS = setOf("NOUN", "PROPN", "X")

    // A minimal Czech stopword set for the parse-less n-gram floor only. When a
    // dep parse is present these never matter (anchoring drives proposal); the
    // floor is a degraded-language safety net, not the precision path.
    private val STOPWORDS =
        setOf(
            "a",
            "i",
            "o",
            "u",
            "v",
            "k",
            "s",
            "z",
            "na",
            "za",
            "do",
            "od",
            "po",
            "ve",
            "se",
            "je",
            "to",
            "jsme",
            "jsou",
            "byl",
            "byla",
            "bylo",
            "kolik",
            "jak",
            "kde",
            "kdy",
            "co",
            "který",
            "která",
            "které",
            "poslední",
            "za",
            "the",
            "of",
        )

    fun proposeDomainSpans(
        parse: AnalyzeResponse,
        entityTypes: List<ResolverEntityType>,
    ): List<DomainSpanCandidate> {
        val tokens = parse.tokensList
        if (tokens.isEmpty()) return emptyList()

        val allCategories = entityTypes.flatMap { it.categories }.distinct()
        val allRefs = entityTypes.map { it.ref }
        val universal = universalCharRanges(parse.entitiesList)

        val hasParse = tokens.any { it.depHead > 0 }
        if (!hasParse) {
            return ngramFloor(tokens, universal, allRefs, allCategories)
        }

        // children[headIndex1Based] = token list indices whose dep_head points here.
        val children = HashMap<Int, MutableList<Int>>()
        tokens.forEachIndexed { idx, t ->
            if (t.depHead > 0) children.getOrPut(t.depHead) { mutableListOf() }.add(idx)
        }

        // Fold declared anchors once: folded anchor word → the entity types owning it.
        val anchorIndex = HashMap<String, MutableList<ResolverEntityType>>()
        for (et in entityTypes) {
            for (anchor in et.anchors) {
                anchorIndex.getOrPut(fold(anchor)) { mutableListOf() }.add(et)
            }
        }

        val out = mutableListOf<DomainSpanCandidate>()
        val coveredTokens = HashSet<Int>()

        // Every token that IS a declared anchor word. An anchor is a mention of its own
        // model object, so it may not be folded into a sibling anchor's phrase nor taken
        // as that anchor's governed value (RV-P2.1 — see the class doc).
        val anchorTokens =
            tokens.indices
                .filter { fold(tokens[it].lemma.ifBlank { tokens[it].text }) in anchorIndex }
                .toHashSet()

        // (a) anchored subtrees
        tokens.forEachIndexed { idx, t ->
            val key = fold(if (t.lemma.isNotBlank()) t.lemma else t.text)
            val owners = anchorIndex[key] ?: return@forEachIndexed
            for (et in owners) {
                // anchor phrase: the anchor noun + its pre-modifiers, contiguous hull.
                val phraseIdx = anchorPhraseIndices(idx, children, tokens, universal, anchorTokens)
                if (phraseIdx.isNotEmpty()) {
                    out +=
                        candidate(
                            phraseIdx,
                            tokens,
                            listOf(et.ref),
                            et.categories,
                            anchored = true,
                            origin = DomainSpanCandidate.Origin.ANCHOR_PHRASE,
                            headToken = idx,
                        )
                    coveredTokens += phraseIdx
                }
                // governed value arguments (e.g. `středisko` → `DF ADNAK`). Only for an anchor
                // that HAS values: an operator or a measure has no member vocabulary, so its
                // nominal arguments are not its values. Without this the operator word — which
                // Stanza often makes the root — governs the rest of the question, and every
                // noun under it is proposed as a value of `op:show` (h2's `stanic`). A blank
                // kind admits values, which is the pre-RV behaviour for a snapshot that carries
                // no object kinds.
                if (et.objectKind in VALUELESS_OBJECT_KINDS) continue
                for (childIdx in children[idx + 1].orEmpty()) {
                    val child = tokens[childIdx]
                    if (child.depRelation !in GOVERNED_VALUE_RELATIONS) continue
                    if (child.upos.uppercase() !in NOMINAL_UPOS) continue
                    if (childIdx in anchorTokens) continue
                    val valueIdx = subtreeIndices(childIdx, children, tokens, universal, anchorTokens)
                    if (valueIdx.isEmpty()) continue
                    out +=
                        candidate(
                            valueIdx,
                            tokens,
                            listOf(et.ref),
                            et.categories,
                            anchored = true,
                            origin = DomainSpanCandidate.Origin.GOVERNED_VALUE,
                            headToken = childIdx,
                        )
                    coveredTokens += valueIdx
                }
            }
        }

        // (b) proper-noun arguments not already anchored
        tokens.forEachIndexed { idx, t ->
            if (idx in coveredTokens) return@forEachIndexed
            if (t.upos.uppercase() != "PROPN") return@forEachIndexed
            if (isUniversal(t, universal)) return@forEachIndexed
            val runIdx = propnRun(idx, children, tokens, universal, coveredTokens)
            if (runIdx.isEmpty()) return@forEachIndexed
            out +=
                candidate(
                    runIdx,
                    tokens,
                    allRefs,
                    allCategories,
                    anchored = false,
                    origin = DomainSpanCandidate.Origin.PROPER_NOUN,
                    headToken = idx,
                )
            coveredTokens += runIdx
        }

        // (c) domain-eligible NER entities. A NER span the classifier does NOT type as
        // universal — CNEC objects/institutions (`op` products, `if` orgs) — is a domain
        // candidate even when the POS tagger calls it a common NOUN, so the anchored/PROPN
        // paths above miss it (RG hero: live morphology tags "Octavie" NNFP4/NOUN, yet
        // NameTag flags it `op`). Gated against ALL declared types; fuzzy stays the filter.
        // Skipped where an already-emitted candidate covers the entity's span.
        for (e in parse.entitiesList) {
            if (UniversalClassifier.isUniversal(e.label, e.normalizedValue)) continue
            if (out.any { it.start <= e.charStart && it.end >= e.charEnd }) continue
            out +=
                DomainSpanCandidate(
                    e.text,
                    e.charStart,
                    e.charEnd,
                    allRefs,
                    allCategories.distinct(),
                    anchored = false,
                    origin = DomainSpanCandidate.Origin.NER_ENTITY,
                    headToken = tokens.indexOfFirst { it.charStart >= e.charStart && it.charEnd <= e.charEnd },
                )
        }

        // (e) literal runs, scoped by the mention beside them (RV-P2.1 / RV-33).
        val gated = dedupe(out)
        return dedupe(gated + literalRuns(tokens, universal, gated, coveredTokens))
    }

    /**
     * Code/number runs (`501001`, `5010O1`, `10`), each gated against the categories of the
     * nearest **mention** — the anchored phrase closest in token distance, left preferred on a
     * tie, within [MAX_ANCHOR_DISTANCE]. A literal with no mention beside it is not proposed at
     * all: an unscoped code search is the over-generation Q-20 removed, and the lattice can say
     * "unattributed" (G3) without having guessed first.
     */
    private fun literalRuns(
        tokens: List<Token>,
        universal: List<IntRange>,
        gated: List<DomainSpanCandidate>,
        covered: Set<Int>,
    ): List<DomainSpanCandidate> {
        val mentions = gated.filter { it.origin == DomainSpanCandidate.Origin.ANCHOR_PHRASE && it.headToken >= 0 }
        if (mentions.isEmpty()) return emptyList()

        val out = mutableListOf<DomainSpanCandidate>()
        var i = 0
        while (i < tokens.size) {
            if (!isLiteral(tokens[i]) || i in covered || isUniversal(tokens[i], universal)) {
                i++
                continue
            }
            // a run of adjacent literal tokens is ONE literal: Stanza splits `5010O1` into
            // `5010O` + `1`, and searching either half finds nothing.
            var end = i
            while (end + 1 < tokens.size &&
                isLiteral(tokens[end + 1]) &&
                tokens[end + 1].charStart <= tokens[end].charEnd + 1 &&
                (end + 1) !in covered &&
                !isUniversal(tokens[end + 1], universal)
            ) {
                end++
            }
            val indices = (i..end).toList()
            val anchor =
                mentions
                    .filter { kotlin.math.abs(it.headToken - i) <= MAX_ANCHOR_DISTANCE }
                    .minWithOrNull(
                        compareBy({ kotlin.math.abs(it.headToken - i) }, { it.headToken > i }, { it.headToken }),
                    )
            if (anchor != null) {
                out +=
                    candidate(
                        indices,
                        tokens,
                        anchor.gatedEntityRefs,
                        anchor.categories,
                        anchored = true,
                        origin = DomainSpanCandidate.Origin.LITERAL,
                        headToken = i,
                        anchorHeadToken = anchor.headToken,
                    )
            }
            i = end + 1
        }
        return out
    }

    /** A code or a number: the POS tagger said so, or the surface carries a digit. */
    private fun isLiteral(token: Token): Boolean =
        token.upos.uppercase() in LITERAL_UPOS || token.text.any { it.isDigit() }

    /**
     * A digit-bearing surface. Narrower than [isLiteral] on purpose: a spelled-out numeral
     * (`deset poboček`) stays part of the phrase it quantifies, while `5010O` does not.
     */
    private fun isCode(token: Token): Boolean = token.text.any { it.isDigit() }

    // --- helpers ------------------------------------------------------------

    private fun anchorPhraseIndices(
        headIdx: Int,
        children: Map<Int, List<Int>>,
        tokens: List<Token>,
        universal: List<IntRange>,
        anchorTokens: Set<Int>,
    ): List<Int> {
        if (isUniversal(tokens[headIdx], universal)) return emptyList()
        val included = sortedSetOf(headIdx)
        for (c in children[headIdx + 1].orEmpty()) {
            if (c in anchorTokens) continue // a sibling anchor is its own mention, not a modifier
            // A code is a VALUE of the thing, never part of its name: `nummod` is in the phrase
            // relations for numeral words, and without this `účtu 5010O` becomes one mention and
            // the code is never looked up at all.
            if (isCode(tokens[c])) continue
            if (tokens[c].depRelation in ANCHOR_PHRASE_RELATIONS && !isUniversal(tokens[c], universal)) {
                included += c
            }
        }
        // contiguous hull, dropping any universal token inside it
        return contiguousHull(included, tokens, universal, anchorTokens)
    }

    private fun subtreeIndices(
        rootIdx: Int,
        children: Map<Int, List<Int>>,
        tokens: List<Token>,
        universal: List<IntRange>,
        anchorTokens: Set<Int>,
    ): List<Int> {
        val acc = sortedSetOf<Int>()
        val stack = ArrayDeque<Int>()
        stack.addLast(rootIdx)
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            if (i in acc) continue
            if (isUniversal(tokens[i], universal)) continue
            if (i in anchorTokens && i != rootIdx) continue
            if (tokens[i].upos.uppercase() !in NOMINAL_UPOS && i != rootIdx) continue
            acc += i
            for (c in children[i + 1].orEmpty()) stack.addLast(c)
        }
        return contiguousHull(acc, tokens, universal, anchorTokens)
    }

    private fun propnRun(
        headIdx: Int,
        children: Map<Int, List<Int>>,
        tokens: List<Token>,
        universal: List<IntRange>,
        covered: Set<Int>,
    ): List<Int> {
        val included = sortedSetOf(headIdx)
        for (c in children[headIdx + 1].orEmpty()) {
            if (c in covered) continue
            if (tokens[c].depRelation in PROPN_RUN_RELATIONS &&
                tokens[c].upos.uppercase() == "PROPN" &&
                !isUniversal(tokens[c], universal)
            ) {
                included += c
            }
        }
        return contiguousHull(included, tokens, universal)
    }

    /**
     * Take the contiguous token-index hull (min..max) of [indices], but drop any
     * token in the gap that is universal-tagged (so a value phrase never swallows
     * an intervening date/person span). Non-universal gap tokens are kept so the
     * emitted phrase reads naturally.
     */
    private fun contiguousHull(
        indices: Set<Int>,
        tokens: List<Token>,
        universal: List<IntRange>,
        anchorTokens: Set<Int> = emptySet(),
    ): List<Int> {
        if (indices.isEmpty()) return emptyList()
        val lo = indices.min()
        val hi = indices.max()
        return (lo..hi).filter {
            !isUniversal(tokens[it], universal) &&
                (it in indices || (it !in anchorTokens && !isCode(tokens[it])))
        }
    }

    private fun candidate(
        indices: List<Int>,
        tokens: List<Token>,
        refs: List<String>,
        categories: List<String>,
        anchored: Boolean,
        origin: DomainSpanCandidate.Origin,
        headToken: Int,
        anchorHeadToken: Int = -1,
    ): DomainSpanCandidate {
        val sorted = indices.sorted()
        val start = sorted.minOf { tokens[it].charStart }
        val end = sorted.maxOf { tokens[it].charEnd }
        return DomainSpanCandidate(
            surface(sorted, tokens),
            start,
            end,
            refs,
            categories.distinct(),
            anchored,
            origin,
            headToken,
            tokens.getOrNull(headToken)?.let { it.lemma.ifBlank { it.text } }.orEmpty(),
            anchorHeadToken,
        )
    }

    /**
     * The surface of a token run, respecting character adjacency: tokens that touch in the
     * source are joined without a space. Stanza splits `5010O1` into two tokens, and the
     * query `5010O 1` matches nothing that `5010O1` would.
     */
    internal fun surface(
        indices: List<Int>,
        tokens: List<Token>,
    ): String {
        val sb = StringBuilder()
        var previousEnd = -1
        for (i in indices) {
            if (previousEnd in 0 until tokens[i].charStart) sb.append(' ')
            sb.append(tokens[i].text)
            previousEnd = tokens[i].charEnd
        }
        return sb.toString()
    }

    /** Collapse candidates that resolve to the same char span (anchored wins). */
    private fun dedupe(cands: List<DomainSpanCandidate>): List<DomainSpanCandidate> {
        val bySpan = LinkedHashMap<Pair<Int, Int>, DomainSpanCandidate>()
        for (c in cands) {
            val k = c.start to c.end
            val existing = bySpan[k]
            if (existing == null || (!existing.anchored && c.anchored)) bySpan[k] = c
        }
        return bySpan.values.toList()
    }

    private fun ngramFloor(
        tokens: List<Token>,
        universal: List<IntRange>,
        allRefs: List<String>,
        allCategories: List<String>,
    ): List<DomainSpanCandidate> {
        val content =
            tokens.indices.filter { i ->
                !isUniversal(tokens[i], universal) &&
                    fold(tokens[i].text) !in STOPWORDS &&
                    tokens[i].text.any { it.isLetter() }
            }
        val out = mutableListOf<DomainSpanCandidate>()
        // windows over the ORIGINAL token order, size 1..MAX_NGRAM, contiguous runs only.
        for (start in content.indices) {
            for (n in 1..MAX_NGRAM) {
                val windowPos = start until minOf(start + n, content.size)
                val idx = windowPos.map { content[it] }
                // require token-order contiguity so windows read as real phrases
                if (idx.zipWithNext().any { (a, b) -> b != a + 1 }) continue
                out +=
                    candidate(
                        idx,
                        tokens,
                        allRefs,
                        allCategories,
                        anchored = false,
                        origin = DomainSpanCandidate.Origin.NGRAM_FLOOR,
                        headToken = idx.first(),
                    )
            }
        }
        return dedupe(out)
    }

    // Universal NER spans (removed before domain gating) come from the shared
    // UniversalClassifier — the same classification UniversalExtraction types by,
    // so the exclusion set and the universal bindings agree by construction.
    private fun universalCharRanges(entities: List<NerEntity>): List<IntRange> =
        entities.filter { UniversalClassifier.isUniversal(it.label, it.normalizedValue) }.map {
            it.charStart until
                it.charEnd
        }

    private fun isUniversal(
        token: Token,
        universal: List<IntRange>,
    ): Boolean {
        val mid = (token.charStart + token.charEnd) / 2
        return universal.any { mid in it }
    }
}
