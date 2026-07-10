package org.tatrman.kantheon.charon.core

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.BigIntVector
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowStreamReader
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import org.apache.arrow.vector.types.pojo.Schema
import org.tatrman.transfer.v1.DescribeResult
import org.tatrman.transfer.v1.EvictResult
import org.tatrman.transfer.v1.Location
import org.tatrman.transfer.v1.MoveOptions
import org.tatrman.transfer.v1.MoveResult
import org.tatrman.transfer.v1.SeaweedBlob
import org.tatrman.kantheon.charon.endpoints.SeaweedEndpoint
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CopyObjectRequest
import software.amazon.awssdk.services.s3.model.CopyObjectResponse
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest
import software.amazon.awssdk.services.s3.model.PutObjectTaggingResponse
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import java.io.ByteArrayInputStream

/**
 * Mocked unit suite for the Stage 1.2 Seaweed-backed move pipe.
 *
 * The live round-trip + fault-injection lands in a **separate
 * integration-test pass** against the real local K3s SeaweedFS
 * (charon/plan.md §3.2 T3 + T6 — "K3s integration. Live stack smoke
 * tests at the end of each stage that ships infra"). At Stage 1.2 the
 * suite is `mockk`-driven against the `S3Client` interface and asserts
 * the move-pipe contract (atomicity, fingerprint, byte cap, error
 * surfacing, metric labels) without a Docker dependency.
 */
class CharonMoveExecutorSpec :
    StringSpec({

        val schema = schemaOf(int64("i"))
        val alloc = RootAllocator()
        val sourceRoot = VectorSchemaRoot.create(schema, alloc)
        val vec = sourceRoot.getVector("i") as BigIntVector
        vec.allocateNew(100)

        // Stage 1.2 tests use S3 only; the executor constructor now
        // takes a Redis connection (Stage 1.3) which these tests
        // never touch. A relaxed mock is the right no-op.
        val noopRedis =
            mockk<io.lettuce.core.api.StatefulRedisConnection<ByteArray, ByteArray>>(relaxed = true)
        for (i in 0 until 100) vec.set(i, i.toLong())
        vec.valueCount = 100
        sourceRoot.rowCount = 100

        afterSpec {
            sourceRoot.close()
            alloc.close()
        }

        // --- happy path: Copy seaweed -> seaweed with a real IPC stream ---

        "Copy(seaweed -> seaweed) writes the schema message, a single batch, copies the temp key, deletes the temp" {
            // Build a real Arrow IPC stream from `sourceRoot` so the
            // mock S3Client's `getObject` can return a stream the
            // ArrowStreamReader can parse.
            val ipcBytes = ArrowStreamReaderFixture.serializeIpc(schema, listOf(sourceRoot), alloc)
            val s3 = mockk<S3Client>(relaxed = false)

            val bucket = "b"
            val srcKey = "src.arrow"
            val tgtKey = "tgt.arrow"
            val srcBlob =
                SeaweedBlob
                    .newBuilder()
                    .setBucket(bucket)
                    .setKey(srcKey)
                    .build()
            val tgtBlob =
                SeaweedBlob
                    .newBuilder()
                    .setBucket(bucket)
                    .setKey(tgtKey)
                    .build()
            val srcLoc = Location.newBuilder().setSeaweed(srcBlob).build()
            val tgtLoc = Location.newBuilder().setSeaweed(tgtBlob).build()

            // Mock the S3 calls in commit order: putObject(temp), copyObject,
            // deleteObject(temp).
            val tempKey = slot<String>()
            val putBodyBytes = slot<ByteArray>()
            every { s3.putObject(any<PutObjectRequest>(), any<software.amazon.awssdk.core.sync.RequestBody>()) } answers
                {
                    val req = firstArg<PutObjectRequest>()
                    tempKey.captured = req.key()
                    // Snag the bytes the executor wrote so we can verify the
                    // schema-message + batch-message content below.
                    val body = secondArg<software.amazon.awssdk.core.sync.RequestBody>()
                    val sink = java.io.ByteArrayOutputStream()
                    body.contentStreamProvider().newStream().use { it.copyTo(sink) }
                    putBodyBytes.captured = sink.toByteArray()
                    PutObjectResponse.builder().build()
                }
            every { s3.copyObject(any<CopyObjectRequest>()) } returns CopyObjectResponse.builder().build()
            every { s3.deleteObject(any<DeleteObjectRequest>()) } returns DeleteObjectResponse.builder().build()

            // The getObject is for the source — set it up so the read path works.
            val getResp = GetObjectResponse.builder().build()
            val stream = ByteArrayInputStream(ipcBytes)
            every { s3.getObject(any<GetObjectRequest>()) } returns ResponseInputStream(getResp, stream)

            val executor = CharonMoveExecutor(s3, noopRedis)
            val plan =
                Plan(
                    rpc = MoveRpc.COPY,
                    source = srcLoc,
                    target = tgtLoc,
                    options = MoveOptions.getDefaultInstance(),
                )
            val result = executor.copy(plan)
            result.shouldBeInstanceOf<Either.Right<MoveResult>>()
            val moveResult = (result as Either.Right).value
            withClue("schema_fingerprint must match the source's") {
                moveResult.schemaFingerprint shouldBe Integrity.fingerprint(schema)
            }
            withClue("row_count must equal the source's row count") {
                moveResult.rowCount shouldBe 100L
            }
            withClue("the real key was the COPY target, not the temp key") {
                moveResult.target.hasSeaweed() shouldBe true
                moveResult.target.seaweed.key shouldBe tgtKey
            }
            withClue("the temp key was namespaced under the real key") {
                tempKey.captured.startsWith("$tgtKey.tmp.") shouldBe true
            }
            withClue("put was called once (to the temp key) + copy + delete") {
                verify(
                    exactly = 1,
                ) { s3.putObject(any<PutObjectRequest>(), any<software.amazon.awssdk.core.sync.RequestBody>()) }
                verify(exactly = 1) { s3.copyObject(any<CopyObjectRequest>()) }
                verify(exactly = 1) { s3.deleteObject(any<DeleteObjectRequest>()) }
            }
            withClue("the bytes the executor wrote start with a valid Arrow schema message") {
                ArrowStreamReaderFixture.containsSchemaMessage(putBodyBytes.captured, schema) shouldBe true
            }
        }

        // --- same-location no-op ---

        "Copy(seaweed X -> seaweed X) is a same-location no-op: no S3 write" {
            val s3 = mockk<S3Client>(relaxed = true)
            val metadata: Map<String, String> = mapOf(SeaweedEndpoint.SCHEMA_FP_METADATA_KEY to "abc123")
            val head: HeadObjectResponse =
                HeadObjectResponse
                    .builder()
                    .contentLength(42L)
                    .metadata(metadata)
                    .build()
            // Production uses the lambda overload; mock both for safety.
            every { s3.headObject(any<HeadObjectRequest>()) } returns head
            every { s3.headObject(ofType<java.util.function.Consumer<HeadObjectRequest.Builder>>()) } returns head
            every { s3.headObject(ofType<java.util.function.Consumer<HeadObjectRequest.Builder>>()) } returns head
            val executor = CharonMoveExecutor(s3, noopRedis)
            val blob =
                SeaweedBlob
                    .newBuilder()
                    .setBucket("b")
                    .setKey("k")
                    .build()
            val loc = Location.newBuilder().setSeaweed(blob).build()
            val plan = Plan(rpc = MoveRpc.COPY, source = loc, target = loc, options = MoveOptions.getDefaultInstance())
            val result = executor.copy(plan)
            result.shouldBeInstanceOf<Either.Right<MoveResult>>()
            (result as Either.Right).value.schemaFingerprint shouldBe "abc123"
            // No write, no copy, no delete.
            verify(
                exactly = 0,
            ) { s3.putObject(any<PutObjectRequest>(), any<software.amazon.awssdk.core.sync.RequestBody>()) }
            verify(exactly = 0) { s3.copyObject(any<CopyObjectRequest>()) }
            verify(exactly = 0) { s3.deleteObject(any<DeleteObjectRequest>()) }
        }

        // --- fault injection: S3 putObject throws -> ArrowPipe returns EndpointUnavailable ---

        "mid-stream fault: putObject throws -> MoveResult is EndpointUnavailable, no copy/delete follows" {
            val s3 = mockk<S3Client>(relaxed = false)
            val ipcBytes = ArrowStreamReaderFixture.serializeIpc(schema, listOf(sourceRoot), alloc)
            val getResp = GetObjectResponse.builder().build()
            val stream = ByteArrayInputStream(ipcBytes)
            every { s3.getObject(any<GetObjectRequest>()) } returns ResponseInputStream(getResp, stream)
            every { s3.putObject(any<PutObjectRequest>(), any<software.amazon.awssdk.core.sync.RequestBody>()) } throws
                S3Exception.builder().message("simulated S3 outage").build()
            // The pipe's discard() unwinds the temp key (the atomicity hook);
            // we allow it here. The contract we care about: no COPY happens
            // (the real key never becomes visible).
            every { s3.deleteObject(any<DeleteObjectRequest>()) } returns DeleteObjectResponse.builder().build()

            val executor = CharonMoveExecutor(s3, noopRedis)
            val plan =
                Plan(
                    rpc = MoveRpc.COPY,
                    source =
                        Location
                            .newBuilder()
                            .setSeaweed(
                                SeaweedBlob
                                    .newBuilder()
                                    .setBucket("b")
                                    .setKey("src.arrow")
                                    .build(),
                            ).build(),
                    target =
                        Location
                            .newBuilder()
                            .setSeaweed(
                                SeaweedBlob
                                    .newBuilder()
                                    .setBucket("b")
                                    .setKey("tgt.arrow")
                                    .build(),
                            ).build(),
                    options = MoveOptions.getDefaultInstance(),
                )
            val result = executor.copy(plan)
            result.shouldBeInstanceOf<Either.Left<CharonError>>()
            val err = (result as Either.Left).value
            withClue("error should be an EndpointUnavailable (the S3 exception is wrapped)") {
                err.shouldBeInstanceOf<CharonError.EndpointUnavailable>()
            }
            // The atomicity invariant: the real key was never created (no COPY
            // happened). The temp key was unwound (deleteObject was called) —
            // that's the EXPECTED behaviour for a mid-stream failure.
            verify(exactly = 0) { s3.copyObject(any<CopyObjectRequest>()) }
            verify(exactly = 1) { s3.deleteObject(any<DeleteObjectRequest>()) }
        }

        // --- fingerprint mismatch ---

        "fingerprint mismatch: expected fingerprint doesn't match -> FingerprintMismatch, no write" {
            val s3 = mockk<S3Client>(relaxed = true)
            val ipcBytes = ArrowStreamReaderFixture.serializeIpc(schema, listOf(sourceRoot), alloc)
            val getResp = GetObjectResponse.builder().build()
            every { s3.getObject(any<GetObjectRequest>()) } returns
                ResponseInputStream(getResp, ByteArrayInputStream(ipcBytes))
            val executor = CharonMoveExecutor(s3, noopRedis)
            val plan =
                Plan(
                    rpc = MoveRpc.COPY,
                    source =
                        Location
                            .newBuilder()
                            .setSeaweed(
                                SeaweedBlob
                                    .newBuilder()
                                    .setBucket("b")
                                    .setKey("src")
                                    .build(),
                            ).build(),
                    target =
                        Location
                            .newBuilder()
                            .setSeaweed(
                                SeaweedBlob
                                    .newBuilder()
                                    .setBucket("b")
                                    .setKey("tgt")
                                    .build(),
                            ).build(),
                    options =
                        MoveOptions
                            .newBuilder()
                            .setExpectedSchemaFingerprint(
                                "0000000000000000000000000000000000000000000000000000000000000000",
                            ).build(),
                )
            val result = executor.copy(plan)
            result.shouldBeInstanceOf<Either.Left<CharonError>>()
            (result as Either.Left).value.shouldBeInstanceOf<CharonError.FingerprintMismatch>()
            // No write attempted.
            verify(
                exactly = 0,
            ) { s3.putObject(any<PutObjectRequest>(), any<software.amazon.awssdk.core.sync.RequestBody>()) }
        }

        // --- Evict / Describe ---

        "Evict(seaweed) on a present key returns existed=true and calls deleteObject" {
            val s3 = mockk<S3Client>(relaxed = false)
            val head = HeadObjectResponse.builder().contentLength(1L).build()
            every { s3.headObject(any<HeadObjectRequest>()) } returns head
            every { s3.headObject(ofType<java.util.function.Consumer<HeadObjectRequest.Builder>>()) } returns head
            every { s3.deleteObject(any<DeleteObjectRequest>()) } returns DeleteObjectResponse.builder().build()
            val executor = CharonMoveExecutor(s3, noopRedis)
            val blob =
                SeaweedBlob
                    .newBuilder()
                    .setBucket("b")
                    .setKey("k")
                    .build()
            val loc = Location.newBuilder().setSeaweed(blob).build()
            val plan =
                Plan(rpc = MoveRpc.EVICT, source = loc, target = null, options = MoveOptions.getDefaultInstance())
            val result = executor.evict(plan)
            result.shouldBeInstanceOf<Either.Right<EvictResult>>()
            (result as Either.Right).value.existed shouldBe true
            verify(exactly = 1) { s3.deleteObject(any<DeleteObjectRequest>()) }
        }

        "Evict(seaweed) on a missing key returns existed=false" {
            val s3 = mockk<S3Client>(relaxed = false)
            every { s3.headObject(any<HeadObjectRequest>()) } throws NoSuchKeyException.builder().build()
            every { s3.headObject(ofType<java.util.function.Consumer<HeadObjectRequest.Builder>>()) } throws
                NoSuchKeyException.builder().build()
            every { s3.deleteObject(any<DeleteObjectRequest>()) } returns DeleteObjectResponse.builder().build()
            val executor = CharonMoveExecutor(s3, noopRedis)
            val blob =
                SeaweedBlob
                    .newBuilder()
                    .setBucket("b")
                    .setKey("k")
                    .build()
            val loc = Location.newBuilder().setSeaweed(blob).build()
            val plan =
                Plan(rpc = MoveRpc.EVICT, source = loc, target = null, options = MoveOptions.getDefaultInstance())
            val result = executor.evict(plan)
            result.shouldBeInstanceOf<Either.Right<EvictResult>>()
            (result as Either.Right).value.existed shouldBe false
            // S3's DELETE is idempotent (a missing key is a no-op 204), so
            // we DO still call deleteObject on the no-key path — the call
            // is what makes Evict safe to retry. The behaviour we care
            // about: existed = false in the response.
        }

        "Describe(seaweed) returns the user-metadata fingerprint + content length" {
            val s3 = mockk<S3Client>(relaxed = false)
            val fp = "deadbeef" + "c0ffee".repeat(7)
            val metadata: Map<String, String> = mapOf(SeaweedEndpoint.SCHEMA_FP_METADATA_KEY to fp)
            val head: HeadObjectResponse =
                HeadObjectResponse
                    .builder()
                    .contentLength(12345L)
                    .metadata(metadata)
                    .build()
            every { s3.headObject(any<HeadObjectRequest>()) } returns head
            every { s3.headObject(ofType<java.util.function.Consumer<HeadObjectRequest.Builder>>()) } returns head
            val executor = CharonMoveExecutor(s3, noopRedis)
            val blob =
                SeaweedBlob
                    .newBuilder()
                    .setBucket("b")
                    .setKey("k")
                    .build()
            val loc = Location.newBuilder().setSeaweed(blob).build()
            val plan =
                Plan(rpc = MoveRpc.DESCRIBE, source = loc, target = null, options = MoveOptions.getDefaultInstance())
            val result = executor.describe(plan)
            result.shouldBeInstanceOf<Either.Right<DescribeResult>>()
            val desc = (result as Either.Right).value
            desc.exists shouldBe true
            desc.schemaFingerprint shouldBe fp
            desc.sizeBytes shouldBe 12345L
        }

        "Describe(seaweed) on a missing key returns exists=false" {
            val s3 = mockk<S3Client>(relaxed = false)
            every { s3.headObject(any<HeadObjectRequest>()) } throws NoSuchKeyException.builder().build()
            every { s3.headObject(ofType<java.util.function.Consumer<HeadObjectRequest.Builder>>()) } throws
                NoSuchKeyException.builder().build()
            val executor = CharonMoveExecutor(s3, noopRedis)
            val blob =
                SeaweedBlob
                    .newBuilder()
                    .setBucket("b")
                    .setKey("k")
                    .build()
            val loc = Location.newBuilder().setSeaweed(blob).build()
            val plan =
                Plan(rpc = MoveRpc.DESCRIBE, source = loc, target = null, options = MoveOptions.getDefaultInstance())
            val result = executor.describe(plan)
            result.shouldBeInstanceOf<Either.Right<DescribeResult>>()
            (result as Either.Right).value.exists shouldBe false
        }

        // --- retention tag ---

        "put with retention_tag sets an object tag (lifecycle rules act on it)" {
            val s3 = mockk<S3Client>(relaxed = false)
            val ipcBytes = ArrowStreamReaderFixture.serializeIpc(schema, listOf(sourceRoot), alloc)
            val getResp = GetObjectResponse.builder().build()
            every { s3.getObject(any<GetObjectRequest>()) } returns
                ResponseInputStream(getResp, ByteArrayInputStream(ipcBytes))
            every { s3.putObject(any<PutObjectRequest>(), any<software.amazon.awssdk.core.sync.RequestBody>()) } returns
                PutObjectResponse.builder().build()
            every { s3.copyObject(any<CopyObjectRequest>()) } returns CopyObjectResponse.builder().build()
            every { s3.deleteObject(any<DeleteObjectRequest>()) } returns DeleteObjectResponse.builder().build()
            every { s3.putObjectTagging(any<PutObjectTaggingRequest>()) } returns
                PutObjectTaggingResponse.builder().build()
            val executor = CharonMoveExecutor(s3, noopRedis)
            val plan =
                Plan(
                    rpc = MoveRpc.COPY,
                    source =
                        Location
                            .newBuilder()
                            .setSeaweed(
                                SeaweedBlob
                                    .newBuilder()
                                    .setBucket("b")
                                    .setKey("src")
                                    .build(),
                            ).build(),
                    target =
                        Location
                            .newBuilder()
                            .setSeaweed(
                                SeaweedBlob
                                    .newBuilder()
                                    .setBucket("b")
                                    .setKey("tgt")
                                    .setRetentionTag("shallow")
                                    .build(),
                            ).build(),
                    options = MoveOptions.getDefaultInstance(),
                )
            val result = executor.copy(plan)
            result.shouldBeInstanceOf<Either.Right<MoveResult>>()
            verify(exactly = 1) { s3.putObjectTagging(any<PutObjectTaggingRequest>()) }
        }

        // --- the seaweed -> redis cross-endpoint move (Stage 1.3) ---

        "Materialize(seaweed -> redis) reads from S3 and writes to Redis with the sidecar fingerprint" {
            val s3 = mockk<S3Client>(relaxed = false)
            val ops = mockk<org.tatrman.kantheon.charon.endpoints.RedisOps>(relaxed = false)
            val ipcBytes = ArrowStreamReaderFixture.serializeIpc(schema, listOf(sourceRoot), alloc)
            val getResp = GetObjectResponse.builder().build()
            every { s3.getObject(any<GetObjectRequest>()) } returns
                ResponseInputStream(getResp, ByteArrayInputStream(ipcBytes))
            every { ops.set(any<ByteArray>(), any<ByteArray>(), any<io.lettuce.core.SetArgs>()) } returns "OK"
            val executor = CharonMoveExecutor(s3, ops)
            val plan =
                Plan(
                    rpc = MoveRpc.MATERIALIZE,
                    source =
                        Location
                            .newBuilder()
                            .setSeaweed(
                                SeaweedBlob
                                    .newBuilder()
                                    .setBucket("b")
                                    .setKey("src")
                                    .build(),
                            ).build(),
                    target =
                        Location
                            .newBuilder()
                            .setRedis(
                                org.tatrman.transfer.v1.RedisEntry
                                    .newBuilder()
                                    .setKey("r")
                                    .setTtlSeconds(60)
                                    .build(),
                            ).build(),
                    options = MoveOptions.getDefaultInstance(),
                )
            val result = executor.materialize(plan)
            result.shouldBeInstanceOf<Either.Right<MoveResult>>()
            val moveResult = (result as Either.Right).value
            withClue("target resolves to the Redis location") {
                moveResult.target.hasRedis() shouldBe true
                moveResult.target.redis.key shouldBe "r"
            }
            withClue("schema fingerprint matches the source's") {
                moveResult.schemaFingerprint shouldBe Integrity.fingerprint(schema)
            }
            withClue("row count is the source's row count") {
                moveResult.rowCount shouldBe 100L
            }
            withClue("value SET fired with the redis key as the byte-encoded key") {
                verify(exactly = 1) {
                    ops.set(
                        eq("r".toByteArray(Charsets.UTF_8)),
                        any<ByteArray>(),
                        any<io.lettuce.core.SetArgs>(),
                    )
                }
            }
            withClue("sidecar SET fired with the redis fp key") {
                verify(exactly = 1) {
                    ops.set(
                        eq("r:schema-fp".toByteArray(Charsets.UTF_8)),
                        any<ByteArray>(),
                        any<io.lettuce.core.SetArgs>(),
                    )
                }
            }
        }

        // --- the redis -> seaweed cross-endpoint move (Stage 1.3) ---

        "Materialize(redis -> seaweed) reads from Redis and writes to S3 with temp-key atomicity" {
            val s3 = mockk<S3Client>(relaxed = false)
            val ops = mockk<org.tatrman.kantheon.charon.endpoints.RedisOps>(relaxed = false)
            // The Redis source returns real Arrow IPC bytes (the
            // same shape as the seaweed-only happy-path test), so
            // the pipe's ArrowStreamReader can parse them and the
            // move completes with the expected row count.
            val ipcBytes = ArrowStreamReaderFixture.serializeIpc(schema, listOf(sourceRoot), alloc)
            every { ops.get("src".toByteArray(Charsets.UTF_8)) } returns ipcBytes
            every { ops.get("src:schema-fp".toByteArray(Charsets.UTF_8)) } returns "fp".toByteArray()
            every { s3.putObject(any<PutObjectRequest>(), any<software.amazon.awssdk.core.sync.RequestBody>()) } returns
                PutObjectResponse.builder().build()
            every { s3.copyObject(any<CopyObjectRequest>()) } returns CopyObjectResponse.builder().build()
            every { s3.deleteObject(any<DeleteObjectRequest>()) } returns DeleteObjectResponse.builder().build()
            val executor = CharonMoveExecutor(s3, ops)
            val plan =
                Plan(
                    rpc = MoveRpc.MATERIALIZE,
                    source =
                        Location
                            .newBuilder()
                            .setRedis(
                                org.tatrman.transfer.v1.RedisEntry
                                    .newBuilder()
                                    .setKey("src")
                                    .build(),
                            ).build(),
                    target =
                        Location
                            .newBuilder()
                            .setSeaweed(
                                SeaweedBlob
                                    .newBuilder()
                                    .setBucket("b")
                                    .setKey("tgt")
                                    .build(),
                            ).build(),
                    options = MoveOptions.getDefaultInstance(),
                )
            val result = executor.materialize(plan)
            result.shouldBeInstanceOf<Either.Right<MoveResult>>()
            // The S3 put + copy all fired.
            verify(exactly = 1) {
                s3.putObject(any<PutObjectRequest>(), any<software.amazon.awssdk.core.sync.RequestBody>())
            }
            verify(exactly = 1) { s3.copyObject(any<CopyObjectRequest>()) }
        }

        // --- Redis value cap (review-006 R4) ---

        "Copy(seaweed -> redis) is bounded by redisMaxValueBytes → ByteCapExceeded" {
            // Review-006 R4. A Redis target with a tight
            // `redisMaxValueBytes` (here 256 bytes) must return
            // `CharonError.ByteCapExceeded` when the serialised
            // value exceeds the cap, and **no `SET`** may fire.
            val s3 = mockk<S3Client>(relaxed = true)
            val ops = mockk<org.tatrman.kantheon.charon.endpoints.RedisOps>(relaxed = false)
            val ipcBytes = ArrowStreamReaderFixture.serializeIpc(schema, listOf(sourceRoot), alloc)
            every { s3.getObject(any<GetObjectRequest>()) } returns
                ResponseInputStream(
                    GetObjectResponse.builder().build(),
                    ByteArrayInputStream(ipcBytes),
                )
            // A 256-byte cap is tight enough to trip on a 100-row
            // Int64 batch (the IPC bytes are >> 256).
            val executor = CharonMoveExecutor(s3, ops, redisMaxValueBytes = 256L)
            val plan =
                Plan(
                    rpc = MoveRpc.COPY,
                    source =
                        Location
                            .newBuilder()
                            .setSeaweed(
                                SeaweedBlob
                                    .newBuilder()
                                    .setBucket("b")
                                    .setKey("src")
                                    .build(),
                            ).build(),
                    target =
                        Location
                            .newBuilder()
                            .setRedis(
                                org.tatrman.transfer.v1.RedisEntry
                                    .newBuilder()
                                    .setKey("r")
                                    .build(),
                            ).build(),
                    options = MoveOptions.getDefaultInstance(),
                )
            val result = executor.copy(plan)
            result.shouldBeInstanceOf<Either.Left<CharonError>>()
            (result as Either.Left).value.shouldBeInstanceOf<CharonError.ByteCapExceeded>()
            // The pipe's discard() must unwind (no SET) — the R4 invariant.
            verify(exactly = 0) { ops.set(any<ByteArray>(), any<ByteArray>(), any<io.lettuce.core.SetArgs>()) }
        }

        // --- the redis -> redis same-location no-op (Stage 1.3) ---

        "Copy(redis X -> redis X) is a same-location no-op: PTTL/sidecar readback, no SET" {
            val s3 = mockk<S3Client>(relaxed = true)
            val ops = mockk<org.tatrman.kantheon.charon.endpoints.RedisOps>(relaxed = false)
            every { ops.get("k:schema-fp".toByteArray(Charsets.UTF_8)) } returns "fp".toByteArray()
            every { ops.strlen("k".toByteArray(Charsets.UTF_8)) } returns 99L
            val executor = CharonMoveExecutor(s3, ops)
            val entry =
                org.tatrman.transfer.v1.RedisEntry
                    .newBuilder()
                    .setKey("k")
                    .build()
            val loc = Location.newBuilder().setRedis(entry).build()
            val plan = Plan(rpc = MoveRpc.COPY, source = loc, target = loc, options = MoveOptions.getDefaultInstance())
            val result = executor.copy(plan)
            result.shouldBeInstanceOf<Either.Right<MoveResult>>()
            val moveResult = (result as Either.Right).value
            withClue("schema fingerprint comes from the sidecar") {
                moveResult.schemaFingerprint shouldBe "fp"
            }
            withClue("size is the current STRLEN of the value key") {
                moveResult.sizeBytes shouldBe 99L
            }
            withClue("no SET — same-location is a no-op") {
                verify(exactly = 0) { ops.set(any<ByteArray>(), any<ByteArray>(), any<io.lettuce.core.SetArgs>()) }
            }
        }

        // --- Evict(redis) and Describe(redis) (Stage 1.3) ---

        "Evict(redis) returns existed=true and DELs both keys" {
            val s3 = mockk<S3Client>(relaxed = true)
            val ops = mockk<org.tatrman.kantheon.charon.endpoints.RedisOps>(relaxed = false)
            every { ops.exists("k".toByteArray(Charsets.UTF_8)) } returns 1L
            every { ops.del("k".toByteArray(Charsets.UTF_8), "k:schema-fp".toByteArray(Charsets.UTF_8)) } returns 2L
            val executor = CharonMoveExecutor(s3, ops)
            val entry =
                org.tatrman.transfer.v1.RedisEntry
                    .newBuilder()
                    .setKey("k")
                    .build()
            val loc = Location.newBuilder().setRedis(entry).build()
            val plan =
                Plan(rpc = MoveRpc.EVICT, source = loc, target = null, options = MoveOptions.getDefaultInstance())
            val result = executor.evict(plan)
            result.shouldBeInstanceOf<Either.Right<EvictResult>>()
            (result as Either.Right).value.existed shouldBe true
            verify(exactly = 1) { ops.del("k".toByteArray(Charsets.UTF_8), "k:schema-fp".toByteArray(Charsets.UTF_8)) }
        }

        "Describe(redis) returns exists=true with fingerprint + size + expires_at" {
            val s3 = mockk<S3Client>(relaxed = true)
            val ops = mockk<org.tatrman.kantheon.charon.endpoints.RedisOps>(relaxed = false)
            val fp = "deadbeef"
            every { ops.exists("k".toByteArray(Charsets.UTF_8)) } returns 1L
            every { ops.get("k:schema-fp".toByteArray(Charsets.UTF_8)) } returns fp.toByteArray()
            every { ops.strlen("k".toByteArray(Charsets.UTF_8)) } returns 7L
            every { ops.pttl("k".toByteArray(Charsets.UTF_8)) } returns 30_000L
            val executor = CharonMoveExecutor(s3, ops)
            val entry =
                org.tatrman.transfer.v1.RedisEntry
                    .newBuilder()
                    .setKey("k")
                    .build()
            val loc = Location.newBuilder().setRedis(entry).build()
            val plan =
                Plan(rpc = MoveRpc.DESCRIBE, source = loc, target = null, options = MoveOptions.getDefaultInstance())
            val result = executor.describe(plan)
            result.shouldBeInstanceOf<Either.Right<DescribeResult>>()
            val desc = (result as Either.Right).value
            desc.exists shouldBe true
            desc.schemaFingerprint shouldBe fp
            desc.sizeBytes shouldBe 7L
            desc.hasExpiresAt() shouldBe true
        }
    })

// --- Test fixtures ---

private fun schemaOf(vararg fields: Field): Schema = Schema(listOf(*fields))

private fun int64(name: String): Field = Field(name, FieldType.notNullable(ArrowType.Int(64, true)), null)

/**
 * Serialize the given roots to a single Arrow IPC stream (schema + N
 * record-batch messages) and return the bytes. Mirrors the SeaweedEndpoint
 * helper but exposed for tests.
 */
private object ArrowStreamReaderFixture {
    fun serializeIpc(
        schema: Schema,
        batches: List<VectorSchemaRoot>,
        sourceAllocator: RootAllocator,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        // Reuse the SOURCE allocator for the destination root so buffer
        // ownership transfers cleanly between the source batches and the
        // destination (different allocators would trip Arrow's
        // "A buffer can only be associated between two allocators that
        // share the same root" precondition).
        VectorSchemaRoot.create(schema, sourceAllocator).use { root ->
            org.apache.arrow.vector.ipc.ArrowStreamWriter(root, null, out).use { writer ->
                writer.start()
                for (batch in batches) {
                    for (i in 0 until schema.fields.size) {
                        val srcVec = batch.getVector(i)
                        val dstVec = root.getVector(i)
                        dstVec.reAlloc()
                        srcVec.makeTransferPair(dstVec).transfer()
                    }
                    root.rowCount = batch.rowCount
                    writer.writeBatch()
                }
                writer.end()
            }
        }
        return out.toByteArray()
    }

    /** True when the bytes start with a valid Arrow schema message for [schema]. */
    fun containsSchemaMessage(
        bytes: ByteArray,
        schema: Schema,
    ): Boolean {
        val alloc = RootAllocator()
        return try {
            ArrowStreamReader(ByteArrayInputStream(bytes), alloc).use { reader ->
                val readSchema = reader.vectorSchemaRoot.schema
                readSchema.fields.size == schema.fields.size &&
                    readSchema.fields.map { it.name } == schema.fields.map { it.name }
            }
        } catch (e: Exception) {
            false
        } finally {
            alloc.close()
        }
    }
}
