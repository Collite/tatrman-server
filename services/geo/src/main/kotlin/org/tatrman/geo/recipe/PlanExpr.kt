package org.tatrman.geo.recipe

import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.FunctionCall
import org.tatrman.plan.v1.ParameterBinding
import org.tatrman.plan.v1.ParameterRef
import org.tatrman.plan.v1.Value

/**
 * plan.v1 Expression builders for geo recipes. `geo_distance_m` is the grounding catalog function
 * (registered in the Translator's operator table); `le` is the lowercase wire comparison token.
 */
object PlanExpr {
    fun col(
        alias: String,
        name: String,
        type: String = "float",
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

    fun le(
        a: Expression,
        b: Expression,
    ) = fn("le", "bool", a, b)

    fun ge(
        a: Expression,
        b: Expression,
    ) = fn("ge", "bool", a, b)

    fun and(
        a: Expression,
        b: Expression,
    ) = fn("and", "bool", a, b)

    fun eq(
        a: Expression,
        b: Expression,
    ) = fn("eq", "bool", a, b)

    /** `geo_distance_m(lat1, lon1, lat2, lon2) → float` (meters, WGS84). */
    fun geoDistanceM(
        lat1: Expression,
        lon1: Expression,
        lat2: Expression,
        lon2: Expression,
    ) = fn("geo_distance_m", "float", lat1, lon1, lat2, lon2)

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
}
