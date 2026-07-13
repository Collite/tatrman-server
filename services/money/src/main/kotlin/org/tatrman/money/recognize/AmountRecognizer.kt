package org.tatrman.money.recognize

import java.math.BigDecimal

/**
 * Rule-based cs + en recognizer for MONEY spans (A10.3). Extracts, from a span like
 * "faktury nad 100 000", "over 5 000 EUR", "kolem 100k", "2 mil. Kč", "sto tisíc korun":
 *  - a comparator (nad/over → GT, pod/under → LT, alespoň/at-least → GE, nejvýše/at-most → LE),
 *  - a tolerance flag ("kolem"/"around"),
 *  - an "at today's rate" flag (forces CURRENT fx),
 *  - a currency (ISO code, symbol, or cs/en name; null ⇒ domestic),
 *  - the magnitude: numerals + scale words (cs tisíc/mil./mld., en k/M/bn) with locale-aware
 *    decimal/thousands separators, plus the cs word forms "sto tisíc" / "půl milionu".
 *
 * Diacritics are folded for keyword matching so cs accents are optional. Returns null when no
 * numeric magnitude can be read (caller → llm-gateway fallback).
 */
class AmountRecognizer {
    fun recognize(
        span: String,
        locale: String,
    ): MoneyAmount? {
        val norm = Diacritics.strip(span).lowercase()
        val amount = parseMagnitude(span, norm, locale) ?: return null
        val comparator = detectComparator(norm)
        val tolerance = TOLERANCE.containsMatchIn(norm)
        val atCurrentRate = CURRENT_RATE.containsMatchIn(norm)
        val currency = detectCurrency(span, norm)
        val confidence = if (comparator != null || tolerance) 0.9 else 0.65
        return MoneyAmount(amount, currency, comparator, tolerance, atCurrentRate, confidence)
    }

    // ----- comparator -----

    private fun detectComparator(norm: String): Comparator? =
        when {
            GE.containsMatchIn(norm) -> Comparator.GE
            LE.containsMatchIn(norm) -> Comparator.LE
            GT.containsMatchIn(norm) -> Comparator.GT
            LT.containsMatchIn(norm) -> Comparator.LT
            else -> null
        }

    // ----- currency -----

    private fun detectCurrency(
        original: String,
        norm: String,
    ): String? {
        // symbols read from the original text (folding would drop them)
        for ((symbol, code) in SYMBOLS) if (original.contains(symbol)) return code
        // ISO codes + cs/en names on the folded text, as whole words
        for ((re, code) in CURRENCY_WORDS) if (re.containsMatchIn(norm)) return code
        return null
    }

    // ----- magnitude -----

    private fun parseMagnitude(
        original: String,
        norm: String,
        locale: String,
    ): BigDecimal? {
        // number-anchored form: "100 000", "100k", "2 mil.", "1,5 mil", "3 mld"
        NUM_SCALE.find(norm)?.let { m ->
            val numberText = m.groupValues[1]
            if (numberText.isNotBlank()) {
                val base = parseNumber(numberText, locale) ?: return null
                return base.multiply(scaleFactor(m.groupValues[2]))
            }
        }
        // cs word form: "sto tisíc", "půl milionu", "sto"
        WORD_QUANTIFIER.find(norm)?.let { wq ->
            val base = if (wq.value.startsWith("pul")) HALF else HUNDRED
            val scale = SCALE_ONLY.find(norm.substring(wq.range.last + 1))
            // "půl" needs a scale to be a plausible amount; "sto" alone = 100
            if (scale == null) return if (base == HUNDRED) HUNDRED else null
            return base.multiply(scaleFactor(scale.value))
        }
        return null
    }

    /** Locale-aware separator handling: cs uses comma-decimal + space/dot-thousands; en dot-decimal. */
    private fun parseNumber(
        token: String,
        locale: String,
    ): BigDecimal? {
        val cleaned =
            if (locale.startsWith("cs", ignoreCase = true)) {
                token
                    .replace(" ", "")
                    .replace(" ", "")
                    .replace(".", "")
                    .replace(",", ".")
            } else {
                token.replace(" ", "").replace(" ", "").replace(",", "")
            }
        return runCatching { BigDecimal(cleaned) }.getOrNull()
    }

    private fun scaleFactor(scaleToken: String): BigDecimal {
        val s = scaleToken.trim().trimEnd('.').lowercase()
        return when {
            s.isEmpty() -> BigDecimal.ONE
            s == "k" || s.startsWith("tis") || s.startsWith("thousand") -> THOUSAND
            s == "b" ||
                s == "bn" ||
                s.startsWith(
                    "mld",
                ) ||
                s.startsWith("miliard") ||
                s.startsWith("billion") -> BILLION
            s == "m" || s == "mio" || s.startsWith("mil") || s.startsWith("million") -> MILLION
            else -> BigDecimal.ONE
        }
    }

    private companion object {
        val THOUSAND = BigDecimal(1_000)
        val MILLION = BigDecimal(1_000_000)
        val BILLION = BigDecimal(1_000_000_000)
        val HUNDRED = BigDecimal(100)
        val HALF = BigDecimal("0.5")

        // GE / LE are checked before GT / LT so "at least"/"at most" — and, crucially, the NEGATED
        // forms "no/not more than" (≤) and "no/not less than" (≥) — win over the bare "more/less
        // than" alternatives in GT/LT, which would otherwise invert the threshold direction.
        val GE =
            Regex(
                """(?i)\b(at least|no less than|not less than|no fewer than|not fewer than|""" +
                    """aspon|alespon|minimalne|nejmene|ne mene nez|>=)\b|>=""",
            )
        val LE =
            Regex(
                """(?i)\b(at most|no more than|not more than|no greater than|not greater than|""" +
                    """nejvyse|maximalne|max|ne vice nez|ne vic nez)\b|<=""",
            )
        val GT = Regex("""(?i)\b(over|above|more than|greater than|nad|vice nez|vic nez)\b|>""")
        val LT = Regex("""(?i)\b(under|below|less than|fewer than|pod|mene nez)\b|<""")
        val TOLERANCE = Regex("""(?i)\b(around|about|approximately|roughly|cca|kolem|okolo|priblizne|zhruba)\b|~""")
        val CURRENT_RATE =
            Regex("""(?i)(today'?s rate|current rate|dnesnim kurzem|aktualnim kurzem|soucasnym kurzem)""")

        // number token (digits + separators) then an OPTIONAL scale word, anchored on the number.
        val NUM_SCALE =
            Regex(
                """(?i)(\d[\d ., ]*\d|\d)\s*""" +
                    """(tisic\w*|tis\.?|thousand[s]?|milion\w*|million[s]?|mil\.?|mio|miliard\w*|""" +
                    """billion[s]?|mld\.?|bn|k|m|b)?(?:\b|\.)""",
            )
        val WORD_QUANTIFIER = Regex("""(?i)\b(sto|pul)\b""")
        val SCALE_ONLY =
            Regex("""(?i)\b(tisic\w*|thousand[s]?|milion\w*|million[s]?|mil\.?|mio|miliard\w*|billion[s]?|mld\.?)\b""")

        // symbol → ISO-4217, from the ORIGINAL span
        val SYMBOLS = listOf("€" to "EUR", "$" to "USD", "£" to "GBP", "Kč" to "CZK", "kč" to "CZK")

        // ISO codes + cs/en currency names (folded), whole-word
        val CURRENCY_WORDS =
            listOf(
                Regex("""(?i)\bczk\b""") to "CZK",
                // Explicit ISO code + cs/en names & declensions — NOT `\beur\w*\b`, which also
                // matches "europe"/"european" and mislabels a region as the EUR currency.
                Regex("""(?i)\beur\b|\beuro\b|\beura\b|\beuru\b|\beury\b|\beurech\b|\beurum\b|\beuros\b""") to "EUR",
                Regex("""(?i)\busd\b|\bdolar\w*\b|\bdollar[s]?\b""") to "USD",
                Regex("""(?i)\bgbp\b|\bliber\b|\bpound[s]?\b""") to "GBP",
                Regex("""(?i)\bpln\b|\bzloty\b|\bzlotych\b""") to "PLN",
                Regex("""(?i)\bkorun\w*\b|\bkc\b""") to "CZK",
            )
    }
}
