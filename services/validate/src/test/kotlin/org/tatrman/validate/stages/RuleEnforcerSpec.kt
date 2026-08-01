// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate.stages

import org.tatrman.common.v1.Severity
import org.tatrman.plan.v1.AggregateCall
import org.tatrman.plan.v1.AggregateNode
import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.LimitOffsetNode
import org.tatrman.plan.v1.Literal
import org.tatrman.plan.v1.NamedExpression
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.ProjectNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.security.v1.ColumnRule
import org.tatrman.validate.v1.ValidationOptions
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainStr

class RuleEnforcerSpec :
    StringSpec({
        val enforcer = RuleEnforcer(serviceDefault = 30)
        val customers =
            QualifiedName
                .newBuilder()
                .setSchemaCode(org.tatrman.plan.v1.SchemaCode.DB)
                .setNamespace("dbo")
                .setName("customers")
                .build()
        val scan =
            PlanNode
                .newBuilder()
                .setTableScan(TableScanNode.newBuilder().setTable(customers))
                .build()

        // --- TopN (unchanged behaviour, now exposed via Result.plan) ---

        "enforce wraps an unbounded plan with default cap" {
            val out = enforcer.enforce(scan, options(enforce = true))
            out.rejected shouldBe false
            out.plan.hasLimitOffset() shouldBe true
            out.plan.limitOffset.limit shouldBe 30L
        }

        "enforce clamps a higher existing limit" {
            val withHighLimit =
                PlanNode
                    .newBuilder()
                    .setLimitOffset(LimitOffsetNode.newBuilder().setInput(scan).setLimit(1000))
                    .build()
            val out = enforcer.enforce(withHighLimit, options(enforce = true))
            out.plan.limitOffset.limit shouldBe 30L
        }

        "enforce leaves an equal-or-lower limit untouched" {
            val withLowLimit =
                PlanNode
                    .newBuilder()
                    .setLimitOffset(LimitOffsetNode.newBuilder().setInput(scan).setLimit(10))
                    .build()
            val out = enforcer.enforce(withLowLimit, options(enforce = true))
            out.plan shouldBe withLowLimit
        }

        "enforce respects ValidationOptions.default_top_n when smaller than service default" {
            val out = enforcer.enforce(scan, options(enforce = true, defaultTopN = 5))
            out.plan.limitOffset.limit shouldBe 5L
        }

        "enforce skips entirely when enforce_top_n = false" {
            val out = enforcer.enforce(scan, options(enforce = false))
            out.plan shouldBe scan
        }

        "enforce preserves existing offset when rewriting limit" {
            val withOffset =
                PlanNode
                    .newBuilder()
                    .setLimitOffset(
                        LimitOffsetNode
                            .newBuilder()
                            .setInput(scan)
                            .setLimit(1000)
                            .setOffset(50),
                    ).build()
            val out = enforcer.enforce(withOffset, options(enforce = true))
            out.plan.limitOffset.limit shouldBe 30L
            out.plan.limitOffset.offset shouldBe 50L
        }

        // --- DF-V01: column deny/mask enforcement ---

        "DENY on a referenced (table, column) rejects with column_denied ERROR" {
            val plan =
                PlanNode
                    .newBuilder()
                    .setProject(
                        ProjectNode
                            .newBuilder()
                            .setInput(scan)
                            .addExpressions(namedColumnRef("ssn"))
                            .addExpressions(namedColumnRef("name")),
                    ).build()
            val deny = denyRule(customers, "ssn", "pii_protection")

            val out = enforcer.enforce(plan, options(enforce = true), listOf(deny))

            out.rejected shouldBe true
            out.messages shouldHaveSize 1
            out.messages[0].severity shouldBe Severity.ERROR
            out.messages[0].code shouldBe "column_denied"
            out.messages[0].humanMessage shouldContainStr "ssn"
            out.messages[0].humanMessage shouldContainStr "db.dbo.customers"
            // The deny short-circuits; topN does NOT run (the response carries no plan).
            out.plan shouldBe plan
        }

        "DENY on a column the plan does NOT reference is a no-op" {
            val plan =
                PlanNode
                    .newBuilder()
                    .setProject(
                        ProjectNode
                            .newBuilder()
                            .setInput(scan)
                            .addExpressions(namedColumnRef("name")),
                    ).build()
            val deny = denyRule(customers, "ssn", "pii_protection")

            val out = enforcer.enforce(plan, options(enforce = true), listOf(deny))

            out.rejected shouldBe false
            out.messages.map { it.code } shouldBe emptyList()
        }

        "DENY scoped to a table NOT in the plan is a no-op" {
            val otherTable =
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(org.tatrman.plan.v1.SchemaCode.DB)
                    .setNamespace("dbo")
                    .setName("orders")
                    .build()
            val plan =
                PlanNode
                    .newBuilder()
                    .setProject(
                        ProjectNode
                            .newBuilder()
                            .setInput(scan)
                            .addExpressions(namedColumnRef("ssn")),
                    ).build()
            val deny = denyRule(otherTable, "ssn", "pii_protection")

            val out = enforcer.enforce(plan, options(enforce = true), listOf(deny))

            out.rejected shouldBe false
        }

        "MASK rewrites a ColumnRef leaf inside a Project expression" {
            val plan =
                PlanNode
                    .newBuilder()
                    .setProject(
                        ProjectNode
                            .newBuilder()
                            .setInput(scan)
                            .addExpressions(namedColumnRef("salary"))
                            .addExpressions(namedColumnRef("name")),
                    ).build()
            val mask = maskRule(customers, "salary", "salary_mask", maskLiteral("***"))

            val out = enforcer.enforce(plan, options(enforce = false), listOf(mask))

            out.rejected shouldBe false
            // After masking, the salary projection's inner expression is a literal, not a column_ref.
            val rewrittenProject = out.plan.project
            val salaryExpr = rewrittenProject.expressionsList.first { it.alias == "salary" }.expression
            salaryExpr.hasLiteral() shouldBe true
            salaryExpr.literal.stringValue shouldBe "***"
            // The non-masked column is untouched.
            val nameExpr = rewrittenProject.expressionsList.first { it.alias == "name" }.expression
            nameExpr.hasColumnRef() shouldBe true
            nameExpr.columnRef.name shouldBe "name"
        }

        "MASK rule without mask_expression is silently skipped (left unmasked, logged)" {
            val plan =
                PlanNode
                    .newBuilder()
                    .setProject(
                        ProjectNode
                            .newBuilder()
                            .setInput(scan)
                            .addExpressions(namedColumnRef("salary")),
                    ).build()
            val maskNoExpr =
                ColumnRule
                    .newBuilder()
                    .setTable(customers)
                    .setColumn("salary")
                    .setAction(ColumnRule.Action.MASK)
                    .setRuleId("incomplete_mask")
                    .build()

            val out = enforcer.enforce(plan, options(enforce = false), listOf(maskNoExpr))

            out.rejected shouldBe false
            out.plan.project.expressionsList[0]
                .expression
                .hasColumnRef() shouldBe true
        }

        "MASK warns when the column is referenced as a bare ColumnRef (group_keys etc.)" {
            // An Aggregate with the masked column as a group key — bare ColumnRef slot, can't be
            // rewritten with an arbitrary Expression (proto type mismatch).
            val plan =
                PlanNode
                    .newBuilder()
                    .setAggregate(
                        AggregateNode
                            .newBuilder()
                            .setInput(scan)
                            .addGroupKeys(ColumnRef.newBuilder().setName("salary"))
                            .addAggregates(
                                AggregateCall
                                    .newBuilder()
                                    .setFunction("count")
                                    .addArgs(ColumnRef.newBuilder().setName("id"))
                                    .setAlias("n"),
                            ),
                    ).build()
            val mask = maskRule(customers, "salary", "salary_mask", maskLiteral("***"))

            val out = enforcer.enforce(plan, options(enforce = false), listOf(mask))

            out.rejected shouldBe false
            out.messages.map { it.code } shouldContain "mask_skipped_bare_column_ref"
        }

        "DENY + TopN: rejection short-circuits before TopN wrapping" {
            val plan =
                PlanNode
                    .newBuilder()
                    .setProject(
                        ProjectNode
                            .newBuilder()
                            .setInput(scan)
                            .addExpressions(namedColumnRef("ssn")),
                    ).build()
            val deny = denyRule(customers, "ssn", "pii_protection")

            val out = enforcer.enforce(plan, options(enforce = true), listOf(deny))

            out.rejected shouldBe true
            // No LimitOffset wrapped on top — the plan slot is the unmodified input.
            out.plan shouldBe plan
        }

        "MASK + TopN: mask runs first, then TopN wraps the masked plan" {
            val plan =
                PlanNode
                    .newBuilder()
                    .setProject(
                        ProjectNode
                            .newBuilder()
                            .setInput(scan)
                            .addExpressions(namedColumnRef("salary")),
                    ).build()
            val mask = maskRule(customers, "salary", "salary_mask", maskLiteral("***"))

            val out = enforcer.enforce(plan, options(enforce = true), listOf(mask))

            out.rejected shouldBe false
            out.plan.hasLimitOffset() shouldBe true
            out.plan.limitOffset.limit shouldBe 30L
            out.plan.limitOffset.input.project.expressionsList[0]
                .expression
                .literal.stringValue shouldBe "***"
        }
    })

private fun options(
    enforce: Boolean,
    defaultTopN: Int = 0,
): ValidationOptions =
    ValidationOptions
        .newBuilder()
        .setEnforceTopN(enforce)
        .setDefaultTopN(defaultTopN)
        .build()

private fun namedColumnRef(name: String): NamedExpression =
    NamedExpression
        .newBuilder()
        .setExpression(
            Expression
                .newBuilder()
                .setColumnRef(ColumnRef.newBuilder().setName(name)),
        ).setAlias(name)
        .build()

private fun denyRule(
    table: QualifiedName,
    column: String,
    ruleId: String,
): ColumnRule =
    ColumnRule
        .newBuilder()
        .setTable(table)
        .setColumn(column)
        .setAction(ColumnRule.Action.DENY)
        .setRuleId(ruleId)
        .build()

private fun maskRule(
    table: QualifiedName,
    column: String,
    ruleId: String,
    mask: Expression,
): ColumnRule =
    ColumnRule
        .newBuilder()
        .setTable(table)
        .setColumn(column)
        .setAction(ColumnRule.Action.MASK)
        .setMaskExpression(mask)
        .setRuleId(ruleId)
        .build()

private fun maskLiteral(value: String): Expression =
    Expression
        .newBuilder()
        .setLiteral(Literal.newBuilder().setStringValue(value).setType("text"))
        .setResultType("text")
        .build()
