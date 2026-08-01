// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.mcp.upstream

import org.tatrman.meta.v1.AttributeDetail
import org.tatrman.meta.v1.GetSnapshotResponse
import org.tatrman.meta.v1.LocalizedString as PbLocalizedString
import org.tatrman.meta.v1.ModelSnapshot
import org.tatrman.meta.v1.ObjectDescriptor
import org.tatrman.meta.v1.ObjectEntry
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * DF-ME02-CACHE — TTL-fast-path + etag conditional GET cover the decoration fetch.
 */
class MetadataDecorationCacheSpec :
    StringSpec({

        fun attrEntry(
            qname: String,
            displayLabel: Map<String, String> = emptyMap(),
        ): ObjectEntry =
            ObjectEntry
                .newBuilder()
                .setObjectDescriptor(ObjectDescriptor.newBuilder().setLocalName(qname))
                .setAttribute(
                    AttributeDetail
                        .newBuilder()
                        .setDisplayLabel(PbLocalizedString.newBuilder().putAllByLanguage(displayLabel)),
                ).build()

        fun snapshot(
            entries: List<ObjectEntry> = emptyList(),
            etag: String = "etag-1",
        ): GetSnapshotResponse =
            GetSnapshotResponse
                .newBuilder()
                .setEtag(etag)
                .setSnapshot(ModelSnapshot.newBuilder().addAllObjects(entries))
                .build()

        fun notModified(): GetSnapshotResponse = GetSnapshotResponse.newBuilder().setNotModified(true).build()

        "TTL fast-path: within TTL the second call does NOT hit the fetcher" {
            var now = Instant.parse("2026-05-13T10:00:00Z")
            val calls = AtomicInteger(0)
            val cache =
                MetadataDecorationCache(
                    ttl = Duration.ofSeconds(30),
                    clock = { now },
                    fetchSnapshot = { _ ->
                        calls.incrementAndGet()
                        snapshot(
                            entries = listOf(attrEntry("er.entity.status", mapOf("cs" to "Stav"))),
                        )
                    },
                )

            runBlocking { cache.decorations() } shouldContainKey "status"
            calls.get() shouldBe 1

            now = now.plusSeconds(29)
            runBlocking { cache.decorations() } shouldContainKey "status"
            calls.get() shouldBe 1
        }

        "after TTL elapses, fetcher is called again with the cached etag" {
            var now = Instant.parse("2026-05-13T10:00:00Z")
            val seenEtags = mutableListOf<String>()
            val cache =
                MetadataDecorationCache(
                    ttl = Duration.ofSeconds(10),
                    clock = { now },
                    fetchSnapshot = { etag ->
                        seenEtags.add(etag)
                        snapshot(
                            entries = listOf(attrEntry("er.entity.status", mapOf("cs" to "Stav"))),
                            etag = "etag-v1",
                        )
                    },
                )

            runBlocking { cache.decorations() }
            now = now.plusSeconds(11)
            runBlocking { cache.decorations() }

            seenEtags shouldBe listOf("", "etag-v1")
        }

        "not_modified response: TTL window restarts but decorations unchanged" {
            var now = Instant.parse("2026-05-13T10:00:00Z")
            val calls = AtomicInteger(0)
            val cache =
                MetadataDecorationCache(
                    ttl = Duration.ofSeconds(10),
                    clock = { now },
                    fetchSnapshot = { _ ->
                        val n = calls.incrementAndGet()
                        if (n == 1) {
                            snapshot(
                                entries = listOf(attrEntry("er.entity.status", mapOf("cs" to "Stav"))),
                                etag = "etag-v1",
                            )
                        } else {
                            notModified()
                        }
                    },
                )

            val first = runBlocking { cache.decorations() }
            first shouldContainKey "status"

            now = now.plusSeconds(11)
            val second = runBlocking { cache.decorations() }
            second shouldContainKey "status"
            calls.get() shouldBe 2

            // After not_modified, TTL window restarts — next call within new TTL stays cached.
            now = now.plusSeconds(5)
            runBlocking { cache.decorations() }
            calls.get() shouldBe 2
        }

        "fetcher error preserves cache state so the next call retries instead of serving stale-empty" {
            var now = Instant.parse("2026-05-13T10:00:00Z")
            val calls = AtomicInteger(0)
            val cache =
                MetadataDecorationCache(
                    ttl = Duration.ofSeconds(10),
                    clock = { now },
                    fetchSnapshot = { _ ->
                        val n = calls.incrementAndGet()
                        when (n) {
                            1 ->
                                snapshot(
                                    entries = listOf(attrEntry("er.entity.status", mapOf("cs" to "Stav"))),
                                )
                            2 -> throw RuntimeException("metadata unreachable")
                            else -> snapshot(entries = listOf(attrEntry("er.entity.status", mapOf("cs" to "Stav"))))
                        }
                    },
                )

            runBlocking { cache.decorations() } shouldContainKey "status"

            now = now.plusSeconds(11)
            // Fetcher throws → returns last cached map (not empty), cachedAt NOT updated.
            val onFailure = runBlocking { cache.decorations() }
            onFailure shouldContainKey "status"

            // No artificial TTL gating after a failure — the next call retries.
            now = now.plusSeconds(1)
            runBlocking { cache.decorations() }
            calls.get() shouldBe 3
        }

        "cold cache + first-call failure returns empty map" {
            val cache =
                MetadataDecorationCache(
                    ttl = Duration.ofSeconds(30),
                    clock = { Instant.parse("2026-05-13T10:00:00Z") },
                    fetchSnapshot = { _ -> throw RuntimeException("metadata down") },
                )
            runBlocking { cache.decorations() } shouldBe emptyMap()
        }

        "ttl=0 disables the fast-path: every call hits the fetcher" {
            val now = Instant.parse("2026-05-13T10:00:00Z")
            val calls = AtomicInteger(0)
            val cache =
                MetadataDecorationCache(
                    ttl = Duration.ZERO,
                    clock = { now },
                    fetchSnapshot = { _ ->
                        calls.incrementAndGet()
                        snapshot(entries = listOf(attrEntry("er.entity.status", mapOf("cs" to "Stav"))))
                    },
                )
            runBlocking { cache.decorations() }
            runBlocking { cache.decorations() }
            runBlocking { cache.decorations() }
            calls.get() shouldBe 3
        }
    })
