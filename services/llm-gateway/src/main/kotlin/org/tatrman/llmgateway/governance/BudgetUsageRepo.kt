// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.governance

import org.jetbrains.exposed.v1.core.DecimalColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import shared.libs.db.common.DatabaseConnection
import java.math.BigDecimal
import java.sql.Connection
import java.time.LocalDate
import java.time.ZoneOffset

/** Read current-month usage; UTC first-of-month is the row key (D-3). */
internal fun firstOfMonthUtc(nowMs: Long = System.currentTimeMillis()): LocalDate =
    LocalDate.ofInstant(java.time.Instant.ofEpochMilli(nowMs), ZoneOffset.UTC).withDayOfMonth(1)

/**
 * `budget_usage` counters (contracts §3, D-4). **Settle is ONE atomic statement** — an
 * `INSERT … ON CONFLICT DO UPDATE SET used_x = used_x + EXCLUDED.used_x` — so N concurrent settles sum
 * exactly with no read-modify-write race (the FI-6 anti-pattern). Reads back the current month for the
 * pre-check.
 */
class BudgetUsageRepo(
    private val db: DatabaseConnection,
) {
    /** Current month's spent USD for a team (0 when no row yet). */
    fun usedUsd(
        teamId: String,
        month: LocalDate,
    ): Double =
        db.query {
            BudgetUsage
                .selectAll()
                .where { (BudgetUsage.teamId eq teamId) and (BudgetUsage.month eq month) }
                .singleOrNull()
                ?.get(BudgetUsage.usedUsd)
                ?.toDouble() ?: 0.0
        }

    /** Atomic upsert-add of one settle's cost + token counters onto the team's monthly row. */
    fun addUsage(
        teamId: String,
        month: LocalDate,
        usd: BigDecimal,
        tokensIn: Long,
        tokensOut: Long,
    ) {
        // READ COMMITTED (not the pool's default REPEATABLE READ): concurrent increments of the same
        // team+month row serialize on the row lock and each re-reads the latest committed value, so the
        // single upsert-add sums exactly. Under REPEATABLE READ they would instead abort with a
        // serialization error — the atomic statement is still the unit of work, just at the right isolation.
        transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED, readOnly = false, db = null) {
            exec(
                UPSERT_SQL,
                listOf(
                    TextColumnType() to teamId,
                    TextColumnType() to month.toString(), // bound as text, cast ?::date in SQL
                    DecimalColumnType(14, 6) to usd,
                    LongColumnType() to tokensIn,
                    LongColumnType() to tokensOut,
                ),
            )
        }
    }

    private companion object {
        val UPSERT_SQL =
            """
            INSERT INTO budget_usage (team_id, month, used_usd, used_tokens_in, used_tokens_out)
            VALUES (?, ?::date, ?, ?, ?)
            ON CONFLICT (team_id, month) DO UPDATE SET
              used_usd        = budget_usage.used_usd        + EXCLUDED.used_usd,
              used_tokens_in  = budget_usage.used_tokens_in  + EXCLUDED.used_tokens_in,
              used_tokens_out = budget_usage.used_tokens_out + EXCLUDED.used_tokens_out
            """.trimIndent()
    }
}
