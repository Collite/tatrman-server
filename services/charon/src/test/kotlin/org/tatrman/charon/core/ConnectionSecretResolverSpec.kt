// SPDX-License-Identifier: Apache-2.0
package org.tatrman.charon.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * The open connection-secret resolution port (CH-D3). Charon resolves `${VAR}`
 * credential tokens through a [ConnectionSecretResolver] — env-var binding by
 * default ([EnvSecretResolver]), or a `ServiceLoader`-registered provider (the
 * platform's `cz.tatrman:secrets-spi` adapter at CH-P2). This spec pins the port
 * contract: default = env; an injected resolver overrides; an unresolved token
 * degrades the connection (never a blank credential). It replaces the platform's
 * SPI-coupled `ConnectionSecretsTest`/`TransferSecretsTest`, which move to the
 * CH-P2 adapter.
 */
class ConnectionSecretResolverSpec :
    StringSpec({

        val yaml =
            """
            connections:
              - id: pg
                kind: postgres
                jdbc_url: ${'$'}{TTR_CONN_PG_URL}
                username: ${'$'}{TTR_CONN_PG_USER}
                password: ${'$'}{TTR_CONN_PG_PASSWORD}
                allow: { read: true, write: false, schemas: ["public"] }
            """.trimIndent()

        "discover() with no registered provider falls back to the env-var default" {
            // No META-INF/services provider is on the test classpath, so discovery
            // returns the env default — open Charon's dependency-free `${ENV}` mode.
            ConnectionSecretResolver.discover() shouldBe EnvSecretResolver
        }

        "EnvSecretResolver reads the process environment; an absent name → null" {
            // PATH is present in every CI/dev shell; a random name is not.
            (EnvSecretResolver.resolve("PATH") != null) shouldBe true
            EnvSecretResolver.resolve("CHARON_DEFINITELY_ABSENT_VAR_XYZ") shouldBe null
        }

        "an injected resolver drives `${'$'}{VAR}` substitution (overrides env)" {
            val resolver =
                ConnectionSecretResolver { name ->
                    when (name) {
                        "TTR_CONN_PG_URL" -> "jdbc:postgresql://pg/analytics"
                        "TTR_CONN_PG_USER" -> "rw"
                        "TTR_CONN_PG_PASSWORD" -> "resolved-secret"
                        else -> null
                    }
                }
            val reg = ConnectionRegistry.fromYaml(yaml, resolver)
            reg.ids() shouldBe setOf("pg")
            val pg = (reg.resolve("pg") as Either.Right).value
            pg.jdbcUrl shouldBe "jdbc:postgresql://pg/analytics"
            pg.password shouldBe "resolved-secret"
        }

        "an unresolved token → the connection is skipped (degraded), never loaded blank" {
            // The resolver returns null for the password token → the connection is
            // omitted from the live set (plan §4 Stage 2.3), never a pod-killer.
            val resolver =
                ConnectionSecretResolver { name ->
                    when (name) {
                        "TTR_CONN_PG_URL" -> "jdbc:postgresql://pg/analytics"
                        "TTR_CONN_PG_USER" -> "rw"
                        else -> null // password unresolved
                    }
                }
            val reg = ConnectionRegistry.fromYaml(yaml, resolver)
            reg.ids() shouldBe emptySet()
            (reg.resolve("pg") is Either.Left) shouldBe true
        }
    })
