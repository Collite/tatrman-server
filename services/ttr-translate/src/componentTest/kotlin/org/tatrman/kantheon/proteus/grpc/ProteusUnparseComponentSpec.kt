package org.tatrman.kantheon.proteus.grpc

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.tatrman.kantheon.proteus.model.StaticModelHandleProvider
import org.tatrman.plan.v1.ParameterBinding
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.Value
import org.tatrman.proteus.v1.Language
import org.tatrman.proteus.v1.ParseRequest
import org.tatrman.proteus.v1.SqlDialect
import org.tatrman.proteus.v1.UnparseRequest
import org.tatrman.translator.framework.InMemoryModelHandle
import org.tatrman.translator.framework.ModelColumn
import org.tatrman.translator.framework.ModelTable
import org.tatrman.translator.framework.SurfaceType
import java.nio.file.Files
import java.nio.file.Path

/**
 * WS-C1 T2 — the component-tier **golden-SQL** PostgreSQL unparse matrix. Where the unit
 * `TpcdsUnparseSpec` only asserts a few shape-defining substrings, this drives the **real**
 * ttr-translator parse → RelNode → unparse path end-to-end for the four TPC-DS curated shapes
 * (join+group-by, join+agg+ORDER/LIMIT, window function, CTE+UNION ALL) and freezes the **exact**
 * emitted PostgreSQL to golden files under `src/componentTest/resources/proteus/`. An exact-match
 * regression catches dialect drift (identifier quoting, function spelling, LIMIT/OFFSET form) that
 * a substring assertion would miss. It also pins the named-parameter `{name}→?` positional rewrite.
 *
 * No container — but it exercises the real translator (not a mock), so it belongs to the component
 * tier, out of the mocked `test` gate.
 *
 * **Regenerating the goldens:** run with `RECORD_GOLDEN=1` in the environment
 * (`RECORD_GOLDEN=1 ./gradlew :services:proteus:componentTest`) after a deliberate, reviewed
 * change to the translator output; the spec writes the current output to the resource dir and
 * passes. Commit the diff only when the change is intended.
 */
@Tags("component")
class ProteusUnparseComponentSpec :
    StringSpec({

        fun qn(name: String): QualifiedName =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.DB)
                .setNamespace("dbo")
                .setName(name)
                .build()

        fun col(
            name: String,
            type: SurfaceType,
        ) = ModelColumn(name = name, surfaceType = type)

        val model =
            InMemoryModelHandle(
                tables =
                    listOf(
                        ModelTable(
                            qn("store_sales"),
                            listOf(
                                col("ss_sold_date_sk", SurfaceType.INT),
                                col("ss_item_sk", SurfaceType.INT),
                                col("ss_customer_sk", SurfaceType.INT),
                                col("ss_sales_price", SurfaceType.FLOAT),
                                col("ss_net_paid", SurfaceType.FLOAT),
                            ),
                        ),
                        ModelTable(
                            qn("catalog_sales"),
                            listOf(
                                col("cs_sold_date_sk", SurfaceType.INT),
                                col("cs_net_paid", SurfaceType.FLOAT),
                            ),
                        ),
                        ModelTable(
                            qn("web_sales"),
                            listOf(
                                col("ws_sold_date_sk", SurfaceType.INT),
                                col("ws_net_paid", SurfaceType.FLOAT),
                            ),
                        ),
                        ModelTable(
                            qn("date_dim"),
                            listOf(
                                col("d_date_sk", SurfaceType.INT),
                                col("d_date", SurfaceType.DATETIME),
                                col("d_year", SurfaceType.INT),
                                col("d_moy", SurfaceType.INT),
                            ),
                        ),
                        ModelTable(
                            qn("item"),
                            listOf(
                                col("i_item_sk", SurfaceType.INT),
                                col("i_item_id", SurfaceType.TEXT),
                            ),
                        ),
                        ModelTable(
                            qn("customer"),
                            listOf(
                                col("c_customer_sk", SurfaceType.INT),
                                col("c_customer_id", SurfaceType.TEXT),
                            ),
                        ),
                    ),
            )

        val service = TranslatorServiceImpl(StaticModelHandleProvider(model))

        /** Parse [sql] (with optional named [params]) and unparse the plan to PostgreSQL. */
        suspend fun unparsePg(
            sql: String,
            params: PipelineContext = PipelineContext.getDefaultInstance(),
        ): UnparseResult {
            val parse =
                service.parseToRelNode(
                    ParseRequest
                        .newBuilder()
                        .setSource(sql)
                        .setSourceLanguage(Language.SQL)
                        .setSourceSchema(SchemaCode.DB)
                        .setTargetSchema(SchemaCode.DB)
                        .setContext(params)
                        .build(),
                )
            parse.messagesList shouldHaveSize 0
            parse.hasPlan() shouldBe true
            val unparse =
                service.unparseFromRelNode(
                    UnparseRequest
                        .newBuilder()
                        .setPlan(parse.plan)
                        .setTargetLanguage(Language.SQL)
                        .setTargetDialect(SqlDialect.POSTGRESQL)
                        .setContext(params)
                        .build(),
                )
            unparse.messagesList shouldHaveSize 0
            // PostgreSQL dialect: never MSSQL square-bracket identifiers.
            unparse.output shouldNotContain "["
            return UnparseResult(unparse.output, unparse.context.parametersList.size)
        }

        /** Assert [name].sql golden matches [actual] (or write it under RECORD_GOLDEN=1). */
        fun assertGolden(
            name: String,
            actual: String,
        ) {
            val resource = "proteus/$name.sql"
            if (System.getenv("RECORD_GOLDEN") == "1") {
                val dir =
                    Path.of(
                        System.getProperty("integrationHarness.repoRoot")
                            ?: error("integrationHarness.repoRoot not set — cannot record goldens"),
                        "services/proteus/src/componentTest/resources/proteus",
                    )
                Files.createDirectories(dir)
                Files.writeString(dir.resolve("$name.sql"), actual.trimEnd() + "\n")
                return
            }
            val golden =
                ProteusUnparseComponentSpec::class.java.classLoader
                    .getResourceAsStream(resource)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: error("golden not found: $resource — run once with RECORD_GOLDEN=1 to create it")
            actual.trimEnd() shouldBe golden.trimEnd()
        }

        "q1 store_sales_by_month (join + group-by) → golden PostgreSQL" {
            val r =
                unparsePg(
                    """
                    SELECT d.d_year, d.d_moy, SUM(ss.ss_sales_price) AS total_sales
                    FROM store_sales ss JOIN date_dim d ON ss.ss_sold_date_sk = d.d_date_sk
                    WHERE d.d_year = 2002 GROUP BY d.d_year, d.d_moy ORDER BY d.d_year, d.d_moy
                    """.trimIndent(),
                )
            assertGolden("q1_store_sales_by_month", r.sql)
        }

        "q2 top_items_by_revenue (join + agg + ORDER/LIMIT) → golden PostgreSQL" {
            val r =
                unparsePg(
                    """
                    SELECT i.i_item_id, SUM(ss.ss_net_paid) AS revenue
                    FROM store_sales ss JOIN item i ON ss.ss_item_sk = i.i_item_sk
                    JOIN date_dim d ON ss.ss_sold_date_sk = d.d_date_sk
                    WHERE d.d_year = 2002 GROUP BY i.i_item_id ORDER BY revenue DESC LIMIT 100
                    """.trimIndent(),
                )
            assertGolden("q2_top_items_by_revenue", r.sql)
        }

        "q3 customer_running_total (window function) → golden PostgreSQL" {
            val r =
                unparsePg(
                    """
                    SELECT c.c_customer_id, d.d_date,
                        SUM(ss.ss_net_paid) OVER (PARTITION BY c.c_customer_sk ORDER BY d.d_date) AS running_total
                    FROM store_sales ss JOIN customer c ON ss.ss_customer_sk = c.c_customer_sk
                    JOIN date_dim d ON ss.ss_sold_date_sk = d.d_date_sk WHERE d.d_year = 2002
                    """.trimIndent(),
                )
            assertGolden("q3_customer_running_total", r.sql)
        }

        "q4 channel_revenue_cte (CTE + UNION ALL) → golden PostgreSQL" {
            val r =
                unparsePg(
                    """
                    WITH all_sales AS (
                        SELECT ss_sold_date_sk AS sold_date_sk, ss_net_paid AS net_paid, 'store' AS channel FROM store_sales
                        UNION ALL
                        SELECT cs_sold_date_sk AS sold_date_sk, cs_net_paid AS net_paid, 'catalog' AS channel FROM catalog_sales
                        UNION ALL
                        SELECT ws_sold_date_sk AS sold_date_sk, ws_net_paid AS net_paid, 'web' AS channel FROM web_sales
                    )
                    SELECT a.channel, SUM(a.net_paid) AS revenue FROM all_sales a
                    JOIN date_dim d ON a.sold_date_sk = d.d_date_sk WHERE d.d_year = 2002
                    GROUP BY a.channel ORDER BY revenue DESC
                    """.trimIndent(),
                )
            assertGolden("q4_channel_revenue_cte", r.sql)
        }

        "a named {year} parameter rewrites to a positional ? placeholder" {
            val ctx =
                PipelineContext
                    .newBuilder()
                    .addParameters(
                        ParameterBinding
                            .newBuilder()
                            .setName("year")
                            .setType("int")
                            .setValue(Value.newBuilder().setIntValue(2002)),
                    ).build()
            val r =
                unparsePg(
                    """
                    SELECT d.d_year, SUM(ss.ss_sales_price) AS total_sales
                    FROM store_sales ss JOIN date_dim d ON ss.ss_sold_date_sk = d.d_date_sk
                    WHERE d.d_year = {year} GROUP BY d.d_year
                    """.trimIndent(),
                    ctx,
                )
            // The named {year} became a single positional bind — no literal 2002 inlined.
            r.sql shouldContain "?"
            r.paramCount shouldBe 1
            assertGolden("q5_named_param", r.sql)
        }
    })

/** Unparse output plus the positional parameter count the plan carried out. */
private data class UnparseResult(
    val sql: String,
    val paramCount: Int,
)
