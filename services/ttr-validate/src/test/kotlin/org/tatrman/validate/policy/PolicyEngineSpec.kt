package org.tatrman.validate.policy

import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.security.v1.EvaluatePoliciesRequest
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class PolicyEngineSpec :
    StringSpec({

        fun customersScan(): PlanNode =
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
                                .setName("customers"),
                        ),
                ).build()

        fun request(
            plan: PlanNode,
            userId: String,
        ): EvaluatePoliciesRequest =
            EvaluatePoliciesRequest
                .newBuilder()
                .setPlan(plan)
                .setContext(PipelineContext.newBuilder().setUserId(userId))
                .build()

        "tenant_isolation policy fires on a db.dbo table for a tenant:user identity" {
            val service = PolicyEngine(PolicyRegistry(DefaultPolicies.core))
            val resp = service.evaluatePolicies(request(customersScan(), userId = "tenant-7:alice"))

            resp.predicatesList shouldHaveSize 1
            val p = resp.predicatesList[0]
            p.ruleId shouldBe "tenant_isolation"
            p.table.name shouldBe "customers"
            p.predicate.function.operation shouldBe "eq"
            // The literal we substituted is the tenant prefix from the user_id.
            p.predicate.function.operandsList[1]
                .literal.stringValue shouldBe "tenant-7"
        }

        "policies skip silently when user_id does not provide the required attribute" {
            val service = PolicyEngine(PolicyRegistry(DefaultPolicies.core))
            val resp = service.evaluatePolicies(request(customersScan(), userId = "alice-no-tenant"))

            // The policy references UserAttribute("tenant_id"), which is not present
            // on a non-tenant-prefixed user_id; the service skips the policy and
            // emits a warning rather than failing the whole call.
            resp.predicatesList shouldHaveSize 0
            resp.messagesList shouldHaveSize 1
            resp.messagesList[0].code shouldBe "policy_evaluation_skipped"
        }

        "no policies apply outside the configured namespace" {
            val erQname =
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(org.tatrman.plan.v1.SchemaCode.ER)
                    .setNamespace("entity")
                    .setName("Customer")
                    .build()
            val plan =
                PlanNode
                    .newBuilder()
                    .setTableScan(TableScanNode.newBuilder().setTable(erQname))
                    .build()
            val service = PolicyEngine(PolicyRegistry(DefaultPolicies.core))
            val resp = service.evaluatePolicies(request(plan, userId = "tenant-7:alice"))
            resp.predicatesList shouldHaveSize 0
            resp.messagesList shouldHaveSize 0
        }

        "JOIN over two tables produces a predicate per matching table" {
            val ordersScan =
                PlanNode
                    .newBuilder()
                    .setTableScan(
                        TableScanNode.newBuilder().setTable(
                            QualifiedName
                                .newBuilder()
                                .setSchemaCode(org.tatrman.plan.v1.SchemaCode.DB)
                                .setNamespace("dbo")
                                .setName("orders"),
                        ),
                    ).build()
            val joinPlan =
                PlanNode
                    .newBuilder()
                    .setJoin(
                        org.tatrman.plan.v1.JoinNode
                            .newBuilder()
                            .setLeft(customersScan())
                            .setRight(ordersScan)
                            .setJoinType(org.tatrman.plan.v1.JoinType.INNER),
                    ).build()
            val service = PolicyEngine(PolicyRegistry(DefaultPolicies.core))
            val resp = service.evaluatePolicies(request(joinPlan, userId = "tenant-7:alice"))
            resp.predicatesList shouldHaveSize 2
            resp.predicatesList.map { it.table.name }.toSet() shouldBe setOf("customers", "orders")
        }

        "engine reports loaded policy count" {
            val service = PolicyEngine(PolicyRegistry(DefaultPolicies.core))
            service.loadedPolicies() shouldBe DefaultPolicies.core.size
        }

        "Eq + And + In + Not predicates render to expected operator codes" {
            val identity = ResolvedIdentity(userId = "u", attributes = mapOf("tenant_id" to "t1"))
            val and =
                PolicyPredicate.And(
                    left = PolicyPredicate.Eq("a", PolicyValue.Literal(1, "int")),
                    right =
                        PolicyPredicate.Not(
                            PolicyPredicate.In(
                                column = "b",
                                values = listOf(PolicyValue.Literal("x", "text"), PolicyValue.Literal("y", "text")),
                            ),
                        ),
                )
            val expr: Expression = PolicyToExpression.convert(and, identity)
            expr.function.operation shouldBe "and"
            expr.function.operandsList[0]
                .function.operation shouldBe "eq"
            expr.function.operandsList[1]
                .function.operation shouldBe "not"
            // Phase 08 B4 / DF-S05 — IN is now a first-class `in` FunctionCall:
            //   { operation: "in", operands: [column_ref(b), lit("x"), lit("y")] }
            // (was an OR-tree of equalities pre-B4).
            val inSide =
                expr.function.operandsList[1]
                    .function.operandsList[0]
            inSide.function.operation shouldBe "in"
            inSide.function.operandsList.size shouldBe 3 // column + 2 values
            inSide.function.operandsList[0]
                .columnRef.name shouldBe "b"
            inSide.function.operandsList[1]
                .literal.stringValue shouldBe "x"
            inSide.function.operandsList[2]
                .literal.stringValue shouldBe "y"
        }

        "IN with empty values still degrades to literal false (Calcite rejects zero-operand IN)" {
            val identity = ResolvedIdentity(userId = "u", attributes = emptyMap())
            val pred = PolicyPredicate.In(column = "x", values = emptyList())
            val expr = PolicyToExpression.convert(pred, identity)
            expr.hasLiteral() shouldBe true
            expr.literal.boolValue shouldBe false
        }
    })
