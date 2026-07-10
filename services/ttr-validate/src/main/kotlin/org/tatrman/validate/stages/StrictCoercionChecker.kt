package org.tatrman.validate.stages

import org.tatrman.common.v1.ResponseMessage
import org.tatrman.common.v1.Severity
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.Literal
import org.tatrman.plan.v1.PlanNode

/**
 * Phase 08 C1 / DF-V06 — strict-mode type-coercion checker.
 *
 * LLM-generated SQL often hides type-mismatches behind the database's implicit-coercion rules
 * (`WHERE int_column = '42'` works in most engines but masks intent). When `strict_coercion` is
 * on, the validator walks every comparison expression in the plan and emits a structured
 * `strict_coercion_rejected` ERROR for sites where the operands' declared surface types disagree
 * (e.g. `int` vs `text`).
 *
 * **Detection scope (v1 best-effort).** The checker reads `Expression.result_type` and
 * `Literal.type` — the surface-type tags the codecs already attach. Without the RESOLVE stage
 * (Phase 08 A1, still pending), bare `ColumnRef` leaves often carry `type = ""` /
 * `result_type = "unknown"` and the checker can't conclude. The contract is "report what we can
 * see"; once RESOLVE attaches concrete types to every `ColumnRef`, the checker's coverage
 * widens for free without an API change.
 *
 * Operators checked: comparison and equality (`eq`, `ne`, `lt`, `le`, `gt`, `ge`, `like`, `in`).
 * Arithmetic and boolean operators are out of scope — implicit numeric coercion (`int + float`)
 * is generally intentional.
 */
internal object StrictCoercionChecker {
    private val COMPARISON_OPS: Set<String> = setOf("eq", "ne", "lt", "le", "gt", "ge", "like")

    /** Returns the violations found; empty when the plan is coercion-clean (or when the
     *  checker can't decide because surface types aren't tagged). */
    fun check(plan: PlanNode): List<ResponseMessage> {
        val violations = mutableListOf<Violation>()
        walkPlan(plan, violations)
        return violations.map { v ->
            ResponseMessage
                .newBuilder()
                .setSeverity(Severity.ERROR)
                .setCode("strict_coercion_rejected")
                .setHumanMessage(
                    "Strict mode: '${v.op}' comparison mixes incompatible surface types " +
                        "'${v.leftType}' vs '${v.rightType}' — wrap one side in an explicit CAST " +
                        "or compare against a literal of the matching type.",
                ).build()
        }
    }

    private data class Violation(
        val op: String,
        val leftType: String,
        val rightType: String,
    )

    private fun walkPlan(
        plan: PlanNode,
        out: MutableList<Violation>,
    ) {
        when (plan.nodeCase) {
            PlanNode.NodeCase.FILTER -> {
                walkExpression(plan.filter.condition, out)
                walkPlan(plan.filter.input, out)
            }
            PlanNode.NodeCase.PROJECT -> {
                plan.project.expressionsList.forEach { walkExpression(it.expression, out) }
                walkPlan(plan.project.input, out)
            }
            PlanNode.NodeCase.JOIN -> {
                walkExpression(plan.join.condition, out)
                walkPlan(plan.join.left, out)
                walkPlan(plan.join.right, out)
            }
            PlanNode.NodeCase.AGGREGATE -> walkPlan(plan.aggregate.input, out)
            PlanNode.NodeCase.SORT -> walkPlan(plan.sort.input, out)
            PlanNode.NodeCase.LIMIT_OFFSET -> walkPlan(plan.limitOffset.input, out)
            PlanNode.NodeCase.SUBQUERY -> walkPlan(plan.subquery.subquery, out)
            else -> Unit
        }
    }

    private fun walkExpression(
        e: Expression,
        out: MutableList<Violation>,
    ) {
        if (e.exprCase != Expression.ExprCase.FUNCTION) {
            if (e.exprCase == Expression.ExprCase.CAST) walkExpression(e.cast.value, out)
            return
        }
        val fn = e.function
        val op = fn.operation
        if (op in COMPARISON_OPS && fn.operandsList.size >= 2) {
            val leftType = surfaceType(fn.operandsList[0])
            // For `IN`, every right-hand value should agree with the left. For `eq`/`ne`/etc.,
            // there's only one right operand.
            val rights = fn.operandsList.drop(1)
            for (right in rights) {
                val rightType = surfaceType(right)
                if (leftType != null && rightType != null && !compatible(leftType, rightType)) {
                    out.add(Violation(op = op, leftType = leftType, rightType = rightType))
                }
            }
        }
        // For `in` (set membership) we also need to check above — same logic; loop over
        // operands[1..]. For nested expressions we recurse.
        if (op == "in" && fn.operandsList.size >= 2) {
            val leftType = surfaceType(fn.operandsList[0])
            for (right in fn.operandsList.drop(1)) {
                val rightType = surfaceType(right)
                if (leftType != null && rightType != null && !compatible(leftType, rightType)) {
                    out.add(Violation(op = "in", leftType = leftType, rightType = rightType))
                }
            }
        }
        // Recurse for compound predicates (and / or / not / etc.).
        fn.operandsList.forEach { walkExpression(it, out) }
    }

    /** Best-effort surface-type read. Returns null when the type is unknown (RESOLVE pending). */
    private fun surfaceType(e: Expression): String? =
        when (e.exprCase) {
            Expression.ExprCase.LITERAL -> normalise(e.literal.type.ifEmpty { literalType(e.literal) })
            Expression.ExprCase.COLUMN_REF -> normalise(e.columnRef.type.ifEmpty { e.resultType })
            Expression.ExprCase.CAST -> normalise(e.cast.targetType)
            Expression.ExprCase.FUNCTION -> normalise(e.resultType)
            else -> null
        }

    /** Infer a surface type from a literal's value shape when the explicit `type` is missing. */
    private fun literalType(lit: Literal): String =
        when (lit.valueCase) {
            Literal.ValueCase.STRING_VALUE -> "text"
            Literal.ValueCase.INT_VALUE -> "int"
            Literal.ValueCase.FLOAT_VALUE -> "float"
            Literal.ValueCase.BOOL_VALUE -> "bool"
            Literal.ValueCase.DATETIME_VALUE -> "datetime"
            else -> ""
        }

    /** Surface-type families: `int` & `float` & `decimal` are numeric; `text` is on its own. */
    private fun compatible(
        a: String,
        b: String,
    ): Boolean {
        if (a == b) return true
        val familyA = family(a)
        val familyB = family(b)
        return familyA != null && familyA == familyB
    }

    private fun family(t: String): String? =
        when (t) {
            "int", "long", "float", "double", "decimal", "numeric" -> "numeric"
            "text", "varchar", "string", "char" -> "text"
            "bool", "boolean" -> "bool"
            "datetime", "date", "time", "timestamp" -> "temporal"
            else -> null
        }

    /** Empty / "unknown" types are dropped on the floor — the checker won't speculate. */
    private fun normalise(t: String): String? {
        val lc = t.lowercase().substringBefore("(")
        return if (lc.isBlank() || lc == "unknown" || lc.startsWith("unknown:")) null else lc
    }
}
