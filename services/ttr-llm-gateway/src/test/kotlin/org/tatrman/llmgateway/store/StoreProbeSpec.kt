// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.store

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * LG-P1·S1·T4 — the truthful-DOWN store-probe path, provable without Docker: an unreachable Redis must
 * make `probe()` return false quickly (bounded by the 2 s connect timeout — no hang), never throw and
 * never fake-green. The reachable-UP path is proven by the Testcontainers BootComponentSpec (T6).
 */
class StoreProbeSpec :
    StringSpec({

        "RedisConn.probe() returns false (no hang) when Redis is unreachable" {
            val redis =
                RedisConn.fromConfig(
                    // a closed loopback port — connection refused, well under the 2 s timeout
                    ConfigFactory.parseString("""redis { host = "127.0.0.1", port = 6390 }"""),
                )
            try {
                redis.probe() shouldBe false
            } finally {
                redis.close()
            }
        }
    })
