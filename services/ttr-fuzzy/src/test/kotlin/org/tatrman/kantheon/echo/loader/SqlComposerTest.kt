package org.tatrman.kantheon.echo.loader

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.echo.config.MssqlConfig
import org.tatrman.kantheon.echo.config.PostgresConfig
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode

class SqlComposerTest :
    StringSpec({
        fun table(): QualifiedName =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.DB)
                .setNamespace("dbo")
                .setName("customers")
                .build()

        "buildSelect — Postgres dialect" {
            val pg = PostgresConfig("localhost", 5432, "test", "user", "pass")
            buildSelect(table(), "id", "full_name", pg) shouldBe
                "SELECT \"id\", \"full_name\" FROM \"dbo\".\"customers\""
        }

        "buildSelect — MSSQL dialect" {
            val mssql = MssqlConfig("localhost", 1433, "test", "user", "pass")
            buildSelect(table(), "id", "full_name", mssql) shouldBe
                "SELECT [id], [full_name] FROM [dbo].[customers]"
        }

        "buildSelect — rejects PK with injection attempt" {
            val pg = PostgresConfig("localhost", 5432, "test", "user", "pass")
            shouldThrow<SqlComposerException> {
                buildSelect(table(), "id; DROP TABLE users;--", "full_name", pg)
            }
        }

        "buildSelect — rejects value with injection attempt" {
            val pg = PostgresConfig("localhost", 5432, "test", "user", "pass")
            shouldThrow<SqlComposerException> {
                buildSelect(table(), "id", "full_name\"\nFROM users;--", pg)
            }
        }
    })
