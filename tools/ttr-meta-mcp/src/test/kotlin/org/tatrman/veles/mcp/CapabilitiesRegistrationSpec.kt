package org.tatrman.veles.mcp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

/**
 * Phase 2.1 review-004 R5.3 — multi-capability envelope.
 *
 * Asserts that the `ManifestLoader` (the review-004 R5.1 answer) publishes
 * one [Capability] per authored manifest under `src/main/resources/manifests/tools/`,
 * not the single-shim `ariadne.get_model:v1` impersonator that review-004
 * F4 flagged. The previous shim folded the other tools into
 * `search_tags` so the registry only saw 1 capability; the loader sees one per manifest.
 */
class CapabilitiesRegistrationSpec :
    StringSpec({

        "ManifestLoader publishes one Capability per authored manifest (6 today)" {
            val loader = ManifestLoader()
            val capabilities = loader.loadAll()
            capabilities.size shouldBe 6
            val ids = capabilities.map { it.tool.capabilityId }
            ids shouldContainExactlyInAnyOrder
                listOf(
                    "ariadne.list_objects:v1",
                    "ariadne.get_object:v1",
                    "ariadne.search:v1",
                    "ariadne.list_queries:v1",
                    "ariadne.get_model:v1",
                    "ariadne.resolve_area:v1",
                )
        }

        "each Capability carries the service_endpoint from its manifest" {
            val loader = ManifestLoader()
            val capabilities = loader.loadAll()
            capabilities.forEach { cap ->
                cap.tool.serviceEndpoint shouldBe "http://ariadne-mcp.kantheon.svc.cluster.local:7262"
            }
        }
    })
