// SPDX-License-Identifier: Apache-2.0
package org.tatrman.grounding.core

import org.tatrman.plan.v1.Expression

/**
 * Renders a plan.v1 [Expression] to generic (Calcite-parsable) SQL text with named params as `{name}`.
 * The `sql_preview` on every GroundingResult is produced HERE from the recipe's own Expression tree —
 * the single source of truth (contracts §1.1 "derived, not duplicated"). The round-trip suites parse
 * the output back through the Translator to prove the SQL text and the Expression tree agree.
 *
 * RG-P3 kernel: the one renderer for chrono / geo / money (was triplicated per service; chrono and geo
 * were byte-identical, money added the infix `mul` case — unioned here).
 *
 * Column names are double-quoted (`t."date"`): real grounding columns collide with SQL reserved words
 * (`date`, `period`, `amount`, …), so an unquoted preview would not parse under the Translator's
 * `Lex.MYSQL_ANSI` (double-quote quoting, casing unchanged, case-insensitive). [quote] the entity name
 * where the caller renders one too.
 */
object SqlRenderer {
    /**
     * Double-quote an identifier so reserved words (`date`, `period`, `amount`, `order`, …) parse.
     * Private: the only public entry points are [render] and [renderJoin], both derived from the
     * plan.v1 Expression tree — there is no public string-assembly primitive for a recipe to build a
     * `sql_preview` by hand (the "derived, not duplicated" invariant, contracts §1.1).
     */
    private fun quote(identifier: String): String = "\"$identifier\""

    /**
     * Render a JoinRecipe's `sql_preview` — `JOIN "<entity>" AS <alias> ON <onCondition> WHERE <filter>`
     * — from its structured parts. The on-condition and filter are [render]ed from their plan.v1
     * Expression trees; centralising the clause assembly here keeps the preview derived-not-duplicated
     * (no service hand-builds the JOIN string). Mirrors the format the three recipe builders emitted.
     */
    fun renderJoin(
        entityName: String,
        alias: String,
        onCondition: Expression,
        filter: Expression,
    ): String = "JOIN ${quote(entityName)} AS $alias ON ${render(onCondition)} WHERE ${render(filter)}"

    /** Render a boolean/scalar Expression to a SQL fragment. */
    fun render(e: Expression): String =
        when (e.exprCase) {
            Expression.ExprCase.COLUMN_REF ->
                e.columnRef.let { c ->
                    if (c.sourceAlias.isEmpty()) quote(c.name) else "${c.sourceAlias}.${quote(c.name)}"
                }
            Expression.ExprCase.PARAMETER -> "{${e.parameter.name}}"
            Expression.ExprCase.LITERAL -> e.literal.toString()
            Expression.ExprCase.FUNCTION -> renderFunction(e)
            else -> error("SqlRenderer: unsupported expression ${e.exprCase}")
        }

    private fun renderFunction(e: Expression): String {
        val f = e.function
        val ops = f.operandsList
        return when (f.operation) {
            "ge" -> "${render(ops[0])} >= ${render(ops[1])}"
            "gt" -> "${render(ops[0])} > ${render(ops[1])}"
            "lt" -> "${render(ops[0])} < ${render(ops[1])}"
            "le" -> "${render(ops[0])} <= ${render(ops[1])}"
            "eq" -> "${render(ops[0])} = ${render(ops[1])}"
            "and" -> "${render(ops[0])} AND ${render(ops[1])}"
            "or" -> "(${render(ops[0])} OR ${render(ops[1])})"
            "mul" -> "${render(ops[0])} * ${render(ops[1])}"
            // catalog + generic functions: name(arg, arg, ...)
            else -> "${f.operation}(${ops.joinToString(", ") { render(it) }})"
        }
    }
}
