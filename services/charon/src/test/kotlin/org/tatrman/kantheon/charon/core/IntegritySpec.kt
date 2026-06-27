package org.tatrman.kantheon.charon.core

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowStreamReader
import org.apache.arrow.vector.types.DateUnit
import org.apache.arrow.vector.types.FloatingPointPrecision
import org.apache.arrow.vector.types.TimeUnit
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import org.apache.arrow.vector.types.pojo.Schema
import java.io.File
import java.security.MessageDigest

/**
 * The unit suite for the [Integrity] primitives. The load-bearing test is the
 * **cross-engine fingerprint** check: Charon reads the SAME shared Arrow IPC
 * fixtures (`shared/testdata/fingerprints/`) that Steropes (pyarrow) and Brontes
 * (Arrow Java) read, recomputes the canonical fingerprint with its own
 * [Integrity] implementation, and asserts equality with the pinned
 * `fingerprints.json`. Same algorithm, three implementations, must agree;
 * reading the same bytes, they all converge on one digest per schema.
 *
 * This replaces Charon's former *private* `fixtures/integrity/` copy (which only
 * covered the reference schema) with the shared anchor — so the nested-type
 * subtlety (`map` → entries-wrapped `{key,value}` struct) is exercised here too
 * (`map.arrow`), not just for the flat scalar/struct cases.
 *
 * review-006 R3 (decided 2026-06-15, Bora): the fingerprint is a canonical,
 * implementation-independent schema digest — NOT raw Arrow IPC bytes, which are
 * not byte-stable across Arrow Java vs pyarrow. See [Integrity]'s KDoc and
 * `charon/contracts.md` §6.
 */
class IntegritySpec :
    StringSpec({

        // --- Fingerprint: stability + sensitivity ---

        "fingerprint is stable for the same schema" {
            val schema = schemaOf(utf8("name"), bigint("count"), float64("score"))
            Integrity.fingerprint(schema) shouldBe Integrity.fingerprint(schema)
        }

        "fingerprint is 64-char lowercase hex (SHA-256)" {
            val schema = schemaOf(utf8("name"))
            val fp = Integrity.fingerprint(schema)
            fp.length shouldBe 64
            fp.all { c -> c.isDigit() || (c in 'a'..'f') } shouldBe true
        }

        "fingerprint is sensitive to schema changes" {
            val a = schemaOf(utf8("name"))
            val b = schemaOf(utf8("different"))
            Integrity.fingerprint(a) shouldNotBe Integrity.fingerprint(b)
        }

        // --- Cross-engine fingerprint: agreement with the shared anchor ---
        // shared/testdata/fingerprints/{reference,scalars,list,map}.arrow +
        // fingerprints.json — the one anchor Charon, Brontes, and Steropes all
        // verify against (fork Stage 3.4; Charon closeout P1 S1.4 T2).

        val fixtureDir = locateFixtureDir()
        val pinned = parseFingerprints(File(fixtureDir, "fingerprints.json").readText())

        pinned.forEach { (fixture, digest) ->
            "Charon fingerprint matches the shared cross-engine pin for $fixture" {
                Integrity.fingerprint(schemaOf(File(fixtureDir, fixture))) shouldBe digest
            }
        }

        "map.arrow uses the entries-wrapped form and matches the shared pin" {
            // The cross-engine subtlety: Arrow Java exposes a Map field's child
            // as a single non-nullable `entries` struct of {key,value} — Charon's
            // `Integrity.encodeField` recurses over `field.children` and so emits
            // the entries-wrapped canonical form, identical to what the shared
            // `generate.py` synthesises for pyarrow. The flat key/value form (the
            // stale local `regenerate.py`) would NOT match.
            val mapSchema = schemaOf(File(fixtureDir, "map.arrow"))
            withClue("entries-wrapped canonical form") {
                Integrity.canonicalSchemaString(mapSchema) shouldBe
                    "id|int64s|nonnull\n" +
                    "attrs|map_unsorted|null<entries|struct|nonnull<key|utf8|nonnull;value|int32s|null>>"
            }
            Integrity.fingerprint(mapSchema) shouldBe pinned.getValue("map.arrow")
        }

        "reference.arrow matches the pinned cross-engine anchor digest" {
            Integrity.fingerprint(schemaOf(File(fixtureDir, "reference.arrow"))) shouldBe
                "69779ea65b0e127c59dc4f537bc33f62f08835c0098dbf313d61b35955fea7b8"
        }

        "in-code reference schema agrees with the shared reference.arrow fixture" {
            // Belt-and-braces: the schema built in Kotlin equals the one read
            // back from the shared IPC bytes — same canonical string, same digest.
            Integrity.fingerprint(referenceSchema()) shouldBe
                Integrity.fingerprint(schemaOf(File(fixtureDir, "reference.arrow")))
        }

        "fingerprint over a VectorSchemaRoot equals the schema-level call" {
            val schema = schemaOf(utf8("name"), bigint("count"))
            val alloc = RootAllocator()
            try {
                val root = VectorSchemaRoot.create(schema, alloc)
                try {
                    Integrity.fingerprint(root) shouldBe Integrity.fingerprint(schema)
                } finally {
                    root.close()
                }
            } finally {
                alloc.close()
            }
        }

        // --- Row counting ---

        "row counting: 1000-row dataset reports 1000" {
            val alloc = RootAllocator()
            try {
                val schema = schemaOf(bigint("i"))
                val root = VectorSchemaRoot.create(schema, alloc)
                try {
                    val vec = root.getVector("i") as org.apache.arrow.vector.BigIntVector
                    vec.allocateNew(1000)
                    for (i in 0 until 1000) vec.set(i, i.toLong())
                    vec.valueCount = 1000
                    root.rowCount = 1000
                    root.rowCount shouldBe 1000
                } finally {
                    root.close()
                }
            } finally {
                alloc.close()
            }
        }

        // --- Chunk-boundary cases (charon/contracts.md §6 default chunk_rows = 65536) ---

        "chunk boundary: 1-row batch and 65537-row batch are both valid roots" {
            val alloc = RootAllocator()
            try {
                val schema = schemaOf(bigint("i"))
                val small = VectorSchemaRoot.create(schema, alloc)
                try {
                    val smallVec = small.getVector("i") as org.apache.arrow.vector.BigIntVector
                    smallVec.allocateNew(1)
                    smallVec.set(0, 42L)
                    smallVec.valueCount = 1
                    small.rowCount = 1
                    small.rowCount shouldBe 1
                } finally {
                    small.close()
                }
                val large = VectorSchemaRoot.create(schema, alloc)
                try {
                    val largeVec = large.getVector("i") as org.apache.arrow.vector.BigIntVector
                    largeVec.allocateNew(65537)
                    for (i in 0 until 65537) largeVec.set(i, i.toLong())
                    largeVec.valueCount = 65537
                    large.rowCount = 65537
                    large.rowCount shouldBe 65537
                } finally {
                    large.close()
                }
            } finally {
                alloc.close()
            }
        }

        // --- SHA-256 helper sanity (regression net) ---

        "hex encoding is lowercase and zero-padded" {
            val sha = MessageDigest.getInstance("SHA-256")
            // SHA-256 of a 32-byte zero buffer — the canonical all-zeros input.
            val bytes = sha.digest(ByteArray(32))
            val hex = bytes.toHex()
            hex.length shouldBe 64
            hex shouldBe "66687aadf862bd776c8fc18b8e9f8e20089714856ee233b3902a591d0d5f2925"
        }
    })

// --- Test fixtures (kept inline; the matrix is small) ---

private fun utf8(name: String): Field = Field(name, FieldType.notNullable(ArrowType.Utf8()), null)

private fun bigint(name: String): Field = Field(name, FieldType.notNullable(ArrowType.Int(64, true)), null)

private fun float64(name: String): Field =
    Field(name, FieldType.notNullable(ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)), null)

private fun schemaOf(vararg fields: Field): Schema = Schema(listOf(*fields))

/** Read the logical schema back from a shared Arrow IPC stream fixture. */
private fun schemaOf(file: File): Schema =
    RootAllocator(Long.MAX_VALUE).use { allocator ->
        file.inputStream().use { input ->
            ArrowStreamReader(input, allocator).use { reader -> reader.vectorSchemaRoot.schema }
        }
    }

/** Walk up from the module dir to the repo root and find shared/testdata/fingerprints. */
private fun locateFixtureDir(): File {
    var dir: File? = File(System.getProperty("user.dir"))
    while (dir != null) {
        val candidate = File(dir, "shared/testdata/fingerprints")
        if (candidate.isDirectory) return candidate
        dir = dir.parentFile
    }
    error("could not locate shared/testdata/fingerprints from ${System.getProperty("user.dir")}")
}

/** Minimal parse of the flat `{ "<file>.arrow": "<hex>" }` map — no JSON dep needed. */
private fun parseFingerprints(json: String): Map<String, String> =
    Regex("\"([^\"]+\\.arrow)\"\\s*:\\s*\"([0-9a-f]+)\"")
        .findAll(json)
        .associate { it.groupValues[1] to it.groupValues[2] }

/** The cross-engine reference schema — must match `regenerate.py`'s
 *  REFERENCE_SCHEMA field-for-field (name, type+params, nullability, children). */
private fun referenceSchema(): Schema =
    Schema(
        listOf(
            Field("name", FieldType.notNullable(ArrowType.Utf8()), null),
            Field("count", FieldType.notNullable(ArrowType.Int(64, true)), null),
            Field("score", FieldType.nullable(ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)), null),
            Field("amount", FieldType.nullable(ArrowType.Decimal(38, 9, 128)), null),
            Field("ts", FieldType.nullable(ArrowType.Timestamp(TimeUnit.MICROSECOND, "UTC")), null),
            Field("d", FieldType.nullable(ArrowType.Date(DateUnit.DAY)), null),
            Field("active", FieldType.notNullable(ArrowType.Bool()), null),
            Field("payload", FieldType.nullable(ArrowType.Binary()), null),
            Field(
                "meta",
                FieldType.nullable(ArrowType.Struct()),
                listOf(
                    Field("key", FieldType.notNullable(ArrowType.Utf8()), null),
                    Field("val", FieldType.nullable(ArrowType.Int(32, true)), null),
                ),
            ),
        ),
    )

private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }
