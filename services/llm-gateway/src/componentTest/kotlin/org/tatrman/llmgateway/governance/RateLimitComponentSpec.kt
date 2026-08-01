// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.governance

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.tatrman.llmgateway.store.RedisConn
import org.testcontainers.containers.GenericContainer

/**
 * LG-P4·S2·T1 — per-key rate limiting over a real Redis (Testcontainers). Proves the rolling two-bucket
 * window blocks the N+1th request, min-wins caps below the team limit, two limiter instances (replicas)
 * share one counter, and INCR+EXPIRE are applied atomically (the bucket carries a TTL). Time is injected so
 * the window is deterministic without real sleeps.
 */
class RateLimitComponentSpec :
    StringSpec({

        val redisC = GenericContainer("redis:7-alpine").apply { withExposedPorts(6379) }
        lateinit var redis: RedisConn

        beforeSpec {
            redisC.start()
            redis =
                RedisConn.fromConfig(
                    ConfigFactory.parseString(
                        """redis { host = "${redisC.host}", port = ${redisC.getMappedPort(6379)} }""",
                    ),
                )
        }
        afterSpec {
            redis.close()
            redisC.stop()
        }

        val clock = 1_700_000_000_000L // fixed ms → a fixed minute bucket

        "the N+1th request in the window is blocked with a positive Retry-After" {
            val rl = RateLimiter(redis, nowMs = { clock })
            val key = "block"
            repeat(3) { rl.check(key, teamRpm = 3, keyRpmOverride = null).allowed shouldBe true }
            val d = rl.check(key, teamRpm = 3, keyRpmOverride = null)
            d.allowed shouldBe false
            d.retryAfterSeconds shouldBeGreaterThan 0L
        }

        "min-wins: a lower per-key override caps below the team limit" {
            val rl = RateLimiter(redis, nowMs = { clock })
            val key = "minwins"
            rl.check(key, teamRpm = 5, keyRpmOverride = 2).allowed shouldBe true
            rl.check(key, teamRpm = 5, keyRpmOverride = 2).allowed shouldBe true
            rl.check(key, teamRpm = 5, keyRpmOverride = 2).allowed shouldBe false // capped at 2, not 5
        }

        "two limiter instances (replicas) share one Redis counter" {
            val a = RateLimiter(redis, nowMs = { clock })
            val b = RateLimiter(redis, nowMs = { clock })
            val key = "shared"
            a.check(key, teamRpm = 2, keyRpmOverride = null).allowed shouldBe true
            b.check(key, teamRpm = 2, keyRpmOverride = null).allowed shouldBe true
            a.check(key, teamRpm = 2, keyRpmOverride = null).allowed shouldBe false // 3rd across both replicas
        }

        "INCR and EXPIRE run together — the bucket carries a TTL (atomic script)" {
            val rl = RateLimiter(redis, nowMs = { clock })
            val key = "ttl"
            rl.check(key, teamRpm = 10, keyRpmOverride = null)
            val bucket = "llm-gateway:rl:$key:${clock / 60_000}"
            redis.sync().ttl(bucket) shouldBeGreaterThan 0L
        }

        "no limit configured (both null) ⇒ always allowed" {
            val rl = RateLimiter(redis, nowMs = { clock })
            repeat(100) { rl.check("unlimited", teamRpm = null, keyRpmOverride = null).allowed shouldBe true }
        }
    })
