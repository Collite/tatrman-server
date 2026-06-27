package org.tatrman.kantheon.charon.core

import org.tatrman.charon.v1.DescribeResult

/**
 * The driver-agnostic DB-edge interfaces (charon/plan.md §4 Stage 2.1 T3 spike).
 *
 * **Spike verdict (2026-06-13, recorded in `services/charon/README.md`):** v1
 * uses plain **JDBC** behind these interfaces ([org.tatrman.kantheon.charon.endpoints.JdbcAdbcReader]
 * / [org.tatrman.kantheon.charon.endpoints.JdbcAdbcWriter]) with a hand-rolled
 * JDBC↔Arrow mapping over the explicit contracts §5 matrix — **not** the ADBC
 * driver-manager (immature on the JVM for MSSQL) and **not** arrow-jdbc's
 * auto-mapping (which needs per-column overrides to hit §5 anyway). Both PG and
 * MSSQL go through the **same** impl; the dialect only changes DDL/quoting
 * ([DbTypeMapping]). Swapping in an ADBC-per-dialect impl later is a single
 * class behind these interfaces — the executor and the rest of the pipe are
 * unchanged.
 */
interface AdbcReader {
    /** A streaming [Source] over `schema.table` (table-level only; no
     *  predicate — security: no query-path bypass, contracts §5). */
    fun source(
        handle: ConnectionHandle,
        schema: String,
        table: String,
        chunkRows: Int,
    ): Source

    /** Catalog-only [DescribeResult] — schema + fingerprint, no row scan
     *  (`row_count = -1`, `row_count_exact = false`). `exists = false` when the
     *  table is absent under the read-allowed connection (Pythia PD-5 probe). */
    fun describe(
        handle: ConnectionHandle,
        schema: String,
        table: String,
    ): DescribeResult
}

/** The Arrow → DB ingest seam (Stage 2.2). One transaction per move; the
 *  reader sees old-or-new, never partial (contracts §1 no-partial-write). */
interface AdbcWriter {
    /** A [Target] that writes the source stream into `schema.table` under
     *  [writeMode] (CREATE / REPLACE / APPEND). */
    fun target(
        handle: ConnectionHandle,
        schema: String,
        table: String,
        writeMode: org.tatrman.charon.v1.DbWriteMode,
    ): Target
}
