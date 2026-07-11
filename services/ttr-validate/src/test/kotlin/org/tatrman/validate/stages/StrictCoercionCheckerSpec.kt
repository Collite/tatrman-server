package org.tatrman.validate.stages

import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.FilterNode
import org.tatrman.plan.v1.FunctionCall
import org.tatrman.plan.v1.Literal
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.TableScanNode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class StrictCoercionCheckerSpec :
    StringSpec({

        fun col(
            name: String,
            type: String,
        ): Expression =
            Expression
                .newBuilder()
                .setColumnRef(ColumnRef.newBuilder().setName(name).setType(type))
                .setResultType(type)
                .build()

        fun stringLit(v: String): Expression =
            Expression
                .newBuilder()
                .setLiteral(Literal.newBuilder().setStringValue(v).setType("text"))
                .setResultType("text")
                .build()

        fun intLit(v: Long): Expression =
            Expression
                .newBuilder()
                .setLiteral(Literal.newBuilder().setIntValue(v).setType("int"))
                .setResultType("int")
                .build()

        fun filterPlan(condition: Expression): PlanNode {
            val scan =
                PlanNode
                    .newBuilder()
                    .setTableScan(
                        TableScanNode.newBuilder().setTable(
                            QualifiedName
                                .newBuilder()
                                .setSchemaCode(org.tatrman.plan.v1.SchemaCode.DB)
                                .setNamespace("dbo")
                                .setName("customers"),
                        ),
                    ).build()
            return PlanNode
                .newBuilder()
                .setFilter(FilterNode.newBuilder().setInput(scan).setCondition(condition))
                .build()
        }

        fun call(
            op: String,
            vararg operands: Expression,
            resultType: String = "bool",
        ): Expression =
            Expression
                .newBuilder()
                .setFunction(
                    FunctionCall.newBuilder().setOperation(op).addAllOperands(operands.toList()),
                ).setResultType(resultType)
                .build()

        "eq between int column and int literal is clean (no violations)" {
            val plan = filterPlan(call("eq", col("id", "int"), intLit(42)))
            StrictCoercionChecker.check(plan) shouldBe emptyList()
        }

        "eq between int column and text literal is rejected" {
            val plan = filterPlan(call("eq", col("id", "int"), stringLit("42")))
            val out = StrictCoercionChecker.check(plan)
            out.size shouldBe 1
            out[0].code shouldBe "strict_coercion_rejected"
            out[0].humanMessage shouldContain "'eq'"
            out[0].humanMessage shouldContain "'int'"
            out[0].humanMessage shouldContain "'text'"
        }

        "compatible numeric widening (int <-> float) is clean" {
            val plan = filterPlan(call("lt", col("price", "float"), intLit(100)))
            StrictCoercionChecker.check(plan) shouldBe emptyList()
        }

        "compatible text family (text <-> varchar) is clean" {
            val plan = filterPlan(call("eq", col("name", "varchar"), stringLit("Alice")))
            StrictCoercionChecker.check(plan) shouldBe emptyList()
        }

        "unknown/empty types skip the check (RESOLVE-pending)" {
            // ColumnRef with empty type (resolver hasn't filled it in) — checker stays silent
            // rather than guessing.
            val untypedCol =
                Expression
                    .newBuilder()
                    .setColumnRef(ColumnRef.newBuilder().setName("mystery"))
                    .build()
            val plan = filterPlan(call("eq", untypedCol, stringLit("?")))
            StrictCoercionChecker.check(plan) shouldBe emptyList()
        }

        "IN with mismatched right-hand types reports each offending value" {
            // left is int; values mix int (clean) + text (mismatched).
            val plan =
                filterPlan(
                    call(
                        "in",
                        col("status", "int"),
                        intLit(1),
                        stringLit("oops"),
                        intLit(2),
                        stringLit("nope"),
                    ),
                )
            val out = StrictCoercionChecker.check(plan)
            out.size shouldBe 2
            out.all { it.code == "strict_coercion_rejected" } shouldBe true
        }

        "compound AND: each comparison is checked independently" {
            val plan =
                filterPlan(
                    call(
                        "and",
                        call("eq", col("id", "int"), stringLit("nope")), // violation
                        call("eq", col("name", "text"), stringLit("Alice")), // clean
                    ),
                )
            val out = StrictCoercionChecker.check(plan)
            out.size shouldBe 1
        }

        "non-comparison operators (and/or/not, add/sub) are out of scope" {
            // `id + 1` is arithmetic — `add` produces an int, eq against an int literal is fine.
            val addExpr = call("add", col("id", "int"), intLit(1), resultType = "int")
            val plan = filterPlan(call("eq", addExpr, intLit(5)))
            StrictCoercionChecker.check(plan) shouldBe emptyList()
        }
    })
