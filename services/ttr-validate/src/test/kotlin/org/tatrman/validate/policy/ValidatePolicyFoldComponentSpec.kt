package org.tatrman.validate.policy

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.tatrman.validate.v1.ValidateRequest
import org.tatrman.validate.v1.ValidationOptions
import org.tatrman.validate.client.LocalPolicyClient
import org.tatrman.validate.client.StaticMetadataClient
import org.tatrman.validate.grpc.ValidateServiceImpl
import org.tatrman.validate.stages.LlmGuard
import org.tatrman.validate.stages.RuleEnforcer
import org.tatrman.validate.stages.SecurityApplier
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode

/**
 * Fork Stage 3.2 T3 — the in-process seam. A fixture plan flows
 * validate → (in-process) policy-evaluate → predicate-inject in ONE process: Validate's
 * validator core wired to [LocalPolicyClient] over the folded [PolicyEngine], no gRPC hop.
 */
class ValidatePolicyFoldComponentSpec :
    StringSpec({
        val service =
            ValidateServiceImpl(
                securityApplier =
                    SecurityApplier(
                        LocalPolicyClient(PolicyEngine(PolicyRegistry(DefaultPolicies.core))),
                    ),
                ruleEnforcer = RuleEnforcer(serviceDefault = 30),
                llmGuard = LlmGuard(enabled = false),
                metadataClient = StaticMetadataClient("v-test"),
            )

        fun customersScan(): PlanNode =
            PlanNode
                .newBuilder()
                .setTableScan(
                    TableScanNode.newBuilder().setTable(
                        QualifiedName
                            .newBuilder()
                            .setSchemaCode(SchemaCode.DB)
                            .setNamespace("dbo")
                            .setName("customers"),
                    ),
                ).build()

        "validate → in-process policy evaluate → tenant predicate injected, single process" {
            val resp =
                service.validate(
                    ValidateRequest
                        .newBuilder()
                        .setPlan(customersScan())
                        .setContext(
                            PipelineContext
                                .newBuilder()
                                .setUserId("tenant-7:alice")
                                .setModelVersion("v-test"),
                        ).setOptions(
                            ValidationOptions
                                .newBuilder()
                                .setApplySecurity(true)
                                .setEnforceTopN(false),
                        ).build(),
                )

            // The folded engine matched tenant_isolation and Validate recorded the audit entry…
            resp.securityAppliedList.any { it.ruleId == "tenant_isolation" } shouldBe true
            // …and wrapped the bare TableScan with a security Filter (no longer a bare scan).
            resp.plan.nodeCase shouldNotBe PlanNode.NodeCase.TABLE_SCAN
        }
    })
