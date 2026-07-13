package org.tatrman.money.recipe

import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.FunctionCall
import org.tatrman.plan.v1.ParameterBinding
import org.tatrman.plan.v1.ParameterRef
import org.tatrman.plan.v1.Value
import java.math.BigDecimal

/**
 * Builders for the plan.v1 Expression fragments a money recipe is made of. Operation tokens are the
 * lowercase wire codes the Translator round-trips (`ge`/`gt`/`lt`/`le`/`eq`/`and`/`mul`). Monetary
 * thresholds bind as `decimal` via [decimalParam], carrying the exact [BigDecimal] as a
 * `string_value` (the plan.v1 `Value` oneof has no decimal carrier, and `float_value` would round
 * fractional cents / magnitudes past 2^53). The Translator maps the `decimal` surface type to a
 * SQL/Calcite `DECIMAL`, so the executed comparison keeps the precision shown in `MoneyValue.amount`.
 */
object PlanExpr {
    fun col(
        alias: String,
        name: String,
        type: String = "decimal",
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

    fun gt(
        a: Expression,
        b: Expression,
    ) = fn("gt", "bool", a, b)

    fun lt(
        a: Expression,
        b: Expression,
    ) = fn("lt", "bool", a, b)

    fun le(
        a: Expression,
        b: Expression,
    ) = fn("le", "bool", a, b)

    fun eq(
        a: Expression,
        b: Expression,
    ) = fn("eq", "bool", a, b)

    fun and(
        a: Expression,
        b: Expression,
    ) = fn("and", "bool", a, b)

    /** `amount * rate` — the FX-converted magnitude (A10.5). */
    fun mul(
        a: Expression,
        b: Expression,
    ) = fn("mul", "decimal", a, b)

    /** A `decimal` threshold carrying the exact [BigDecimal] as a string (precision-preserving). */
    fun decimalParam(
        name: String,
        value: BigDecimal,
        label: String,
    ): ParameterBinding =
        ParameterBinding
            .newBuilder()
            .setName(name)
            .setType("decimal")
            .setValue(Value.newBuilder().setStringValue(value.toPlainString()))
            .setLabel(label)
            .build()

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
