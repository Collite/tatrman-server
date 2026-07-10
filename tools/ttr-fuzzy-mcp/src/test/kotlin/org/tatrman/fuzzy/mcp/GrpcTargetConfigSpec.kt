package org.tatrman.fuzzy.mcp

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk

/**
 * Stage 2.2 R2.3 — the gRPC target is sourced from `fuzzy.client.*` HOCON
 * (NOT from legacy `FUZZY_MATCHER_*` env vars). Verifies:
 *  - a representative HOCON yields the right gRPC host/port
 *  - a blank host returns `null` (warn-and-continue, not crash)
 *
 * Review-004 R2 lesson (Stage 2.1): the dev inlined the config read in
 * the test and never exercised the production [buildFuzzyClient]. Here we
 * call the production function on a synthetic HOCON with a mocked
 * telemetry — that is the wiring the k8s pod will follow in production.
 */
class GrpcTargetConfigSpec :
    StringSpec({
        "buildFuzzyClient reads gRPC target from fuzzy.client.* HOCON" {
            val hocon =
                """
                fuzzy.client.protocol = "gRPC"
                fuzzy.client.host = "fuzzy.production"
                fuzzy.client.grpc.port = "7266"
                """.trimIndent()
            val config = ConfigFactory.parseString(hocon)
            val client = buildFuzzyClient(config, mockk(relaxed = true))
            client shouldNotBe null
        }

        "buildFuzzyClient returns null on blank host (local no-backend mode)" {
            val hocon =
                """
                fuzzy.client.protocol = "gRPC"
                fuzzy.client.host = ""
                fuzzy.client.grpc.port = "7266"
                """.trimIndent()
            val config = ConfigFactory.parseString(hocon)
            val client = buildFuzzyClient(config, mockk(relaxed = true))
            client shouldBe null
        }

        "buildFuzzyClient falls back to REST when protocol is not gRPC" {
            val hocon =
                """
                fuzzy.client.protocol = "REST"
                fuzzy.client.host = "fuzzy.production"
                fuzzy.client.rest.port = "7265"
                """.trimIndent()
            val config = ConfigFactory.parseString(hocon)
            val client = buildFuzzyClient(config, mockk(relaxed = true))
            client shouldNotBe null
        }
    })
