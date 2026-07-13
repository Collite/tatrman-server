package org.tatrman.money.recognize

import java.text.Normalizer

/** Diacritic folding so Czech spans match with or without accents ("květen" → "kveten"). */
object Diacritics {
    private val marks = Regex("\\p{M}+")

    fun strip(s: String): String = marks.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "")
}
