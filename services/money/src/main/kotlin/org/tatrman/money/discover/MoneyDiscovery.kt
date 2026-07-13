// SPDX-License-Identifier: Apache-2.0
package org.tatrman.money.discover

import org.tatrman.plan.v1.QualifiedName

/** An ER column resolved from metadata: its qname plus the owning-entity + column names for rendering. */
data class ColumnRef(
    val qname: QualifiedName,
    val entityName: String,
    val columnName: String,
)

/** The package's amount-role columns + the optional currency-code column (A10.4). */
data class AmountColumns(
    val domestic: ColumnRef?,
    val amount: List<ColumnRef>,
    val currencyCode: ColumnRef?,
)

/**
 * An FX-rate table (`semantics{kind: fx_rate}`) and its role columns (A10.5). `validFrom`/`validTo`
 * are null on a rate table with no validity window (⇒ the join omits the temporal predicate).
 */
data class FxTable(
    val entity: QualifiedName,
    val entityName: String,
    val rate: ColumnRef,
    val fromCurrency: ColumnRef,
    val toCurrency: ColumnRef,
    val validFrom: ColumnRef?,
    val validTo: ColumnRef?,
)

/**
 * The metadata seam (RG-P3.S0.T5 / G2 closure) — money's discovery as a **domain-typed port**.
 * MoneyDiscovery returns the recipe layer's domain types ([AmountColumns]/[FxTable]/[ColumnRef]);
 * the raw `org.tatrman.meta.v1` reads live behind a single adapter ([MetaV1MoneyDiscovery]), so the
 * recipe layer and every grounding test stay proto-free and back it with domain objects.
 */
interface MoneyDiscovery {
    /** The package's amount / amount_domestic role columns + optional currency_code column. */
    suspend fun amountColumns(pkg: String): AmountColumns

    /** The package's FX-rate table with rate/from/to (+ optional validity), or null when incomplete. */
    suspend fun fxTable(pkg: String): FxTable?

    /** The fact's `event_date` column — the transaction date the TRANSACTION_DATE fx policy joins on. */
    suspend fun eventDateColumn(pkg: String): ColumnRef?

    /** Liveness probe surfaced through GetStatus (the metadata backend's `model_loaded`). */
    suspend fun probeReady(): Boolean
}

/** Fixture/degrade discovery — ready, but surfaces nothing (no grounding without real metadata). */
object EmptyMoneyDiscovery : MoneyDiscovery {
    override suspend fun amountColumns(pkg: String): AmountColumns =
        AmountColumns(domestic = null, amount = emptyList(), currencyCode = null)

    override suspend fun fxTable(pkg: String): FxTable? = null

    override suspend fun eventDateColumn(pkg: String): ColumnRef? = null

    override suspend fun probeReady(): Boolean = true
}
