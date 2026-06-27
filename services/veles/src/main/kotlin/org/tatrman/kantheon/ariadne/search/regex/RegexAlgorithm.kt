package org.tatrman.kantheon.ariadne.search.regex

import org.tatrman.ariadne.v1.SearchRequest
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.kantheon.ariadne.registry.RegistrySnapshot
import org.tatrman.kantheon.ariadne.search.CompileError
import org.tatrman.kantheon.ariadne.search.RebuildOutcome
import org.tatrman.kantheon.ariadne.search.SearchAlgorithm
import org.tatrman.kantheon.ariadne.search.SearchHit
import org.tatrman.kantheon.ariadne.search.SearchIndex
import org.tatrman.kantheon.ariadne.search.searchableObjects
import java.text.Normalizer

/**
 * Pattern-template matcher lifted from v0 `services/sql-pattern-service`.
 *
 * Patterns use `{name}` or `@name` placeholders. Compiled at rebuild into
 * named-capture-group regexes (Czech-aware sanitised names since
 * java.util.regex requires ASCII alphanumerics in group names). The last
 * placeholder is greedy so tail input is captured whole.
 *
 * Score per spec §6.3:
 *   1.0  — full query consumed by the regex
 *   0.85 — substring match (regex consumed less than the full query)
 *
 * Adaptations from v0:
 *  - Lowercase + NFD diacritic-fold the user query (the v0 only lowercased).
 *  - Pattern matching applies to all owner kinds (Query, Entity, Attribute,
 *    Role); extracted_parameters is only populated when the owner is a Query
 *    AND request.include_extracted_parameters is true AND the captured names
 *    correspond to declared parameters on that query. Non-Query owners
 *    always return an empty extraction map regardless of the request flag.
 *  - First-match-wins is dropped — we score every match and keep the best
 *    per object. The original pattern_index is recorded.
 */
class RegexAlgorithm : SearchAlgorithm {
    override val name: String = "regex"

    override fun rebuild(
        snapshot: RegistrySnapshot,
        language: String,
    ): RebuildOutcome {
        val compiled = mutableListOf<CompiledPattern>()
        val errors = mutableListOf<CompileError>()
        for (obj in snapshot.searchableObjects()) {
            obj.search.patterns.forEachIndexed { idx, pattern ->
                runCatching { buildCapturingRegex(pattern) }
                    .onSuccess { (regex, paramNames) ->
                        compiled +=
                            CompiledPattern(
                                regex = regex,
                                paramNames = paramNames,
                                ownerQname = obj.qname,
                                ownerKind = obj.kind,
                                patternIndex = idx,
                                source = pattern,
                            )
                    }.onFailure { ex ->
                        errors +=
                            CompileError(
                                objectQname = obj.qname,
                                kind = obj.kind,
                                field = "patterns[$idx]",
                                message = ex.message ?: ex::class.simpleName ?: "compile failure",
                            )
                    }
            }
        }
        return RebuildOutcome(RegexIndex(compiled.toList()), errors)
    }

    override fun search(
        request: SearchRequest,
        index: SearchIndex,
    ): List<SearchHit> {
        if (index !is RegexIndex) return emptyList()
        val needle = fold(request.query.trim())
        if (needle.isEmpty()) return emptyList()

        val bestPerOwner = HashMap<QualifiedName, SearchHit>()
        for (cp in index.patterns) {
            val match = cp.regex.find(needle) ?: continue
            val score =
                if (match.range.first == 0 &&
                    match.range.last == needle.length - 1
                ) {
                    FULL_MATCH
                } else {
                    SUBSTRING_MATCH
                }
            val extracted =
                if (cp.ownerKind == "query" && request.includeExtractedParameters) {
                    cp.paramNames.associateWith { name ->
                        try {
                            match.groups[sanitizeGroupName(name)]?.value?.trim() ?: ""
                        } catch (_: Exception) {
                            ""
                        }
                    }
                } else {
                    emptyMap()
                }
            val hit =
                SearchHit(
                    ownerQname = cp.ownerQname,
                    ownerKind = cp.ownerKind,
                    score = score,
                    matchedField = "pattern",
                    matchedValue = cp.source,
                    snippet = needle.substring(match.range),
                    algorithm = name,
                    patternIndex = cp.patternIndex,
                    extractedParameters = extracted,
                )
            val current = bestPerOwner[cp.ownerQname]
            if (current == null || hit.score > current.score) {
                bestPerOwner[cp.ownerQname] = hit
            }
        }
        return bestPerOwner.values.toList()
    }

    companion object {
        private const val FULL_MATCH = 1.0f
        private const val SUBSTRING_MATCH = 0.85f
        private val COMBINING_MARKS = Regex("\\p{M}+")
        private val CURLY_PARAM = Regex("""\{([\w\p{L}]+)}""")
        private val AT_PARAM = Regex("""@([\w\p{L}]+)""")

        internal fun fold(s: String): String {
            val nfd = Normalizer.normalize(s, Normalizer.Form.NFD)
            return COMBINING_MARKS.replace(nfd, "").lowercase()
        }

        /**
         * Build a regex with named capture groups from a pattern template.
         * `{name}` and `@name` placeholders become `(?<sanitised>.+?)`. The
         * LAST placeholder uses a greedy `.+` so trailing input is consumed
         * whole — matches v0 behaviour ("objednávky zákazníka kaufland a.s."
         * captures "kaufland a.s." rather than just "kaufland").
         *
         * Returns the compiled regex plus the ORIGINAL parameter names (in
         * declaration order). Callers map captures back through
         * [sanitizeGroupName] when reading the match.
         */
        internal fun buildCapturingRegex(parameterizedPattern: String): Pair<Regex, List<String>> {
            val paramNames = mutableListOf<String>()
            CURLY_PARAM.findAll(parameterizedPattern).forEach { m ->
                val n = m.groupValues[1]
                if (n !in paramNames) paramNames += n
            }
            AT_PARAM.findAll(parameterizedPattern).forEach { m ->
                val n = m.groupValues[1]
                if (n !in paramNames) paramNames += n
            }

            var tokenized = parameterizedPattern
            val tokenMap = mutableMapOf<String, String>()
            paramNames.forEachIndexed { idx, n ->
                // Token must survive `fold` (NFD + lowercase) unchanged so we can
                // still find it after fold normalises the literal pattern text.
                val token = "paramtoken${idx}paramtoken"
                tokenMap[token] = n
                tokenized = tokenized.replace("{$n}", token).replace("@$n", token)
            }
            // Fold diacritics on the literal pattern AFTER placeholders have been
            // replaced with ASCII tokens — the original Czech parameter names are
            // preserved in tokenMap and re-used as extraction keys at match time.
            // The user query is folded the same way at match time, so the literal
            // text on both sides aligns.
            val folded = fold(tokenized)
            val escaped = escapeRegexExceptTokens(folded, tokenMap.keys)
            var regexString = escaped
            for ((token, name) in tokenMap) {
                val safe = sanitizeGroupName(name)
                regexString = regexString.replace(token, "(?<$safe>.+?)")
            }
            // Greedy tail on the last placeholder — same trick as v0.
            if (paramNames.isNotEmpty()) {
                val lastSafe = sanitizeGroupName(paramNames.last())
                val lastLazy = "(?<$lastSafe>.+?)"
                if (regexString.endsWith(lastLazy)) {
                    regexString = regexString.removeSuffix(lastLazy) + "(?<$lastSafe>.+)"
                }
            }
            return Regex(regexString, RegexOption.IGNORE_CASE) to paramNames
        }

        internal fun escapeRegexExceptTokens(
            input: String,
            tokens: Set<String>,
        ): String {
            var result = input
            val safeMarkers = mutableMapOf<String, String>()
            tokens.forEachIndexed { idx, token ->
                val marker = " SAFE$idx "
                safeMarkers[marker] = token
                result = result.replace(token, marker)
            }
            result =
                result
                    .replace("\\", "\\\\")
                    .replace(".", "\\.")
                    .replace("*", "\\*")
                    .replace("+", "\\+")
                    .replace("?", "\\?")
                    .replace("[", "\\[")
                    .replace("]", "\\]")
                    .replace("^", "\\^")
                    .replace("$", "\\$")
            for ((marker, token) in safeMarkers) result = result.replace(marker, token)
            return result
        }

        /**
         * Sanitises a parameter name to a valid Java regex named-group
         * identifier (ASCII letters / digits only — no underscore). Czech
         * diacritics transliterate to closest ASCII; everything else is
         * dropped. Lifted verbatim from v0's PatternMatcher.sanitizeGroupName.
         */
        internal fun sanitizeGroupName(name: String): String =
            name
                .map { ch ->
                    when {
                        ch.isLetterOrDigit() && ch.code < 128 -> ch.toString()
                        ch == '_' -> ""
                        ch in "áàâä" -> "a"
                        ch in "éèêë" -> "e"
                        ch in "íìîï" -> "i"
                        ch in "óòôö" -> "o"
                        ch in "úùûü" -> "u"
                        ch in "ýÿ" -> "y"
                        ch == 'č' -> "c"
                        ch == 'ř' -> "r"
                        ch == 'š' -> "s"
                        ch == 'ž' -> "z"
                        ch == 'ň' -> "n"
                        ch == 'ť' -> "t"
                        ch == 'ď' -> "d"
                        ch == 'ů' -> "u"
                        ch in "ÁÀÂÄ" -> "A"
                        ch in "ÉÈÊË" -> "E"
                        ch in "ÍÌÎÏ" -> "I"
                        ch in "ÓÒÔÖ" -> "O"
                        ch in "ÚÙÛÜ" -> "U"
                        ch == 'Č' -> "C"
                        ch == 'Ř' -> "R"
                        ch == 'Š' -> "S"
                        ch == 'Ž' -> "Z"
                        ch == 'Ň' -> "N"
                        ch == 'Ť' -> "T"
                        ch == 'Ď' -> "D"
                        ch == 'Ů' -> "U"
                        else -> ""
                    }
                }.joinToString("")
    }
}

internal data class CompiledPattern(
    val regex: Regex,
    val paramNames: List<String>,
    val ownerQname: QualifiedName,
    val ownerKind: String,
    val patternIndex: Int,
    val source: String,
)

class RegexIndex internal constructor(
    internal val patterns: List<CompiledPattern>,
) : SearchIndex
