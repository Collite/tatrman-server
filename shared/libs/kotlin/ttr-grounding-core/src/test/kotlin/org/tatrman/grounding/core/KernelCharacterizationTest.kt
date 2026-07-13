// SPDX-License-Identifier: Apache-2.0
package org.tatrman.grounding.core

import com.google.protobuf.util.JsonFormat
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.tatrman.plan.v1.Expression
import java.io.File

/**
 * RG-P3.S1.T2 — the kernel characterization golden. Locks [SqlRenderer].render for the full recipe
 * expression surface the three services emit: **ValueBinding** (a bare comparison / column ref),
 * **FilterRecipe** (a boolean condition tree), and **JoinRecipe** (on-condition + filter), ≥15 cases
 * per service (chrono / geo / money). Each case's `plan.v1` Expression is captured as JSON alongside
 * its rendered SQL in `sql-preview-golden.jsonl`; the test re-renders the built tree AND re-parses the
 * stored JSON, asserting both reproduce the frozen SQL byte-identically.
 *
 * These trees mirror the exact shapes [org.tatrman.grounding.core.PlanExpr] produces inside the
 * chrono/geo/money recipe builders (the renderer was byte-identical across chrono/geo, +`mul` in
 * money). Behaviour-equivalence to the *pre-extraction* services is separately guaranteed by S1.T3
 * keeping every inherited service suite (with its own `sql_preview` assertions) green after the
 * services are repointed onto this kernel. Regenerate the golden by deleting it and re-running.
 */
class KernelCharacterizationTest :
    StringSpec({

        // ---- curated recipe-shaped expressions, per service, per recipe kind ----
        data class Case(
            val name: String,
            val service: String,
            val kind: String, // ValueBinding | FilterRecipe | JoinRecipe
            val expr: Expression,
        )

        // service + recipe kind are derived from the dotted name (`<service>.<value|filter|join>.<...>`).
        fun case(
            name: String,
            expr: Expression,
        ): Case {
            val kind =
                when (name.split(".")[1]) {
                    "value" -> "ValueBinding"
                    "filter" -> "FilterRecipe"
                    "join" -> "JoinRecipe"
                    else -> error("unknown recipe kind in case name '$name'")
                }
            return Case(name, name.substringBefore("."), kind, expr)
        }

        val pe = PlanExpr
        val cases =
            buildList {
                // ===== chrono — period / interval =====
                add(case("chrono.value.event_date", pe.col("t", "date")))
                add(case("chrono.value.period_start_of_code", pe.periodStart(pe.param("code", "text"))))
                add(
                    case(
                        "chrono.filter.plain_interval",
                        pe.and(
                            pe.ge(pe.col("t", "date"), pe.param("from", "datetime")),
                            pe.lt(pe.col("t", "date"), pe.param("to", "datetime")),
                        ),
                    ),
                )
                add(
                    case(
                        "chrono.filter.calendar_period",
                        pe.and(
                            pe.ge(pe.col("t", "date"), pe.periodStart(pe.param("code", "text"))),
                            pe.lt(pe.col("t", "date"), pe.periodEnd(pe.param("code", "text"))),
                        ),
                    ),
                )
                add(
                    case(
                        "chrono.filter.period_code_eq",
                        pe.eq(pe.col("t", "period", "text"), pe.param("code", "text")),
                    ),
                )
                add(
                    case(
                        "chrono.filter.due_anchor_interval",
                        pe.and(
                            pe.ge(pe.col("t", "due"), pe.param("from", "datetime")),
                            pe.lt(pe.col("t", "due"), pe.param("to", "datetime")),
                        ),
                    ),
                )
                add(
                    case(
                        "chrono.join.period_table_on",
                        pe.and(
                            pe.ge(pe.col("t", "date"), pe.col("ap", "start_date")),
                            pe.lt(pe.col("t", "date"), pe.col("ap", "end_date")),
                        ),
                    ),
                )
                add(
                    case(
                        "chrono.join.period_table_filter",
                        pe.eq(pe.col("ap", "period", "text"), pe.param("code", "text")),
                    ),
                )
                add(case("chrono.value.due_col", pe.col("t", "due")))
                add(case("chrono.value.period_end_of_code", pe.periodEnd(pe.param("code", "text"))))
                add(case("chrono.filter.open_ended_since", pe.ge(pe.col("t", "date"), pe.param("from", "datetime"))))
                add(case("chrono.filter.before_bound", pe.lt(pe.col("t", "date"), pe.param("to", "datetime"))))
                add(
                    case(
                        "chrono.filter.posting_anchor_interval",
                        pe.and(
                            pe.ge(pe.col("t", "posted"), pe.param("from", "datetime")),
                            pe.lt(pe.col("t", "posted"), pe.param("to", "datetime")),
                        ),
                    ),
                )
                add(
                    case(
                        "chrono.filter.document_anchor_interval",
                        pe.and(
                            pe.ge(pe.col("t", "doc_date"), pe.param("from", "datetime")),
                            pe.lt(pe.col("t", "doc_date"), pe.param("to", "datetime")),
                        ),
                    ),
                )
                add(
                    case(
                        "chrono.join.period_table_on_due",
                        pe.and(
                            pe.ge(pe.col("t", "due"), pe.col("ap", "start_date")),
                            pe.lt(pe.col("t", "due"), pe.col("ap", "end_date")),
                        ),
                    ),
                )

                // ===== money — domestic / native-foreign / fx-join =====
                add(case("money.value.amount_domestic", pe.col("t", "amount_dom", "decimal")))
                add(
                    case(
                        "money.value.amount_times_rate",
                        pe.mul(pe.col("t", "amount", "decimal"), pe.col("fx", "rate", "decimal")),
                    ),
                )
                add(
                    case(
                        "money.filter.domestic_ge",
                        pe.ge(pe.col("t", "amount_dom", "decimal"), pe.param("amt", "decimal")),
                    ),
                )
                add(
                    case(
                        "money.filter.domestic_gt",
                        pe.gt(pe.col("t", "amount", "decimal"), pe.param("amt", "decimal")),
                    ),
                )
                add(
                    case(
                        "money.filter.domestic_lt",
                        pe.lt(pe.col("t", "amount", "decimal"), pe.param("amt", "decimal")),
                    ),
                )
                add(
                    case(
                        "money.filter.domestic_le",
                        pe.le(pe.col("t", "amount", "decimal"), pe.param("amt", "decimal")),
                    ),
                )
                add(
                    case(
                        "money.filter.tolerance_band",
                        pe.and(
                            pe.ge(pe.col("t", "amount", "decimal"), pe.param("lower", "decimal")),
                            pe.le(pe.col("t", "amount", "decimal"), pe.param("upper", "decimal")),
                        ),
                    ),
                )
                add(
                    case(
                        "money.filter.native_foreign",
                        pe.and(
                            pe.ge(pe.col("t", "amount", "decimal"), pe.param("amt", "decimal")),
                            pe.eq(pe.col("t", "currency", "text"), pe.param("ccy", "text")),
                        ),
                    ),
                )
                add(
                    case(
                        "money.join.fx_on_pair",
                        pe.and(
                            pe.eq(pe.col("fx", "from_ccy", "text"), pe.param("ccy", "text")),
                            pe.eq(pe.col("fx", "to_ccy", "text"), pe.param("domestic", "text")),
                        ),
                    ),
                )
                add(
                    case(
                        "money.join.fx_on_pair_with_validity",
                        pe.and(
                            pe.and(
                                pe.eq(pe.col("fx", "from_ccy", "text"), pe.param("ccy", "text")),
                                pe.eq(pe.col("fx", "to_ccy", "text"), pe.param("domestic", "text")),
                            ),
                            pe.and(
                                pe.ge(pe.param("ref", "datetime"), pe.col("fx", "valid_from", "datetime")),
                                pe.lt(pe.param("ref", "datetime"), pe.col("fx", "valid_to", "datetime")),
                            ),
                        ),
                    ),
                )
                add(
                    case(
                        "money.join.fx_converted_ge",
                        pe.ge(
                            pe.mul(pe.col("t", "amount", "decimal"), pe.col("fx", "rate", "decimal")),
                            pe.param("amt", "decimal"),
                        ),
                    ),
                )
                add(case("money.value.rate_col", pe.col("fx", "rate", "decimal")))
                add(
                    case(
                        "money.filter.native_foreign_gt",
                        pe.and(
                            pe.gt(pe.col("t", "amount", "decimal"), pe.param("amt", "decimal")),
                            pe.eq(pe.col("t", "currency", "text"), pe.param("ccy", "text")),
                        ),
                    ),
                )
                add(
                    case(
                        "money.join.fx_converted_le",
                        pe.le(
                            pe.mul(pe.col("t", "amount", "decimal"), pe.col("fx", "rate", "decimal")),
                            pe.param("amt", "decimal"),
                        ),
                    ),
                )
                add(
                    case(
                        "money.join.fx_on_pair_event_date_validity",
                        pe.and(
                            pe.and(
                                pe.eq(pe.col("fx", "from_ccy", "text"), pe.param("ccy", "text")),
                                pe.eq(pe.col("fx", "to_ccy", "text"), pe.param("domestic", "text")),
                            ),
                            pe.and(
                                pe.ge(pe.col("t", "date", "datetime"), pe.col("fx", "valid_from", "datetime")),
                                pe.lt(pe.col("t", "date", "datetime"), pe.col("fx", "valid_to", "datetime")),
                            ),
                        ),
                    ),
                )

                // ===== geo — distance / containment / poi-join =====
                add(
                    case(
                        "geo.value.distance_m",
                        pe.geoDistanceM(
                            pe.col("t", "lat", "float"),
                            pe.col("t", "lon", "float"),
                            pe.param("clat", "float"),
                            pe.param("clon", "float"),
                        ),
                    ),
                )
                add(case("geo.value.lat_col", pe.col("t", "lat", "float")))
                add(
                    case(
                        "geo.filter.within_radius",
                        pe.le(
                            pe.geoDistanceM(
                                pe.col("t", "lat", "float"),
                                pe.col("t", "lon", "float"),
                                pe.param("clat", "float"),
                                pe.param("clon", "float"),
                            ),
                            pe.param("radius", "float"),
                        ),
                    ),
                )
                add(
                    case(
                        "geo.filter.bbox_lat",
                        pe.and(
                            pe.ge(pe.col("t", "lat", "float"), pe.param("min_lat", "float")),
                            pe.le(pe.col("t", "lat", "float"), pe.param("max_lat", "float")),
                        ),
                    ),
                )
                add(
                    case(
                        "geo.filter.bbox_containment",
                        pe.and(
                            pe.and(
                                pe.ge(pe.col("t", "lat", "float"), pe.param("min_lat", "float")),
                                pe.le(pe.col("t", "lat", "float"), pe.param("max_lat", "float")),
                            ),
                            pe.and(
                                pe.ge(pe.col("t", "lon", "float"), pe.param("min_lon", "float")),
                                pe.le(pe.col("t", "lon", "float"), pe.param("max_lon", "float")),
                            ),
                        ),
                    ),
                )
                add(case("geo.join.poi_on_name", pe.eq(pe.col("poi", "store_name", "text"), pe.param("place", "text"))))
                add(
                    case(
                        "geo.join.poi_distance_filter",
                        pe.le(
                            pe.geoDistanceM(
                                pe.col("t", "lat", "float"),
                                pe.col("t", "lon", "float"),
                                pe.col("poi", "lat", "float"),
                                pe.col("poi", "lon", "float"),
                            ),
                            pe.param("radius", "float"),
                        ),
                    ),
                )
                add(case("geo.value.lon_col", pe.col("t", "lon", "float")))
                add(case("geo.value.poi_lat_col", pe.col("poi", "lat", "float")))
                add(
                    case(
                        "geo.value.distance_to_poi",
                        pe.geoDistanceM(
                            pe.col("t", "lat", "float"),
                            pe.col("t", "lon", "float"),
                            pe.col("poi", "lat", "float"),
                            pe.col("poi", "lon", "float"),
                        ),
                    ),
                )
                add(
                    case(
                        "geo.filter.bbox_lon",
                        pe.and(
                            pe.ge(pe.col("t", "lon", "float"), pe.param("min_lon", "float")),
                            pe.le(pe.col("t", "lon", "float"), pe.param("max_lon", "float")),
                        ),
                    ),
                )
                add(case("geo.filter.min_lat_only", pe.ge(pe.col("t", "lat", "float"), pe.param("min_lat", "float"))))
                add(case("geo.filter.max_lat_only", pe.le(pe.col("t", "lat", "float"), pe.param("max_lat", "float"))))
                add(
                    case(
                        "geo.join.poi_on_name_and_distance",
                        pe.and(
                            pe.eq(pe.col("poi", "store_name", "text"), pe.param("place", "text")),
                            pe.le(
                                pe.geoDistanceM(
                                    pe.col("t", "lat", "float"),
                                    pe.col("t", "lon", "float"),
                                    pe.col("poi", "lat", "float"),
                                    pe.col("poi", "lon", "float"),
                                ),
                                pe.param("radius", "float"),
                            ),
                        ),
                    ),
                )
                add(
                    case(
                        "geo.join.poi_name_ne_fallback",
                        pe.eq(pe.col("poi", "code", "text"), pe.param("place", "text")),
                    ),
                )
            }

        val goldenFile = File("src/test/resources/sql-preview-golden.jsonl")
        val json = Json { }
        val printer = JsonFormat.printer().omittingInsignificantWhitespace()
        val parser = JsonFormat.parser().ignoringUnknownFields()

        // Record mode: write the frozen golden on first run (or after a deliberate delete).
        if (!goldenFile.exists()) {
            goldenFile.parentFile.mkdirs()
            goldenFile.writeText(
                cases.joinToString("\n", postfix = "\n") { c ->
                    buildJsonObject {
                        put("name", c.name)
                        put("service", c.service)
                        put("kind", c.kind)
                        put("sql", SqlRenderer.render(c.expr))
                        put("expr", json.parseToJsonElement(printer.print(c.expr)))
                    }.toString()
                },
            )
        }

        val golden =
            goldenFile
                .readLines()
                .filter { it.isNotBlank() }
                .map { json.parseToJsonElement(it) as JsonObject }
                .associateBy { it["name"]!!.jsonPrimitive.content }

        "every curated case has a golden entry and vice versa" {
            cases.map { it.name }.toSet() shouldBe golden.keys
        }

        "coverage: ≥15 cases per service across ValueBinding / FilterRecipe / JoinRecipe" {
            listOf("chrono", "geo", "money").forEach { svc ->
                val svcCases = cases.filter { it.service == svc }
                (svcCases.size >= 15) shouldBe true
                svcCases.map { it.kind }.toSet() shouldBe setOf("ValueBinding", "FilterRecipe", "JoinRecipe")
            }
        }

        cases.forEach { c ->
            "kernel reproduces the golden sql_preview for ${c.name}" {
                val expectedSql = golden[c.name]!!["sql"]!!.jsonPrimitive.content
                // (1) the built tree renders to the frozen SQL
                SqlRenderer.render(c.expr) shouldBe expectedSql
                // (2) the stored plan.v1 Expression JSON re-parses and renders to the same SQL
                val rebuilt =
                    Expression
                        .newBuilder()
                        .also {
                            parser.merge(
                                golden[c.name]!!["expr"].toString(),
                                it,
                            )
                        }.build()
                SqlRenderer.render(rebuilt) shouldBe expectedSql
            }
        }

        // renderJoin centralises the JoinRecipe sql_preview the three services used to hand-assemble.
        "renderJoin emits JOIN \"entity\" AS alias ON <on> WHERE <filter> from the Expression trees" {
            val on =
                pe.and(
                    pe.eq(pe.col("fx", "from_ccy", "text"), pe.param("ccy", "text")),
                    pe.eq(pe.col("fx", "to_ccy", "text"), pe.param("domestic", "text")),
                )
            val filter =
                pe.ge(
                    pe.mul(pe.col("t", "amount", "decimal"), pe.col("fx", "rate", "decimal")),
                    pe.param("amt", "decimal"),
                )
            SqlRenderer.renderJoin("FxRate", "fx", on, filter) shouldBe
                "JOIN \"FxRate\" AS fx ON fx.\"from_ccy\" = {ccy} AND fx.\"to_ccy\" = {domestic} " +
                "WHERE t.\"amount\" * fx.\"rate\" >= {amt}"
        }

        "renderJoin matches the chrono period-table and geo POI join shapes" {
            SqlRenderer.renderJoin(
                "AccountingPeriod",
                "ap",
                pe.and(
                    pe.ge(pe.col("t", "date"), pe.col("ap", "start_date")),
                    pe.lt(pe.col("t", "date"), pe.col("ap", "end_date")),
                ),
                pe.eq(pe.col("ap", "period", "text"), pe.param("code", "text")),
            ) shouldBe
                "JOIN \"AccountingPeriod\" AS ap ON t.\"date\" >= ap.\"start_date\" AND t.\"date\" < ap.\"end_date\" " +
                "WHERE ap.\"period\" = {code}"
        }
    })
