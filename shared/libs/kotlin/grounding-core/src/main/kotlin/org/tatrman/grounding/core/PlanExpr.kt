// SPDX-License-Identifier: Apache-2.0
package org.tatrman.grounding.core

import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.FunctionCall
import org.tatrman.plan.v1.ParameterBinding
import org.tatrman.plan.v1.ParameterRef
import org.tatrman.plan.v1.Value
import java.math.BigDecimal

/**
 * Builders for the plan.v1 Expression fragments a grounding recipe is made of. Operation tokens are
 * the lowercase wire codes the Translator round-trips (`ge`/`gt`/`lt`/`le`/`eq`/`and`/`mul` and the
 * grounding catalog functions `period_start`/`period_end`/`geo_distance_m`).
 *
 * RG-P3 kernel: the one builder surface for chrono / geo / money (was triplicated — the comparison
 * core was shared; each service added its own operators + param carriers, unioned here). Callers pass
 * the column/param `type` explicitly where it isn't the datetime default (money `decimal`, geo
 * `float`, currency `text`) so the Expression carries the surface type the Translator maps.
 *
 * A `decimal` threshold binds the exact [BigDecimal] as a `string_value` (the plan.v1 `Value` oneof
 * has no decimal carrier, and `float_value` would round fractional cents / magnitudes past 2^53); the
 * Translator maps the `decimal` surface type to a SQL/Calcite `DECIMAL`, preserving precision.
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

    // ----- comparison + boolean operators -----

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

    /** `amount * rate` — the FX-converted magnitude (money A10.5). */
    fun mul(
        a: Expression,
        b: Expression,
    ) = fn("mul", "decimal", a, b)

    // ----- grounding catalog functions (render through the generic name(args) path) -----

    /** `period_start(codeParam)` / `period_end(codeParam)` — the calendar-aligned catalog functions. */
    fun periodStart(codeParam: Expression) = fn("period_start", "datetime", codeParam)

    fun periodEnd(codeParam: Expression) = fn("period_end", "datetime", codeParam)

    /** `geo_distance_m(lat1, lon1, lat2, lon2) → float` (meters, WGS84). */
    fun geoDistanceM(
        lat1: Expression,
        lon1: Expression,
        lat2: Expression,
        lon2: Expression,
    ) = fn("geo_distance_m", "float", lat1, lon1, lat2, lon2)

    // ----- typed parameter carriers -----

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

    fun floatParam(
        name: String,
        value: Double,
        label: String,
    ): ParameterBinding =
        ParameterBinding
            .newBuilder()
            .setName(name)
            .setType("float")
            .setValue(Value.newBuilder().setFloatValue(value))
            .setLabel(label)
            .build()
}
