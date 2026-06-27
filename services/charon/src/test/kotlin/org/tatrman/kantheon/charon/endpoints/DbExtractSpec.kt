package org.tatrman.kantheon.charon.endpoints

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.BigIntVector
import org.apache.arrow.vector.IntVector
import org.apache.arrow.vector.VarCharVector
import org.apache.arrow.vector.types.DateUnit
import org.apache.arrow.vector.types.FloatingPointPrecision
import org.apache.arrow.vector.types.TimeUnit
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import org.apache.arrow.vector.types.pojo.Schema
import org.tatrman.kantheon.charon.core.DbTypeMapping
import org.tatrman.kantheon.charon.core.HikariConnectionProvider
import org.tatrman.kantheon.charon.core.Integrity
import org.tatrman.kantheon.charon.core.UnmappableTypeException

/**
 * The DB → Arrow extract path (charon/plan.md §4 Stage 2.1 T4/T5) — driven by
 * the H2 stand-in driver. Asserts: the extracted Arrow schema fingerprints
 * **identically** to the same logical schema built in-code (the cross-engine
 * identity Pythia's PD-5 drift check relies on); NULLs + empty tables;
 * chunk-spanning multi-batch reads; an unmappable column → named error.
 */
class DbExtractSpec :
    StringSpec({

        val provider = HikariConnectionProvider()
        afterSpec { provider.close() }

        fun nf(
            name: String,
            type: ArrowType,
            nullable: Boolean = true,
        ): Field = Field(name, FieldType(nullable, type, null), null)

        "extract maps JDBC types to Arrow and fingerprints identically to the in-code schema" {
            val h = H2TestSupport.handle("extract-types")
            H2TestSupport.exec(
                provider,
                h,
                """
                CREATE TABLE public.t (
                  id INTEGER NOT NULL,
                  big BIGINT,
                  name VARCHAR(64),
                  score DOUBLE PRECISION,
                  active BOOLEAN,
                  d DATE,
                  ts TIMESTAMP,
                  amount NUMERIC(18,2)
                )
                """.trimIndent(),
                "INSERT INTO public.t VALUES (1, 100, 'a', 1.5, TRUE, DATE '2026-06-26', TIMESTAMP '2026-06-26 12:00:00', 12.34)",
                "INSERT INTO public.t VALUES (2, NULL, NULL, NULL, NULL, NULL, NULL, NULL)",
            )

            val expected =
                Schema(
                    listOf(
                        nf("id", ArrowType.Int(32, true), nullable = false),
                        nf("big", ArrowType.Int(64, true)),
                        nf("name", ArrowType.Utf8()),
                        nf("score", ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
                        nf("active", ArrowType.Bool()),
                        nf("d", ArrowType.Date(DateUnit.DAY)),
                        nf("ts", ArrowType.Timestamp(TimeUnit.MICROSECOND, null)),
                        nf("amount", ArrowType.Decimal(18, 2, 128)),
                    ),
                )

            RootAllocator().use { alloc ->
                val reader = JdbcAdbcReader(provider, alloc).source(h, "public", "t", 65536)
                reader.open()!!.use { rd ->
                    Integrity.fingerprint(rd.vectorSchemaRoot.schema) shouldBe Integrity.fingerprint(expected)
                    var rows = 0
                    var firstId = -1
                    var firstName: String? = "?"
                    var secondBigNull = false
                    while (rd.loadNextBatch()) {
                        val root = rd.vectorSchemaRoot
                        val idVec = root.getVector("id") as IntVector
                        val nameVec = root.getVector("name") as VarCharVector
                        val bigVec = root.getVector("big") as BigIntVector
                        for (i in 0 until root.rowCount) {
                            if (rows == 0) {
                                firstId = idVec.get(i)
                                firstName = String(nameVec.get(i), Charsets.UTF_8)
                            }
                            if (rows == 1) secondBigNull = bigVec.isNull(i)
                            rows++
                        }
                    }
                    rows shouldBe 2
                    firstId shouldBe 1
                    firstName shouldBe "a"
                    secondBigNull shouldBe true
                }
            }
        }

        "an empty table extracts zero rows (loadNextBatch returns false immediately)" {
            val h = H2TestSupport.handle("extract-empty")
            H2TestSupport.exec(provider, h, "CREATE TABLE public.e (id INTEGER NOT NULL)")
            RootAllocator().use { alloc ->
                JdbcAdbcReader(provider, alloc).source(h, "public", "e", 65536).open()!!.use { rd ->
                    rd.loadNextBatch() shouldBe false
                    rd.vectorSchemaRoot.rowCount shouldBe 0
                }
            }
        }

        "chunked extract spans multiple batches and preserves total row count" {
            val h = H2TestSupport.handle("extract-chunked")
            H2TestSupport.exec(provider, h, "CREATE TABLE public.c (id INTEGER NOT NULL)")
            H2TestSupport.open(provider, h).use { conn ->
                conn.prepareStatement("INSERT INTO public.c VALUES (?)").use { ps ->
                    for (i in 0 until 5) {
                        ps.setInt(1, i)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
            }
            RootAllocator().use { alloc ->
                // chunkRows = 2 → batches of 2, 2, 1.
                JdbcAdbcReader(provider, alloc).source(h, "public", "c", 2).open()!!.use { rd ->
                    var batches = 0
                    var total = 0
                    while (rd.loadNextBatch()) {
                        batches++
                        total += rd.vectorSchemaRoot.rowCount
                    }
                    total shouldBe 5
                    (batches >= 2) shouldBe true
                }
            }
        }

        "a column with an unmapped JDBC type → UnmappableTypeException naming the column" {
            val h = H2TestSupport.handle("extract-unmappable")
            H2TestSupport.exec(provider, h, "CREATE TABLE public.u (id INTEGER NOT NULL, tags INTEGER ARRAY)")
            RootAllocator().use { alloc ->
                val ex =
                    shouldThrow<UnmappableTypeException> {
                        JdbcAdbcReader(provider, alloc).source(h, "public", "u", 65536).open()
                    }
                ex.column shouldBe "tags"
            }
        }

        "describe(db_table) returns schema + fingerprint without scanning; absent table → exists=false" {
            val h = H2TestSupport.handle("describe")
            H2TestSupport.exec(provider, h, "CREATE TABLE public.present (id INTEGER NOT NULL, name VARCHAR(8))")
            RootAllocator().use { alloc ->
                val reader = JdbcAdbcReader(provider, alloc)
                val present = reader.describe(h, "public", "present")
                present.exists shouldBe true
                present.rowCount shouldBe -1L
                present.rowCountExact shouldBe false
                val expected =
                    Schema(
                        listOf(
                            nf("id", ArrowType.Int(32, true), nullable = false),
                            nf("name", ArrowType.Utf8()),
                        ),
                    )
                present.schemaFingerprint shouldBe Integrity.fingerprint(expected)

                val absent = reader.describe(h, "public", "no_such_table")
                absent.exists shouldBe false
            }
        }

        "the extract SQL is table-level only (qualified name, no predicate)" {
            // Guard the security note (contracts §5): the reader builds
            // `SELECT * FROM <qualified>` — no caller-supplied predicate path.
            DbTypeMapping.qualify("public", "t", org.tatrman.kantheon.charon.core.DbDialect.POSTGRES) shouldBe
                "\"public\".\"t\""
        }
    })
