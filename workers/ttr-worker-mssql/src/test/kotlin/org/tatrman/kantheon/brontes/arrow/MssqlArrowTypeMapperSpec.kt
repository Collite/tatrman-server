package org.tatrman.kantheon.brontes.arrow

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.arrow.vector.types.DateUnit
import org.apache.arrow.vector.types.FloatingPointPrecision
import org.apache.arrow.vector.types.TimeUnit
import org.apache.arrow.vector.types.Types
import org.apache.arrow.vector.types.pojo.ArrowType
import java.sql.JDBCType

class MssqlArrowTypeMapperSpec :
    StringSpec({
        "tinyint maps to UINT8" {
            val (t, _) = MssqlArrowTypeMapper.mapType("tinyint", JDBCType.TINYINT, 0, 0)
            t shouldBe Types.MinorType.UINT1.type
        }
        "smallint maps to INT16" {
            val (t, _) = MssqlArrowTypeMapper.mapType("smallint", JDBCType.SMALLINT, 0, 0)
            t shouldBe Types.MinorType.SMALLINT.type
        }
        "int maps to INT32" {
            val (t, _) = MssqlArrowTypeMapper.mapType("int", JDBCType.INTEGER, 0, 0)
            t shouldBe Types.MinorType.INT.type
        }
        "bigint maps to INT64" {
            val (t, _) = MssqlArrowTypeMapper.mapType("bigint", JDBCType.BIGINT, 0, 0)
            t shouldBe Types.MinorType.BIGINT.type
        }
        "bit maps to BOOL" {
            val (t, _) = MssqlArrowTypeMapper.mapType("bit", JDBCType.BIT, 0, 0)
            t shouldBe Types.MinorType.BIT.type
        }
        "decimal preserves precision and scale" {
            val (t, _) = MssqlArrowTypeMapper.mapType("decimal", JDBCType.DECIMAL, 19, 5)
            val d = t.shouldBeInstanceOf<ArrowType.Decimal>()
            d.precision shouldBe 19
            d.scale shouldBe 5
            d.bitWidth shouldBe 128
        }
        "money maps to Decimal128(19,4)" {
            val (t, _) = MssqlArrowTypeMapper.mapType("money", JDBCType.DECIMAL, 19, 4)
            val d = t.shouldBeInstanceOf<ArrowType.Decimal>()
            d.precision shouldBe 19
            d.scale shouldBe 4
        }
        "smallmoney maps to Decimal128(10,4)" {
            val (t, _) = MssqlArrowTypeMapper.mapType("smallmoney", JDBCType.DECIMAL, 10, 4)
            val d = t.shouldBeInstanceOf<ArrowType.Decimal>()
            d.precision shouldBe 10
            d.scale shouldBe 4
        }
        "float maps to FLOAT64" {
            val (t, _) = MssqlArrowTypeMapper.mapType("float", JDBCType.DOUBLE, 0, 0)
            val fp = t.shouldBeInstanceOf<ArrowType.FloatingPoint>()
            fp.precision shouldBe FloatingPointPrecision.DOUBLE
        }
        "real maps to FLOAT32" {
            val (t, _) = MssqlArrowTypeMapper.mapType("real", JDBCType.REAL, 0, 0)
            val fp = t.shouldBeInstanceOf<ArrowType.FloatingPoint>()
            fp.precision shouldBe FloatingPointPrecision.SINGLE
        }
        "varchar/nvarchar/text/ntext map to VARCHAR (UTF8)" {
            listOf("varchar", "nvarchar", "char", "nchar", "text", "ntext").forEach { name ->
                val (t, _) = MssqlArrowTypeMapper.mapType(name, JDBCType.VARCHAR, 50, 0)
                t shouldBe Types.MinorType.VARCHAR.type
            }
        }
        "date maps to DATE32(DAY)" {
            val (t, _) = MssqlArrowTypeMapper.mapType("date", JDBCType.DATE, 0, 0)
            val d = t.shouldBeInstanceOf<ArrowType.Date>()
            d.unit shouldBe DateUnit.DAY
        }
        "datetime maps to TIMESTAMP(MILLI, none)" {
            val (t, _) = MssqlArrowTypeMapper.mapType("datetime", JDBCType.TIMESTAMP, 0, 0)
            val ts = t.shouldBeInstanceOf<ArrowType.Timestamp>()
            ts.unit shouldBe TimeUnit.MILLISECOND
            ts.timezone shouldBe null
        }
        "datetime2 maps to TIMESTAMP(NANO, none)" {
            val (t, _) = MssqlArrowTypeMapper.mapType("datetime2", JDBCType.TIMESTAMP, 0, 0)
            val ts = t.shouldBeInstanceOf<ArrowType.Timestamp>()
            ts.unit shouldBe TimeUnit.NANOSECOND
            ts.timezone shouldBe null
        }
        "smalldatetime maps to TIMESTAMP(SECOND, none)" {
            val (t, _) = MssqlArrowTypeMapper.mapType("smalldatetime", JDBCType.TIMESTAMP, 0, 0)
            val ts = t.shouldBeInstanceOf<ArrowType.Timestamp>()
            ts.unit shouldBe TimeUnit.SECOND
        }
        "datetimeoffset maps to TIMESTAMP(NANO, UTC) with metadata" {
            val (t, m) = MssqlArrowTypeMapper.mapType("datetimeoffset", JDBCType.TIMESTAMP_WITH_TIMEZONE, 0, 0)
            val ts = t.shouldBeInstanceOf<ArrowType.Timestamp>()
            ts.unit shouldBe TimeUnit.NANOSECOND
            ts.timezone shouldBe "UTC"
            m?.get(MssqlArrowTypeMapper.originalTypeKey()) shouldBe "datetimeoffset"
        }
        "uniqueidentifier maps to VARCHAR with original_type metadata" {
            val (t, m) = MssqlArrowTypeMapper.mapType("uniqueidentifier", JDBCType.OTHER, 0, 0)
            t shouldBe Types.MinorType.VARCHAR.type
            m?.get(MssqlArrowTypeMapper.originalTypeKey()) shouldBe "uniqueidentifier"
        }
        "rowversion maps to VARBINARY with rowversion metadata, NOT a timestamp" {
            val (t, m) = MssqlArrowTypeMapper.mapType("rowversion", JDBCType.BINARY, 8, 0)
            t shouldBe Types.MinorType.VARBINARY.type
            m?.get(MssqlArrowTypeMapper.originalTypeKey()) shouldBe "rowversion"
        }
        "binary/varbinary/image map to VARBINARY" {
            listOf("binary", "varbinary", "image").forEach { name ->
                val (t, _) = MssqlArrowTypeMapper.mapType(name, JDBCType.BINARY, 0, 0)
                t shouldBe Types.MinorType.VARBINARY.type
            }
        }
        "xml maps to VARCHAR with metadata" {
            val (t, m) = MssqlArrowTypeMapper.mapType("xml", JDBCType.SQLXML, 0, 0)
            t shouldBe Types.MinorType.VARCHAR.type
            m?.get(MssqlArrowTypeMapper.originalTypeKey()) shouldBe "xml"
        }
        "geography/geometry/hierarchyid map to VARBINARY with metadata" {
            listOf("geography", "geometry", "hierarchyid").forEach { name ->
                val (t, m) = MssqlArrowTypeMapper.mapType(name, JDBCType.OTHER, 0, 0)
                t shouldBe Types.MinorType.VARBINARY.type
                m?.get(MssqlArrowTypeMapper.originalTypeKey()) shouldBe name
            }
        }
        "unknown native type falls back via JDBC code" {
            val (t, _) = MssqlArrowTypeMapper.mapType("not-a-real-type", JDBCType.INTEGER, 0, 0)
            t shouldBe Types.MinorType.INT.type
        }

        "DF-W03 — unsupportedBinaryFallbacks lists columns mapped to opaque VARBINARY" {
            val fields =
                listOf(
                    field("id", JDBCType.INTEGER, "int"),
                    field("loc", JDBCType.OTHER, "geography"),
                    field("path", JDBCType.OTHER, "hierarchyid"),
                    field("blob", JDBCType.VARBINARY, "varbinary"), // genuine binary, NOT a fallback
                    field("xml_doc", JDBCType.SQLXML, "xml"), // VARCHAR with metadata, NOT binary
                )
            val schema =
                org.apache.arrow.vector.types.pojo
                    .Schema(fields)
            val fallbacks = MssqlArrowTypeMapper.unsupportedBinaryFallbacks(schema)
            fallbacks.map { it.first }.toSet() shouldBe setOf("loc", "path")
            fallbacks.toMap()["loc"] shouldBe "geography"
            fallbacks.toMap()["path"] shouldBe "hierarchyid"
        }
    })

private fun field(
    name: String,
    jdbc: JDBCType,
    nativeType: String,
): org.apache.arrow.vector.types.pojo.Field {
    val (t, m) = MssqlArrowTypeMapper.mapType(nativeType, jdbc, 0, 0)
    val fieldType =
        org.apache.arrow.vector.types.pojo
            .FieldType(true, t, null, m)
    return org.apache.arrow.vector.types.pojo
        .Field(name, fieldType, emptyList())
}
