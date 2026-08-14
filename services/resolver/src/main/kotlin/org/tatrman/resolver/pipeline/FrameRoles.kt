// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.resolver.v1.FrameRole
import org.tatrman.resolver.v1.TargetClass

/**
 * RV-P2.1.T5 — frame-role derivation (RV-21, Q-15 **RULED rule-based, no LLM assist**:
 * SUBJECT precision 1.000 against a 0.85 bar, on the 39-fixture corpus *and* on a held-out
 * set authored after the rules were frozen). This is the port of
 * `implementation/spikes/frame-roles/deriver.py`, and `FrameRolesFixtureTest` re-runs the
 * spike's own corpus against it in-process.
 *
 * **The precedence is the design, so this is an ordered pass and not a rule bag.** Roles that
 * follow from the MODEL are settled first, because they are the facts we are most sure of;
 * roles that follow from SYNTAX are layered on top; SUBJECT is resolved LAST, from the
 * residue — "what the question is about" is what remains once the filters, the groupings and
 * the operators are accounted for.
 *
 * ```
 * R0  target_class == OPERATOR                      -> {}         # RV-35, always
 * R1  target_class == MEMBER                        -> +FILTER    # a member IS a restriction
 * R2  object_kind  == measure                       -> +MEASURE   # a measure IS the measure  ⛔ DEAD
 * R3  prep in GROUPING_PREPS and not measure(m)     -> +GROUPING
 * R4  prep in FILTER_PREPS   and not measure(m)     -> +FILTER
 * R5  no prep, deprel == compound, not measure      -> +FILTER     # "WEB revenue"
 * R6  no prep, deprel starts "obl", not measure     -> +FILTER     # "minulý TÝDEN"
 * R6' no prep, deprel == advmod, binds an attribute -> +FILTER     # "LONI" (spike F-1)
 * R7  deprel == conj  -> inherit the head mention's FILTER / GROUPING
 * R8  m anchors a value and deprel not nsubj        -> +FILTER     # "účtu 501001"
 * R9  residue: the nsubj candidate, else the shallowest+leftmost   -> +SUBJECT (AT MOST ONE)
 * ```
 *
 * Three of these carry most of the weight:
 *
 * **R3's measure exception is what makes `podle`/`by` safe.** It is GROUP-BY in *"tržby podle
 * prodejen"* and ORDER-BY in *"prvních 10 stanic podle tržby"* — the same preposition, opposite
 * roles. The discriminator is neither the operator nor the word order: it is whether the
 * governed mention **binds a measure**, which is model-driven and therefore does not need a
 * top-n operator to have been recognised first.
 *
 * **R9(a) `nsubj` is the highest-value signal in the set.** It separates *"Které POLOŽKY nebyly
 * skladem"* (subject = the dimension) from *"Zobraz TRŽBY podle prodejen"* (subject = the
 * measure) with no model-side heuristic. A deriver built on "the subject is the measure" gets
 * the stock-out question wrong; one built on "the subject is the root" gets H1 wrong, because
 * Stanza tags the Czech imperative *Zobraz* as a NOUN and sometimes as the root.
 *
 * **R9's at-most-one is a contract, not a convenience.** RV-15 fires an ask on *the*
 * load-bearing gap. A deriver that always names some subject would ask a question on a
 * follow-up turn whose subject lives in the conversation rather than in the core.
 */
object FrameRoles {
    private val SUBJECT_DEPRELS = setOf("nsubj", "nsubj:pass")

    /**
     * One mention as the rules see it: where it sits in the parse, and the two model facts
     * (what class it bound, what kind of object that is). Nothing else about the model is
     * read — no ref-name string matching, no lexicon lookup.
     */
    data class Input(
        val id: String,
        val charStart: Int,
        val headToken: Int,
        val targetClass: TargetClass,
        val objectKind: String,
        val anchorsValue: Boolean,
    )

    fun derive(
        mentions: List<Input>,
        parse: AnalyzeResponse,
        lang: String,
        preps: FrameRolePreps,
    ): Map<String, List<FrameRole>> {
        val tokens = parse.tokensList
        val roles = mentions.associate { it.id to sortedSetOf<FrameRole>(compareBy { role -> role.number }) }
        val grouping = preps.grouping(lang)
        val filter = preps.filter(lang)
        val byHead = mentions.filter { it.headToken >= 0 }.associateBy { it.headToken }

        // -- R0 operators carry no frame role, ever (RV-35) --------------------
        val scoreable = mentions.filter { it.targetClass != TargetClass.TARGET_CLASS_OPERATOR }

        // -- R1/R2 model-driven roles -----------------------------------------
        for (m in scoreable) {
            if (m.targetClass == TargetClass.TARGET_CLASS_MEMBER) roles.getValue(m.id) += FrameRole.FRAME_ROLE_FILTER
            if (isMeasure(m)) roles.getValue(m.id) += FrameRole.FRAME_ROLE_MEASURE
        }

        // -- R3..R6' syntax-driven roles --------------------------------------
        for (m in scoreable) {
            if (m.headToken < 0 || m.headToken >= tokens.size) continue
            val measure = isMeasure(m)
            val prep = prepositionOf(parse, m.headToken)
            val relation = tokens[m.headToken].depRelation
            when {
                prep != null && prep in grouping -> {
                    // "podle X" / "by X" is GROUP-BY for a dimension and ORDER-BY for a measure.
                    // The measure case needs no extra role — R2 already gave it MEASURE — and
                    // crucially must NOT become a grouping.
                    if (!measure) roles.getValue(m.id) += FrameRole.FRAME_ROLE_GROUPING
                }
                prep != null && prep in filter -> {
                    if (!measure) roles.getValue(m.id) += FrameRole.FRAME_ROLE_FILTER
                }
                prep == null && !measure ->
                    when {
                        // A noun premodifying another noun restricts it: "WEB revenue".
                        relation == "compound" -> roles.getValue(m.id) += FrameRole.FRAME_ROLE_FILTER
                        // A bare oblique with no preposition is an adverbial restriction:
                        // "minulý TÝDEN". With a preposition it was caught above.
                        relation.startsWith("obl") -> roles.getValue(m.id) += FrameRole.FRAME_ROLE_FILTER
                        // Spike F-1: a temporal adverb has no nominal head at all — `loni`
                        // ("last year") is ADV/advmod, and `obl` is a nominal relation. The
                        // report's one-line fix reads "advmod + Calendar binding"; the rules may
                        // not match a ref string, so the model fact used is the object KIND —
                        // an adverb that binds an attribute is restricting by it.
                        relation == "advmod" && m.objectKind.equals("attribute", ignoreCase = true) ->
                            roles.getValue(m.id) += FrameRole.FRAME_ROLE_FILTER
                    }
            }
        }

        // -- R7 coordination: `conj` inherits the head mention's axis role ------
        for (m in scoreable) {
            if (m.headToken < 0 || m.headToken >= tokens.size) continue
            if (tokens[m.headToken].depRelation != "conj") continue
            val parent = byHead[headIndexOf(tokens[m.headToken].depHead)] ?: continue
            for (role in listOf(FrameRole.FRAME_ROLE_GROUPING, FrameRole.FRAME_ROLE_FILTER)) {
                if (role in roles.getValue(parent.id)) roles.getValue(m.id) += role
            }
        }

        // -- R8 a mention that anchors a value is a filter axis ------------------
        // ("účtu 501001", "kategorii Elektronika"). Skipped when the mention is the clause
        // subject — "kategorie Knihy" in a share-of question is what the question is ABOUT,
        // not a restriction on it.
        for (m in scoreable) {
            if (!m.anchorsValue || m.headToken < 0 || m.headToken >= tokens.size) continue
            if (tokens[m.headToken].depRelation in SUBJECT_DEPRELS) continue
            if (FrameRole.FRAME_ROLE_GROUPING in roles.getValue(m.id)) continue
            roles.getValue(m.id) += FrameRole.FRAME_ROLE_FILTER
        }

        // -- R9 SUBJECT, last, from the residue ---------------------------------
        val candidates =
            scoreable.filter {
                FrameRole.FRAME_ROLE_FILTER !in roles.getValue(it.id) &&
                    FrameRole.FRAME_ROLE_GROUPING !in roles.getValue(it.id)
            }
        val pick =
            candidates.firstOrNull {
                it.headToken >= 0 && it.headToken < tokens.size && tokens[it.headToken].depRelation in SUBJECT_DEPRELS
            } ?: candidates.minWithOrNull(compareBy({ depth(parse, it.headToken) }, { it.charStart }))
        if (pick != null) roles.getValue(pick.id) += FrameRole.FRAME_ROLE_SUBJECT

        return roles.mapValues { (_, set) -> set.toList() }
    }

    /**
     * ⛔ **ALWAYS FALSE IN PRODUCTION, and not because anything here is unwired.**
     *
     * `objectKind` has **no source in this system** — checked against the artifacts 2026-08-14,
     * after two rounds of assuming otherwise:
     *
     *  - `meta.v1` contains **zero** occurrences of `measure`. `ObjectDescriptor.kind` comes
     *    straight from `ttr-metadata`'s `ModelObject.kind`, whose whole vocabulary is
     *    `table | view | column | procedure | foreign_key | entity | attribute | relation |
     *    role | query | drill_map | world | …` — no measure, no dimension, no cubelet. Its
     *    `SchemaCode` has no `MD` member either.
     *  - the compiled lexicon archive states a target **class**
     *    (`MODEL_OBJECT | MEMBER | OPERATOR | GROUNDING_TRIGGER`), a different axis; its reader
     *    says so in as many words.
     *  - the per-request `Registry` override has the field, and nothing populates it.
     *
     * The `md.` layer these rules were written against is authored in TTR but is **not
     * represented in the metadata model** — which is also why `TransDslRenderer.address()`
     * rejects `md.` refs outright (*"no md layer exists to say what object carries it"*).
     *
     * ⚠ **The cost is wider than R2.** This predicate also gates R3–R6, each of which reads
     * *"and NOT measure(m)"* — so with the kind blank, nothing is ever exempted from FILTER or
     * GROUPING **for being the measure**. Observed live: a measure mention comes back
     * `FRAME_ROLE_SUBJECT`, and its compound modifier takes FILTER through R5.
     *
     * ⛔ **Do not "fix" this by deriving a kind from the ref prefix.**
     * `LexiconArchiveRegistrySource` refuses to do that because it would be a second rule, free
     * to drift from the model's own — and that argument gets *stronger*, not weaker, when the
     * first rule turns out to be missing: there would be no authority to drift from at all. The
     * real fix is an md layer in the metadata model, upstream. Recorded here rather than only in
     * a tracker, because a rule that reads as merely unwired invites exactly the local fix this
     * comment refuses.
     */
    private fun isMeasure(mention: Input): Boolean = mention.objectKind.equals("measure", ignoreCase = true)

    /** The lemma of the adposition attached to [headToken] by a `case` relation, folded low. */
    private fun prepositionOf(
        parse: AnalyzeResponse,
        headToken: Int,
    ): String? =
        parse.tokensList
            .firstOrNull { headIndexOf(it.depHead) == headToken && it.depRelation == "case" }
            ?.let { (it.lemma.ifBlank { it.text }).lowercase() }

    /** Depth of a token in the dep tree; cycle-safe, because a bad parse must not hang a resolve. */
    private fun depth(
        parse: AnalyzeResponse,
        token: Int,
    ): Int {
        var current = token
        var depth = 0
        val seen = HashSet<Int>()
        while (current in parse.tokensList.indices && seen.add(current)) {
            val next = headIndexOf(parse.tokensList[current].depHead)
            if (next < 0) break
            current = next
            depth++
        }
        return depth
    }

    /** `dep_head` is 1-based with 0 for the root; the token list is 0-based. */
    private fun headIndexOf(depHead: Int): Int = depHead - 1
}
