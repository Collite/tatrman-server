package org.tatrman.dispatch.sticky

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant

class StickyRegistrySpec :
    StringSpec({
        "record + find round-trips" {
            val s = StickyRegistry()
            s.recordSticky("sess-1", "worker-a:9000")
            s.findSticky("sess-1") shouldBe "worker-a:9000"
        }

        "empty session_id never records" {
            val s = StickyRegistry()
            s.recordSticky("", "worker-a:9000")
            s.size() shouldBe 0
        }

        "evictByEndpoint removes pinned sessions" {
            val s = StickyRegistry()
            s.recordSticky("sess-1", "worker-a:9000")
            s.recordSticky("sess-2", "worker-a:9000")
            s.recordSticky("sess-3", "worker-b:9000")
            s.evictByEndpoint("worker-a:9000") shouldBe 2
            s.findSticky("sess-1") shouldBe null
            s.findSticky("sess-3") shouldBe "worker-b:9000"
        }

        "TTL sweep evicts stale sessions" {
            var now = Instant.parse("2026-01-01T00:00:00Z")
            val s = StickyRegistry(idleTimeout = Duration.ofMinutes(60), clock = { now })
            s.recordSticky("sess-1", "worker-a")
            now = now.plus(Duration.ofMinutes(70))
            s.sweepIdle() shouldBe 1
            s.findSticky("sess-1") shouldBe null
        }

        "findSticky bumps lastSeen so the entry survives the next sweep" {
            var now = Instant.parse("2026-01-01T00:00:00Z")
            val s = StickyRegistry(idleTimeout = Duration.ofMinutes(60), clock = { now })
            s.recordSticky("sess-1", "worker-a")
            now = now.plus(Duration.ofMinutes(50))
            s.findSticky("sess-1") shouldBe "worker-a"
            now = now.plus(Duration.ofMinutes(50))
            s.sweepIdle() shouldBe 0 // bumped lastSeen kept it alive
        }

        "max-entries cap evicts least-recent on overflow" {
            var now = Instant.parse("2026-01-01T00:00:00Z")
            val s = StickyRegistry(maxEntries = 2, clock = { now })
            s.recordSticky("a", "w1")
            now = now.plusSeconds(1)
            s.recordSticky("b", "w1")
            now = now.plusSeconds(1)
            s.recordSticky("c", "w1")
            s.size() shouldBe 2
            s.findSticky("a") shouldBe null // oldest evicted
        }

        "activeSessionsForEndpoint counts correctly" {
            val s = StickyRegistry()
            s.recordSticky("a", "w1")
            s.recordSticky("b", "w1")
            s.recordSticky("c", "w2")
            s.activeSessionsForEndpoint("w1") shouldBe 2
            s.activeSessionsForEndpoint("w2") shouldBe 1
        }
    })
