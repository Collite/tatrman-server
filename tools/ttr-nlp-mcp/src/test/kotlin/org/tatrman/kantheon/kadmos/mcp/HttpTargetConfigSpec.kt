package org.tatrman.kantheon.kadmos.mcp

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * T4 — `buildKadmosClient` reads `kadmos.{host,port,timeout}` from HOCON and
 * returns `null` when the host is blank (local no-backend mode), a live client
 * otherwise. Mirrors echo-mcp's GrpcTargetConfigSpec.
 */
class HttpTargetConfigSpec :
    StringSpec({

        "blank kadmos.host yields a null client (not wired)" {
            val config =
                ConfigFactory.parseString(
                    """
                    kadmos { host = "", port = "7270", timeout = 30000 }
                    """.trimIndent(),
                )
            buildKadmosClient(config) shouldBe null
        }

        "a non-blank kadmos.host yields a live client" {
            val config =
                ConfigFactory.parseString(
                    """
                    kadmos { host = "kadmos", port = "7270", timeout = 30000 }
                    """.trimIndent(),
                )
            buildKadmosClient(config).shouldNotBeNull()
        }

        "the default application.conf points at localhost:7270" {
            val config = ConfigFactory.load()
            config.getString("kadmos.host") shouldBe "localhost"
            config.getString("kadmos.port") shouldBe "7270"
            config.getString("server.port") shouldBe "7272"
        }
    })
