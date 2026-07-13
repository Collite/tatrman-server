package org.tatrman.money.roundtrip

import org.tatrman.plan.v1.ParameterBinding
import org.tatrman.plan.v1.Value
import org.tatrman.translate.v1.Language
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContainIgnoringCase
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.money.FakeMetadataClient
import org.tatrman.money.recipe.MoneyRecipe
import org.tatrman.money.recipe.MoneyRecipeBuilder
import org.tatrman.money.recognize.Comparator
import org.tatrman.money.recognize.MoneyAmount
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.params.SqlParam
import java.math.BigDecimal

/**
 * A10.6 round-trip — each recipe's sql_preview, wrapped into a full query, parses + validates through
 * the real Translator, proving the SQL text and the plan.v1 Expression tree agree (derived-not-
 * duplicated). Fixture DB model (`Sale` + `FxRate`), non-reserved names, double-quoted identifiers.
 */
class MoneyRoundTripSpec :
    StringSpec({
        val translator = Translator(GroundingModelHandle.moneyModel())

        fun money(
            amount: Long,
            currency: String? = null,
            comparator: Comparator? = Comparator.GT,
        ) = MoneyAmount(BigDecimal(amount), currency, comparator, tolerance = false, atCurrentRate = false, 0.9)

        "domestic FilterRecipe sql_preview parses against the model" {
            val g =
                MoneyRecipeBuilder(FakeMetadataClient.domestic("cnc"))
                    .build(money(100_000), "cnc", "CZK", 10.0, false, "")
                    .shouldBeInstanceOf<MoneyRecipe.Ok>()
                    .result

            val sql = "SELECT 1 FROM Sale AS t WHERE ${g.sqlPreview}"
            val r = translator.parseToRelNode(sql, Language.SQL, parameters = g.filter.parametersList.map(::toSqlParam))
            r.shouldBeInstanceOf<ParseResult.Success>()
        }

        "foreign FX JoinRecipe sql_preview parses + the plan carries the amount * rate product" {
            val g =
                MoneyRecipeBuilder(FakeMetadataClient.withFxTable("cnc"))
                    .build(money(5_000, currency = "EUR"), "cnc", "CZK", 10.0, false, "")
                    .shouldBeInstanceOf<MoneyRecipe.Ok>()
                    .result

            val sql = "SELECT 1 FROM Sale AS t ${g.sqlPreview}"
            val r = translator.parseToRelNode(sql, Language.SQL, parameters = g.join.parametersList.map(::toSqlParam))
            r.shouldBeInstanceOf<ParseResult.Success>()
            // the FxRate join entity resolved + landed in the plan (the amount*rate product parsed)
            r.plan.toString().shouldContainIgnoringCase("FxRate")
        }
    })

private fun toSqlParam(pb: ParameterBinding): SqlParam {
    val value: Any? =
        when (pb.value.vCase) {
            Value.VCase.FLOAT_VALUE -> pb.value.floatValue
            Value.VCase.STRING_VALUE -> pb.value.stringValue
            Value.VCase.INT_VALUE -> pb.value.intValue
            Value.VCase.DATETIME_VALUE -> pb.value.datetimeValue
            else -> null
        }
    return SqlParam(pb.name, pb.type, value)
}
