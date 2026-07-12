// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate.policy

import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.security.v1.EvaluatePoliciesRequest
import org.tatrman.validate.policy.StaticPolicyMetadataClient
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

class SecurityMetadataAwareSpec :
    StringSpec({

        fun dbQn(name: String) =
            QualifiedName
                .newBuilder()
                .setSchemaCode(org.tatrman.plan.v1.SchemaCode.DB)
                .setNamespace("dbo")
                .setName(name)
                .build()

        fun scan(name: String): PlanNode =
            PlanNode.newBuilder().setTableScan(TableScanNode.newBuilder().setTable(dbQn(name))).build()

        fun request(
            plan: PlanNode,
            userId: String = "tenant-7:alice",
            modelVersion: String = "",
        ): EvaluatePoliciesRequest =
            EvaluatePoliciesRequest
                .newBuilder()
                .setPlan(plan)
                .setContext(
                    PipelineContext.newBuilder().setUserId(userId).also {
                        if (modelVersion.isNotEmpty()) {
                            it.modelVersion =
                                modelVersion
                        }
                    },
                ).build()

        val customersQn = dbQn("customers")

        "a referenced table the metadata model doesn't know → unknown_table warning" {
            val service =
                PolicyEngine(
                    PolicyRegistry(DefaultPolicies.core),
                    metadata = StaticPolicyMetadataClient(version = "v1", knownQnames = setOf(customersQn)),
                )
            val resp = service.evaluatePolicies(request(scan("ghost_table")))
            resp.messagesList.map { it.code } shouldContain "unknown_table"
            // policies for db.dbo.* still apply (the warning is advisory)
            resp.predicatesList.size shouldBe 1
        }

        "a known table → no unknown_table warning" {
            val service =
                PolicyEngine(
                    PolicyRegistry(DefaultPolicies.core),
                    metadata = StaticPolicyMetadataClient(version = "v1", knownQnames = setOf(customersQn)),
                )
            val resp = service.evaluatePolicies(request(scan("customers")))
            resp.messagesList.none { it.code == "unknown_table" } shouldBe true
            resp.predicatesList.size shouldBe 1
        }

        "request model_version ≠ live metadata version → model_version_mismatch warning" {
            val service =
                PolicyEngine(
                    PolicyRegistry(DefaultPolicies.core),
                    metadata = StaticPolicyMetadataClient(version = "v2"),
                )
            val resp = service.evaluatePolicies(request(scan("customers"), modelVersion = "v1"))
            resp.messagesList.map { it.code } shouldContain "model_version_mismatch"
        }

        "matching model_version → no mismatch warning" {
            val service =
                PolicyEngine(
                    PolicyRegistry(DefaultPolicies.core),
                    metadata = StaticPolicyMetadataClient(version = "v1"),
                )
            val resp = service.evaluatePolicies(request(scan("customers"), modelVersion = "v1"))
            resp.messagesList.none { it.code == "model_version_mismatch" } shouldBe true
        }

        "permissive client (default) → no metadata-aware warnings at all" {
            val resp =
                PolicyEngine(PolicyRegistry(DefaultPolicies.core))
                    .evaluatePolicies(request(scan("anything"), modelVersion = "whatever"))
            resp.messagesList.none { it.code == "unknown_table" || it.code == "model_version_mismatch" } shouldBe true
        }
    })
