package org.tatrman.query.mcp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

/**
 * Stage 3.5 T5 — the run_query/compile ToolCapability manifests load and publish
 * one [Capability] each (the tool vocabulary forks as-is, contracts §2). The
 * registry will find theseus-mcp by these ids.
 */
class CapabilitiesRegistrationSpec :
    StringSpec({

        "ManifestLoader publishes one Capability per authored manifest (query + compile)" {
            val capabilities = ManifestLoader().loadAll()
            capabilities.size shouldBe 2
            capabilities.map { it.tool.capabilityId } shouldContainExactlyInAnyOrder
                listOf("theseus.compile:v1", "theseus.query:v1")
        }

        "each Capability carries the theseus-mcp service endpoint" {
            ManifestLoader().loadAll().forEach { cap ->
                cap.tool.serviceEndpoint shouldBe "http://theseus-mcp.kantheon.svc.cluster.local:7307"
            }
        }

        "run_query is discoverable by its own id with sql/dfdsl search tags" {
            val query = ManifestLoader().loadAll().single { it.tool.capabilityId == "theseus.query:v1" }
            query.tool.searchTagsList shouldContain "sql"
            query.tool.searchTagsList shouldContain "dfdsl"
        }
    })
