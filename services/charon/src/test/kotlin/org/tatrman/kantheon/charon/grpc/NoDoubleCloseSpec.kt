package org.tatrman.kantheon.charon.grpc

import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.charon.v1.DbTable
import org.tatrman.charon.v1.DescribeRequest
import org.tatrman.charon.v1.DescribeResult
import org.tatrman.charon.v1.EvictRequest
import org.tatrman.charon.v1.Location
import org.tatrman.charon.v1.MaterializeRequest
import org.tatrman.charon.v1.MoveResult
import org.tatrman.charon.v1.SeaweedBlob
import org.tatrman.kantheon.charon.core.MovePlanner
import org.tatrman.kantheon.charon.core.SkeletonMoveExecutor
import java.util.concurrent.atomic.AtomicInteger

/**
 * review-006 R6.2 — explicit no-double-close pin.
 *
 * R6.1 fixed the code so an error path never calls `onCompleted()` after
 * `onError()`. This spec pins that behaviour: every rejected RPC closes the
 * call **exactly once**, with the error status, and never also completes. We
 * drive [CharonServiceImpl] directly with a counting [StreamObserver] spy so we
 * observe the raw terminal events (the in-process client coalesces them into a
 * single thrown [StatusRuntimeException], which would hide a stray
 * `onCompleted()`).
 */
class NoDoubleCloseSpec :
    StringSpec({

        fun service(): CharonServiceImpl =
            CharonServiceImpl(
                planner = MovePlanner(),
                executor = SkeletonMoveExecutor(),
            )

        "evict on a DB table closes exactly once with onError, no onCompleted" {
            val evictSpy = CountingObserver<org.tatrman.charon.v1.EvictResult>()
            service().evict(
                EvictRequest
                    .newBuilder()
                    .setLocation(
                        Location
                            .newBuilder()
                            .setDbTable(
                                DbTable
                                    .newBuilder()
                                    .setConnectionId("c")
                                    .setSchema("s")
                                    .setTable("t")
                                    .build(),
                            ).build(),
                    ).build(),
                evictSpy,
            )
            evictSpy.next.get() shouldBe 0
            evictSpy.completed.get() shouldBe 0
            evictSpy.errors.get() shouldBe 1
            evictSpy.lastErrorCode shouldBe Status.Code.INVALID_ARGUMENT
        }

        "describe error path closes exactly once with onError, no onCompleted" {
            // Describe always passes the planner (read-only); the skeleton
            // executor returns UNIMPLEMENTED → onError, never onCompleted.
            val spy = CountingObserver<DescribeResult>()
            service().describe(
                DescribeRequest
                    .newBuilder()
                    .setLocation(
                        Location
                            .newBuilder()
                            .setSeaweed(
                                SeaweedBlob
                                    .newBuilder()
                                    .setBucket("b")
                                    .setKey("k")
                                    .build(),
                            ).build(),
                    ).build(),
                spy,
            )
            spy.next.get() shouldBe 0
            spy.completed.get() shouldBe 0
            spy.errors.get() shouldBe 1
            spy.lastErrorCode shouldBe Status.Code.UNIMPLEMENTED
        }

        "materialize error path (executor Left) closes exactly once with onError, no onCompleted" {
            val spy = CountingObserver<MoveResult>()
            service().materialize(
                MaterializeRequest
                    .newBuilder()
                    .setSource(
                        Location
                            .newBuilder()
                            .setSeaweed(
                                SeaweedBlob
                                    .newBuilder()
                                    .setBucket("b")
                                    .setKey("k")
                                    .build(),
                            ).build(),
                    ).setTarget(
                        Location
                            .newBuilder()
                            .setRedis(
                                org.tatrman.charon.v1.RedisEntry
                                    .newBuilder()
                                    .setKey("r")
                                    .build(),
                            ).build(),
                    ).build(),
                spy,
            )
            spy.next.get() shouldBe 0
            spy.completed.get() shouldBe 0
            spy.errors.get() shouldBe 1
            spy.lastErrorCode shouldBe Status.Code.UNIMPLEMENTED
        }
    })

/** A [StreamObserver] that counts each terminal/next event so a test can assert
 *  "exactly one onError, zero onCompleted". */
private class CountingObserver<T> : StreamObserver<T> {
    val next = AtomicInteger(0)
    val errors = AtomicInteger(0)
    val completed = AtomicInteger(0)
    var lastErrorCode: Status.Code? = null

    override fun onNext(value: T) {
        next.incrementAndGet()
    }

    override fun onError(t: Throwable) {
        errors.incrementAndGet()
        lastErrorCode =
            when (t) {
                is StatusException -> t.status.code
                is StatusRuntimeException -> t.status.code
                else -> null
            }
    }

    override fun onCompleted() {
        completed.incrementAndGet()
    }
}
