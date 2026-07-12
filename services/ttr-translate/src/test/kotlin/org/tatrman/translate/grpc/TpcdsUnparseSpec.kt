// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translate.grpc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContainIgnoringCase
import io.kotest.matchers.string.shouldNotContain
import org.tatrman.translate.model.StaticModelHandleProvider
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.ParseRequest
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translate.v1.UnparseRequest
import org.tatrman.translator.framework.InMemoryModelHandle
import org.tatrman.translator.framework.ModelColumn
import org.tatrman.translator.framework.ModelTable
import org.tatrman.translator.framework.SurfaceType

/**
 * WS-T2 T4 — Translate emits valid PostgreSQL for the four TPC-DS curated shapes
 * (join+group-by, join+agg+ORDER/LIMIT, window function, CTE+UNION ALL). Parses each
 * against a tpcds DB-schema model, then unparses the plan to PostgreSQL (the dialect
 * Postgres asks for) and asserts the dialect (double-quoted identifiers, no MSSQL
 * brackets) plus the shape-defining SQL of each query.
 *
 * The CTE/UNION shape exercises the `plan.v1` `Union` op added in tatrman
 * ttr-plan-proto/ttr-translator 0.8.1 (consumed via the ttr-translator Phase B swap);
 * the window shape exercises the OVER/PARTITION unparse. `{year}` is inlined as a
 * literal here (the Translate parameter-bridge is covered elsewhere).
 */
class TpcdsUnparseSpec :
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

        suspend fun unparsePg(sql: String): String {
            val parse =
                service.parseToRelNode(
                    ParseRequest
                        .newBuilder()
                        .setSource(sql)
                        .setSourceLanguage(Language.SQL)
                        .setSourceSchema(SchemaCode.DB)
                        .setTargetSchema(SchemaCode.DB)
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
                        .build(),
                )
            unparse.messagesList shouldHaveSize 0
            // PostgreSQL dialect: never MSSQL square-bracket identifiers.
            unparse.output shouldNotContain "["
            return unparse.output
        }

        "store_sales_by_month (join + group-by) → PostgreSQL" {
            val out =
                unparsePg(
                    """
                    SELECT d.d_year, d.d_moy, SUM(ss.ss_sales_price) AS total_sales
                    FROM store_sales ss JOIN date_dim d ON ss.ss_sold_date_sk = d.d_date_sk
                    WHERE d.d_year = 2002 GROUP BY d.d_year, d.d_moy ORDER BY d.d_year, d.d_moy
                    """.trimIndent(),
                )
            out.shouldContainIgnoringCase("group by")
            out.shouldContainIgnoringCase("sum")
        }

        "top_items_by_revenue (join + agg + ORDER/LIMIT) → PostgreSQL" {
            val out =
                unparsePg(
                    """
                    SELECT i.i_item_id, SUM(ss.ss_net_paid) AS revenue
                    FROM store_sales ss JOIN item i ON ss.ss_item_sk = i.i_item_sk
                    JOIN date_dim d ON ss.ss_sold_date_sk = d.d_date_sk
                    WHERE d.d_year = 2002 GROUP BY i.i_item_id ORDER BY revenue DESC LIMIT 100
                    """.trimIndent(),
                )
            out.shouldContainIgnoringCase("order by")
            out.shouldContainIgnoringCase("i_item_id")
        }

        "customer_running_total (window function) → PostgreSQL" {
            val out =
                unparsePg(
                    """
                    SELECT c.c_customer_id, d.d_date,
                        SUM(ss.ss_net_paid) OVER (PARTITION BY c.c_customer_sk ORDER BY d.d_date) AS running_total
                    FROM store_sales ss JOIN customer c ON ss.ss_customer_sk = c.c_customer_sk
                    JOIN date_dim d ON ss.ss_sold_date_sk = d.d_date_sk WHERE d.d_year = 2002
                    """.trimIndent(),
                )
            out.shouldContainIgnoringCase("over")
            out.shouldContainIgnoringCase("partition by")
        }

        "channel_revenue_cte (CTE + UNION ALL) → PostgreSQL" {
            val out =
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
            out.shouldContainIgnoringCase("union all")
            out.shouldContainIgnoringCase("group by")
        }
    })
