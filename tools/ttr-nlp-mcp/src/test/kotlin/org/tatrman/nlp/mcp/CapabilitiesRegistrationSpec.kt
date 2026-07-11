package org.tatrman.nlp.mcp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.tatrman.capabilities.v1.ToolCapability

/**
 * T4 — capabilities-mcp registration asserts the `manifests/tools` tree
 * (containing analyze.yaml) loads cleanly and yields one Capability per
 * authored manifest. Nlp v1 has a single `analyze` manifest (the NLP ops
 * are tool args, not separate tools, per contracts §2).
 */
class CapabilitiesRegistrationSpec :
    StringSpec({

        "ManifestLoader loads one capability per manifest under manifests/tools/" {
            ManifestLoader().loadAll() shouldHaveSize 1
        }

        "the analyze capability has the right capability_id, category, version" {
            val tool = ManifestLoader().loadAll().single().tool as ToolCapability
            tool.capabilityId shouldBe "nlp.analyze:v1"
            tool.category shouldBe "nlp"
            tool.version shouldBe "1.0.0"
        }

        "the analyze capability advertises the in-cluster service endpoint" {
            val tool = ManifestLoader().loadAll().single().tool as ToolCapability
            tool.serviceEndpoint shouldBe "http://nlp-mcp.kantheon.svc.cluster.local:7272"
        }

        "the analyze capability carries the NLP search tags" {
            val tool = ManifestLoader().loadAll().single().tool as ToolCapability
            tool.searchTagsList shouldContainAll listOf("nlp", "nlp", "lemmatize", "ner")
        }

        "the analyze capability declares idempotency + concurrency cost hints" {
            val tool = ManifestLoader().loadAll().single().tool as ToolCapability
            tool.costHints.isIdempotent shouldBe true
            tool.costHints.maxConcurrent shouldBe 16
            tool.costHints.typicalLatencyMs shouldBe 150.0
        }
    })
