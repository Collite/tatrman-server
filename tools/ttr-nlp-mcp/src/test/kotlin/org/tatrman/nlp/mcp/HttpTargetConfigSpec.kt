package org.tatrman.nlp.mcp

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * T4 — `buildNlpClient` reads `nlp.{host,port,timeout}` from HOCON and
 * returns `null` when the host is blank (local no-backend mode), a live client
 * otherwise. Mirrors fuzzy-mcp's GrpcTargetConfigSpec.
 */
class HttpTargetConfigSpec :
    StringSpec({

        "blank nlp.host yields a null client (not wired)" {
            val config =
                ConfigFactory.parseString(
                    """
                    nlp { host = "", port = "7270", timeout = 30000 }
                    """.trimIndent(),
                )
            buildNlpClient(config) shouldBe null
        }

        "a non-blank nlp.host yields a live client" {
            val config =
                ConfigFactory.parseString(
                    """
                    nlp { host = "nlp", port = "7270", timeout = 30000 }
                    """.trimIndent(),
                )
            buildNlpClient(config).shouldNotBeNull()
        }

        "the default application.conf points at localhost:7270" {
            val config = ConfigFactory.load()
            config.getString("nlp.host") shouldBe "localhost"
            config.getString("nlp.port") shouldBe "7270"
            config.getString("server.port") shouldBe "7272"
        }
    })
