package org.tatrman.kantheon.arges.pipeline

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.plan.v1.AggregateNode
import org.tatrman.plan.v1.JoinNode
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.plan.v1.UnionNode

/**
 * WS-T2 — the worker concretizes a logical plan's table namespace to the connection's physical
 * schema before unparse (the model's default `dbo` → Postgres `public`). Proves the rewrite reaches
 * every table scan, including ones nested under joins, aggregates, and unions, and that a blank
 * schema is a no-op.
 */
class PlanSchemaConcretizerSpec :
    StringSpec({

        fun scan(name: String): PlanNode =
            PlanNode
                .newBuilder()
                .setTableScan(
                    TableScanNode.newBuilder().setTable(
                        QualifiedName
                            .newBuilder()
                            .setSchemaCode(SchemaCode.DB)
                            .setNamespace("dbo")
                            .setName(name),
                    ),
                ).build()

        fun namespacesOf(plan: PlanNode): List<String> =
            when (plan.nodeCase) {
                PlanNode.NodeCase.TABLE_SCAN -> listOf(plan.tableScan.table.namespace)
                PlanNode.NodeCase.JOIN -> namespacesOf(plan.join.left) + namespacesOf(plan.join.right)
                PlanNode.NodeCase.AGGREGATE -> namespacesOf(plan.aggregate.input)
                PlanNode.NodeCase.UNION -> plan.union.inputsList.flatMap { namespacesOf(it) }
                else -> emptyList()
            }

        "rewrites a bare table-scan namespace to the connection schema" {
            val out = PlanSchemaConcretizer.withSchema(scan("store_sales"), "public")
            out.tableScan.table.namespace shouldBe "public"
            // Everything else is preserved.
            out.tableScan.table.name shouldBe "store_sales"
            out.tableScan.table.schemaCode shouldBe SchemaCode.DB
        }

        "recurses through a join into both sides" {
            val join =
                PlanNode
                    .newBuilder()
                    .setJoin(JoinNode.newBuilder().setLeft(scan("store_sales")).setRight(scan("date_dim")))
                    .build()
            val out = PlanSchemaConcretizer.withSchema(join, "public")
            namespacesOf(out) shouldBe listOf("public", "public")
        }

        "recurses through aggregate + union (the CTE/UNION shape)" {
            val union =
                PlanNode
                    .newBuilder()
                    .setUnion(
                        UnionNode
                            .newBuilder()
                            .addInputs(scan("store_sales"))
                            .addInputs(scan("catalog_sales"))
                            .addInputs(scan("web_sales")),
                    ).build()
            val agg =
                PlanNode.newBuilder().setAggregate(AggregateNode.newBuilder().setInput(union)).build()
            val out = PlanSchemaConcretizer.withSchema(agg, "public")
            namespacesOf(out) shouldBe listOf("public", "public", "public")
        }

        "a blank schema is a no-op" {
            val out = PlanSchemaConcretizer.withSchema(scan("store_sales"), "")
            out.tableScan.table.namespace shouldBe "dbo"
        }
    })
