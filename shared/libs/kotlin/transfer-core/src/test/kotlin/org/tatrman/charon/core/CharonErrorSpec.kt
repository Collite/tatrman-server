// SPDX-License-Identifier: Apache-2.0
package org.tatrman.charon.core

import io.grpc.Status
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.common.v1.Severity

/**
 * The published lib's OWN gate for its error model (review-075 F5).
 *
 * Before this, `CharonError` was exercised only through `services/charon`'s suite
 * (the lib `api`s into it). That works today, but the published artifact had no
 * gate of its own: slim or re-shape charon's suite and the lib's public contract
 * — the `code` ↔ gRPC-status mapping and the Rule-6 `ResponseMessage` projection
 * (`charon/contracts.md` §1 error model) — would silently lose coverage. These
 * pin it at the lib boundary, independent of any consumer.
 */
class CharonErrorSpec :
    StringSpec({
        "each variant maps to its contracts §1 gRPC status" {
            // One representative per gRPC status code the error model uses — the mapping
            // is the seam's promise to a consumer that pattern-matches on `toStatus()`.
            val cases: List<Pair<CharonError, Status.Code>> =
                listOf(
                    CharonError.IllegalTargetForRpc(MoveRpc.STAGE, LocationKind.DB_TABLE) to
                        Status.Code.INVALID_ARGUMENT,
                    CharonError.EmptyLocation(MoveRpc.MATERIALIZE, "source") to Status.Code.INVALID_ARGUMENT,
                    CharonError.UnknownConnectionId("erp") to Status.Code.INVALID_ARGUMENT,
                    CharonError.UnmappableType("amount", "List<Struct>") to Status.Code.FAILED_PRECONDITION,
                    CharonError.FingerprintMismatch("aaa", "bbb") to Status.Code.FAILED_PRECONDITION,
                    CharonError.SourceNotFound(LocationKind.SEAWEED, "run/key.arrow") to Status.Code.NOT_FOUND,
                    CharonError.ByteCapExceeded(9, 8) to Status.Code.RESOURCE_EXHAUSTED,
                    CharonError.WorkerResourceExhausted("workspace_cap_exceeded") to Status.Code.RESOURCE_EXHAUSTED,
                    CharonError.EndpointUnavailable("s3") to Status.Code.UNAVAILABLE,
                    CharonError.WorkerEngineUnavailable("polars") to Status.Code.UNAVAILABLE,
                    CharonError.DeadlineExceeded(5_000) to Status.Code.DEADLINE_EXCEEDED,
                    CharonError.WorkerOpUnsupported("polars", "stage", "no arrow-ingest RPC") to
                        Status.Code.UNIMPLEMENTED,
                    CharonError.NotYetImplemented(MoveRpc.EVICT) to Status.Code.UNIMPLEMENTED,
                )
            cases.forEach { (err, expected) ->
                err.toStatus().code shouldBe expected
            }
        }

        "toResponseMessage() carries the machine code, a UI-safe human message, and ERROR severity" {
            val err = CharonError.UnknownConnectionId("erp")
            val msg = err.toResponseMessage()
            msg.code shouldBe "unknown_connection_id"
            msg.severity shouldBe Severity.ERROR
            msg.humanMessage shouldContain "erp"
        }

        "toResponseMessages() projects a list one-for-one, preserving order" {
            val errs =
                listOf(
                    CharonError.EndpointUnavailable("s3"),
                    CharonError.DeadlineExceeded(5_000),
                )
            val msgs = errs.toResponseMessages()
            msgs.map { it.code } shouldBe listOf("endpoint_unavailable", "deadline_exceeded")
        }
    })
