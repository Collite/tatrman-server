// SPDX-License-Identifier: Apache-2.0
package org.tatrman.nlp.mcp

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * `buildNlpClient` reads `nlp.{host,port,timeout}` from HOCON and returns `null`
 * when the host is blank (local no-backend mode), a live gRPC client otherwise
 * (the channel connects lazily). Mirrors fuzzy-mcp's GrpcTargetConfigSpec.
 */
class GrpcTargetConfigSpec :
    StringSpec({

        "blank nlp.host yields a null client (not wired)" {
            val config =
                ConfigFactory.parseString(
                    """
                    nlp { host = "", port = "7271", timeout = 30000 }
                    """.trimIndent(),
                )
            buildNlpClient(config) shouldBe null
        }

        "a non-blank nlp.host yields a live gRPC client" {
            val config =
                ConfigFactory.parseString(
                    """
                    nlp { host = "nlp", port = "7271", timeout = 30000 }
                    """.trimIndent(),
                )
            val client = buildNlpClient(config)
            client.shouldNotBeNull()
            client.close()
        }

        "the default application.conf points at localhost:7271 (gRPC)" {
            val config = ConfigFactory.load()
            config.getString("nlp.host") shouldBe "localhost"
            config.getString("nlp.port") shouldBe "7271"
            config.getString("server.port") shouldBe "7272"
        }
    })
