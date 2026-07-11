package org.tatrman.fuzzy.mcp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.tatrman.capabilities.v1.ToolCapability

/**
 * Stage 2.2 T6 — capability-mcp registration asserts that the
 * `manifests/tools` tree (containing match.yaml) loads cleanly and
 * yields one Capability per authored manifest. Fuzzy v1 has a single
 * match manifest (the cascade is exposed as tool args, not separate
 * tools per contracts §2).
 */
class CapabilitiesRegistrationSpec :
    StringSpec({

        "ManifestLoader loads one capability per manifest under manifests/tools/" {
            val caps = ManifestLoader().loadAll()
            caps shouldHaveSize 1
        }

        "the match capability has the right capability_id and category" {
            val caps = ManifestLoader().loadAll()
            val cap = caps.single()
            val tool = cap.tool
            (tool is ToolCapability) shouldBe true
            tool as ToolCapability
            tool.capabilityId shouldBe "fuzzy.match:v1"
            tool.category shouldBe "fuzzy"
            tool.version shouldBe "1.0.0"
        }

        "the match capability advertises the in-cluster service endpoint" {
            val caps = ManifestLoader().loadAll()
            val tool = caps.single().tool as ToolCapability
            tool.serviceEndpoint shouldBe "http://fuzzy-mcp.kantheon.svc.cluster.local:7267"
        }

        "the match capability carries cascade + fuzzy search tags" {
            val caps = ManifestLoader().loadAll()
            val tool = caps.single().tool as ToolCapability
            tool.searchTagsList shouldBe listOf("fuzzy", "fuzzy", "match", "cascade")
        }

        "the match capability declares idempotency + concurrency cost hints" {
            val caps = ManifestLoader().loadAll()
            val tool = caps.single().tool as ToolCapability
            tool.costHints.isIdempotent shouldBe true
            tool.costHints.maxConcurrent shouldBe 32
            tool.costHints.typicalLatencyMs shouldBe 100.0
        }
    })
