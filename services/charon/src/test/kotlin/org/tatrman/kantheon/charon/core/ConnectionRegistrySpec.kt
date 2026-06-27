package org.tatrman.kantheon.charon.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The connection registry (charon/contracts.md §4 / plan §4 Stage 2.1 T1/T2):
 * YAML parse + `${ENV}` substitution; unknown id; allow-list enforcement
 * (read / write / schemas); pool config; `/refresh` reload. **Pythia's internal
 * PG is never listed** — a request for an unlisted id simply doesn't resolve.
 */
class ConnectionRegistrySpec :
    StringSpec({

        val yaml =
            """
            connections:
              - id: erp-replica
                kind: mssql
                jdbc_url: ${'$'}{ERP_URL}
                username: ${'$'}{ERP_USER}
                password: ${'$'}{ERP_PASSWORD}
                allow:
                  read: true
                  write: false
                  schemas: ["dbo"]
                pool: { max: 4 }
              - id: analytics-staging
                kind: postgres
                jdbc_url: ${'$'}{PG_URL}
                username: ${'$'}{PG_USER}
                password: ${'$'}{PG_PASSWORD}
                allow: { read: true, write: true, schemas: ["staging"] }
            """.trimIndent()

        val env =
            mapOf(
                "ERP_URL" to "jdbc:sqlserver://erp;databaseName=erp",
                "ERP_USER" to "ro",
                "ERP_PASSWORD" to "secret-erp",
                "PG_URL" to "jdbc:postgresql://pg/analytics",
                "PG_USER" to "rw",
                "PG_PASSWORD" to "secret-pg",
            )

        "parses YAML, substitutes env, resolves dialects + pool" {
            val reg = ConnectionRegistry.fromYaml(yaml, env)
            reg.ids() shouldBe setOf("erp-replica", "analytics-staging")
            val erp = (reg.resolve("erp-replica") as Either.Right).value
            erp.dialect shouldBe DbDialect.MSSQL
            erp.jdbcUrl shouldBe "jdbc:sqlserver://erp;databaseName=erp"
            erp.username shouldBe "ro"
            erp.password shouldBe "secret-erp"
            erp.poolMax shouldBe 4
            erp.allow.schemas shouldBe setOf("dbo")
            val pg = (reg.resolve("analytics-staging") as Either.Right).value
            pg.dialect shouldBe DbDialect.POSTGRES
            pg.allow.write shouldBe true
        }

        "credentials never appear in toString (redacted)" {
            val reg = ConnectionRegistry.fromYaml(yaml, env)
            val erp = (reg.resolve("erp-replica") as Either.Right).value
            erp.toString() shouldContain "erp-replica"
            (erp.toString().contains("secret-erp")) shouldBe false
        }

        "unknown connection_id → UnknownConnectionId (INVALID_ARGUMENT)" {
            val reg = ConnectionRegistry.fromYaml(yaml, env)
            val r = reg.resolve("pythia-internal-pg")
            (r as Either.Left).value.shouldBeUnknownConnection("pythia-internal-pg")
        }

        "authorize: read on a read:false connection → AllowListViolation" {
            val reg = ConnectionRegistry.fromYaml(yaml, env)
            // erp-replica is read:true so READ ok; WRITE forbidden.
            (reg.authorize("erp-replica", DbOp.READ, "dbo") is Either.Right) shouldBe true
            val w = reg.authorize("erp-replica", DbOp.WRITE, "dbo")
            ((w as Either.Left).value is CharonError.AllowListViolation) shouldBe true
        }

        "authorize: a schema outside the allow-list → AllowListViolation" {
            val reg = ConnectionRegistry.fromYaml(yaml, env)
            val r = reg.authorize("erp-replica", DbOp.READ, "secret_schema")
            val v = (r as Either.Left).value
            (v is CharonError.AllowListViolation) shouldBe true
            v.humanMessage shouldContain "secret_schema"
        }

        "authorize: write on a write:true connection + allowed schema → ok" {
            val reg = ConnectionRegistry.fromYaml(yaml, env)
            (reg.authorize("analytics-staging", DbOp.WRITE, "staging") is Either.Right) shouldBe true
        }

        "a connection with an unresolved env var is skipped (degraded), not loaded blank" {
            // Lazily-validated, per-connection (plan §4 Stage 2.3): a connection
            // whose ${'$'}{ENV} credential is unset is omitted from the live set —
            // never loaded with a blank credential, and never a pod-killer.
            val partialEnv = env.filterKeys { it.startsWith("PG_") } // only the PG connection resolves
            val reg = ConnectionRegistry.fromYaml(yaml, partialEnv)
            reg.ids() shouldBe setOf("analytics-staging")
            (reg.resolve("erp-replica") is Either.Left) shouldBe true
        }

        "refresh atomically swaps the live set" {
            val reg = ConnectionRegistry.fromYaml(yaml, env)
            reg.ids() shouldBe setOf("erp-replica", "analytics-staging")
            val newYaml =
                """
                connections:
                  - id: only-one
                    kind: postgres
                    jdbc_url: jdbc:postgresql://x/y
                    username: u
                    password: p
                    allow: { read: true, write: false, schemas: ["public"] }
                """.trimIndent()
            reg.refresh(newYaml)
            reg.ids() shouldBe setOf("only-one")
            (reg.resolve("erp-replica") is Either.Left) shouldBe true
        }

        "an empty / no-connections registry is valid (blob-only pod)" {
            val reg = ConnectionRegistry.fromYaml("connections: []")
            reg.ids() shouldBe emptySet()
        }
    })

private fun CharonError.shouldBeUnknownConnection(id: String) {
    (this is CharonError.UnknownConnectionId) shouldBe true
    (this as CharonError.UnknownConnectionId).connectionId shouldBe id
}
