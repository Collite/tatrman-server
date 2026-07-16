// SPDX-License-Identifier: Apache-2.0
package org.tatrman.geo.parse

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

    fun parse(span: String): GeoQuery? {
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
                ?.let(::cleanPlace)

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
            radius == null && place == null -> containment(trimmed)
            else -> null
        }
    }

    private fun containment(span: String): GeoQuery? {
        val place =
            inPlaceRe
                .find(span)
                ?.groupValues
                ?.get(1)
                ?.let(::cleanPlace) ?: return null
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
    private fun cleanPlace(raw: String): String? {
        val kept = mutableListOf<String>()
        for (word in raw.trim().split(Regex("\\s+"))) {
            val cleaned = word.trim('.', ',', '?', '!', ';', ':')
            if (cleaned.isEmpty()) continue
            if (Normalization.fold(cleaned) in PLACE_STOP_WORDS) break
            kept += cleaned
            if (kept.size >= MAX_PLACE_WORDS) break
        }
        return kept.joinToString(" ").ifEmpty { null }
    }

    private companion object {
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
