// SPDX-License-Identifier: Apache-2.0
package org.tatrman.money.recipe

import org.tatrman.grounding.v1.FilterRecipe
import org.tatrman.grounding.v1.GroundingResult
import org.tatrman.grounding.v1.JoinRecipe
import org.tatrman.grounding.v1.MoneyValue
import org.tatrman.grounding.v1.Normalized
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.JoinType
import org.tatrman.plan.v1.ParameterBinding
import org.tatrman.grounding.core.PlanExpr
import org.tatrman.grounding.core.SqlRenderer
import org.tatrman.money.discover.ColumnRef
import org.tatrman.money.discover.FxTable
import org.tatrman.money.discover.MoneyDiscovery
import org.tatrman.money.recognize.Comparator
import org.tatrman.money.recognize.MoneyAmount
import java.math.BigDecimal
import java.math.MathContext

/** The recipe builder's verdict for the caller (A10.4): a result, a column clarification, or a miss. */
sealed interface MoneyRecipe {
    data class Ok(
        val result: GroundingResult,
    ) : MoneyRecipe

    /** Multiple amount-role columns on the anchor entity — the service asks which one. */
    data class Clarify(
        val columns: List<ColumnRef>,
    ) : MoneyRecipe

    data class Ungroundable(
        val reason: String,
    ) : MoneyRecipe
}

/**
 * Turns a [MoneyAmount] + the package's metadata into a [GroundingResult] (A10.4/A10.5). Targeting:
 *  - domestic (no currency, or == default) → **FilterRecipe** on `amount_domestic` (else `amount`);
 *  - foreign + an `fx_rate` table → **FX JoinRecipe** `t.amount * fx.rate {op} {amt}` with the
 *    per-policy validity window;
 *  - foreign + no fx table + a `currency_code` column → **native FilterRecipe**
 *    `t.amount {op} {amt} AND t.currency_code = {ccy}`;
 *  - foreign + neither → Ungroundable.
 *
 * Multiple `amount` columns with no domestic column ⇒ Clarify. `sql_preview` is rendered by
 * [SqlRenderer] from the recipe's own Expression tree (single source of truth; A10.6 round-trips it).
 *
 * FX unit convention (fixture-only, per A10.1): the row's native `amount` is converted via the
 * rate whose source currency = the span's currency (`fx.from = {ccy}`, `fx.to = {domestic}`) and the
 * product compared to the threshold. **Comparison-only, no rounding** — the amount column's currency
 * and the from/to orientation are the model's responsibility.
 */
class MoneyRecipeBuilder(
    private val discovery: MoneyDiscovery,
) {
    private companion object {
        const val FACT_ALIAS = "t"
        const val FX_ALIAS = "fx"
    }

    suspend fun build(
        amount: MoneyAmount,
        pkg: String,
        defaultCurrency: String,
        tolerancePct: Double,
        fxCurrent: Boolean,
        referenceDatetime: String,
        forcedColumnName: String? = null,
    ): MoneyRecipe {
        val effectiveCcy = amount.currency ?: defaultCurrency
        val cols = discovery.amountColumns(pkg)
        val normalized = normalized(amount, effectiveCcy, tolerancePct)
        val isDomestic = amount.currency == null || amount.currency.equals(defaultCurrency, ignoreCase = true)

        if (isDomestic) {
            if (cols.domestic == null && cols.amount.size > 1) {
                val forced = forcedColumn(cols.amount, forcedColumnName) ?: return MoneyRecipe.Clarify(cols.amount)
                return MoneyRecipe.Ok(domesticRecipe(forced, amount, tolerancePct, normalized))
            }
            val col =
                cols.domestic ?: cols.amount.firstOrNull()
                    ?: return MoneyRecipe.Ungroundable("package '$pkg' has no amount / amount_domestic column")
            return MoneyRecipe.Ok(domesticRecipe(col, amount, tolerancePct, normalized))
        }

        // foreign currency — convert via an fx table, else a native currency filter, else give up.
        val fx = discovery.fxTable(pkg)
        if (fx != null) {
            val amountCol =
                when (val pick = pickAmountColumn(cols.amount, forcedColumnName)) {
                    is AmountPick.Single -> pick.column
                    is AmountPick.Ambiguous -> return MoneyRecipe.Clarify(pick.columns)
                    AmountPick.None ->
                        cols.domestic
                            ?: return MoneyRecipe.Ungroundable("package '$pkg' has no amount column for FX conversion")
                }
            val eventDate = discovery.eventDateColumn(pkg)
            return fxJoinRecipe(
                fx,
                amountCol,
                eventDate,
                amount,
                effectiveCcy,
                defaultCurrency,
                tolerancePct,
                fxCurrent,
                referenceDatetime,
                normalized,
            )
        }
        if (cols.currencyCode != null) {
            val amountCol =
                when (val pick = pickAmountColumn(cols.amount, forcedColumnName)) {
                    is AmountPick.Single -> pick.column
                    is AmountPick.Ambiguous -> return MoneyRecipe.Clarify(pick.columns)
                    AmountPick.None ->
                        return MoneyRecipe.Ungroundable(
                            "package '$pkg' has no native amount column for a currency filter",
                        )
                }
            return MoneyRecipe.Ok(
                nativeForeignRecipe(amountCol, cols.currencyCode, amount, effectiveCcy, tolerancePct, normalized),
            )
        }
        return MoneyRecipe.Ungroundable(
            "foreign currency $effectiveCcy but package '$pkg' has no fx_rate table or currency_code column",
        )
    }

    private sealed interface AmountPick {
        data class Single(
            val column: ColumnRef,
        ) : AmountPick

        data class Ambiguous(
            val columns: List<ColumnRef>,
        ) : AmountPick

        data object None : AmountPick
    }

    /** Resolve the native amount column: the forced one, the sole one, ambiguous, or none. */
    private fun pickAmountColumn(
        amounts: List<ColumnRef>,
        forced: String?,
    ): AmountPick =
        when {
            forced != null ->
                amounts.firstOrNull { it.columnName == forced }?.let { AmountPick.Single(it) }
                    ?: AmountPick.None
            amounts.size == 1 -> AmountPick.Single(amounts.first())
            amounts.size > 1 -> AmountPick.Ambiguous(amounts)
            else -> AmountPick.None
        }

    private fun forcedColumn(
        columns: List<ColumnRef>,
        forced: String?,
    ): ColumnRef? = forced?.let { name -> columns.firstOrNull { it.columnName == name } }

    // ----- domestic → FilterRecipe on amount_domestic / amount -----

    private fun domesticRecipe(
        col: ColumnRef,
        amount: MoneyAmount,
        tolerancePct: Double,
        normalized: Normalized,
    ): GroundingResult {
        val left = PlanExpr.col(FACT_ALIAS, col.columnName)
        val (condition, params) = comparison(left, amount, tolerancePct)
        val filter =
            FilterRecipe
                .newBuilder()
                .setCondition(condition)
                .addAllParameters(params)
                .setAnchorColumn(col.qname)
                .build()
        return result(normalized, amount, SqlRenderer.render(condition), describe(amount)) { setFilter(filter) }
    }

    // ----- foreign, no fx table → native FilterRecipe with a currency predicate -----

    private fun nativeForeignRecipe(
        amountCol: ColumnRef,
        currencyCol: ColumnRef,
        amount: MoneyAmount,
        currency: String,
        tolerancePct: Double,
        normalized: Normalized,
    ): GroundingResult {
        val left = PlanExpr.col(FACT_ALIAS, amountCol.columnName)
        val (amtCond, amtParams) = comparison(left, amount, tolerancePct)
        val ccyCond =
            PlanExpr.eq(PlanExpr.col(FACT_ALIAS, currencyCol.columnName, "text"), PlanExpr.param("ccy", "text"))
        val condition = PlanExpr.and(amtCond, ccyCond)
        val params = amtParams + PlanExpr.textParam("ccy", currency, "Currency")
        val filter =
            FilterRecipe
                .newBuilder()
                .setCondition(condition)
                .addAllParameters(params)
                .setAnchorColumn(amountCol.qname)
                .build()
        return result(normalized, amount, SqlRenderer.render(condition), "${describe(amount)} in $currency") {
            setFilter(filter)
        }
    }

    // ----- foreign, fx table → JoinRecipe converting amount * rate -----

    @Suppress("LongParameterList")
    private fun fxJoinRecipe(
        fx: FxTable,
        amountCol: ColumnRef,
        eventDate: ColumnRef?,
        amount: MoneyAmount,
        currency: String,
        domesticCurrency: String,
        tolerancePct: Double,
        fxCurrent: Boolean,
        referenceDatetime: String,
        normalized: Normalized,
    ): MoneyRecipe {
        val converted =
            PlanExpr.mul(
                PlanExpr.col(FACT_ALIAS, amountCol.columnName),
                PlanExpr.col(FX_ALIAS, fx.rate.columnName),
            )
        val (filterCond, filterParams) = comparison(converted, amount, tolerancePct)

        val onBase =
            PlanExpr.and(
                PlanExpr.eq(PlanExpr.col(FX_ALIAS, fx.fromCurrency.columnName, "text"), PlanExpr.param("ccy", "text")),
                PlanExpr.eq(
                    PlanExpr.col(FX_ALIAS, fx.toCurrency.columnName, "text"),
                    PlanExpr.param("domestic", "text"),
                ),
            )
        val validityParams = mutableListOf<ParameterBinding>()
        val onCondition =
            when (val v = withValidity(onBase, fx, eventDate, fxCurrent, referenceDatetime, validityParams)) {
                is Validity.Resolved -> v.condition
                is Validity.Unavailable -> return MoneyRecipe.Ungroundable(v.reason)
            }

        val params =
            listOf(
                PlanExpr.textParam("ccy", currency, "Source currency"),
                PlanExpr.textParam("domestic", domesticCurrency, "Domestic currency"),
            ) + validityParams + filterParams

        val join =
            JoinRecipe
                .newBuilder()
                .setEntity(fx.entity)
                .setJoinType(JoinType.INNER)
                .setOnCondition(onCondition)
                .setFilter(filterCond)
                .addAllParameters(params)
                .setSuggestedAlias(FX_ALIAS)
                .build()
        val sql = SqlRenderer.renderJoin(fx.entityName, FX_ALIAS, onCondition, filterCond)
        val policy = if (fxCurrent) "current rate" else "transaction-date rate"
        return MoneyRecipe.Ok(
            result(normalized, amount, sql, "${describe(amount)} converted from $currency at the $policy") {
                setJoin(join)
            },
        )
    }

    /** Outcome of resolving the fx join's temporal predicate. */
    private sealed interface Validity {
        data class Resolved(
            val condition: Expression,
        ) : Validity

        /** The rate table is time-versioned but no as-of date is available — must fail, not fan out. */
        data class Unavailable(
            val reason: String,
        ) : Validity
    }

    /**
     * Add the per-policy validity predicate to the fx join. When the rate table carries a validity
     * window, an as-of date is REQUIRED to pick the one applicable row: the reference date for the
     * current-rate policy, the fact's event_date for the transaction-date policy. Without it the
     * INNER join would match every historical rate version for the pair and multiply the fact rows —
     * so we fail loudly ([Validity.Unavailable]) rather than silently emit a fan-out join. A rate
     * table with no validity columns is a single rate per pair and needs no predicate.
     */
    private fun withValidity(
        onBase: Expression,
        fx: FxTable,
        eventDate: ColumnRef?,
        fxCurrent: Boolean,
        referenceDatetime: String,
        outParams: MutableList<ParameterBinding>,
    ): Validity {
        val from = fx.validFrom ?: return Validity.Resolved(onBase)
        val to = fx.validTo ?: return Validity.Resolved(onBase)
        val fromCol = PlanExpr.col(FX_ALIAS, from.columnName, "datetime")
        val toCol = PlanExpr.col(FX_ALIAS, to.columnName, "datetime")
        val asOf: Expression =
            if (fxCurrent) {
                if (referenceDatetime.isEmpty()) {
                    return Validity.Unavailable(
                        "fx_rate '${fx.entityName}' is time-versioned but no reference_datetime was " +
                            "supplied for the current-rate conversion (would match every rate version)",
                    )
                }
                outParams.add(PlanExpr.datetimeParam("ref", referenceDatetime, "As-of date (current rate)"))
                PlanExpr.param("ref", "datetime")
            } else {
                if (eventDate == null) {
                    return Validity.Unavailable(
                        "fx_rate '${fx.entityName}' is time-versioned but package has no transaction-date " +
                            "(event_date) column to pick the applicable rate (would match every rate version)",
                    )
                }
                PlanExpr.col(FACT_ALIAS, eventDate.columnName, "datetime")
            }
        val validity = PlanExpr.and(PlanExpr.ge(asOf, fromCol), PlanExpr.lt(asOf, toCol))
        return Validity.Resolved(PlanExpr.and(onBase, validity))
    }

    // ----- shared comparison + normalized + result -----

    /** The threshold predicate over [left]: tolerance → inclusive band; else the comparator (default GE). */
    private fun comparison(
        left: Expression,
        amount: MoneyAmount,
        tolerancePct: Double,
    ): Pair<Expression, List<ParameterBinding>> {
        if (amount.tolerance) {
            val (lower, upper) = toleranceBounds(amount.amount, tolerancePct)
            val cond =
                PlanExpr.and(
                    PlanExpr.ge(left, PlanExpr.param("lower", "decimal")),
                    PlanExpr.le(left, PlanExpr.param("upper", "decimal")),
                )
            val params =
                listOf(
                    PlanExpr.decimalParam("lower", lower, "Lower bound (inclusive)"),
                    PlanExpr.decimalParam("upper", upper, "Upper bound (inclusive)"),
                )
            return cond to params
        }
        val threshold = PlanExpr.param("amt", "decimal")
        val cond =
            when (amount.comparator) {
                Comparator.GT -> PlanExpr.gt(left, threshold)
                Comparator.LT -> PlanExpr.lt(left, threshold)
                Comparator.LE -> PlanExpr.le(left, threshold)
                Comparator.GE, null -> PlanExpr.ge(left, threshold)
            }
        return cond to listOf(PlanExpr.decimalParam("amt", amount.amount, "Threshold amount"))
    }

    private fun normalized(
        amount: MoneyAmount,
        currency: String,
        tolerancePct: Double,
    ): Normalized {
        val money =
            MoneyValue
                .newBuilder()
                .setAmount(amount.amount.stripTrailingZeros().toPlainString())
                .setCurrency(currency)
        if (amount.tolerance) {
            val (lower, upper) = toleranceBounds(amount.amount, tolerancePct)
            money.setLowerBound(lower.stripTrailingZeros().toPlainString())
            money.setUpperBound(upper.stripTrailingZeros().toPlainString())
        }
        return Normalized.newBuilder().setMoney(money).build()
    }

    private fun toleranceBounds(
        amount: BigDecimal,
        tolerancePct: Double,
    ): Pair<BigDecimal, BigDecimal> {
        val half = BigDecimal(tolerancePct).divide(BigDecimal(100), MathContext.DECIMAL64)
        val lower = amount.multiply(BigDecimal.ONE.subtract(half), MathContext.DECIMAL64)
        val upper = amount.multiply(BigDecimal.ONE.add(half), MathContext.DECIMAL64)
        return lower to upper
    }

    private fun describe(amount: MoneyAmount): String {
        val n = amount.amount.stripTrailingZeros().toPlainString()
        return if (amount.tolerance) "amounts around $n" else "amounts ${comparatorWord(amount.comparator)} $n"
    }

    private fun comparatorWord(c: Comparator?): String =
        when (c) {
            Comparator.GT -> "over"
            Comparator.LT -> "under"
            Comparator.LE -> "at most"
            Comparator.GE, null -> "at least"
        }

    private fun result(
        normalized: Normalized,
        amount: MoneyAmount,
        sqlPreview: String,
        desc: String,
        application: GroundingResult.Builder.() -> GroundingResult.Builder,
    ): GroundingResult =
        GroundingResult
            .newBuilder()
            .setNormalized(normalized)
            .apply { application() }
            .setSqlPreview(sqlPreview)
            .setConfidence(amount.confidence.toFloat())
            .setSource(GroundingResult.Source.RULES)
            .setExplanation("Resolved the monetary constraint to $desc.")
            .build()
}
