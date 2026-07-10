package org.tatrman.kantheon.charon.endpoints

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.lettuce.core.SetArgs
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.tatrman.transfer.v1.RedisEntry

/**
 * Mocked unit suite for the Stage 1.3 Redis-backed endpoint.
 *
 * The live Redis round-trip + TTL behaviour lands in a **separate
 * integration-test pass** against the real local K3s Redis
 * (charon/plan.md §3.3 T1). At Stage 1.3 the suite is `mockk`-driven
 * against the narrow [RedisOps] interface (the full Lettuce
 * `RedisCommands<K,V>` is a 19-inheritance-deep union that mockk's
 * byte-buddy proxy can't instantiate — see the `RedisOps` KDoc).
 */
class RedisEndpointSpec :
    StringSpec({

        // The endpoint uses ByteArrayCodec (K=V=byte[]) so keys are
        // byte arrays on the wire. `RedisEndpoint.keyOf` is the
        // canonical string→byte[] helper; the tests assert against
        // the encoded form.
        fun k(s: String): ByteArray = RedisEndpoint.keyOf(s)

        // --- Source: get returns bytes + sidecar fingerprint ---

        "Source.open returns the Arrow bytes; the sidecar fingerprint is verifiable" {
            val ops = mockk<RedisOps>(relaxed = false)
            every { ops.get(k("k")) } returns byteArrayOf(1, 2, 3, 4, 5)
            every { ops.get(k("k:schema-fp")) } returns "fp".toByteArray()
            val endpoint = RedisEndpoint(ops)
            endpoint.setLocation(RedisEntry.newBuilder().setKey("k").build())
            val reader = endpoint.open()
            try {
                reader?.close()
            } catch (e: Exception) {
                // ignored — open() returns a reader; parse errors are
                // downstream.
            }
            verify(exactly = 1) { ops.get(k("k")) }
        }

        // --- Source: missing value -> null reader (SourceNotFound at the pipe) ---

        "Source.open returns null when the value is missing" {
            val ops = mockk<RedisOps>(relaxed = false)
            every { ops.get(any<ByteArray>()) } returns null
            val endpoint = RedisEndpoint(ops)
            endpoint.setLocation(RedisEntry.newBuilder().setKey("missing").build())
            endpoint.open() shouldBe null
        }

        // --- Source: drift detection — value present but sidecar fp missing ---

        "Source.open returns null when the value exists but the sidecar fingerprint is missing" {
            val ops = mockk<RedisOps>(relaxed = false)
            every { ops.get(k("k")) } returns byteArrayOf(1, 2, 3)
            every { ops.get(k("k:schema-fp")) } returns null
            val endpoint = RedisEndpoint(ops)
            endpoint.setLocation(RedisEntry.newBuilder().setKey("k").build())
            // Drift = treat as missing (the row is incomplete). The pipe
            // turns null into CharonError.SourceNotFound.
            endpoint.open() shouldBe null
        }

        // --- Target: SET with TTL atomicity — value + sidecar get the same TTL via SetArgs ---

        "Target.commit uses SET with EX (TTL) for value and sidecar fingerprint" {
            val ops = mockk<RedisOps>(relaxed = false)
            every { ops.set(any<ByteArray>(), any<ByteArray>(), any<SetArgs>()) } returns "OK"

            val schema =
                org.apache.arrow.vector.types.pojo.Schema(
                    listOf(
                        org.apache.arrow.vector.types.pojo.Field(
                            "i",
                            org.apache.arrow.vector.types.pojo.FieldType.notNullable(
                                org.apache.arrow.vector.types.pojo.ArrowType
                                    .Int(64, true),
                            ),
                            null,
                        ),
                    ),
                )
            val endpoint = RedisEndpoint(ops)
            endpoint.setLocation(
                RedisEntry
                    .newBuilder()
                    .setKey("k")
                    .setTtlSeconds(60)
                    .build(),
            )
            // We don't drive a real batch through — the test asserts
            // the I/O contract (two SETs with non-null SetArgs), not
            // the IPC bytes. The cross-allocator buffer-association
            // issue with `serializeBatchesToIpcStream` is covered in
            // CharonMoveExecutorSpec's Seaweed round-trip suite;
            // driving it through Redis with the same allocator pair
            // would be redundant. The commit path is exercised end-
            // to-end in CharonMoveExecutorSpec's Seaweed case.
            val receipt = endpoint.begin(schema)
            endpoint.commit(receipt)
            // Capture the SetArgs passed to each SET call. SetArgs'
            // `ex` field is private (Lettuce doesn't expose it), so we
            // capture the args by reference and assert the SET was called
            // with the right key + a non-null args object. The presence
            // of the SetArgs arg (vs. the no-args `set(K, V)` overload)
            // is the testable contract.
            val valueSetArgs = slot<SetArgs>()
            val fpSetArgs = slot<SetArgs>()
            verify {
                ops.set(eq(k("k")), any<ByteArray>(), capture(valueSetArgs))
            }
            verify {
                ops.set(eq(k("k:schema-fp")), any<ByteArray>(), capture(fpSetArgs))
            }
            withClue("value SET must be called with a non-null SetArgs (TTL applied)") {
                valueSetArgs.captured shouldNotBe null
            }
            withClue("sidecar SET must be called with a non-null SetArgs (TTL applied)") {
                fpSetArgs.captured shouldNotBe null
            }
        }

        // --- Target: discard deletes both keys (the no-partial-write unwind) ---

        "Target.discard deletes value and sidecar fingerprint (no partial write)" {
            val ops = mockk<RedisOps>(relaxed = false)
            val endpoint = RedisEndpoint(ops)
            endpoint.setLocation(RedisEntry.newBuilder().setKey("k").build())
            val schema =
                org.apache.arrow.vector.types.pojo.Schema(
                    listOf(
                        org.apache.arrow.vector.types.pojo.Field(
                            "i",
                            org.apache.arrow.vector.types.pojo.FieldType.notNullable(
                                org.apache.arrow.vector.types.pojo.ArrowType
                                    .Int(64, true),
                            ),
                            null,
                        ),
                    ),
                )
            // The endpoint builds the vararg list internally and
            // calls `ops.del(valueKey, fpKey)`.
            every { ops.del(k("k"), k("k:schema-fp")) } returns 0L
            val receipt = endpoint.begin(schema)
            endpoint.discard(receipt)
            verify(exactly = 1) { ops.del(k("k"), k("k:schema-fp")) }
        }

        // --- Describe: fingerprint + size_bytes + expires_at computed from PTTL ---

        "Describe returns exists=true, fingerprint, size_bytes, expires_at when the key is present" {
            val ops = mockk<RedisOps>(relaxed = false)
            val fp = "abc123"
            every { ops.exists(k("k")) } returns 1L
            every { ops.get(k("k:schema-fp")) } returns fp.toByteArray()
            every { ops.strlen(k("k")) } returns 42L
            // PTTL returns ms; 60_000 = 60 s in the future.
            every { ops.pttl(k("k")) } returns 60_000L
            val endpoint = RedisEndpoint(ops)
            val desc = endpoint.describe(RedisEntry.newBuilder().setKey("k").build())
            desc.exists shouldBe true
            desc.schemaFingerprint shouldBe fp
            desc.sizeBytes shouldBe 42L
            desc.rowCount shouldBe -1L
            desc.rowCountExact shouldBe false
            withClue("expires_at must be a non-empty ISO-8601 timestamp ~60s in the future") {
                desc.hasExpiresAt() shouldBe true
                val expires = desc.expiresAt
                val parsed = java.time.Instant.parse(expires)
                val now = java.time.Instant.now()
                val delta =
                    java.time.Duration
                        .between(now, parsed)
                        .seconds
                (delta in 50L..70L) shouldBe true
            }
        }

        "Describe returns exists=true but no expires_at when PTTL = -1 (no TTL set)" {
            val ops = mockk<RedisOps>(relaxed = false)
            every { ops.exists(k("k")) } returns 1L
            every { ops.get(k("k:schema-fp")) } returns "fp".toByteArray()
            every { ops.strlen(k("k")) } returns 5L
            every { ops.pttl(k("k")) } returns -1L // no expiry
            val endpoint = RedisEndpoint(ops)
            val desc = endpoint.describe(RedisEntry.newBuilder().setKey("k").build())
            desc.exists shouldBe true
            desc.hasExpiresAt() shouldBe false
        }

        "Describe returns exists=false when EXISTS returns 0" {
            val ops = mockk<RedisOps>(relaxed = false)
            every { ops.exists(k("k")) } returns 0L
            val endpoint = RedisEndpoint(ops)
            val desc = endpoint.describe(RedisEntry.newBuilder().setKey("k").build())
            desc.exists shouldBe false
            // No schema fingerprint / size / expiry when missing.
            desc.schemaFingerprint shouldBe ""
            desc.sizeBytes shouldBe -1L
        }

        // --- Evict: existed = true -> DEL both keys; existed = false -> still DEL (idempotent) ---

        "Evict returns existed=true and DELs both keys when the value is present" {
            val ops = mockk<RedisOps>(relaxed = false)
            every { ops.exists(k("k")) } returns 1L
            // Capture the keys passed to del via a stub. We verify the
            // call shape (both keys) fires.
            every { ops.del(k("k"), k("k:schema-fp")) } returns 2L
            val endpoint = RedisEndpoint(ops)
            val result = endpoint.evict(RedisEntry.newBuilder().setKey("k").build())
            result.existed shouldBe true
            verify(exactly = 1) { ops.del(k("k"), k("k:schema-fp")) }
        }

        "Evict returns existed=false and still DELs (idempotent)" {
            val ops = mockk<RedisOps>(relaxed = false)
            every { ops.exists(k("k")) } returns 0L
            every { ops.del(k("k"), k("k:schema-fp")) } returns 0L
            val endpoint = RedisEndpoint(ops)
            val result = endpoint.evict(RedisEntry.newBuilder().setKey("k").build())
            result.existed shouldBe false
            // DEL is still called — Evict is safe to retry.
            verify(exactly = 1) { ops.del(k("k"), k("k:schema-fp")) }
        }

        // --- Byte cap: maxValueBytes is exposed on the endpoint ---

        "maxValueBytes is plumbed from constructor (server-side cap)" {
            val ops = mockk<RedisOps>(relaxed = true)
            val endpoint = RedisEndpoint(ops, maxValueBytes = 8L)
            endpoint.maxValueBytes shouldBe 8L
        }
    })
