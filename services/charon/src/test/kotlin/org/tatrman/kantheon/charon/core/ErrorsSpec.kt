package org.tatrman.kantheon.charon.core

import io.grpc.Status
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.common.v1.Severity

/**
 * Asserts the gRPC `Status.Code` mapping per `charon/contracts.md` §1 "Error
 * model" — every typed `CharonError` variant maps to the right code, and the
 * Rule-6 `ResponseMessage` carries the variant's `code` and `human_message`.
 */
class ErrorsSpec :
    StringSpec({

        "IllegalTargetForRpc maps to INVALID_ARGUMENT" {
            val e = CharonError.IllegalTargetForRpc(MoveRpc.MATERIALIZE, LocationKind.WORKER_DF)
            e.toStatus().code shouldBe Status.Code.INVALID_ARGUMENT
        }

        "IllegalPair maps to INVALID_ARGUMENT" {
            val e = CharonError.IllegalPair(MoveRpc.MATERIALIZE, LocationKind.SEAWEED, LocationKind.WORKER_DF)
            e.toStatus().code shouldBe Status.Code.INVALID_ARGUMENT
        }

        "CannotEvictDbTable maps to INVALID_ARGUMENT" {
            val e = CharonError.CannotEvictDbTable("c", "s", "t")
            e.toStatus().code shouldBe Status.Code.INVALID_ARGUMENT
        }

        "MissingOrInvalidDbWriteMode maps to INVALID_ARGUMENT" {
            val e = CharonError.MissingOrInvalidDbWriteMode(LocationKind.DB_TABLE)
            e.toStatus().code shouldBe Status.Code.INVALID_ARGUMENT
        }

        "FingerprintMismatch maps to FAILED_PRECONDITION" {
            val e = CharonError.FingerprintMismatch("a", "b")
            e.toStatus().code shouldBe Status.Code.FAILED_PRECONDITION
        }

        "SourceNotFound maps to NOT_FOUND" {
            val e = CharonError.SourceNotFound(LocationKind.SEAWEED, "bucket/key")
            e.toStatus().code shouldBe Status.Code.NOT_FOUND
        }

        "ByteCapExceeded maps to RESOURCE_EXHAUSTED" {
            val e = CharonError.ByteCapExceeded(1_000_000, 500_000)
            e.toStatus().code shouldBe Status.Code.RESOURCE_EXHAUSTED
        }

        "EndpointUnavailable maps to UNAVAILABLE" {
            val e = CharonError.EndpointUnavailable("data-seaweedfs:8333")
            e.toStatus().code shouldBe Status.Code.UNAVAILABLE
        }

        "DeadlineExceeded maps to DEADLINE_EXCEEDED" {
            val e = CharonError.DeadlineExceeded(30_000)
            e.toStatus().code shouldBe Status.Code.DEADLINE_EXCEEDED
        }

        "NotYetImplemented maps to UNIMPLEMENTED (Stage 1.1 skeleton)" {
            val e = CharonError.NotYetImplemented(MoveRpc.MATERIALIZE)
            e.toStatus().code shouldBe Status.Code.UNIMPLEMENTED
        }

        "toResponseMessage carries the variant's code, human message, and ERROR severity" {
            val e = CharonError.IllegalPair(MoveRpc.MATERIALIZE, LocationKind.SEAWEED, LocationKind.WORKER_DF)
            val msg = e.toResponseMessage()
            msg.severity shouldBe Severity.ERROR
            msg.code shouldBe "illegal_pair"
            msg.humanMessage shouldBe e.humanMessage
        }

        "toResponseMessages converts a list of errors" {
            val errors =
                listOf(
                    CharonError.IllegalPair(MoveRpc.MATERIALIZE, LocationKind.SEAWEED, LocationKind.WORKER_DF),
                    CharonError.MissingOrInvalidDbWriteMode(LocationKind.DB_TABLE),
                )
            val msgs = errors.toResponseMessages()
            msgs.size shouldBe 2
            msgs[0].code shouldBe "illegal_pair"
            msgs[1].code shouldBe "missing_or_invalid_db_write_mode"
            msgs.forEach { it.severity shouldBe Severity.ERROR }
        }
    })
