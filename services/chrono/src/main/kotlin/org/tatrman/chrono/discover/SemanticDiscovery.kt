// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.discover

import org.tatrman.chrono.recognize.DateTarget
import org.tatrman.plan.v1.QualifiedName

/** An ER column resolved from metadata: its qname plus the owning-entity + column names for rendering. */
data class ColumnRef(
    val qname: QualifiedName,
    val entityName: String,
    val columnName: String,
)

/**
 * The package's period table (`semantics{kind: period_table}`) and its role columns. Absent columns
 * are null — the recipe builder chooses FilterRecipe-with-functions vs JoinRecipe on their presence.
 */
data class PeriodTable(
    val entity: QualifiedName,
    val entityName: String,
    val start: ColumnRef?,
    val end: ColumnRef?,
    val code: ColumnRef?,
    /** `code_format` from the period_code attribute's semantics, else the service default. */
    val codeFormat: String,
)

/**
 * The metadata seam (RG-P3.S0.T5 / G2 closure) — grounding discovery as a **domain-typed port**.
 * SemanticDiscovery returns the recipe layer's domain types ([PeriodTable]/[ColumnRef]); the raw
 * `org.tatrman.meta.v1` reads live behind a single adapter ([MetaV1SemanticDiscovery]), so the rest
 * of the grounding tree never touches metadata protos and the tests back it with domain objects.
 */
interface SemanticDiscovery {
    /** The package's period table, or null when it has none (⇒ calendar-aligned FilterRecipe path). */
    suspend fun periodTable(pkg: String): PeriodTable?

    /**
     * The fact column the recognized interval constrains: the wording's explicit date role
     * ([DateTarget]) if any, else the package's `event_date` column. Null when neither is present.
     */
    suspend fun anchorColumn(
        pkg: String,
        target: DateTarget?,
    ): ColumnRef?

    /** Liveness probe surfaced through GetStatus (the metadata backend's `model_loaded`). */
    suspend fun probeReady(): Boolean
}

/** Fixture/degrade discovery — ready, but surfaces nothing (no grounding without real metadata). */
object EmptySemanticDiscovery : SemanticDiscovery {
    override suspend fun periodTable(pkg: String): PeriodTable? = null

    override suspend fun anchorColumn(
        pkg: String,
        target: DateTarget?,
    ): ColumnRef? = null

    override suspend fun probeReady(): Boolean = true
}
