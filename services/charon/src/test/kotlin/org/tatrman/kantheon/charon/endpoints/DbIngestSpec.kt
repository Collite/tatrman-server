package org.tatrman.kantheon.charon.endpoints

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.BigIntVector
import org.apache.arrow.vector.VarCharVector
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowReader
import org.apache.arrow.vector.ipc.ArrowStreamReader
import org.apache.arrow.vector.ipc.ArrowStreamWriter
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import org.apache.arrow.vector.types.pojo.Schema
import org.tatrman.charon.v1.DbWriteMode
import org.tatrman.charon.v1.Location
import org.tatrman.charon.v1.MoveResult
import org.tatrman.kantheon.charon.core.ArrowPipe
import org.tatrman.kantheon.charon.core.DbWritePreconditionException
import org.tatrman.kantheon.charon.core.Either
import org.tatrman.kantheon.charon.core.HikariConnectionProvider
import org.tatrman.kantheon.charon.core.Integrity
import org.tatrman.kantheon.charon.core.LocationKind
import org.tatrman.kantheon.charon.core.MoveRpc
import org.tatrman.kantheon.charon.core.PipeOptions
import org.tatrman.kantheon.charon.core.Source

/**
 * The Arrow → DB ingest path (charon/plan.md §4 Stage 2.2) — driven through the
 * real [ArrowPipe] into [JdbcAdbcWriter] over the H2 stand-in driver. Asserts
 * the write-mode contract (CREATE fail-if-exists, REPLACE transactional swap,
 * APPEND schema-compat), the no-partial-write rollback on a mid-commit fault,
 * and the extract∘ingest schema round-trip (fingerprint identity).
 */
class DbIngestSpec :
    StringSpec({

        val provider = HikariConnectionProvider()
        afterSpec { provider.close() }

        // (id: Int64 non-null, name: Utf8) — the canonical small ingest schema.
        val schema =
            Schema(
                listOf(
                    Field("id", FieldType(false, ArrowType.Int(64, true), null), null),
                    Field("name", FieldType(true, ArrowType.Utf8(), null), null),
                ),
            )

        fun ingest(
            handle: org.tatrman.kantheon.charon.core.ConnectionHandle,
            table: String,
            mode: DbWriteMode,
            rows: List<Pair<Long, String?>>,
        ): Either<org.tatrman.kantheon.charon.core.CharonError, MoveResult> {
            val alloc = RootAllocator()
            return try {
                val source = inMemoryArrowSource(schema, rows, alloc)
                val target = JdbcAdbcWriter(provider).target(handle, "public", table, mode)
                ArrowPipe.pipe(
                    MoveRpc.MATERIALIZE,
                    source,
                    target,
                    Location.getDefaultInstance(),
                    Location.getDefaultInstance(),
                    PipeOptions(),
                )
            } finally {
                alloc.close()
            }
        }

        fun rowCount(
            handle: org.tatrman.kantheon.charon.core.ConnectionHandle,
            table: String,
        ): Int =
            H2TestSupport.open(provider, handle).use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT COUNT(*) FROM public.$table").use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            }

        "CREATE creates the table and inserts all rows" {
            val h = H2TestSupport.handle("ingest-create")
            val r = ingest(h, "made", DbWriteMode.CREATE, listOf(1L to "a", 2L to "b", 3L to null))
            r.shouldBeInstanceOf<Either.Right<MoveResult>>()
            (r as Either.Right).value.rowCount shouldBe 3L
            rowCount(h, "made") shouldBe 3
        }

        "CREATE on an existing table → DbWritePreconditionException (FAILED_PRECONDITION)" {
            val h = H2TestSupport.handle("ingest-create-exists")
            H2TestSupport.exec(provider, h, "CREATE TABLE public.dupe (id BIGINT, name TEXT)")
            shouldThrow<DbWritePreconditionException> {
                ingest(h, "dupe", DbWriteMode.CREATE, listOf(1L to "a"))
            }
        }

        "REPLACE drops + recreates + inserts (old rows gone, new rows present)" {
            val h = H2TestSupport.handle("ingest-replace")
            H2TestSupport.exec(
                provider,
                h,
                "CREATE TABLE public.swap (id BIGINT, name TEXT)",
                "INSERT INTO public.swap VALUES (99, 'old')",
            )
            val r = ingest(h, "swap", DbWriteMode.REPLACE, listOf(1L to "new1", 2L to "new2"))
            r.shouldBeInstanceOf<Either.Right<MoveResult>>()
            rowCount(h, "swap") shouldBe 2
            H2TestSupport.open(provider, h).use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT COUNT(*) FROM public.swap WHERE name = 'old'").use { rs ->
                        rs.next()
                        rs.getInt(1) shouldBe 0
                    }
                }
            }
        }

        "APPEND adds to an existing compatible table" {
            val h = H2TestSupport.handle("ingest-append")
            ingest(h, "log", DbWriteMode.CREATE, listOf(1L to "a")).shouldBeInstanceOf<Either.Right<MoveResult>>()
            ingest(
                h,
                "log",
                DbWriteMode.APPEND,
                listOf(2L to "b", 3L to "c"),
            ).shouldBeInstanceOf<Either.Right<MoveResult>>()
            rowCount(h, "log") shouldBe 3
        }

        "APPEND onto a missing table → DbWritePreconditionException" {
            val h = H2TestSupport.handle("ingest-append-missing")
            shouldThrow<DbWritePreconditionException> {
                ingest(h, "absent", DbWriteMode.APPEND, listOf(1L to "a"))
            }
        }

        "APPEND with an incompatible existing schema → DbWritePreconditionException naming the column" {
            val h = H2TestSupport.handle("ingest-append-incompat")
            // Existing table has a different column type than the source schema.
            H2TestSupport.exec(provider, h, "CREATE TABLE public.bad (id BIGINT, name INTEGER)")
            val ex =
                shouldThrow<DbWritePreconditionException> {
                    ingest(h, "bad", DbWriteMode.APPEND, listOf(1L to "a"))
                }
            ex.message!!.contains("name") shouldBe true
        }

        "a mid-commit constraint fault rolls the whole transaction back (no partial write)" {
            val h = H2TestSupport.handle("ingest-rollback")
            // name is NOT NULL; the second row carries a null name → the batch
            // executeBatch at commit fails → ArrowPipe calls discard (ROLLBACK).
            H2TestSupport.exec(provider, h, "CREATE TABLE public.strict (id BIGINT, name TEXT NOT NULL)")
            val r = ingest(h, "strict", DbWriteMode.APPEND, listOf(1L to "ok", 2L to null))
            r.shouldBeInstanceOf<Either.Left<org.tatrman.kantheon.charon.core.CharonError>>()
            // Rolled back: not even the first (valid) row landed.
            rowCount(h, "strict") shouldBe 0
        }

        "extract ∘ ingest is schema-stable (CREATE then re-extract fingerprints identically)" {
            val h = H2TestSupport.handle("ingest-roundtrip")
            ingest(
                h,
                "rt",
                DbWriteMode.CREATE,
                listOf(1L to "a", 2L to "b"),
            ).shouldBeInstanceOf<Either.Right<MoveResult>>()
            RootAllocator().use { alloc ->
                JdbcAdbcReader(provider, alloc).source(h, "public", "rt", 65536).open()!!.use { rd ->
                    Integrity.fingerprint(rd.vectorSchemaRoot.schema) shouldBe Integrity.fingerprint(schema)
                }
            }
        }
    })

// --- In-memory Arrow IPC source for the ingest pipe ---

private fun inMemoryArrowSource(
    schema: Schema,
    rows: List<Pair<Long, String?>>,
    alloc: RootAllocator,
): Source {
    val out = ByteArrayOutputStream()
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
    val bytes = out.toByteArray()
    return object : Source {
        override fun open(): ArrowReader = ArrowStreamReader(ByteArrayInputStream(bytes), alloc)

        override fun kind(): LocationKind = LocationKind.SEAWEED

        override fun ref(): String = "in-memory"
    }
}
