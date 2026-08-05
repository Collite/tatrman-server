// SPDX-License-Identifier: Apache-2.0
package org.tatrman.geo.parse

import org.tatrman.grounding.lexicon.GroundingSlice
import org.tatrman.text.Normalization

/** What the span asks for geographically. */
sealed interface GeoQuery {
    val confidence: Double

    /**
     * "POIs within 20 km of Brno" / "do 20 km od Brna" / "within 5 km of here". A radius around an
     * anchor — a resolved place [place], or the request's `here_place_ref` when [here] is true.
     */
    data class Distance(
        val place: String?,
        val here: Boolean,
        val radiusMeters: Double,
        override val confidence: Double,
    ) : GeoQuery

    /** "POIs in Brno" — inside a place's boundary (recipe built from the polygon, A9.4/A9.5). */
    data class Containment(
        val place: String,
        override val confidence: Double,
    ) : GeoQuery
}

/**
 * Rule-based cs + en parser for LOCATION spans (A9.1/A9.3). Distinguishes a distance query (has a
 * radius) from a containment query (a place with a locative preposition, no radius), and pulls the
 * radius + the raw anchor place text. Place-name *normalization* (cs declension "Brna"→Brno,
 * disambiguation) is the resolver's job, not the parser's — this keeps the anchor text verbatim.
 */
class GeoSpanParser {
    // radius: "20 km", "20km", "1,5 km", "500 m" (cs decimal comma tolerated)
    private val radiusRe = Regex("""(?i)\b(\d+(?:[.,]\d+)?)\s*(km|km\.|kilometers?|kilometru?|m|meters?|metru?)\b""")

    // anchor after a connective preposition — captured from the ORIGINAL span to keep the place name
    private val anchorRe = Regex("""(?i)\b(?:of|from|around|near|od|kolem|u)\s+(.+)$""")

    // locative "in <place>" / "v|ve <place>" for containment (no radius)
    private val inPlaceRe = Regex("""(?i)\b(?:in|v|ve)\s+(.+)$""")

    // "here" words as WHOLE words — else "somewhere"/"there" would false-match on the substring.
    private val hereRe = Regex("""\b(?:here|tady|zde|odsud|pobliz)\b""")

    /**
     * @param triggers RV-P1.6 T5 (RV-42) — the `ground:geo` slice: **category words only**
     *   ("město", "kraj", "region", "city"). The place-name gazetteer stays geo-side and is
     *   PARKED, so nothing here resolves a place differently because of a slice: a category word
     *   is used only to strip the noun off the front of an anchor ("v kraji Vysočina" → the place
     *   is "Vysočina", not "kraji Vysočina"), and a category word with no place behind it is not
     *   a query at all. With an empty slice the parser is byte-for-byte what it was.
     */
    fun parse(
        span: String,
        triggers: GroundingSlice = GroundingSlice.empty(GEO_KIND),
    ): GeoQuery? {
        val trimmed = span.trim()
        if (trimmed.isEmpty()) return null
        val n = Normalization.fold(trimmed)
        val here = hereRe.containsMatchIn(n)
        val radius = radiusMeters(trimmed)
        val place =
            anchorRe
                .find(trimmed)
                ?.groupValues
                ?.get(1)
                ?.let { cleanPlace(it, triggers) }

        return when {
            radius != null && (place != null || here) -> GeoQuery.Distance(place, here, radius, 0.9)
            // "near here" with no explicit radius — still a distance query, radius decided downstream
            radius == null && here && place == null ->
                GeoQuery.Distance(
                    null,
                    here = true,
                    radiusMeters = 0.0,
                    confidence = 0.6,
                )
            radius == null && place == null -> containment(trimmed, triggers)
            else -> null
        }
    }

    private fun containment(
        span: String,
        triggers: GroundingSlice,
    ): GeoQuery? {
        val place =
            inPlaceRe
                .find(span)
                ?.groupValues
                ?.get(1)
                ?.let { cleanPlace(it, triggers) } ?: return null
        return GeoQuery.Containment(place, 0.85)
    }

    private fun radiusMeters(span: String): Double? {
        val m = radiusRe.find(span) ?: return null
        val value = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val unit = m.groupValues[2].lowercase()
        return if (unit.startsWith("km") || unit.startsWith("kilomet")) value * 1000.0 else value
    }

    /**
     * Trim the greedy `(.+)$` capture down to the place name: keep leading tokens until a word that
     * clearly starts a new (status / date / relative) clause — "brno paid in march" → "brno", "the
     * brno airport open now" → "the brno airport". The stop set deliberately EXCLUDES the Czech place
     * connectors u/nad/pod/od (so "Újezd u Brna", "Ústí nad Labem" survive); it is a heuristic bound,
     * not a full place-name grammar — a stray trailing clause is dropped, a place name is not.
     */
    private fun cleanPlace(
        raw: String,
        triggers: GroundingSlice = GroundingSlice.empty(GEO_KIND),
    ): String? {
        val kept = mutableListOf<String>()
        for (word in raw.trim().split(Regex("\\s+"))) {
            val cleaned = word.trim('.', ',', '?', '!', ';', ':')
            if (cleaned.isEmpty()) continue
            if (Normalization.fold(cleaned) in PLACE_STOP_WORDS) break
            // RV-42 T5: a declared CATEGORY word in front of the place is not part of the name —
            // "v kraji Vysočina" asks about Vysočina, not about a place called "kraji Vysočina".
            //
            // Two guards, because a leading category word CAN be part of the name: **Město
            // Albrechtice**, **Město Touškov** and **Městec Králové** are real municipalities.
            //  1. only a LOWERCASE occurrence is dropped — Czech writes the common noun in
            //     lowercase ("v kraji Vysočina") and capitalises it when it belongs to the name
            //     ("v Městě Albrechticích"); and
            //  2. only a LEADING one, so a category word deeper in the name survives regardless.
            // A capitalised leading category word therefore falls back to the pre-RV reading,
            // which is the safe direction: the gazetteer gets the whole name, as it always did.
            if (kept.isEmpty() && cleaned.first().isLowerCase() && triggers.matches(cleaned)) continue
            kept += cleaned
            if (kept.size >= MAX_PLACE_WORDS) break
        }
        return kept.joinToString(" ").ifEmpty { null }
    }

    private companion object {
        const val GEO_KIND = "geo"

        private const val MAX_PLACE_WORDS = 5

        /**
         * Words that start a trailing clause after a place — never part of the place name. Excludes
         * the cs place connectors u/nad/pod/od, which DO occur inside Czech place names.
         */
        private val PLACE_STOP_WORDS =
            setOf(
                // en clause / status / relative starters
                "paid",
                "posted",
                "due",
                "open",
                "opened",
                "closed",
                "created",
                "issued",
                "invoiced",
                "now",
                "today",
                "yesterday",
                "that",
                "which",
                "with",
                "and",
                "or",
                "over",
                "under",
                "above",
                "below",
                "during",
                "between",
                "before",
                "after",
                "since",
                "last",
                "this",
                // cs equivalents (diacritics stripped)
                "zaplaceno",
                "zaplacene",
                "vystaveno",
                "otevreno",
                "zavreno",
                "dnes",
                "vcera",
                "nyni",
                "ktere",
                "ktery",
                "ktera",
                "nebo",
                "mezi",
                "behem",
                "posledni",
                "otevrene",
            )
    }
}
