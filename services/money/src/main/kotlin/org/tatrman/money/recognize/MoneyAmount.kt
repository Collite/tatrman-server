// SPDX-License-Identifier: Apache-2.0
package org.tatrman.money.recognize

import java.math.BigDecimal

/** Threshold comparator recognized from the span: `nad`/over → GT, `pod`/under → LT, etc. */
enum class Comparator { GT, LT, GE, LE }

/**
 * A recognized monetary constraint from a MONEY span (A10.3). [amount] is precision-preserving
 * ([BigDecimal]); [currency] is null when the span names none (⇒ the request's default currency,
 * i.e. domestic). [comparator] null with [tolerance] false ⇒ the recipe defaults to GE. [tolerance]
 * marks "around X" / "kolem X" → the recipe expands it into inclusive bounds from
 * `context.tolerance_pct`. [atCurrentRate] marks "at today's rate" → forces the CURRENT fx policy.
 */
data class MoneyAmount(
    val amount: BigDecimal,
    val currency: String?,
    val comparator: Comparator?,
    val tolerance: Boolean,
    val atCurrentRate: Boolean,
    val confidence: Double,
)
