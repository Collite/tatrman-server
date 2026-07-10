package org.tatrman.worker.mssql.arrow

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import org.apache.arrow.vector.types.Types
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import org.apache.arrow.vector.types.pojo.Schema

class SchemaFingerprintSpec :
    StringSpec({
        "fingerprint is deterministic across calls with the same schema" {
            val schema = schemaOf(intField("a"), varcharField("b"))
            val a = ArrowIpcSerializer.fingerprintFor(schema)
            val b = ArrowIpcSerializer.fingerprintFor(schema)
            a shouldBe b
            a.shouldMatch("^[0-9a-f]{64}$".toRegex())
        }

        "fingerprint differs when the schema differs" {
            val a = ArrowIpcSerializer.fingerprintFor(schemaOf(intField("a")))
            val b = ArrowIpcSerializer.fingerprintFor(schemaOf(intField("b")))
            a shouldNotBe b
        }

        "fingerprint differs when a field type changes" {
            val a = ArrowIpcSerializer.fingerprintFor(schemaOf(intField("a")))
            val b = ArrowIpcSerializer.fingerprintFor(schemaOf(varcharField("a")))
            a shouldNotBe b
        }
    })

private fun schemaOf(vararg fields: Field): Schema = Schema(fields.toList())

private fun intField(name: String): Field = Field(name, FieldType(true, Types.MinorType.INT.type, null), emptyList())

private fun varcharField(name: String): Field =
    Field(name, FieldType(true, Types.MinorType.VARCHAR.type, null), emptyList())
