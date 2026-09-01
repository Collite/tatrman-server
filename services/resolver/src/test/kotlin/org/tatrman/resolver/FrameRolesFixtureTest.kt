// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.pipeline.FrameRolePreps
import org.tatrman.resolver.pipeline.FrameRoles
import org.tatrman.resolver.v1.FrameRole
import org.tatrman.resolver.v1.TargetClass
import org.tatrman.ttr.semantics.semanticsblock.MentionKinds

/**
 * RV-P2.1.T5 — the Q-15 spike's own corpus, re-run in process against the ported rules.
 *
 * This is the spike's gate, not a report generator: it asserts **at least** the numbers
 * `results-main.json` / `results-holdout.json` recorded, per role, so the port cannot quietly
 * score below the evidence Q-15 was ruled on. See `frame-roles/PROVENANCE.md` for what was
 * copied, the two known misses, and the one structural difference from the spike harness.
 *
 * The held-out corpus is the one to read: it was authored after the rules were frozen and run
 * once. It also scores ABOVE its recorded floor here, because the port applies the report's
 * handoff item 4 (the F-1 `advmod` one-liner) that the spike deliberately left off so its
 * out-of-sample number would stay honest.
 */
class FrameRolesFixtureTest :
    StringSpec({

        val preps = FrameRolePreps.shipped()

        listOf(
            Corpus("fixtures.yaml", "results-main.json", knownMisses = 1),
            Corpus("holdout.yaml", "results-holdout.json", knownMisses = 0),
        ).forEach { corpus ->
            "${corpus.fixtures}: every role scores at least what the spike recorded" {
                val fixtures = load(corpus.fixtures)
                val recorded = recorded(corpus.results)
                val score = Score()

                for (fixture in fixtures) {
                    val text = fixture["text"] as String
                    val lang = fixture["lang"] as String
                    val parse = parseOf(text, lang)
                    val mentions = fixture.list("mentions")
                    val values = fixture.list("values")

                    val anchors = values.mapNotNull { it["anchor"] as String? }.toSet()
                    val model = modelFacts(fixture)
                    val inputs =
                        mentions.mapIndexed { i, mention ->
                            val span = mention["span"] as String
                            val (start, end) = locate(text, span, (mention["occurrence"] as Int?) ?: 1)
                            val binding = mention["binding"] as Map<*, *>?
                            FrameRoles.Input(
                                id = "${fixture["id"]}#$i",
                                charStart = start,
                                headToken = headToken(parse, start, end),
                                targetClass = targetClass(binding?.get("target_class") as String?),
                                objectKind = objectKind(binding, model, "${fixture["id"]}#$i"),
                                anchorsValue = span in anchors,
                            )
                        }

                    val derived = FrameRoles.derive(inputs, parse, lang, preps)
                    mentions.forEachIndexed { i, mention ->
                        val gold = (mention["roles"] as List<*>? ?: emptyList<String>()).map { it as String }.toSet()
                        val predicted = derived["${fixture["id"]}#$i"].orEmpty().map { it.shortName() }.toSet()
                        score.add("${fixture["id"]}#$i", mention["span"] as String, gold, predicted)
                    }
                }

                withClue(score) {
                    for (role in ROLES) {
                        // The spike recorded to four decimals (0.9737); compare at the precision it
                        // published, or an exactly-reproduced score fails on the rounding alone.
                        round4(score.precision(role)) shouldBeGreaterThanOrEqual recorded.getValue(role).first
                        round4(score.recall(role)) shouldBeGreaterThanOrEqual recorded.getValue(role).second
                    }
                    // The SUBJECT bar Q-15 was ruled on, restated as a floor of its own so a
                    // regression cannot hide behind a recorded number being lowered.
                    score.precision("SUBJECT") shouldBeGreaterThanOrEqual 0.85
                    score.mismatches.size shouldBe corpus.knownMisses
                }
            }
        }

        // --- MS-P3·S4 — the mention-facet corpus (contracts §8.5) ----------------------------

        "ms.yaml: every mention's roles are EXACTLY what the fixture says" {
            // Stricter than the two frozen corpora on purpose. Those assert "at least the score
            // the spike recorded", which is the right shape for a regression floor over evidence
            // someone else gathered; this corpus was authored WITH the rules it tests, so a floor
            // would let a wrong answer through as long as three others were right.
            for (fixture in load("ms.yaml")) {
                val text = fixture["text"] as String
                val lang = fixture["lang"] as String
                val parse = parseOf(text, lang)
                val mentions = fixture.list("mentions")
                val anchors = fixture.list("values").mapNotNull { it["anchor"] as String? }.toSet()
                val model = modelFacts(fixture)
                val inputs =
                    mentions.mapIndexed { i, mention ->
                        val span = mention["span"] as String
                        val (start, end) = locate(text, span, (mention["occurrence"] as Int?) ?: 1)
                        val binding = mention["binding"] as Map<*, *>?
                        FrameRoles.Input(
                            id = "${fixture["id"]}#$i",
                            charStart = start,
                            headToken = headToken(parse, start, end),
                            targetClass = targetClass(binding?.get("target_class") as String?),
                            objectKind = objectKind(binding, model, "${fixture["id"]}#$i"),
                            anchorsValue = span in anchors,
                        )
                    }
                val derived = FrameRoles.derive(inputs, parse, lang, preps)
                mentions.forEachIndexed { i, mention ->
                    val id = "${fixture["id"]}#$i"
                    val gold = (mention["roles"] as List<*>? ?: emptyList<String>()).map { it as String }.toSet()
                    val predicted = derived[id].orEmpty().map { it.shortName() }.toSet()
                    withClue("$id '${mention["span"]}'") { predicted shouldBe gold }
                }
            }
        }

        "ms.yaml derives its kinds through the REAL MentionKinds table, and they are the four" {
            // The point of §8.5: the fixture states model FACTS and the shipped table turns them
            // into a kind. If this corpus could state a kind directly, it would be scoring the
            // rules against a vocabulary no producer has ever emitted — which is the half of
            // issue #69 the frozen corpora were guilty of for a year.
            val kinds =
                load("ms.yaml")
                    .flatMap { fixture ->
                        val model = modelFacts(fixture)
                        fixture.list("mentions").map { objectKind(it["binding"] as Map<*, *>?, model, "ms") }
                    }.toSet()
            kinds shouldBe setOf("", "entity_with_measures", "attribute", "entity")
        }

        "no fixture in ANY corpus states object_kind — the loader rejects it, this says why" {
            // Belt and braces with the `require` in `objectKind`: that one fires when a fixture is
            // loaded, this one names the file and the rule for whoever added the line. Both exist
            // because "the fixture supplies the kind by hand" is the habit MS is here to end, and
            // habits come back.
            // the KEY, not the word: ms.yaml's own header prose names it while forbidding it
            for (file in listOf("fixtures.yaml", "holdout.yaml", "ms.yaml")) {
                withClue(file) { resource(file).contains("object_kind:") shouldBe false }
            }
        }

        "the rules read the language's own prepositions, not one hardcoded table" {
            // `by` groups in English and means nothing in the Czech table; `podle` the reverse.
            // The tables are config (frame-roles.conf) precisely so the next language is data.
            preps.grouping("en") shouldBe setOf("by")
            preps.grouping("cs") shouldBe setOf("podle", "dle")
            // an unlisted language borrows the conservative table rather than the Czech one
            preps.grouping("pl") shouldBe preps.grouping("en")
            preps.filter("cs-CZ") shouldBe preps.filter("cs")
        }
    }) {
    private data class Corpus(
        val fixtures: String,
        val results: String,
        val knownMisses: Int,
    )

    private class Score {
        val tp = ROLES.associateWith { 0 }.toMutableMap()
        val fp = ROLES.associateWith { 0 }.toMutableMap()
        val fn = ROLES.associateWith { 0 }.toMutableMap()
        val mismatches = mutableListOf<String>()

        fun add(
            id: String,
            span: String,
            gold: Set<String>,
            predicted: Set<String>,
        ) {
            for (role in ROLES) {
                when {
                    role in gold && role in predicted -> tp[role] = tp.getValue(role) + 1
                    role in predicted -> fp[role] = fp.getValue(role) + 1
                    role in gold -> fn[role] = fn.getValue(role) + 1
                }
            }
            if (gold != predicted) mismatches += "$id ${span.let { "'$it'" }} gold=$gold predicted=$predicted"
        }

        /**
         * No predictions at all is a FAILURE to predict, not perfect precision — a vacuous
         * 1.000 would let a stub pass the SUBJECT gate. The spike harness scores it the same way.
         */
        fun precision(role: String): Double {
            val denominator = tp.getValue(role) + fp.getValue(role)
            if (denominator > 0) return tp.getValue(role).toDouble() / denominator
            return if (tp.getValue(role) + fn.getValue(role) == 0) 1.0 else 0.0
        }

        fun recall(role: String): Double {
            val denominator = tp.getValue(role) + fn.getValue(role)
            return if (denominator > 0) tp.getValue(role).toDouble() / denominator else 1.0
        }

        override fun toString(): String =
            ROLES.joinToString("\n") {
                "%-9s P=%.3f R=%.3f  tp=%d fp=%d fn=%d".format(
                    it,
                    precision(it),
                    recall(it),
                    tp.getValue(it),
                    fp.getValue(it),
                    fn.getValue(it),
                )
            } + "\nmismatches:\n" + mismatches.joinToString("\n").ifBlank { "  (none)" }
    }

    companion object {
        private val ROLES = listOf("SUBJECT", "MEASURE", "FILTER", "GROUPING")
        private val yaml = ObjectMapper(YAMLFactory())
        private val json = ObjectMapper()

        private fun resource(name: String): String =
            FrameRolesFixtureTest::class.java.getResource("/frame-roles/$name")?.readText()
                ?: error("missing test resource /frame-roles/$name")

        @Suppress("UNCHECKED_CAST")
        private fun load(file: String): List<Map<String, Any?>> =
            (yaml.readValue(resource(file), Map::class.java)["fixtures"] as List<Map<String, Any?>>)

        /** `{role -> (precision, recall)}` as the spike recorded them. */
        private fun recorded(file: String): Map<String, Pair<Double, Double>> {
            val roles = json.readTree(resource(file))["roles"]
            return ROLES.associateWith { role ->
                roles[role]["precision"].asDouble() to roles[role]["recall"].asDouble()
            }
        }

        /**
         * The cached Stanza parse, keyed the way the spike's `parse_client` keyed it. `depHeadIdx`
         * is the spike's flattened 0-based head; the proto carries the 1-based `dep_head`, so the
         * topology the Kotlin rules walk is byte-identical to the one the Python rules walked.
         */
        private fun parseOf(
            text: String,
            lang: String,
        ): AnalyzeResponse {
            val key =
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest("$lang\u0000$text".toByteArray())
                    .joinToString("") { "%02x".format(it) }
                    .take(16)
            val doc = json.readTree(resource("parses/$key.json"))
            val builder = AnalyzeResponse.newBuilder().setLanguage(lang).setDetectedLanguage(lang)
            for (token in doc["tokens"]) {
                builder.addTokens(
                    Token
                        .newBuilder()
                        .setText(token["text"].asText())
                        .setCharStart(token["charStart"].asInt())
                        .setCharEnd(token["charEnd"].asInt())
                        .setLemma(token["lemma"].asText(""))
                        .setUpos(token["upos"].asText(""))
                        .setDepHead(token["depHeadIdx"].asInt(-1) + 1)
                        .setDepRelation(token["depRelation"].asText("")),
                )
            }
            return builder.build()
        }

        private fun locate(
            text: String,
            span: String,
            occurrence: Int,
        ): Pair<Int, Int> {
            var start = -1
            repeat(occurrence) {
                start = text.indexOf(span, start + 1)
                require(start >= 0) { "span '$span' (occurrence $occurrence) not in '$text'" }
            }
            return start to start + span.length
        }

        /** The head of a multi-token span: the one whose own head lies outside it (spike parity). */
        private fun headToken(
            parse: AnalyzeResponse,
            start: Int,
            end: Int,
        ): Int {
            val inside =
                parse.tokensList.indices.filter {
                    parse.getTokens(it).charStart < end && parse.getTokens(it).charEnd > start
                }
            if (inside.isEmpty()) return -1
            return inside.firstOrNull { parse.getTokens(it).depHead - 1 !in inside } ?: inside.last()
        }

        private fun round4(value: Double): Double = Math.round(value * 10_000.0) / 10_000.0

        /**
         * MS-P3·S4 (contracts §8.5) — a mention's `objectKind`, DERIVED rather than declared.
         *
         * Issue #69's second problem was this corpus: every fixture hand-supplied `object_kind`,
         * so the rules were scored against kinds no producer had ever computed, and R2 could be
         * "passing" on 39 fixtures while being dead in every deployment. The fixture now states
         * the MODEL FACTS — which node the ref is, who owns it, whether the owner lists it as a
         * measure — and the kind comes out of the real [MentionKinds] table, the same one the
         * lexicon compiler calls.
         *
         * A ref with no `model:` entry derives `""`: the estate that declared no mention facet,
         * representable in a fixture exactly as it is in an archive.
         */
        private fun objectKind(
            binding: Map<*, *>?,
            model: Map<String, MentionKinds.ObjectFacts>,
            mentionId: String,
        ): String {
            require(binding == null || !binding.containsKey("object_kind")) {
                "$mentionId states `object_kind` directly. Fixtures declare MODEL FACTS in the " +
                    "fixture's `model:` map and let MentionKinds derive the kind — see " +
                    "frame-roles/PROVENANCE.md §MS. A stated kind is a kind no producer computed."
            }
            val ref = binding?.get("ref") as String? ?: return ""
            return model[ref]?.let { MentionKinds.of(it) } ?: ""
        }

        /** The fixture's `model:` section: ref → the facts the model graph would state. */
        private fun modelFacts(fixture: Map<String, Any?>): Map<String, MentionKinds.ObjectFacts> {
            val declared = fixture["model"] as Map<*, *>? ?: return emptyMap()
            return declared.entries.associate { (ref, facts) ->
                val f = facts as Map<*, *>
                ref as String to
                    MentionKinds.ObjectFacts(
                        isAttribute = f["isAttribute"] as Boolean? ?: false,
                        ownerRef = f["ownerRef"] as String?,
                        listedAsMeasure = f["listedAsMeasure"] as Boolean? ?: false,
                        ownerHasMeasures = f["ownerHasMeasures"] as Boolean? ?: false,
                    )
            }
        }

        private fun targetClass(name: String?): TargetClass =
            when (name) {
                "OPERATOR" -> TargetClass.TARGET_CLASS_OPERATOR
                "MEMBER" -> TargetClass.TARGET_CLASS_MEMBER
                "MODEL_OBJECT" -> TargetClass.TARGET_CLASS_MODEL_OBJECT
                "GROUNDING_TRIGGER" -> TargetClass.TARGET_CLASS_GROUNDING_TRIGGER
                else -> TargetClass.TARGET_CLASS_UNSPECIFIED
            }

        private fun FrameRole.shortName(): String = name.removePrefix("FRAME_ROLE_")

        @Suppress("UNCHECKED_CAST")
        private fun Map<String, Any?>.list(key: String): List<Map<String, Any?>> =
            (this[key] as List<Map<String, Any?>>?).orEmpty()
    }
}
