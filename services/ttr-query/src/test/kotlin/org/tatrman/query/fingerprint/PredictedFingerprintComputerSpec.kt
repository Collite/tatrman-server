// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.fingerprint

import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.NamedExpression
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.ProjectNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.TableScanNode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch

class PredictedFingerprintComputerSpec :
    StringSpec({
        "deterministic across calls with the same projecting plan" {
            val plan = projectPlan(listOf("id" to "int", "name" to "text"))
            val a = PredictedFingerprintComputer.compute(plan)
            val b = PredictedFingerprintComputer.compute(plan)
            a shouldBe b
            a.shouldMatch("^[0-9a-f]{64}$".toRegex())
        }

        "differs when a column name changes" {
            val a = PredictedFingerprintComputer.compute(projectPlan(listOf("id" to "int")))
            val b = PredictedFingerprintComputer.compute(projectPlan(listOf("user_id" to "int")))
            a shouldNotBe b
        }

        "differs when a column type changes" {
            val a = PredictedFingerprintComputer.compute(projectPlan(listOf("id" to "int")))
            val b = PredictedFingerprintComputer.compute(projectPlan(listOf("id" to "text")))
            a shouldNotBe b
        }

        "walks through LimitOffset and Sort wrappers" {
            val inner = projectPlan(listOf("id" to "int"))
            val limit =
                PlanNode
                    .newBuilder()
                    .setLimitOffset(
                        org.tatrman.plan.v1.LimitOffsetNode
                            .newBuilder()
                            .setInput(inner)
                            .setLimit(10),
                    ).build()
            PredictedFingerprintComputer.compute(limit) shouldBe PredictedFingerprintComputer.compute(inner)
        }

        "TableScan-only plan uses output columns" {
            val a =
                PredictedFingerprintComputer.compute(
                    PlanNode
                        .newBuilder()
                        .setTableScan(
                            TableScanNode
                                .newBuilder()
                                .setTable(
                                    QualifiedName
                                        .newBuilder()
                                        .setSchemaCode(org.tatrman.plan.v1.SchemaCode.DB)
                                        .setNamespace("dbo")
                                        .setName("t"),
                                ).addOutputColumns(
                                    ColumnRef.newBuilder().setName("a").setType("int"),
                                ),
                        ).build(),
                )
            val b =
                PredictedFingerprintComputer.compute(
                    PlanNode
                        .newBuilder()
                        .setTableScan(
                            TableScanNode
                                .newBuilder()
                                .setTable(
                                    QualifiedName
                                        .newBuilder()
                                        .setSchemaCode(org.tatrman.plan.v1.SchemaCode.DB)
                                        .setNamespace("dbo")
                                        .setName("t"),
                                ).addOutputColumns(
                                    ColumnRef.newBuilder().setName("a").setType("text"),
                                ),
                        ).build(),
                )
            a shouldNotBe b
        }
    })

private fun projectPlan(columns: List<Pair<String, String>>): PlanNode =
    PlanNode
        .newBuilder()
        .setProject(
            ProjectNode
                .newBuilder()
                .setInput(
                    PlanNode
                        .newBuilder()
                        .setTableScan(
                            TableScanNode.newBuilder().setTable(
                                QualifiedName
                                    .newBuilder()
                                    .setSchemaCode(org.tatrman.plan.v1.SchemaCode.DB)
                                    .setNamespace("dbo")
                                    .setName("t"),
                            ),
                        ),
                ).also { proj ->
                    columns.forEach { (n, t) ->
                        proj.addExpressions(
                            NamedExpression
                                .newBuilder()
                                .setExpression(
                                    Expression
                                        .newBuilder()
                                        .setColumnRef(ColumnRef.newBuilder().setName(n))
                                        .setResultType(t),
                                ).setAlias(n),
                        )
                    }
                },
        ).build()
