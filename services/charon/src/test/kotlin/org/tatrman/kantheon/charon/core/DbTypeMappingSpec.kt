package org.tatrman.kantheon.charon.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import java.sql.ResultSetMetaData
import java.sql.Types
import org.apache.arrow.vector.types.DateUnit
import org.apache.arrow.vector.types.FloatingPointPrecision
import org.apache.arrow.vector.types.TimeUnit
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType

/**
 * The contracts §5 DB ↔ Arrow type matrix, both directions, both dialects
 * (charon/plan.md §4 Stage 2.1 T3/T4). [DbTypeMapping] is the source of truth;
 * this spec walks the matrix so any drift fails the build. No driver — the
 * extract-direction test mocks `ResultSetMetaData`.
 */
class DbTypeMappingSpec :
    StringSpec({

        fun field(
            name: String,
            type: ArrowType,
            nullable: Boolean = true,
            children: List<Field>? = null,
        ): Field = Field(name, FieldType(nullable, type, null), children)

        // --- Arrow → DDL (ingest), per contracts §5 ---

        val matrix =
            listOf(
                Triple(ArrowType.Int(8, true), "SMALLINT", "SMALLINT"),
                Triple(ArrowType.Int(16, true), "SMALLINT", "SMALLINT"),
                Triple(ArrowType.Int(32, true), "INTEGER", "INT"),
                Triple(ArrowType.Int(64, true), "BIGINT", "BIGINT"),
                Triple(ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE), "REAL", "REAL"),
                Triple(ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE), "DOUBLE PRECISION", "FLOAT"),
                Triple(ArrowType.Decimal(18, 2, 128), "NUMERIC(18,2)", "DECIMAL(18,2)"),
                Triple(ArrowType.Utf8(), "TEXT", "NVARCHAR(MAX)"),
                Triple(ArrowType.LargeUtf8(), "TEXT", "NVARCHAR(MAX)"),
                Triple(ArrowType.Bool(), "BOOLEAN", "BIT"),
                Triple(ArrowType.Date(DateUnit.DAY), "DATE", "DATE"),
                Triple(ArrowType.Timestamp(TimeUnit.MICROSECOND, null), "TIMESTAMP", "DATETIME2"),
                Triple(ArrowType.Timestamp(TimeUnit.MICROSECOND, "UTC"), "TIMESTAMPTZ", "DATETIMEOFFSET"),
                Triple(ArrowType.Binary(), "BYTEA", "VARBINARY(MAX)"),
            )

        matrix.forEach { (type, pg, mssql) ->
            "arrowToDdl maps $type to PG=$pg / MSSQL=$mssql" {
                DbTypeMapping.arrowToDdl(field("c", type), DbDialect.POSTGRES) shouldBe pg
                DbTypeMapping.arrowToDdl(field("c", type), DbDialect.MSSQL) shouldBe mssql
            }
        }

        "List / Struct / Map columns are unmappable → UnmappableTypeException naming the column" {
            val nested =
                listOf(
                    field("tags", ArrowType.List(), children = listOf(field("item", ArrowType.Int(64, true)))),
                    field(
                        "meta",
                        ArrowType.Struct(),
                        children = listOf(field("k", ArrowType.Utf8())),
                    ),
                    field(
                        "attrs",
                        ArrowType.Map(false),
                        children =
                            listOf(
                                field("entries", ArrowType.Struct(), children = listOf(field("key", ArrowType.Utf8()))),
                            ),
                    ),
                )
            nested.forEach { f ->
                val ex = shouldThrow<UnmappableTypeException> { DbTypeMapping.arrowToDdl(f, DbDialect.POSTGRES) }
                ex.column shouldBe f.name
            }
        }

        "createTableDdl quotes identifiers + emits NOT NULL for non-nullable columns" {
            val schema =
                org.apache.arrow.vector.types.pojo
                    .Schema(
                        listOf(
                            field("id", ArrowType.Int(64, true), nullable = false),
                            field("name", ArrowType.Utf8(), nullable = true),
                        ),
                    )
            DbTypeMapping.createTableDdl(schema, DbDialect.POSTGRES, "\"s\".\"t\"") shouldBe
                "CREATE TABLE \"s\".\"t\" (\"id\" BIGINT NOT NULL, \"name\" TEXT)"
            DbTypeMapping.createTableDdl(schema, DbDialect.MSSQL, "[s].[t]") shouldBe
                "CREATE TABLE [s].[t] ([id] BIGINT NOT NULL, [name] NVARCHAR(MAX))"
        }

        "qualify quotes per dialect" {
            DbTypeMapping.qualify("dbo", "orders", DbDialect.POSTGRES) shouldBe "\"dbo\".\"orders\""
            DbTypeMapping.qualify("dbo", "orders", DbDialect.MSSQL) shouldBe "[dbo].[orders]"
        }

        // --- JDBC → Arrow (extract), mocked ResultSetMetaData ---

        "schemaFromMetadata maps JDBC types inversely to Arrow" {
            val md = mockk<ResultSetMetaData>()
            every { md.columnCount } returns 5
            // col 1: INTEGER, not null
            stubColumn(md, 1, "id", Types.INTEGER, nullable = false)
            stubColumn(md, 2, "name", Types.VARCHAR)
            stubColumn(md, 3, "amount", Types.NUMERIC, precision = 18, scale = 2)
            stubColumn(md, 4, "active", Types.BOOLEAN)
            stubColumn(md, 5, "ts", Types.TIMESTAMP)

            val schema = DbTypeMapping.schemaFromMetadata(md)
            schema.fields.map { it.name } shouldBe listOf("id", "name", "amount", "active", "ts")
            schema.fields[0].type shouldBe ArrowType.Int(32, true)
            schema.fields[0].isNullable shouldBe false
            schema.fields[1].type shouldBe ArrowType.Utf8()
            schema.fields[2].type shouldBe ArrowType.Decimal(18, 2, 128)
            schema.fields[3].type shouldBe ArrowType.Bool()
            schema.fields[4].type shouldBe ArrowType.Timestamp(TimeUnit.MICROSECOND, null)
        }

        "an unmapped JDBC type → UnmappableTypeException naming the column" {
            val md = mockk<ResultSetMetaData>()
            every { md.columnCount } returns 1
            stubColumn(md, 1, "weird", Types.ARRAY)
            val ex = shouldThrow<UnmappableTypeException> { DbTypeMapping.schemaFromMetadata(md) }
            ex.column shouldBe "weird"
            ex.message!! shouldContain "weird"
        }
    })

private fun stubColumn(
    md: ResultSetMetaData,
    idx: Int,
    name: String,
    type: Int,
    nullable: Boolean = true,
    precision: Int = 0,
    scale: Int = 0,
) {
    every { md.getColumnLabel(idx) } returns name
    every { md.getColumnName(idx) } returns name
    every { md.getColumnType(idx) } returns type
    every { md.getPrecision(idx) } returns precision
    every { md.getScale(idx) } returns scale
    every { md.isNullable(idx) } returns
        if (nullable) ResultSetMetaData.columnNullable else ResultSetMetaData.columnNoNulls
}
