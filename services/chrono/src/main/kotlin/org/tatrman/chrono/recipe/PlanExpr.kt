package org.tatrman.chrono.recipe

import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.FunctionCall
import org.tatrman.plan.v1.ParameterBinding
import org.tatrman.plan.v1.ParameterRef
import org.tatrman.plan.v1.Value

/**
 * Builders for the plan.v1 Expression fragments a grounding recipe is made of. Operation tokens are
 * the lowercase wire codes the Translator round-trips (`ge`/`lt`/`le`/`eq`/`and` and the grounding
 * catalog functions `period_start`/`period_end`); result types mirror the A7 golden fixtures.
 */
object PlanExpr {
    fun col(
        alias: String,
        name: String,
        type: String = "datetime",
    ): Expression =
        Expression
            .newBuilder()
            .setColumnRef(
                ColumnRef
                    .newBuilder()
                    .setSourceAlias(alias)
                    .setName(name)
                    .setType(type),
            ).setResultType(type)
            .build()

    fun param(
        name: String,
        type: String,
    ): Expression =
        Expression
            .newBuilder()
            .setParameter(ParameterRef.newBuilder().setName(name))
            .setResultType(type)
            .build()

    fun fn(
        operation: String,
        resultType: String,
        vararg operands: Expression,
    ): Expression =
        Expression
            .newBuilder()
            .setFunction(FunctionCall.newBuilder().setOperation(operation).addAllOperands(operands.toList()))
            .setResultType(resultType)
            .build()

    fun ge(
        a: Expression,
        b: Expression,
    ) = fn("ge", "bool", a, b)

    fun lt(
        a: Expression,
        b: Expression,
    ) = fn("lt", "bool", a, b)

    fun eq(
        a: Expression,
        b: Expression,
    ) = fn("eq", "bool", a, b)

    fun and(
        a: Expression,
        b: Expression,
    ) = fn("and", "bool", a, b)

    /** `period_start(codeParam)` / `period_end(codeParam)` — the calendar-aligned catalog functions. */
    fun periodStart(codeParam: Expression) = fn("period_start", "datetime", codeParam)

    fun periodEnd(codeParam: Expression) = fn("period_end", "datetime", codeParam)

    fun textParam(
        name: String,
        value: String,
        label: String,
    ): ParameterBinding =
        ParameterBinding
            .newBuilder()
            .setName(name)
            .setType("text")
            .setValue(Value.newBuilder().setStringValue(value))
            .setLabel(label)
            .build()

    fun datetimeParam(
        name: String,
        iso: String,
        label: String,
    ): ParameterBinding =
        ParameterBinding
            .newBuilder()
            .setName(name)
            .setType("datetime")
            .setValue(Value.newBuilder().setDatetimeValue(iso))
            .setLabel(label)
            .build()
}
