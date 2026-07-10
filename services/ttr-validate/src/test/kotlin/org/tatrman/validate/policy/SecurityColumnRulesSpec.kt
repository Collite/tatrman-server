package org.tatrman.validate.policy

import com.typesafe.config.ConfigFactory
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.security.v1.ColumnRule
import org.tatrman.security.v1.EvaluatePoliciesRequest
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class SecurityColumnRulesSpec :
    StringSpec({

        val hoconWithColumnRules =
            """
            validate.policies = [
              {
                id = "pii_protection"
                match { type = "namespace", schema = "db", namespace = "dbo" }
                predicate { type = "eq", column = "tenant_id", value { kind = "user-attr", attribute = "tenant_id" } }
                column-rules = [
                  { column = "ssn", action = "deny" }
                  { column = "salary", action = "mask", mask-value { kind = "literal", value = "***", literal-type = "text" } }
                ]
              }
            ]
            """.trimIndent()

        "PolicyConfigLoader parses deny + mask column rules" {
            val p = PolicyConfigLoader.load(ConfigFactory.parseString(hoconWithColumnRules)).single()
            p.columnRules shouldHaveSize 2
            val deny = p.columnRules.first { it.column == "ssn" }
            deny.action.shouldBeInstanceOf<ColumnAction.Deny>()
            val mask = p.columnRules.first { it.column == "salary" }
            val maskAction = mask.action.shouldBeInstanceOf<ColumnAction.Mask>()
            maskAction.maskValue.shouldBeInstanceOf<PolicyValue.Literal>().value shouldBe "***"
        }

        "EvaluatePolicies surfaces column_rules for a referenced table" {
            val service =
                PolicyEngine(
                    PolicyRegistry(PolicyConfigLoader.load(ConfigFactory.parseString(hoconWithColumnRules))),
                )
            val plan =
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
            val resp =
                service.evaluatePolicies(
                    EvaluatePoliciesRequest
                        .newBuilder()
                        .setPlan(
                            plan,
                        ).setContext(PipelineContext.newBuilder().setUserId("t1:alice"))
                        .build(),
                )
            resp.columnRulesList shouldHaveSize 2
            val deny = resp.columnRulesList.first { it.column == "ssn" }
            deny.action shouldBe ColumnRule.Action.DENY
            deny.ruleId shouldBe "pii_protection"
            deny.table.name shouldBe "customers"
            val mask = resp.columnRulesList.first { it.column == "salary" }
            mask.action shouldBe ColumnRule.Action.MASK
            mask.hasMaskExpression() shouldBe true
            mask.maskExpression.literal.stringValue shouldBe "***"
        }

        "EvaluatePolicies returns no column_rules when no policy defines any" {
            val service = PolicyEngine(PolicyRegistry(DefaultPolicies.core))
            val plan =
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
            val resp =
                service.evaluatePolicies(
                    EvaluatePoliciesRequest
                        .newBuilder()
                        .setPlan(
                            plan,
                        ).setContext(PipelineContext.newBuilder().setUserId("t1:alice"))
                        .build(),
                )
            resp.columnRulesList shouldHaveSize 0
        }

        "unknown column-rule action → PolicyConfigException" {
            val bad =
                """validate.policies = [ { id = "p", match { type = "all" }, predicate { type = "eq", column = "x", value { kind = "literal", value = 1 } }, column-rules = [ { column = "y", action = "redact" } ] } ]"""
            io.kotest.assertions.throwables.shouldThrow<PolicyConfigException> {
                PolicyConfigLoader.load(ConfigFactory.parseString(bad))
            }
        }
    })
