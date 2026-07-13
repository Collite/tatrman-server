package org.tatrman.chrono.recipe

import org.tatrman.grounding.v1.GroundingResult
import org.tatrman.plan.v1.JoinType
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.chrono.FakeMetadataClient
import org.tatrman.chrono.recognize.DateRecognizer
import java.time.LocalDate

/**
 * A8.4 — recognizer → recipe, one case per recipe shape. Recognitions come from the real
 * [DateRecognizer] (integration), metadata from [FakeMetadataClient]. sql_preview structure is
 * asserted here; A8.5 adds the Translator round-trip.
 */
class RecipeBuilderSpec :
    StringSpec({
        val ref = LocalDate.of(2026, 5, 15)
        val tz = "Europe/Prague"
        val recognizer = DateRecognizer()
        val tableBacked = RecipeBuilder(FakeMetadataClient.accounting("cnc"))

        "table-backed 'May 2026' → JoinRecipe on AccountingPeriod with code 202605" {
            val rec = recognizer.recognize("May 2026", ref).shouldNotBeNull()
            val r = tableBacked.build("May 2026", rec, "cnc", tz).shouldNotBeNull()

            r.applicationCase shouldBe GroundingResult.ApplicationCase.JOIN
            val join = r.join
            join.entity.name shouldBe "AccountingPeriod"
            join.joinType shouldBe JoinType.INNER
            join.suggestedAlias shouldBe "ap"
            join.onCondition.function.operation shouldBe "and"
            join.onCondition.function
                .getOperands(0)
                .function.operation shouldBe "ge"
            join.onCondition.function
                .getOperands(1)
                .function.operation shouldBe "lt" // exclusive end
            join.filter.function.operation shouldBe "eq"
            val p = join.parametersList.single()
            p.name shouldBe "p"
            p.type shouldBe "text"
            p.value.stringValue shouldBe "202605"

            r.sqlPreview shouldContain "JOIN \"AccountingPeriod\" AS ap"
            r.sqlPreview shouldContain "t.\"date\" >= ap.\"start_date\""
            r.sqlPreview shouldContain "t.\"date\" < ap.\"end_date\""
            r.sqlPreview shouldContain "ap.\"period\" = {p}"
            r.source shouldBe GroundingResult.Source.RULES
            r.normalized.interval.start shouldContain "2026-05-01"
            r.normalized.interval.end shouldContain "2026-06-01"
        }

        "calendar-aligned 'May 2026' (no period table) → FilterRecipe over period_start/period_end" {
            val noTable =
                RecipeBuilder(
                    FakeMetadataClient(
                        listOf(
                            FakeMetadataClient.Obj("cnc", "Transaction", "date", "attribute", role = "event_date"),
                        ),
                    ),
                )
            val rec = recognizer.recognize("May 2026", ref).shouldNotBeNull()
            val r = noTable.build("May 2026", rec, "cnc", tz).shouldNotBeNull()

            r.applicationCase shouldBe GroundingResult.ApplicationCase.FILTER
            r.sqlPreview shouldContain "period_start({p})"
            r.sqlPreview shouldContain "period_end({p})"
            r.filter.parametersList
                .single()
                .value.stringValue shouldBe "202605"
            r.filter.anchorColumn.name shouldBe "date"
        }

        "plain interval 'yesterday' → FilterRecipe with datetime bounds" {
            val rec = recognizer.recognize("yesterday", ref).shouldNotBeNull()
            val r = tableBacked.build("yesterday", rec, "cnc", tz).shouldNotBeNull()

            r.applicationCase shouldBe GroundingResult.ApplicationCase.FILTER
            r.filter.parametersList.map { it.name } shouldBe listOf("start", "end")
            r.filter.parametersList[0]
                .value.datetimeValue shouldContain "2026-05-14"
            r.filter.parametersList[1]
                .value.datetimeValue shouldContain "2026-05-15" // exclusive next day
            r.sqlPreview shouldContain "t.\"date\" >= {start}"
            r.sqlPreview shouldContain "t.\"date\" < {end}"
        }

        "explicit DUE target anchors the recipe on the due_date column" {
            val rec = recognizer.recognize("due in May", ref).shouldNotBeNull()
            val r = tableBacked.build("due in May", rec, "cnc", tz).shouldNotBeNull()
            r.sqlPreview shouldContain "t.\"due\" >= ap.\"start_date\""
        }

        "no anchor date column in the package → null (caller emits UNGROUNDABLE)" {
            val empty = RecipeBuilder(FakeMetadataClient(emptyList()))
            val rec = recognizer.recognize("May 2026", ref).shouldNotBeNull()
            empty.build("May 2026", rec, "cnc", tz).shouldBeNull()
        }

        // Issue #140 — a period-coded fact with NO date column (accounting ledger keyed only by
        // period): the recipe binds the period code directly (`code = {p}`), no fact anchor needed.
        val datelessLedger =
            RecipeBuilder(
                FakeMetadataClient(
                    listOf(
                        FakeMetadataClient.Obj(
                            "ucet",
                            "UcetniObdobi",
                            "UcetniObdobi",
                            "entity",
                            semanticKind = "period_table",
                        ),
                        FakeMetadataClient.Obj(
                            "ucet",
                            "UcetniObdobi",
                            "PrvniDen",
                            "attribute",
                            role = "period_start",
                        ),
                        FakeMetadataClient.Obj("ucet", "UcetniObdobi", "PoslDen", "attribute", role = "period_end"),
                        FakeMetadataClient.Obj(
                            "ucet",
                            "UcetniObdobi",
                            "UCET_OBD",
                            "attribute",
                            role = "period_code",
                            codeFormat = "yyyy.MM",
                        ),
                        // No event_date / posting_date anywhere → anchorColumn is null.
                    ),
                ),
            )

        "date-less period-coded fact 'minulý měsíc' → FilterRecipe code = {p} (reformatted per code_format)" {
            val ref2 = LocalDate.of(2026, 7, 10)
            val rec = recognizer.recognize("minulý měsíc", ref2).shouldNotBeNull()
            rec.periodCode shouldBe "202606" // relative month now carries a period code

            val r = datelessLedger.build("minulý měsíc", rec, "ucet", tz).shouldNotBeNull()
            r.applicationCase shouldBe GroundingResult.ApplicationCase.FILTER
            r.filter.condition.function.operation shouldBe "eq"
            r.filter.anchorColumn.name shouldBe "UCET_OBD"
            val p = r.filter.parametersList.single()
            p.name shouldBe "p"
            p.type shouldBe "text"
            p.value.stringValue shouldBe "2026.06" // yyyyMM 202606 → yyyy.MM
            r.sqlPreview shouldContain "= {p}"
            r.normalized.interval.start shouldContain "2026-06-01"
            r.normalized.interval.end shouldContain "2026-07-01"
        }

        "date-less period-coded fact explicit 'období 202605' → FilterRecipe code = {p}" {
            val rec = recognizer.recognize("období 202605", ref).shouldNotBeNull()
            val r = datelessLedger.build("období 202605", rec, "ucet", tz).shouldNotBeNull()
            r.applicationCase shouldBe GroundingResult.ApplicationCase.FILTER
            r.filter.parametersList
                .single()
                .value.stringValue shouldBe "2026.05"
        }
    })
