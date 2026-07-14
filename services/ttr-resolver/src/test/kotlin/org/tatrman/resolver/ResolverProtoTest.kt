// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.tatrman.resolver.v1.AwaitingClarification
import org.tatrman.resolver.v1.BindingProvenance
import org.tatrman.resolver.v1.Capabilities
import org.tatrman.resolver.v1.Domain
import org.tatrman.resolver.v1.EntityBinding
import org.tatrman.resolver.v1.Option
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ResolveResponse
import org.tatrman.resolver.v1.Resolution
import org.tatrman.resolver.v1.Span

/**
 * RG-P5.S1.T1 — the reshaped `org.tatrman.resolver.v1` proto (contracts §4).
 * Asserts the round-trip AND that the NORMAL-mode machinery (function_specs /
 * ResolveMode / joint-inference) is GONE (RS-23), and that provenance (S-1/S-4),
 * `degraded` (RS-25), the parse passthrough (E-T1) and the capability echo (RS-7)
 * are present.
 */
class ResolverProtoTest :
    StringSpec({

        "a Resolution with a Domain binding round-trips with full provenance + degraded" {
            val response =
                ResolveResponse
                    .newBuilder()
                    .setResolution(
                        Resolution
                            .newBuilder()
                            .addBindings(
                                EntityBinding
                                    .newBuilder()
                                    .setSpan(
                                        Span
                                            .newBuilder()
                                            .setStart(6)
                                            .setEnd(13)
                                            .setText("Octavie"),
                                    ).setDomain(
                                        Domain
                                            .newBuilder()
                                            .setEntityTypeRef("md.dimension.product")
                                            .setRawText("Octavie")
                                            .setResolvedId("prod-42")
                                            .setResolvedLabel("Škoda Octavia"),
                                    ).setProvenance(
                                        BindingProvenance
                                            .newBuilder()
                                            .setVocabularySource("MEMBER")
                                            .setAlgorithm("TATRMAN")
                                            .setScore(0.97)
                                            .setSnapshotHash("sha256:abc"),
                                    ).setDegraded(false),
                            ).setConfidence(0.97),
                    ).setTraceId("t-1")
                    .setCapabilities(
                        Capabilities
                            .newBuilder()
                            .setLanguage("cs")
                            .setCsNer(true)
                            .setFuzzyReady(true),
                    ).build()

            val round = ResolveResponse.parseFrom(response.toByteArray())
            round shouldBe response
            round.hasResolution().shouldBeTrue()
            val binding = round.resolution.getBindings(0)
            binding.domain.resolvedId shouldBe "prod-42"
            binding.provenance.vocabularySource shouldBe "MEMBER"
            binding.provenance.snapshotHash shouldBe "sha256:abc"
            binding.degraded.shouldBeFalse()
            round.capabilities.language shouldBe "cs"
        }

        "an AwaitingClarification round-trips with a signed resume token + options" {
            val awaiting =
                ResolveResponse
                    .newBuilder()
                    .setAwaiting(
                        AwaitingClarification
                            .newBuilder()
                            .addOptions(
                                Option
                                    .newBuilder()
                                    .setId("o1")
                                    .setLabel("Praha")
                                    .setResolvedId("br-1"),
                            ).setResumeToken("payload.signature"),
                    ).build()
            val round = ResolveResponse.parseFrom(awaiting.toByteArray())
            round.hasAwaiting().shouldBeTrue()
            round.awaiting.resumeToken shouldBe "payload.signature"
            round.awaiting.getOptions(0).resolvedId shouldBe "br-1"
        }

        "the parse passthrough field is present on the response (E-T1)" {
            ResolveResponse.getDescriptor().findFieldByName("parse").shouldNotBeNull()
        }

        "RS-23 — the NORMAL-mode machinery is GONE: no function_specs, no ResolveMode" {
            // function_specs was on the old request; joint-inference is agent-side now.
            ResolveRequest.getDescriptor().findFieldByName("function_specs").shouldBeNull()
            ResolveRequest.getDescriptor().findFieldByName("mode").shouldBeNull()
            ResolveRequest.getDescriptor().findFieldByName("resolve_mode").shouldBeNull()
            // No ResolveMode enum/message survives in the file.
            val file = ResolveRequest.getDescriptor().file
            file.messageTypes
                .map { it.name }
                .contains("ResolveMode")
                .shouldBeFalse()
            file.enumTypes
                .map { it.name }
                .contains("ResolveMode")
                .shouldBeFalse()
        }
    })

private fun Boolean.shouldBeTrue() = this shouldBe true
