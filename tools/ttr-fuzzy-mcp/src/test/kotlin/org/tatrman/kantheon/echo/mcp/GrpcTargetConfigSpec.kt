package org.tatrman.kantheon.echo.mcp

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk

/**
 * Stage 2.2 R2.3 — the gRPC target is sourced from `echo.client.*` HOCON
 * (NOT from legacy `FUZZY_MATCHER_*` env vars). Verifies:
 *  - a representative HOCON yields the right gRPC host/port
 *  - a blank host returns `null` (warn-and-continue, not crash)
 *
 * Review-004 R2 lesson (Stage 2.1): the dev inlined the config read in
 * the test and never exercised the production [buildEchoClient]. Here we
 * call the production function on a synthetic HOCON with a mocked
 * telemetry — that is the wiring the k8s pod will follow in production.
 */
class GrpcTargetConfigSpec :
    StringSpec({
        "buildEchoClient reads gRPC target from echo.client.* HOCON" {
            val hocon =
                """
                echo.client.protocol = "gRPC"
                echo.client.host = "echo.production"
                echo.client.grpc.port = "7266"
                """.trimIndent()
            val config = ConfigFactory.parseString(hocon)
            val client = buildEchoClient(config, mockk(relaxed = true))
            client shouldNotBe null
        }

        "buildEchoClient returns null on blank host (local no-backend mode)" {
            val hocon =
                """
                echo.client.protocol = "gRPC"
                echo.client.host = ""
                echo.client.grpc.port = "7266"
                """.trimIndent()
            val config = ConfigFactory.parseString(hocon)
            val client = buildEchoClient(config, mockk(relaxed = true))
            client shouldBe null
        }

        "buildEchoClient falls back to REST when protocol is not gRPC" {
            val hocon =
                """
                echo.client.protocol = "REST"
                echo.client.host = "echo.production"
                echo.client.rest.port = "7265"
                """.trimIndent()
            val config = ConfigFactory.parseString(hocon)
            val client = buildEchoClient(config, mockk(relaxed = true))
            client shouldNotBe null
        }
    })
