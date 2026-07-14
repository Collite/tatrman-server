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
 * Three deterministic candidate sources:
 *   (a) **anchored subtrees** — for each declared anchor word found in the parse,
 *       the anchor's own nominal phrase (`pražských pobočkách` as ONE candidate)
 *       plus each nominal/proper-noun argument it governs (`středisko DF ADNAK`
 *       → the value `DF ADNAK`), gated against THAT entity only. Precision path.
 *   (b) **proper-noun arguments** — PROPN runs not already anchored and not
 *       universal-tagged, gated against ALL declared types. Admits data values
 *       like `Octavie` without re-admitting common-noun junk (the 33 spurious in
 *       config B were common nouns: `záznamy`, `roce`, `vývoj nákladů`).
 *   (c) **n-gram floor (R4-γ)** — only when there is no dep parse (degraded
 *       language): content n-grams (1..[MAX_NGRAM]) over non-stopword,
 *       non-universal tokens, gated against ALL types.
 *
 * Universal-typed NER spans (person/geo/time/number) are removed before domain
 * gating (spike §1). Institutions/objects stay domain-eligible — a domain value
 * like `DF ADNAK` is `io`-tagged, so NER is not the domain filter; fuzzy is.
 */
object SpanProposal {
    private const val MAX_NGRAM = 3

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

        // (a) anchored subtrees
        tokens.forEachIndexed { idx, t ->
            val key = fold(if (t.lemma.isNotBlank()) t.lemma else t.text)
            val owners = anchorIndex[key] ?: return@forEachIndexed
            for (et in owners) {
                // anchor phrase: the anchor noun + its pre-modifiers, contiguous hull.
                val phraseIdx = anchorPhraseIndices(idx, children, tokens, universal)
                if (phraseIdx.isNotEmpty()) {
                    out += candidate(phraseIdx, tokens, listOf(et.ref), et.categories, anchored = true)
                    coveredTokens += phraseIdx
                }
                // governed value arguments (e.g. `středisko` → `DF ADNAK`)
                for (childIdx in children[idx + 1].orEmpty()) {
                    val child = tokens[childIdx]
                    if (child.depRelation !in GOVERNED_VALUE_RELATIONS) continue
                    if (child.upos.uppercase() !in NOMINAL_UPOS) continue
                    val valueIdx = subtreeIndices(childIdx, children, tokens, universal)
                    if (valueIdx.isEmpty()) continue
                    out += candidate(valueIdx, tokens, listOf(et.ref), et.categories, anchored = true)
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
            out += candidate(runIdx, tokens, allRefs, allCategories, anchored = false)
            coveredTokens += runIdx
        }

        return dedupe(out)
    }

    // --- helpers ------------------------------------------------------------

    private fun anchorPhraseIndices(
        headIdx: Int,
        children: Map<Int, List<Int>>,
        tokens: List<Token>,
        universal: List<IntRange>,
    ): List<Int> {
        if (isUniversal(tokens[headIdx], universal)) return emptyList()
        val included = sortedSetOf(headIdx)
        for (c in children[headIdx + 1].orEmpty()) {
            if (tokens[c].depRelation in ANCHOR_PHRASE_RELATIONS && !isUniversal(tokens[c], universal)) {
                included += c
            }
        }
        // contiguous hull, dropping any universal token inside it
        return contiguousHull(included, tokens, universal)
    }

    private fun subtreeIndices(
        rootIdx: Int,
        children: Map<Int, List<Int>>,
        tokens: List<Token>,
        universal: List<IntRange>,
    ): List<Int> {
        val acc = sortedSetOf<Int>()
        val stack = ArrayDeque<Int>()
        stack.addLast(rootIdx)
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            if (i in acc) continue
            if (isUniversal(tokens[i], universal)) continue
            if (tokens[i].upos.uppercase() !in NOMINAL_UPOS && i != rootIdx) continue
            acc += i
            for (c in children[i + 1].orEmpty()) stack.addLast(c)
        }
        return contiguousHull(acc, tokens, universal)
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
    ): List<Int> {
        if (indices.isEmpty()) return emptyList()
        val lo = indices.min()
        val hi = indices.max()
        return (lo..hi).filter { !isUniversal(tokens[it], universal) }
    }

    private fun candidate(
        indices: List<Int>,
        tokens: List<Token>,
        refs: List<String>,
        categories: List<String>,
        anchored: Boolean,
    ): DomainSpanCandidate {
        val sorted = indices.sorted()
        val text = sorted.joinToString(" ") { tokens[it].text }
        val start = sorted.minOf { tokens[it].charStart }
        val end = sorted.maxOf { tokens[it].charEnd }
        return DomainSpanCandidate(text, start, end, refs, categories.distinct(), anchored)
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
                out += candidate(idx, tokens, allRefs, allCategories, anchored = false)
            }
        }
        return dedupe(out)
    }

    // Universal NER spans (removed before domain gating) come from the shared
    // UniversalClassifier — the same classification UniversalExtraction types by,
    // so the exclusion set and the universal bindings agree by construction.
    private fun universalCharRanges(entities: List<NerEntity>): List<IntRange> =
        entities.filter { UniversalClassifier.isUniversal(it.label) }.map { it.charStart until it.charEnd }

    private fun isUniversal(
        token: Token,
        universal: List<IntRange>,
    ): Boolean {
        val mid = (token.charStart + token.charEnd) / 2
        return universal.any { mid in it }
    }
}
