// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.governance

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.tatrman.llmgateway.config.BudgetDef
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * Monthly money budgets (D-3/D-4/D-6). [precheck] reads the current-month row and decides admit/block on
 * the effective cap (**min-wins** key-override vs team default); [settle] adds one request's spend to the
 * atomic counter — **skipped when the response was cached** (E-1 seam). `hard` mode blocks at 100 %;
 * `soft` (the default, D-6) always admits but flips a breach metric. The `llm_gateway_budget_used_ratio`
 * gauge and `llm_gateway_budget_breach_total` counter feed alerts (the alert itself is Grafana's job, F-1).
 */
class BudgetService(
    private val usage: BudgetUsageRepo,
    private val teamBudgets: Map<String, BudgetDef>,
    private val metrics: MeterRegistry? = null,
    private val nowMonth: () -> LocalDate = { firstOfMonthUtc() },
) {
    data class Decision(
        val allowed: Boolean,
        val reason: String?, // null when allowed; a human reason when blocked (hard breach)
    )

    private val ratioByTeam = ConcurrentHashMap<String, Double>()

    /** Pre-flight admission on the money budget. Unknown/uncapped team ⇒ always allowed. */
    fun precheck(
        teamId: String,
        keyBudgetOverride: Double?,
    ): Decision {
        val teamBudget = teamBudgets[teamId]
        val cap = listOfNotNull(teamBudget?.usdPerMonth, keyBudgetOverride).minOrNull() ?: return ALLOW
        if (cap <= 0) return ALLOW

        val used = usage.usedUsd(teamId, nowMonth())
        recordRatio(teamId, used / cap)
        val atOrOver = used >= cap
        val hard = (teamBudget?.mode ?: "soft") == "hard"

        return when {
            atOrOver && hard -> {
                breach(teamId, "hard")
                Decision(false, "monthly budget exceeded")
            }
            atOrOver -> {
                breach(teamId, "soft") // soft: admit but record the breach
                ALLOW
            }
            else -> ALLOW
        }
    }

    /** Post-response settlement. Cached responses never settle (they cost nothing upstream). */
    fun settle(s: Settle) {
        if (s.cached) return
        usage.addUsage(
            teamId = s.teamId,
            month = nowMonth(),
            usd = s.costUsd.toBigDecimal(),
            tokensIn = s.usage.promptTokens,
            tokensOut = s.usage.completionTokens,
        )
    }

    private fun breach(
        teamId: String,
        mode: String,
    ) {
        metrics?.counter("llm_gateway_budget_breach_total", "team", teamId, "mode", mode)?.increment()
    }

    private fun recordRatio(
        teamId: String,
        ratio: Double,
    ) {
        val firstObservation = ratioByTeam.put(teamId, ratio) == null
        if (firstObservation) {
            metrics?.let { reg ->
                Gauge
                    .builder("llm_gateway_budget_used_ratio") { ratioByTeam[teamId] ?: 0.0 }
                    .tag("team", teamId)
                    .register(reg)
            }
        }
    }

    private companion object {
        val ALLOW = Decision(true, null)
    }
}
