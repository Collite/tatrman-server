// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.loader

import org.slf4j.LoggerFactory
import org.tatrman.meta.v1.DbTableDetail
import org.tatrman.fuzzy.config.DatabaseConfig
import org.tatrman.fuzzy.core.Candidate
import org.tatrman.fuzzy.core.LoaderWarningInfo
import org.tatrman.fuzzy.telemetry.FuzzyTelemetry
import org.tatrman.plan.v1.QualifiedName

/**
 * Metadata-driven loader: walks fuzzy-tagged DB columns reported by Veles,
 * composes `SELECT pk, col FROM table` per column, runs it via [fetchCandidates],
 * and returns the candidate map for `StringRepository.refreshCache` to swap in
 * atomically. This is the full ai-platform `fuzzy-matcher` behaviour, re-forked
 * 2026-06-14 onto Veles (`org.tatrman.meta.v1`).
 *
 * The gRPC channel and the [MetadataServiceClient] are constructed and owned by
 * `Application.module(...)` — this class never builds or closes them.
 *
 * The `sourceNamespace` guard enforces the v1 "single source, asserted" rule:
 * if non-empty, columns whose `QualifiedName.namespace` does not match are
 * skipped with reason `wrong_source`. Empty disables the guard.
 */
class MetadataLoaderSource(
    private val client: MetadataServiceClient,
    private val dialect: DatabaseConfig,
    private val sourceNamespace: String,
    private val fetchCandidates: (String) -> List<Candidate>,
    private val telemetry: FuzzyTelemetry? = null,
    // RS-12-γ alias tables (`semantics{kind: alias_table}`). PENDING COUPLING
    // (rule 6): Veles does not yet report alias-table declarations (no
    // `semantics` in `org.tatrman.meta.v1`); this provider is stubbed to empty
    // until the RG-P4 metadata work lands. The ingestion + merge logic is live
    // and tested (composeAliasCandidates); only the source of `decls` is stubbed.
    private val aliasTables: () -> List<AliasTableDecl> = { emptyList() },
) : LoaderSource {
    private val logger = LoggerFactory.getLogger(MetadataLoaderSource::class.java)

    // B-T4 loader report: warnings from the last load (PK-skipped declared
    // columns → RG-FUZ-001), retrievable via GetStatus so estates learn which
    // declared columns aren't actually searchable.
    @Volatile
    private var lastWarnings: List<LoaderWarningInfo> = emptyList()

    override fun warnings(): List<LoaderWarningInfo> = lastWarnings

    override suspend fun loadNextCache(): Map<String, List<Candidate>>? {
        val start = System.nanoTime()
        val warnings = mutableListOf<LoaderWarningInfo>()
        val targets =
            try {
                client.listFuzzyColumns()
            } catch (e: Exception) {
                telemetry?.recordMetadataFailure()
                logger.error("Metadata (veles) call failed; preserving previous cache", e)
                return null
            }

        val tableCache = mutableMapOf<QualifiedName, DbTableDetail>()
        val result = mutableMapOf<String, List<Candidate>>()

        for (target in targets) {
            if (!sourceMatchesConfig(target.qname)) {
                telemetry?.recordSkipped("wrong_source")
                logger.error(
                    "Column {} (namespace='{}') is outside configured sourceNamespace='{}' — skipped",
                    target.qname,
                    target.qname.namespace,
                    sourceNamespace,
                )
                continue
            }

            val tableDetail =
                tableCache.getOrPut(target.tableQname) {
                    try {
                        client.getTableDetail(target.tableQname)
                    } catch (e: Exception) {
                        telemetry?.recordMetadataFailure()
                        logger.error("getTableDetail failed for table {} — column skipped", target.tableQname, e)
                        DbTableDetail.getDefaultInstance()
                    }
                }
            val pk = singleColumnPkOrNull(tableDetail)
            if (pk == null) {
                val reason = pkReason(tableDetail) ?: "no_pk"
                telemetry?.recordSkipped(reason)
                // B-T4: a declared fuzzy column with no usable PK isn't searchable.
                warnings.add(
                    LoaderWarningInfo(
                        code = "RG-FUZ-001",
                        category = target.qname.toCategoryString(),
                        message = "declared fuzzy column skipped ($reason) — not searchable",
                    ),
                )
                logger.debug("Skipping column {} - {}.", target.qname, reason)
                continue
            }

            val category = target.qname.toCategoryString()
            val sql =
                try {
                    buildSelect(target.tableQname, pk, target.localName, dialect)
                } catch (e: SqlComposerException) {
                    telemetry?.recordSkipped("sql_failed")
                    logger.error("SQL composition rejected for '$category': ${e.message}")
                    continue
                }

            try {
                val raw = fetchCandidates(sql)
                result[category] = raw
                logger.info("Loaded ${raw.size} candidates for '$category'")
            } catch (e: Exception) {
                telemetry?.recordSkipped("sql_failed")
                logger.error("SQL failed for '$category': $sql", e)
            }
        }

        // RS-12-γ: merge estate alias-table synonyms into their owning member
        // category (same PK space). `composeAliasCandidates` reuses the SQL
        // identifier-validation discipline; a rejected declaration contributes
        // nothing rather than aborting the load.
        val aliasByCategory =
            composeAliasCandidates(aliasTables(), dialect) { sql ->
                try {
                    fetchCandidates(sql)
                } catch (e: Exception) {
                    telemetry?.recordSkipped("alias_sql_failed")
                    logger.error("Alias-table SQL failed: $sql", e)
                    emptyList()
                }
            }
        aliasByCategory.forEach { (category, aliases) ->
            result.merge(category, aliases) { primary, alias -> primary + alias }
        }

        lastWarnings = warnings

        val durationSeconds = (System.nanoTime() - start) / 1_000_000_000.0
        telemetry?.recordRefreshDuration(durationSeconds)
        telemetry?.updateCategories(result.mapValues { it.value.size })

        return result
    }

    /**
     * `sourceNamespace = ""` disables the check (single-source v1 default).
     * Non-empty asserts that every returned column lives in that SQL namespace.
     */
    private fun sourceMatchesConfig(targetQname: QualifiedName): Boolean =
        sourceNamespace.isEmpty() || targetQname.namespace == sourceNamespace
}
