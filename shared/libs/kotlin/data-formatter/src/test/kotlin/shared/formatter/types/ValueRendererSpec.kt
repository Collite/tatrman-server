package shared.formatter.types

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import shared.formatter.core.FormatOptions
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

class ValueRendererSpec :
    StringSpec({

        val opts = FormatOptions()

        "INT64 — non-null and null across all formats" {
            ValueRenderer.renderForJson(42L, LogicalType.Int64, opts) shouldBe JsonPrimitive(42L)
            ValueRenderer.renderForCsv(42L, LogicalType.Int64, opts) shouldBe "42"
            ValueRenderer.renderForTsv(42L, LogicalType.Int64, opts) shouldBe "42"
            ValueRenderer.renderForMarkdown(42L, LogicalType.Int64, opts) shouldBe "42"

            ValueRenderer.renderForJson(null, LogicalType.Int64, opts) shouldBe JsonNull
            ValueRenderer.renderForCsv(null, LogicalType.Int64, opts) shouldBe ""
            ValueRenderer.renderForTsv(null, LogicalType.Int64, opts) shouldBe ""
            ValueRenderer.renderForMarkdown(null, LogicalType.Int64, opts) shouldBe "null"
        }

        "DOUBLE — non-null and null" {
            ValueRenderer.renderForJson(3.14, LogicalType.Double, opts) shouldBe JsonPrimitive(3.14)
            ValueRenderer.renderForCsv(3.14, LogicalType.Double, opts) shouldBe "3.14"
            ValueRenderer.renderForMarkdown(null, LogicalType.Double, opts) shouldBe "null"
        }

        "DECIMAL — preserves precision" {
            val d = BigDecimal("123.4500")
            ValueRenderer.renderForCsv(d, LogicalType.Decimal(10, 4), opts) shouldBe "123.4500"
            ValueRenderer.renderForMarkdown(d, LogicalType.Decimal(10, 4), opts) shouldBe "123.4500"
            // JSON renders as unquoted literal preserving textual form.
            ValueRenderer.renderForJson(d, LogicalType.Decimal(10, 4), opts).toString() shouldBe "123.4500"
        }

        "STRING — passthrough" {
            ValueRenderer.renderForJson("hi", LogicalType.StringT, opts) shouldBe JsonPrimitive("hi")
            ValueRenderer.renderForCsv("hi", LogicalType.StringT, opts) shouldBe "hi"
            ValueRenderer.renderForMarkdown(null, LogicalType.StringT, opts) shouldBe "null"
        }

        "BOOL — true/false" {
            ValueRenderer.renderForJson(true, LogicalType.Bool, opts) shouldBe JsonPrimitive(true)
            ValueRenderer.renderForCsv(false, LogicalType.Bool, opts) shouldBe "false"
            ValueRenderer.renderForMarkdown(true, LogicalType.Bool, opts) shouldBe "true"
        }

        "DATE — ISO-8601" {
            val d = LocalDate.of(2026, 5, 3)
            ValueRenderer.renderForCsv(d, LogicalType.Date, opts) shouldBe "2026-05-03"
            ValueRenderer.renderForJson(d, LogicalType.Date, opts) shouldBe JsonPrimitive("2026-05-03")
            ValueRenderer.renderForMarkdown(null, LogicalType.Date, opts) shouldBe "null"
        }

        "TIMESTAMP — ISO-8601 local" {
            val ts = LocalDateTime.of(2026, 5, 3, 12, 34, 56)
            ValueRenderer.renderForCsv(ts, LogicalType.Timestamp, opts) shouldBe "2026-05-03T12:34:56"
        }

        "TIMESTAMP_TZ — uses opts.timestampZone" {
            val instant = OffsetDateTime.of(2026, 5, 3, 12, 0, 0, 0, ZoneOffset.UTC).toInstant()
            val zUtc =
                ValueRenderer.renderForCsv(instant, LogicalType.TimestampTz, FormatOptions(timestampZone = "Z"))
            val zPrague =
                ValueRenderer.renderForCsv(
                    instant,
                    LogicalType.TimestampTz,
                    FormatOptions(timestampZone = "Europe/Prague"),
                )
            zUtc shouldBe "2026-05-03T12:00:00Z"
            zPrague shouldBe "2026-05-03T14:00:00+02:00"
        }

        "BYTES — base64" {
            val bytes = byteArrayOf(0x41, 0x42, 0x43) // "ABC"
            ValueRenderer.renderForCsv(bytes, LogicalType.Bytes, opts) shouldBe "QUJD"
            ValueRenderer.renderForJson(bytes, LogicalType.Bytes, opts) shouldBe JsonPrimitive("QUJD")
        }

        "NULL_TYPE — always null" {
            ValueRenderer.renderForJson("anything", LogicalType.NullType, opts) shouldBe JsonNull
            ValueRenderer.renderForCsv("anything", LogicalType.NullType, opts) shouldBe ""
            ValueRenderer.renderForTsv("anything", LogicalType.NullType, opts) shouldBe ""
            ValueRenderer.renderForMarkdown("anything", LogicalType.NullType, opts) shouldBe "null"
        }

        "Number widening — Int and Short coerce into INT64" {
            ValueRenderer.renderForCsv(7, LogicalType.Int64, opts) shouldBe "7"
            ValueRenderer.renderForCsv(7.toShort(), LogicalType.Int64, opts) shouldBe "7"
        }

        "Instant Timestamp falls back to UTC LocalDateTime" {
            val instant = Instant.parse("2026-05-03T12:34:56Z")
            ValueRenderer.renderForCsv(instant, LogicalType.Timestamp, opts) shouldBe "2026-05-03T12:34:56"
        }
    })
