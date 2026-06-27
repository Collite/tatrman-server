package org.tatrman.kantheon.charon.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.BigIntVector
import org.apache.arrow.vector.VarCharVector
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowStreamWriter
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import org.apache.arrow.vector.types.pojo.Schema
import org.tatrman.charon.v1.DbTable
import org.tatrman.charon.v1.DbWriteMode
import org.tatrman.charon.v1.Location
import org.tatrman.charon.v1.MoveOptions
import org.tatrman.charon.v1.MoveResult
import org.tatrman.charon.v1.SeaweedBlob
import org.tatrman.kantheon.charon.endpoints.H2TestSupport
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CopyObjectRequest
import software.amazon.awssdk.services.s3.model.CopyObjectResponse
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest
import software.amazon.awssdk.services.s3.model.PutObjectTaggingResponse

/**
 * Component suite for the DB-edge moves through the real [CharonMoveExecutor]
 * (charon/plan.md §4 Stage 2.1 T7 + Stage 2.2 T5): db→seaweed (Pythia's
 * evidence-from-DB path), seaweed→db ingest, db→db cross-connection Copy, and
 * the allow-list / unknown-connection rejection *before any I/O*. Blob
 * endpoints are mocked S3; the DB side is the H2 stand-in driver.
 */
class DbMoveComponentSpec :
    StringSpec({

        val provider = HikariConnectionProvider()
        afterSpec { provider.close() }

        fun executorFor(
            s3: S3Client,
            vararg handles: org.tatrman.kantheon.charon.core.ConnectionHandle,
        ): CharonMoveExecutor =
            CharonMoveExecutor(
                s3Client = s3,
                redisOps = mockk(relaxed = true),
                connectionRegistry = ConnectionRegistry.of(handles.toList()),
                dbProvider = provider,
            )

        fun dbLoc(
            id: String,
            table: String,
        ): Location =
            Location
                .newBuilder()
                .setDbTable(
                    DbTable
                        .newBuilder()
                        .setConnectionId(id)
                        .setSchema("public")
                        .setTable(table)
                        .build(),
                ).build()

        fun seaweedLoc(
            key: String,
            retention: String? = null,
        ): Location {
            val b = SeaweedBlob.newBuilder().setBucket("evidence").setKey(key)
            if (retention != null) b.retentionTag = retention
            return Location.newBuilder().setSeaweed(b.build()).build()
        }

        "db→seaweed extracts the table, writes a readable Arrow object, flows the retention tag" {
            val h = H2TestSupport.handle("comp-db-seaweed")
            H2TestSupport.exec(
                provider,
                h,
                "CREATE TABLE public.evi (id BIGINT, name TEXT)",
                "INSERT INTO public.evi VALUES (1, 'x'), (2, 'y')",
            )
            val s3 = mockk<S3Client>(relaxed = false)
            val putBytes = slot<ByteArray>()
            every { s3.putObject(any<PutObjectRequest>(), any<RequestBody>()) } answers {
                val sink = ByteArrayOutputStream()
                secondArg<RequestBody>().contentStreamProvider().newStream().use { it.copyTo(sink) }
                putBytes.captured = sink.toByteArray()
                PutObjectResponse.builder().build()
            }
            every { s3.copyObject(any<CopyObjectRequest>()) } returns CopyObjectResponse.builder().build()
            every { s3.deleteObject(any<DeleteObjectRequest>()) } returns DeleteObjectResponse.builder().build()
            every { s3.putObjectTagging(any<PutObjectTaggingRequest>()) } returns
                PutObjectTaggingResponse.builder().build()

            val plan =
                Plan(
                    MoveRpc.MATERIALIZE,
                    dbLoc("comp-db-seaweed", "evi"),
                    seaweedLoc("inv/h1.arrow", retention = "production"),
                    MoveOptions.getDefaultInstance(),
                )
            val result = executorFor(s3, h).materialize(plan)
            result.shouldBeInstanceOf<Either.Right<MoveResult>>()
            (result as Either.Right).value.rowCount shouldBe 2L

            // The stored object reads back as the 2 extracted rows.
            RootAllocator().use { alloc ->
                org.apache.arrow.vector.ipc
                    .ArrowStreamReader(ByteArrayInputStream(putBytes.captured), alloc)
                    .use { rd ->
                        var rows = 0
                        while (rd.loadNextBatch()) rows += rd.vectorSchemaRoot.rowCount
                        rows shouldBe 2
                    }
            }
            // Retention tag flowed to the object (lifecycle rules act on it).
            verify(exactly = 1) { s3.putObjectTagging(any<PutObjectTaggingRequest>()) }
        }

        "seaweed→db ingests the Arrow object into a new table (CREATE)" {
            val h = H2TestSupport.handle("comp-seaweed-db")
            val schema =
                Schema(
                    listOf(
                        Field("id", FieldType(false, ArrowType.Int(64, true), null), null),
                        Field("name", FieldType(true, ArrowType.Utf8(), null), null),
                    ),
                )
            val ipc = ipcBytes(schema, listOf(10L to "a", 20L to "b", 30L to "c"))
            val s3 = mockk<S3Client>(relaxed = false)
            every { s3.getObject(any<GetObjectRequest>()) } returns
                ResponseInputStream(GetObjectResponse.builder().build(), ByteArrayInputStream(ipc))

            val plan =
                Plan(
                    MoveRpc.MATERIALIZE,
                    seaweedLoc("inv/src.arrow"),
                    dbLoc("comp-seaweed-db", "ingested"),
                    MoveOptions.newBuilder().setDbWriteMode(DbWriteMode.CREATE).build(),
                )
            val result = executorFor(s3, h).materialize(plan)
            result.shouldBeInstanceOf<Either.Right<MoveResult>>()
            (result as Either.Right).value.rowCount shouldBe 3L
            H2TestSupport.open(provider, h).use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT COUNT(*) FROM public.ingested").use { rs ->
                        rs.next()
                        rs.getInt(1) shouldBe 3
                    }
                }
            }
        }

        "db→db Copy moves rows across connections" {
            val src = H2TestSupport.handle("comp-db-src")
            val dst = H2TestSupport.handle("comp-db-dst")
            H2TestSupport.exec(
                provider,
                src,
                "CREATE TABLE public.s (id BIGINT, name TEXT)",
                "INSERT INTO public.s VALUES (1, 'a'), (2, 'b')",
            )
            val plan =
                Plan(
                    MoveRpc.COPY,
                    dbLoc("comp-db-src", "s"),
                    dbLoc("comp-db-dst", "d"),
                    MoveOptions.newBuilder().setDbWriteMode(DbWriteMode.CREATE).build(),
                )
            val s3 = mockk<S3Client>(relaxed = true)
            val result = executorFor(s3, src, dst).copy(plan)
            result.shouldBeInstanceOf<Either.Right<MoveResult>>()
            H2TestSupport.open(provider, dst).use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT COUNT(*) FROM public.d").use { rs ->
                        rs.next()
                        rs.getInt(1) shouldBe 2
                    }
                }
            }
        }

        "an allow-list violation is rejected BEFORE any I/O (S3 never touched)" {
            // read:false connection asked to be a source → AllowListViolation,
            // and the S3 target is never called.
            val h = H2TestSupport.handle("comp-denied", read = false, write = false)
            H2TestSupport.exec(provider, h, "CREATE TABLE public.secret (id BIGINT)")
            val s3 = mockk<S3Client>(relaxed = false)
            val plan =
                Plan(
                    MoveRpc.MATERIALIZE,
                    dbLoc("comp-denied", "secret"),
                    seaweedLoc("inv/leak.arrow"),
                    MoveOptions.getDefaultInstance(),
                )
            val result = executorFor(s3, h).materialize(plan)
            result.shouldBeInstanceOf<Either.Left<CharonError>>()
            ((result as Either.Left).value is CharonError.AllowListViolation) shouldBe true
            verify(exactly = 0) { s3.putObject(any<PutObjectRequest>(), any<RequestBody>()) }
        }

        "unknown connection_id → UnknownConnectionId (no I/O)" {
            val s3 = mockk<S3Client>(relaxed = false)
            val plan =
                Plan(
                    MoveRpc.MATERIALIZE,
                    dbLoc("not-registered", "t"),
                    seaweedLoc("inv/x.arrow"),
                    MoveOptions.getDefaultInstance(),
                )
            val result = executorFor(s3).materialize(plan)
            result.shouldBeInstanceOf<Either.Left<CharonError>>()
            ((result as Either.Left).value is CharonError.UnknownConnectionId) shouldBe true
        }

        "Describe(db_table) through the executor returns schema + fingerprint" {
            val h = H2TestSupport.handle("comp-describe")
            H2TestSupport.exec(provider, h, "CREATE TABLE public.dd (id BIGINT, name TEXT)")
            val s3 = mockk<S3Client>(relaxed = true)
            val result =
                executorFor(s3, h).describe(
                    Plan(MoveRpc.DESCRIBE, dbLoc("comp-describe", "dd"), null, MoveOptions.getDefaultInstance()),
                )
            result.shouldBeInstanceOf<Either.Right<org.tatrman.charon.v1.DescribeResult>>()
            (result as Either.Right).value.exists shouldBe true
        }
    })

private fun ipcBytes(
    schema: Schema,
    rows: List<Pair<Long, String?>>,
): ByteArray {
    val out = ByteArrayOutputStream()
    RootAllocator().use { alloc ->
        VectorSchemaRoot.create(schema, alloc).use { root ->
            val idVec = root.getVector("id") as BigIntVector
            val nameVec = root.getVector("name") as VarCharVector
            idVec.allocateNew(rows.size)
            nameVec.allocateNew()
            rows.forEachIndexed { i, (id, name) ->
                idVec.setSafe(i, id)
                if (name == null) nameVec.setNull(i) else nameVec.setSafe(i, name.toByteArray(Charsets.UTF_8))
            }
            root.rowCount = rows.size
            ArrowStreamWriter(root, null, out).use { w ->
                w.start()
                w.writeBatch()
                w.end()
            }
        }
    }
    return out.toByteArray()
}
