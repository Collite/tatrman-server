package org.tatrman.kantheon.argos.stages

import org.tatrman.kantheon.common.v1.ResponseMessage
import org.tatrman.kantheon.common.v1.Severity
import org.tatrman.plan.v1.LimitOffsetNode
import org.tatrman.plan.v1.PlanNode
import org.tatrman.security.v1.ColumnRule
import org.tatrman.argos.v1.ValidationOptions
import org.slf4j.LoggerFactory
import org.tatrman.plan.v1.schemaCodeToToken

/**
 * RULES stage. Two responsibilities:
 *
 *   1. **Column rule enforcement (DF-V01).** Reads `column_rules` from `EvaluatePoliciesResponse`
 *      (per-table `DENY` / `MASK` rules, DF-S02). A `DENY` on a `(table, column)` pair that the
 *      plan touches produces a structured `column_denied` ERROR and short-circuits the rest of
 *      the stage (the plan is dropped — the validator returns an error response with no plan).
 *      A `MASK` rewrites every `ColumnRef` leaf inside an `Expression` (project expressions,
 *      filter/join conditions, cast inputs) for the masked column to the rule's `mask_expression`.
 *      Bare `ColumnRef` slots that aren't wrapped in an `Expression` — group-by keys, sort keys,
 *      aggregate-call args — can't be substituted with an arbitrary expression; v1 leaves them
 *      and emits a `mask_skipped_bare_column_ref` warning so the caller knows.
 *
 *      Match policy: a denied `(T, c)` fires when `T` is in the plan's TableScans AND `c` appears
 *      in the plan's column references (`ColumnRef.name`, `TableScan.output_columns[*].name`,
 *      group_keys, sort_keys, aggregate args). Without RESOLVE-stage column qualification (Phase
 *      08) we can't always disambiguate which TableScan a bare `ColumnRef` belongs to — so this
 *      errs toward denying, which is the safe v1 posture. Document any false-positive surprises
 *      as DF-V01 follow-ups.
 *
 *   2. **TopN enforcement** (unchanged): cap the root limit at `min(options.default_top_n,
 *      validator.default-top-n)` when `options.enforce_top_n` is true.
 *
 * The cap is the smaller of:
 *   - `options.default_top_n`, when the caller supplied one (>0)
 *   - the service-level default from HOCON (`validator.default-top-n`), passed in as [serviceDefault].
 */
class RuleEnforcer(
    private val serviceDefault: Int = 30,
) {
    fun enforce(
        plan: PlanNode,
        options: ValidationOptions,
        columnRules: List<ColumnRule> = emptyList(),
    ): Result {
        val messages = mutableListOf<ResponseMessage>()

        // 1. Column rule enforcement.
        val (rewritten, rejected) = applyColumnRules(plan, columnRules, messages)
        if (rejected) {
            return Result(plan = plan, messages = messages, rejected = true)
        }

        // 2. TopN.
        val withTopN =
            if (options.enforceTopN) {
                applyTopN(rewritten, effectiveCap(options))
            } else {
                log.debug("TopN enforcement disabled by ValidationOptions")
                rewritten
            }
        return Result(plan = withTopN, messages = messages, rejected = false)
    }

    data class Result(
        val plan: PlanNode,
        val messages: List<ResponseMessage>,
        val rejected: Boolean,
    )

    private fun applyColumnRules(
        plan: PlanNode,
        rules: List<ColumnRule>,
        messages: MutableList<ResponseMessage>,
    ): Pair<PlanNode, Boolean> {
        if (rules.isEmpty()) return plan to false
        val tableQnames = ColumnUsage.tableQnames(plan)
        val columnNames = ColumnUsage.columnNames(plan)

        val applicable = rules.filter { it.table in tableQnames && it.column in columnNames }
        if (applicable.isEmpty()) return plan to false

        var rejected = false
        for (rule in applicable.filter { it.action == ColumnRule.Action.DENY }) {
            log.warn(
                "Column rule '{}' denies access to {}.{}",
                rule.ruleId,
                qnameDot(rule.table),
                rule.column,
            )
            messages.add(
                ResponseMessage
                    .newBuilder()
                    .setSeverity(Severity.ERROR)
                    .setCode("column_denied")
                    .setHumanMessage(
                        "Query references column '${rule.column}' on table '${qnameDot(
                            rule.table,
                        )}', which is restricted by policy.",
                    ).build(),
            )
            rejected = true
        }
        if (rejected) return plan to true

        // Build a per-table mask lookup (column -> mask expression). Multiple masks on the same
        // (table, column) are unusual; last wins, with a warning.
        val maskLookup =
            applicable
                .filter { it.action == ColumnRule.Action.MASK }
                .groupBy { it.table to it.column }
                .mapValues { (key, group) ->
                    if (group.size > 1) {
                        log.warn(
                            "Multiple MASK rules for ${qnameDot(key.first)}.${key.second}; using rule '{}'",
                            group.last().ruleId,
                        )
                    }
                    group.last()
                }
        if (maskLookup.isEmpty()) return plan to false

        var bareRefSkipped = false
        val masked =
            ExpressionRewriter.rewriteColumnRefs(plan) { ref ->
                // Match by column name only (table membership is checked at the plan level via
                // `tableQnames` above — every applicable rule's table is in this plan, so a bare
                // ColumnRef matching a rule's column is conservatively assumed to be from that
                // table). When multiple tables have masks on the same column name, the v1 walker
                // can't disambiguate — both rules apply and the first match wins.
                val rule = maskLookup.values.firstOrNull { it.column == ref.name } ?: return@rewriteColumnRefs null
                if (!rule.hasMaskExpression()) {
                    log.warn(
                        "MASK rule '{}' for ${qnameDot(
                            rule.table,
                        )}.${rule.column} has no mask_expression; column left unmasked",
                        rule.ruleId,
                    )
                    return@rewriteColumnRefs null
                }
                rule.maskExpression
            }

        // Detect bare-ColumnRef slots that the rewriter can't touch — group_keys, sort_keys,
        // aggregate.args — and warn the caller. (Done after the rewrite since the rewriter doesn't
        // visit those slots.)
        for ((_, rule) in maskLookup) {
            if (bareColumnRefExistsFor(masked, rule.column)) {
                bareRefSkipped = true
                messages.add(
                    ResponseMessage
                        .newBuilder()
                        .setSeverity(Severity.WARNING)
                        .setCode("mask_skipped_bare_column_ref")
                        .setHumanMessage(
                            "MASK rule '${rule.ruleId}' for ${qnameDot(
                                rule.table,
                            )}.${rule.column} could not be applied to a group-by / sort / aggregate column reference; that occurrence is unmasked.",
                        ).build(),
                )
            }
        }
        if (bareRefSkipped) {
            log.warn(
                "MASK rewrite left at least one bare ColumnRef untouched; see mask_skipped_bare_column_ref warnings",
            )
        }

        return masked to false
    }

    private fun bareColumnRefExistsFor(
        plan: PlanNode,
        column: String,
    ): Boolean =
        when (plan.nodeCase) {
            PlanNode.NodeCase.AGGREGATE ->
                plan.aggregate.groupKeysList.any { it.name == column } ||
                    plan.aggregate.aggregatesList.any { call -> call.argsList.any { it.name == column } } ||
                    bareColumnRefExistsFor(plan.aggregate.input, column)
            PlanNode.NodeCase.SORT ->
                plan.sort.sortKeysList.any { it.column.name == column } ||
                    bareColumnRefExistsFor(plan.sort.input, column)
            PlanNode.NodeCase.PROJECT -> bareColumnRefExistsFor(plan.project.input, column)
            PlanNode.NodeCase.FILTER -> bareColumnRefExistsFor(plan.filter.input, column)
            PlanNode.NodeCase.JOIN ->
                bareColumnRefExistsFor(plan.join.left, column) || bareColumnRefExistsFor(plan.join.right, column)
            PlanNode.NodeCase.LIMIT_OFFSET -> bareColumnRefExistsFor(plan.limitOffset.input, column)
            PlanNode.NodeCase.SUBQUERY -> bareColumnRefExistsFor(plan.subquery.subquery, column)
            PlanNode.NodeCase.TABLE_SCAN,
            PlanNode.NodeCase.SCAN,
            PlanNode.NodeCase.WORKSPACE_REF,
            PlanNode.NodeCase.VALUES,
            PlanNode.NodeCase.NODE_NOT_SET,
            -> false
        }

    private fun qnameDot(qn: org.tatrman.plan.v1.QualifiedName): String =
        buildString {
            append(schemaCodeToToken(qn.schemaCode))
            append('.')
            append(qn.namespace)
            append('.')
            append(qn.name)
        }

    private fun effectiveCap(options: ValidationOptions): Int {
        val requested = options.defaultTopN
        return if (requested > 0) minOf(requested, serviceDefault) else serviceDefault
    }

    private fun applyTopN(
        plan: PlanNode,
        cap: Int,
    ): PlanNode {
        if (plan.nodeCase != PlanNode.NodeCase.LIMIT_OFFSET) {
            return PlanNode
                .newBuilder()
                .setLimitOffset(
                    LimitOffsetNode
                        .newBuilder()
                        .setInput(plan)
                        .setLimit(cap.toLong()),
                ).build()
        }
        val existing = plan.limitOffset
        if (existing.hasLimit() && existing.limit <= cap.toLong()) {
            return plan
        }
        val rewritten =
            existing
                .toBuilder()
                .setLimit(cap.toLong())
        return PlanNode.newBuilder().setLimitOffset(rewritten).build()
    }

    companion object {
        private val log = LoggerFactory.getLogger(RuleEnforcer::class.java)
    }
}
