package org.tatrman.kantheon.argos.policy

import com.typesafe.config.ConfigFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class PolicyConfigLoaderSpec :
    StringSpec({

        fun cfg(hocon: String) = ConfigFactory.parseString(hocon)

        "loads a namespace-matched eq policy with a user-attr value" {
            val policies =
                PolicyConfigLoader.load(
                    cfg(
                        """
                        argos.policies = [
                          {
                            id = "tenant_isolation"
                            description = "tenant"
                            match { type = "namespace", schema = "db", namespace = "dbo" }
                            predicate { type = "eq", column = "tenant_id", value { kind = "user-attr", attribute = "tenant_id" } }
                          }
                        ]
                        """.trimIndent(),
                    ),
                )
            policies shouldHaveSize 1
            val p = policies.single()
            p.id shouldBe "tenant_isolation"
            p.description shouldBe "tenant"
            val match = p.tableMatch.shouldBeInstanceOf<TableMatcher.Namespace>()
            match.schemaCode shouldBe org.tatrman.plan.v1.SchemaCode.DB
            match.namespace shouldBe "dbo"
            val pred = p.predicate.shouldBeInstanceOf<PolicyPredicate.Eq>()
            pred.column shouldBe "tenant_id"
            pred.value.shouldBeInstanceOf<PolicyValue.UserAttribute>().attribute shouldBe "tenant_id"
        }

        "loads exact-match, literal values, IN, and AND/OR/NOT predicates" {
            val policies =
                PolicyConfigLoader.load(
                    cfg(
                        """
                        argos.policies = [
                          {
                            id = "complex"
                            match { type = "exact", qname = "db.dbo.orders" }
                            predicate {
                              type = "and"
                              left { type = "in", column = "status", values = [
                                { kind = "literal", value = "OPEN", literal-type = "text" },
                                { kind = "literal", value = "PAID", literal-type = "text" }
                              ] }
                              right {
                                type = "not"
                                child { type = "eq", column = "deleted", value = { kind = "literal", value = true, literal-type = "bool" } }
                              }
                            }
                          }
                        ]
                        """.trimIndent(),
                    ),
                )
            val p = policies.single()
            p.tableMatch
                .shouldBeInstanceOf<TableMatcher.Exact>()
                .qname.name shouldBe "orders"
            val and = p.predicate.shouldBeInstanceOf<PolicyPredicate.And>()
            val inPred = and.left.shouldBeInstanceOf<PolicyPredicate.In>()
            inPred.column shouldBe "status"
            inPred.values shouldHaveSize 2
            inPred.values[0].shouldBeInstanceOf<PolicyValue.Literal>().value shouldBe "OPEN"
            val notPred = and.right.shouldBeInstanceOf<PolicyPredicate.Not>()
            val eq = notPred.child.shouldBeInstanceOf<PolicyPredicate.Eq>()
            eq.value.shouldBeInstanceOf<PolicyValue.Literal>().value shouldBe true
        }

        "all-match policy" {
            val policies =
                PolicyConfigLoader.load(
                    cfg(
                        """argos.policies = [ { id = "org_wide", match { type = "all" }, predicate { type = "eq", column = "x", value { kind = "literal", value = 1, literal-type = "int" } } } ]""",
                    ),
                )
            policies.single().tableMatch.shouldBeInstanceOf<TableMatcher.All>()
        }

        "absent argos.policies → empty list (no error)" {
            PolicyConfigLoader.load(cfg("app {}")) shouldBe emptyList()
        }

        "unknown match type → PolicyConfigException naming the policy" {
            val ex =
                shouldThrow<PolicyConfigException> {
                    PolicyConfigLoader.load(
                        cfg(
                            """argos.policies = [ { id = "bad", match { type = "regex" }, predicate { type = "eq", column = "x", value { kind = "literal", value = 1 } } } ]""",
                        ),
                    )
                }
            ex.message!!.contains("bad") shouldBe true
        }

        "unknown predicate type → PolicyConfigException" {
            shouldThrow<PolicyConfigException> {
                PolicyConfigLoader.load(
                    cfg(
                        """argos.policies = [ { id = "p", match { type = "all" }, predicate { type = "like", column = "x", pattern = "%" } } ]""",
                    ),
                )
            }
        }

        "unknown value kind → PolicyConfigException" {
            shouldThrow<PolicyConfigException> {
                PolicyConfigLoader.load(
                    cfg(
                        """argos.policies = [ { id = "p", match { type = "all" }, predicate { type = "eq", column = "x", value { kind = "env-var", name = "X" } } } ]""",
                    ),
                )
            }
        }

        "missing predicate block → PolicyConfigException" {
            shouldThrow<PolicyConfigException> {
                PolicyConfigLoader.load(cfg("""argos.policies = [ { id = "p", match { type = "all" } } ]"""))
            }
        }

        "the bundled application.conf parses into a working tenant-isolation policy" {
            // Argos ships a default policy store (policies/policies.conf, included by
            // application.conf) — the fold's HOCON policy store (Stage 3.2 T4).
            val policies = PolicyConfigLoader.load(ConfigFactory.load())
            policies shouldHaveSize 1
            policies.single().id shouldBe "tenant_isolation"
        }
    })
