package org.tatrman.chrono.recognize

import org.tatrman.text.Normalization
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * Rule-based cs + en date/period recognizer (A8.3). Duckling's rule-table *approach* ported to
 * Kotlin (see the A8.3 spike verdict): a prioritized list of composable rules over the span,
 * every result resolved against [reference] — there is no `now()` in this class.
 *
 * Rules are tried most-specific first; the first that matches wins. Anything unrecognized returns
 * null → the caller emits UNGROUNDABLE (or, below threshold, calls the llm-gateway fallback, A8.6).
 *
 * Intervals are half-open `[start, end)` with an EXCLUSIVE end (contracts §1.1).
 */
class DateRecognizer {
    fun recognize(
        span: String,
        reference: LocalDate,
    ): ChronoRecognition? {
        val n = Normalization.fold(span).trim()
        if (n.isEmpty()) return null
        val target = detectTarget(n)
        val base =
            fiscalYear(n)
                ?: periodCode(n)
                ?: isoDate(n)
                ?: numericDate(n, reference)
                ?: namedMonthDate(n, reference)
                ?: relative(n, reference)
                ?: return null
        return if (target != null) base.copy(target = target) else base
    }

    // ----- date-role targeting (which column, not which interval) -----

    private fun detectTarget(n: String): DateTarget? =
        when {
            hasAny(n, "due", "splatn") -> DateTarget.DUE
            hasAny(n, "posted", "posting", "zauctov") -> DateTarget.POSTING
            hasAny(n, "document date", "doc date", "datum dokladu", "datum vystaveni") -> DateTarget.DOCUMENT
            else -> null
        }

    // ----- fiscal year: "fiscal year 2026" / "fiskalni rok 2026" -----

    private val fiscalYearRe = Regex("""(?:fiscal|financial|fiskaln\w*|financn\w*|ucetni)\s+(?:year|rok)\s+(\d{4})""")

    private fun fiscalYear(n: String): ChronoRecognition? {
        val y =
            fiscalYearRe
                .find(n)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull() ?: return null
        return yearInterval(y, ChronoKind.FISCAL_YEAR, 0.95)
    }

    // ----- period code: "202605", "period 202605", "obdobi 202605" -----

    private val sixDigit = Regex("""\b(\d{6})\b""")

    private fun periodCode(n: String): ChronoRecognition? {
        val m = sixDigit.find(n) ?: return null
        val code = m.groupValues[1]
        val year = code.substring(0, 4).toInt()
        val month = code.substring(4, 6).toInt()
        if (month !in 1..12 || year !in 1900..2999) return null
        val explicit = hasAny(n, "period", "obdobi")
        // A bare 6-digit run is ambiguous with document/order ids ("doklad 200312", "objednávka
        // 202612"). Only read it as an accounting period when the span says so ("period"/"období") or
        // when the code stands alone as the whole span — otherwise fall through (→ LLM fallback), so
        // an embedded id is never silently grounded as a month at 0.9 confidence.
        if (!explicit && n.trim() != code) return null
        return monthInterval(year, month, ChronoKind.PERIOD, if (explicit) 0.97 else 0.9, code)
    }

    // ----- ISO date: 2026-03-15 -----

    private val isoRe = Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""")

    private fun isoDate(n: String): ChronoRecognition? {
        val m = isoRe.find(n) ?: return null
        val d =
            runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
                .getOrNull() ?: return null
        return dayInterval(d, 0.97)
    }

    // ----- numeric cs/en date: 15.3.2026 · 15.3. · 15/3/2026 -----

    // Tolerates spaces around separators — Czech dates are often written "15. 3. 2026".
    private val dmyRe = Regex("""\b(\d{1,2})\s*[./]\s*(\d{1,2})(?:\s*[./]\s*(\d{4}))?\.?""")

    private fun numericDate(
        n: String,
        reference: LocalDate,
    ): ChronoRecognition? {
        val m = dmyRe.find(n) ?: return null
        val day = m.groupValues[1].toInt()
        val month = m.groupValues[2].toInt()
        val year = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt() ?: reference.year
        if (month !in 1..12 || day !in 1..31) return null
        val d = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return null
        // Slightly lower confidence when the year was inferred (no explicit year in the span).
        return dayInterval(d, if (m.groupValues[3].isEmpty()) 0.9 else 0.96)
    }

    // ----- named month: "March 15 2026" · "15. května" · "May 2026" · "May period" -----

    private val yearRe = Regex("""\b(\d{4})\b""")
    private val dayRe = Regex("""\b(\d{1,2})\b""")

    private fun namedMonthDate(
        n: String,
        reference: LocalDate,
    ): ChronoRecognition? {
        val month = Months.find(n) ?: return null
        val year =
            yearRe
                .find(n)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
        // A 1–2 digit token → the day-of-month; else month granularity (a period). The year is only
        // ever a 4-digit token (yearRe), and `\b\d{1,2}\b` cannot match digits inside it, so no
        // value-based exclusion is needed — filtering out days that merely equal year%100 (e.g. the
        // 26th in "May 26 2026") only dropped legitimate days and collapsed them to a whole month.
        val day =
            dayRe
                .findAll(n)
                .map { it.groupValues[1].toInt() }
                .firstOrNull { it in 1..31 }
        return if (day != null) {
            val d = runCatching { LocalDate.of(year ?: reference.year, month, day) }.getOrNull() ?: return null
            dayInterval(d, if (year != null) 0.95 else 0.85)
        } else if (year != null) {
            monthPeriod(year, month, 0.9)
        } else if (month > reference.monthValue) {
            // A bare FUTURE month (no year) is genuinely ambiguous: this year (upcoming) or last
            // year (most recent past). Primary = this year; the alternative drives A8.6 clarification.
            monthPeriod(reference.year, month, 0.6)
                .copy(alternatives = listOf(monthPeriod(reference.year - 1, month, 0.6)))
        } else {
            // A bare past/current month resolves to this year unambiguously.
            monthPeriod(reference.year, month, 0.85)
        }
    }

    private fun monthPeriod(
        year: Int,
        month: Int,
        confidence: Double,
    ): ChronoRecognition = monthInterval(year, month, ChronoKind.PERIOD, confidence, "%04d%02d".format(year, month))

    // ----- relative: today/yesterday/tomorrow · this/last week/month/year · last N days/months -----

    private val lastNRe =
        Regex("""(?:last|past|poslednic?h?|minul\w*)\s+(\d{1,3})\s+(day|days|month|months|den|dn[iíuů]|mesic\w*)""")

    private fun relative(
        n: String,
        reference: LocalDate,
    ): ChronoRecognition? {
        when {
            hasAny(n, "today", "dnes") -> return dayInterval(reference, 0.95)
            hasAny(n, "yesterday", "vcera") -> return dayInterval(reference.minusDays(1), 0.95)
            hasAny(n, "tomorrow", "zitra") -> return dayInterval(reference.plusDays(1), 0.95)
        }
        lastNRe.find(n)?.let { m ->
            val count = m.groupValues[1].toLong()
            val unit = m.groupValues[2]
            val start =
                if (unit.startsWith("month") || unit.startsWith("mesic")) {
                    reference.minusMonths(count)
                } else {
                    reference.minusDays(count)
                }
            return ChronoRecognition(start, reference.plusDays(1), ChronoKind.RELATIVE, 0.85)
        }
        val thisScope = hasAny(n, "this", "tento", "tato", "letos") // "letos" = this year
        val lastScope = hasAny(n, "last", "minul", "loni", "predchoz") // "loni" = last year
        if (!thisScope && !lastScope) return null
        val delta = if (lastScope) -1L else 0L
        return when {
            hasAny(n, "week", "tyden", "tydnu") -> weekInterval(reference, delta)
            hasAny(n, "month", "mesic") -> {
                val base = reference.plusMonths(delta)
                // Carry a period code (yyyyMM) so a period-coded package (e.g. an
                // accounting ledger keyed by period, issue #140) can bind "minulý
                // měsíc" to its period; kind stays RELATIVE so a date-column fact
                // still gets an interval filter.
                monthInterval(
                    base.year,
                    base.monthValue,
                    ChronoKind.RELATIVE,
                    0.9,
                    "%04d%02d".format(base.year, base.monthValue),
                )
            }
            hasAny(n, "year", "rok", "letos", "loni") ->
                yearInterval(reference.year + delta.toInt(), ChronoKind.RELATIVE, 0.9)
            else -> null
        }
    }

    // ----- interval constructors -----

    private fun dayInterval(
        d: LocalDate,
        confidence: Double,
    ) = ChronoRecognition(d, d.plusDays(1), ChronoKind.ABSOLUTE, confidence)

    private fun monthInterval(
        year: Int,
        month: Int,
        kind: ChronoKind,
        confidence: Double,
        code: String? = null,
    ): ChronoRecognition {
        val start = LocalDate.of(year, month, 1)
        return ChronoRecognition(start, start.plusMonths(1), kind, confidence, periodCode = code)
    }

    private fun yearInterval(
        year: Int,
        kind: ChronoKind,
        confidence: Double,
    ): ChronoRecognition {
        val start = LocalDate.of(year, 1, 1)
        return ChronoRecognition(start, start.plusYears(1), kind, confidence)
    }

    private fun weekInterval(
        reference: LocalDate,
        weekDelta: Long,
    ): ChronoRecognition {
        val monday = reference.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(weekDelta)
        return ChronoRecognition(monday, monday.plusWeeks(1), ChronoKind.RELATIVE, 0.9)
    }

    private fun hasAny(
        n: String,
        vararg needles: String,
    ): Boolean = needles.any { n.contains(it) }
}
