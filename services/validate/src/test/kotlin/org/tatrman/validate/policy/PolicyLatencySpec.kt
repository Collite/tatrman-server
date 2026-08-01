// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate.policy

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.security.v1.EvaluatePoliciesRequest

/**
 * Fork Stage 3.2 T5 — hot-path latency guard. In-process policy evaluation must be far under any
 * gRPC round-trip (the network hop the fold removed — typically ~1ms+ in-cluster). A generous 2ms
 * p50 bound documents the headroom without being flaky on a loaded CI box. Numbers recorded in the
 * module README.
 */
class PolicyLatencySpec :
    StringSpec({
        "in-process EvaluatePolicies p50 is well under the removed network hop" {
            val engine = PolicyEngine(PolicyRegistry(DefaultPolicies.core))
            val request =
                EvaluatePoliciesRequest
                    .newBuilder()
                    .setPlan(
                        PlanNode.newBuilder().setTableScan(
                            TableScanNode.newBuilder().setTable(
                                QualifiedName
                                    .newBuilder()
                                    .setSchemaCode(SchemaCode.DB)
                                    .setNamespace("dbo")
                                    .setName("customers"),
                            ),
                        ),
                    ).setContext(PipelineContext.newBuilder().setUserId("tenant-7:alice"))
                    .build()

            repeat(100) { engine.evaluatePolicies(request) } // warm up JIT

            val samplesMs =
                (1..300)
                    .map {
                        val t0 = System.nanoTime()
                        engine.evaluatePolicies(request)
                        (System.nanoTime() - t0) / 1_000_000.0
                    }.sorted()
            val p50 = samplesMs[samplesMs.size / 2]
            p50 shouldBeLessThan 2.0
        }
    })
