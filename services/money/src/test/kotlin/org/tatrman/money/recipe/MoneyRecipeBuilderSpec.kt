package org.tatrman.money.recipe

import org.tatrman.grounding.v1.GroundingResult
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.money.FakeMetadataClient
import org.tatrman.money.recognize.Comparator
import org.tatrman.money.recognize.MoneyAmount
import java.math.BigDecimal

class MoneyRecipeBuilderSpec :
    StringSpec({
        fun builder(client: FakeMetadataClient) = MoneyRecipeBuilder(client)

        fun money(
            amount: Long,
            currency: String? = null,
            comparator: Comparator? = Comparator.GT,
            tolerance: Boolean = false,
            atCurrentRate: Boolean = false,
        ) = MoneyAmount(BigDecimal(amount), currency, comparator, tolerance, atCurrentRate, 0.9)

        "domestic 'over 100k' → FilterRecipe on amount_domestic" {
            val r =
                builder(FakeMetadataClient.domestic("cnc"))
                    .build(money(100_000), "cnc", "CZK", 10.0, false, "")
                    .shouldBeInstanceOf<MoneyRecipe.Ok>()
                    .result
            r.applicationCase shouldBe GroundingResult.ApplicationCase.FILTER
            r.filter.condition.function.operation shouldBe "gt"
            // The threshold is carried as an exact decimal string (not a lossy float_value).
            r.filter.parametersList
                .first { it.name == "amt" }
                .let {
                    it.type shouldBe "decimal"
                    it.value.stringValue shouldBe "100000"
                }
            r.sqlPreview shouldContain "t.\"amount_dom\" > {amt}"
            r.normalized.money.amount shouldBe "100000"
            r.normalized.money.currency shouldBe "CZK"
            r.source shouldBe GroundingResult.Source.RULES
        }

        "explicit domestic currency (CZK == default) also takes the amount_domestic shortcut" {
            val r =
                builder(FakeMetadataClient.domestic("cnc"))
                    .build(money(100_000, currency = "CZK"), "cnc", "CZK", 10.0, false, "")
                    .shouldBeInstanceOf<MoneyRecipe.Ok>()
                    .result
            r.applicationCase shouldBe GroundingResult.ApplicationCase.FILTER
            r.sqlPreview shouldContain "t.\"amount_dom\""
        }

        "domestic tolerance ('kolem 100k') → ge/le band + normalized bounds" {
            val r =
                builder(FakeMetadataClient.domestic("cnc"))
                    .build(money(100_000, comparator = null, tolerance = true), "cnc", "CZK", 10.0, false, "")
                    .shouldBeInstanceOf<MoneyRecipe.Ok>()
                    .result
            r.filter.condition.function.operation shouldBe "and"
            r.normalized.money.lowerBound shouldBe "90000"
            r.normalized.money.upperBound shouldBe "110000"
            r.sqlPreview shouldContain ">= {lower}"
            r.sqlPreview shouldContain "<= {upper}"
        }

        "foreign EUR + fx table (transaction-date) → JoinRecipe converting amount*rate w/ validity" {
            val r =
                builder(FakeMetadataClient.withFxTable("cnc"))
                    .build(money(5_000, currency = "EUR"), "cnc", "CZK", 10.0, false, "")
                    .shouldBeInstanceOf<MoneyRecipe.Ok>()
                    .result
            r.applicationCase shouldBe GroundingResult.ApplicationCase.JOIN
            r.join.suggestedAlias shouldBe "fx"
            r.join.filter.function.operation shouldBe "gt"
            r.join.filter.function
                .getOperands(0)
                .function.operation shouldBe "mul"
            r.join.parametersList.map { it.name } shouldContainAll listOf("ccy", "domestic", "amt")
            r.join.parametersList
                .first { it.name == "ccy" }
                .value.stringValue shouldBe "EUR"
            r.sqlPreview shouldContain "t.\"amount\" * fx.\"rate\" > {amt}"
            r.sqlPreview shouldContain "fx.\"from_ccy\" = {ccy}"
            // TRANSACTION_DATE validity binds the fact's event_date against the rate window
            r.sqlPreview shouldContain "t.\"date\" >= fx.\"valid_from\""
        }

        "foreign EUR + fx, current rate → validity uses the {ref} as-of date, ref param present" {
            val r =
                builder(FakeMetadataClient.withFxTable("cnc"))
                    .build(
                        money(5_000, currency = "EUR", atCurrentRate = true),
                        "cnc",
                        "CZK",
                        10.0,
                        fxCurrent = true,
                        referenceDatetime = "2026-05-15T00:00:00+02:00",
                    ).shouldBeInstanceOf<MoneyRecipe.Ok>()
                    .result
            r.join.parametersList.map { it.name } shouldContain "ref"
            r.sqlPreview shouldContain "{ref} >= fx.\"valid_from\""
        }

        "foreign EUR + fx, current rate but NO reference date → Ungroundable (no silent rate fan-out)" {
            builder(FakeMetadataClient.withFxTable("cnc"))
                .build(
                    money(5_000, currency = "EUR", atCurrentRate = true),
                    "cnc",
                    "CZK",
                    10.0,
                    fxCurrent = true,
                    referenceDatetime = "",
                ).shouldBeInstanceOf<MoneyRecipe.Ungroundable>()
        }

        "fractional threshold keeps exact decimal precision (no double rounding)" {
            val r =
                builder(FakeMetadataClient.domestic("cnc"))
                    .build(
                        MoneyAmount(BigDecimal("100.10"), null, Comparator.GT, false, false, 0.9),
                        "cnc",
                        "CZK",
                        10.0,
                        false,
                        "",
                    ).shouldBeInstanceOf<MoneyRecipe.Ok>()
                    .result
            r.filter.parametersList
                .first { it.name == "amt" }
                .value.stringValue shouldBe "100.10"
        }

        "foreign USD, no fx, currency_code column → native FilterRecipe with a currency predicate" {
            val r =
                builder(FakeMetadataClient.withCurrencyCode("cnc"))
                    .build(money(1_000, currency = "USD", comparator = Comparator.GE), "cnc", "CZK", 10.0, false, "")
                    .shouldBeInstanceOf<MoneyRecipe.Ok>()
                    .result
            r.applicationCase shouldBe GroundingResult.ApplicationCase.FILTER
            r.filter.condition.function.operation shouldBe "and"
            r.sqlPreview shouldContain "t.\"amount\" >= {amt}"
            r.sqlPreview shouldContain "t.\"currency\" = {ccy}"
            r.filter.parametersList
                .first { it.name == "ccy" }
                .value.stringValue shouldBe "USD"
        }

        "foreign EUR, no fx table, no currency_code → Ungroundable" {
            builder(FakeMetadataClient.amountOnly("cnc"))
                .build(money(5_000, currency = "EUR"), "cnc", "CZK", 10.0, false, "")
                .shouldBeInstanceOf<MoneyRecipe.Ungroundable>()
        }

        "two amount columns (net/gross), no domestic → Clarify" {
            val r =
                builder(FakeMetadataClient.ambiguousAmounts("cnc"))
                    .build(money(100), "cnc", "CZK", 10.0, false, "")
                    .shouldBeInstanceOf<MoneyRecipe.Clarify>()
            r.columns.map { it.columnName } shouldBe listOf("net_amount", "gross_amount")
        }

        "a forced column id resolves the ambiguity → Ok on that column" {
            val r =
                builder(FakeMetadataClient.ambiguousAmounts("cnc"))
                    .build(money(100), "cnc", "CZK", 10.0, false, "", forcedColumnName = "gross_amount")
                    .shouldBeInstanceOf<MoneyRecipe.Ok>()
                    .result
            r.sqlPreview shouldContain "t.\"gross_amount\" > {amt}"
        }
    })
