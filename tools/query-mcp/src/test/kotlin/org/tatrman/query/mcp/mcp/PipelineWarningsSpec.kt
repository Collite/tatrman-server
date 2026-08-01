// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.mcp.mcp

import org.tatrman.plan.v1.Warning
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class PipelineWarningsSpec :
    StringSpec({

        fun warn(
            code: String,
            msg: String = "",
            stage: String = "",
            service: String = "",
        ): Warning =
            Warning
                .newBuilder()
                .setCode(code)
                .setMessage(msg)
                .setSourceStage(stage)
                .setSourceService(service)
                .build()

        "empty list → empty JSON array" {
            PipelineWarnings.toJsonArray(emptyList()).size shouldBe 0
        }

        "known INFO code → severity=info" {
            val arr =
                PipelineWarnings.toJsonArray(
                    listOf(
                        warn(
                            code = "security_predicate_applied",
                            msg = "Filter applied for table dbo.Customers",
                            stage = "security",
                            service = "validator",
                        ),
                    ),
                )
            val obj = arr[0] as JsonObject
            (obj["code"] as JsonPrimitive).content shouldBe "security_predicate_applied"
            (obj["severity"] as JsonPrimitive).content shouldBe "info"
            (obj["text"] as JsonPrimitive).content shouldBe "Filter applied for table dbo.Customers"
            (obj["sourceService"] as JsonPrimitive).content shouldBe "validator"
            ((obj["metadata"] as JsonObject)["sourceStage"] as JsonPrimitive).content shouldBe "security"
        }

        "unknown code defaults to severity=warn" {
            val arr = PipelineWarnings.toJsonArray(listOf(warn("model_version_mismatch_minor", service = "validator")))
            ((arr[0] as JsonObject)["severity"] as JsonPrimitive).content shouldBe "warn"
        }

        "stage-less warning omits sourceStage from metadata" {
            val arr = PipelineWarnings.toJsonArray(listOf(warn("anything", service = "translator", stage = "")))
            val md = ((arr[0] as JsonObject)["metadata"] as JsonObject)
            md.containsKey("sourceStage") shouldBe false
        }

        "DF-Q01 / G7 — sticky_failover and unsupported_type_as_binary round-trip as warn-severity entries" {
            val arr =
                PipelineWarnings.toJsonArray(
                    listOf(
                        warn(
                            code = "sticky_failover",
                            msg = "Pinned worker w-1 unavailable; failed over to w-2.",
                            stage = "dispatch",
                            service = "dispatcher",
                        ),
                        warn(
                            code = "unsupported_type_as_binary",
                            msg = "Column 'loc' has unsupported type 'geography'; emitted as opaque binary.",
                            stage = "execute",
                            service = "workers/mssql",
                        ),
                    ),
                )
            arr.size shouldBe 2
            val sf = arr[0] as JsonObject
            (sf["code"] as JsonPrimitive).content shouldBe "sticky_failover"
            (sf["severity"] as JsonPrimitive).content shouldBe "warn"
            (sf["sourceService"] as JsonPrimitive).content shouldBe "dispatcher"
            val ub = arr[1] as JsonObject
            (ub["code"] as JsonPrimitive).content shouldBe "unsupported_type_as_binary"
            (ub["severity"] as JsonPrimitive).content shouldBe "warn"
            (ub["sourceService"] as JsonPrimitive).content shouldBe "workers/mssql"
        }

        "preserves order across multiple warnings" {
            val list =
                listOf(
                    warn(code = "first", service = "translator"),
                    warn(code = "second", service = "validator"),
                    warn(code = "third", service = "dispatcher"),
                )
            val arr = PipelineWarnings.toJsonArray(list)
            ((arr[0] as JsonObject)["code"] as JsonPrimitive).content shouldBe "first"
            ((arr[1] as JsonObject)["code"] as JsonPrimitive).content shouldBe "second"
            ((arr[2] as JsonObject)["code"] as JsonPrimitive).content shouldBe "third"
        }
    })
