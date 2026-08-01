// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.registry.DeclaredValue
import org.tatrman.resolver.registry.DeclaredVocabulary
import org.tatrman.resolver.registry.DeclaredVocabularyEntry
import org.tatrman.resolver.registry.LiveMetadataRegistryAdapter
import org.tatrman.resolver.registry.RegistrySource
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.registry.StubRegistrySource

/**
 * RG-P5.S2.T1 — the one-channel snapshot registry (RS-24). The registry is built
 * from the SAME `SnapshotVocabularySource` seam RG-P2 defined; a hash change
 * reloads it; the snapshot hash reaches the registry (→ every `BindingProvenance`);
 * the live-metadata step-one adapter satisfies the same interface.
 */
class SnapshotRegistryTest :
    StringSpec({

        val vocab =
            DeclaredVocabulary(
                entries =
                    listOf(
                        DeclaredVocabularyEntry(
                            "er.branch",
                            "er.branch",
                            listOf(DeclaredValue("term-pobocka", "pobočka")),
                        ),
                        DeclaredVocabularyEntry(
                            "er.product",
                            "er.product",
                            listOf(DeclaredValue("p-octavia", "Škoda Octavia")),
                        ),
                    ),
                locales = listOf("cs"),
            )

        "builds the registry from the seam: entity refs, categories, anchors, snapshot hash" {
            val registry = SnapshotRegistry(StubRegistrySource(vocab, "snap-1"), ResolverThresholds.LIVE)
            val built = runBlocking { registry.current() }

            built.snapshotHash shouldBe "snap-1"
            built.locales shouldContainExactlyInAnyOrder listOf("cs")
            built.entityTypes.map { it.ref } shouldContainExactlyInAnyOrder listOf("er.branch", "er.product")
            val branch = built.entityTypes.single { it.ref == "er.branch" }
            branch.categories shouldContainExactlyInAnyOrder listOf("er.branch")
            branch.anchors shouldContainExactlyInAnyOrder listOf("pobočka")
            // the config thresholds carry through
            built.thresholds shouldBe ResolverThresholds.LIVE
        }

        "a hash change reloads the registry; a stable hash serves the cache" {
            val source =
                object : RegistrySource {
                    var h = "snap-1"
                    var v = vocab

                    override suspend fun fetch(): DeclaredVocabulary = v

                    override fun hash(): String = h
                }
            val registry = SnapshotRegistry(source, ResolverThresholds.LIVE)

            runBlocking { registry.current() }.entityTypes.map { it.ref } shouldContainExactlyInAnyOrder
                listOf("er.branch", "er.product")

            // change the vocabulary but NOT the hash → cached registry is served.
            source.v = DeclaredVocabulary(entries = emptyList())
            runBlocking { registry.current() }.entityTypes.map { it.ref } shouldContainExactlyInAnyOrder
                listOf("er.branch", "er.product")

            // now bump the hash → the new (empty) snapshot loads.
            source.h = "snap-2"
            val reloaded = runBlocking { registry.current() }
            reloaded.snapshotHash shouldBe "snap-2"
            reloaded.entityTypes shouldBe emptyList()
        }

        "the live-metadata step-one adapter satisfies the same seam (RS-24, named not invented)" {
            val adapter = LiveMetadataRegistryAdapter()
            val built = runBlocking { SnapshotRegistry(adapter, ResolverThresholds.LIVE).current() }
            built.entityTypes shouldBe emptyList()
            built.snapshotHash shouldBe "live-metadata:step-one"
        }
    })
