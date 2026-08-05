// SPDX-License-Identifier: Apache-2.0
package org.tatrman.proto

import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorSet
import com.google.protobuf.Descriptors
import com.google.protobuf.DynamicMessage
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.v1.Binding
import org.tatrman.resolver.v1.BindingProvenance
import org.tatrman.resolver.v1.Capabilities
import org.tatrman.resolver.v1.Domain
import org.tatrman.resolver.v1.EntityBinding
import org.tatrman.resolver.v1.Mention
import org.tatrman.resolver.v1.ResolutionState
import org.tatrman.resolver.v1.ResolveResponse
import org.tatrman.resolver.v1.Resolution
import org.tatrman.resolver.v1.Span
import org.tatrman.resolver.v1.TargetClass
import java.io.File

/**
 * RV-P2.1.T7 — **the old-client proof.** A client generated from the PRE-lattice
 * `org.tatrman.resolver.v1` parses a response that carries the lattice, reads every field it
 * knew, and hands the response on unharmed.
 *
 * The "pre-change generated stub" is not a copy of the old Kotlin classes: it is the baseline
 * descriptor set (`compat/wire-baseline.desc`, the protos exactly as they were before this
 * change) driving a [DynamicMessage]. That is a stricter test than keeping stale generated
 * sources around, because the descriptor cannot drift from what actually shipped.
 *
 * Three claims, and the third is the one that usually breaks in practice: an intermediary that
 * parses and re-emits a message must not silently DROP the field it did not understand. Proto3
 * preserves unknown fields, and this asserts it end to end rather than trusting it.
 */
class ResolverOldClientSpec :
    StringSpec({

        "a pre-lattice client parses a lattice-carrying response, reads its own fields, and loses nothing" {
            val response = latticeCarryingResponse()
            val wire = response.toByteArray()

            val oldResolveResponse = baselineDescriptor("org/tatrman/resolver/v1/resolver.proto", "ResolveResponse")
            // the baseline really is pre-change — otherwise this proves nothing
            oldResolveResponse.findFieldByName("resolution_state") shouldBe null

            val asOldClient = DynamicMessage.parseFrom(oldResolveResponse, wire)

            // 1. everything the old client knew is still where it was
            val resolution = asOldClient.getField(oldResolveResponse.findFieldByName("resolution")) as DynamicMessage
            val bindings =
                resolution.getField(
                    resolution.descriptorForType.findFieldByName("bindings"),
                ) as List<*>
            val binding = bindings.single() as DynamicMessage
            val domain = binding.getField(binding.descriptorForType.findFieldByName("domain")) as DynamicMessage
            domain.getField(domain.descriptorForType.findFieldByName("entity_type_ref")) shouldBe
                "md.dimension.Account"
            asOldClient.getField(oldResolveResponse.findFieldByName("trace_id")) shouldBe "trace-compat"

            // 2. the lattice arrives as an unknown field rather than as a parse failure
            asOldClient.unknownFields.hasField(7).shouldBeTrue()
            asOldClient.unknownFields shouldNotBe
                com.google.protobuf.UnknownFieldSet
                    .getDefaultInstance()

            // 3. and it SURVIVES a round trip through the old client — an intermediary that
            //    parsed and re-emitted this response would not silently drop the annotation.
            val reEmitted = asOldClient.toByteArray()
            ResolveResponse
                .parseFrom(reEmitted)
                .resolutionState.mentionsList
                .single()
                .bindingsList
                .single()
                .ref shouldBe "md.dimension.Account"
        }
    })

private fun latticeCarryingResponse(): ResolveResponse {
    val parse =
        AnalyzeResponse
            .newBuilder()
            .setLanguage("cs")
            .addTokens(
                Token
                    .newBuilder()
                    .setText("účtu")
                    .setCharStart(15)
                    .setCharEnd(19)
                    .setLemma("účet"),
            ).build()
    val span =
        Span
            .newBuilder()
            .setStart(15)
            .setEnd(19)
            .setText("účtu")
    return ResolveResponse
        .newBuilder()
        .setParse(parse)
        .setTraceId("trace-compat")
        .setCapabilities(Capabilities.newBuilder().setLanguage("cs").setFuzzyReady(true))
        .setResolution(
            Resolution
                .newBuilder()
                .setConfidence(1.0)
                .addBindings(
                    EntityBinding
                        .newBuilder()
                        .setSpan(span)
                        .setDomain(Domain.newBuilder().setEntityTypeRef("md.dimension.Account").setRawText("účtu"))
                        .setProvenance(BindingProvenance.newBuilder().setVocabularySource("DECLARED")),
                ),
        ).setResolutionState(
            ResolutionState
                .newBuilder()
                .setParse(parse)
                .addMentions(
                    Mention
                        .newBuilder()
                        .setId("m1")
                        .setSpan(span)
                        .setLemma("účet")
                        .addBindings(
                            Binding
                                .newBuilder()
                                .setRef("md.dimension.Account")
                                .setTargetClass(TargetClass.TARGET_CLASS_MODEL_OBJECT),
                        ),
                ),
        ).build()
}

/** A message descriptor as it was BEFORE this change, from the checked-in wire baseline. */
private fun baselineDescriptor(
    file: String,
    message: String,
): Descriptors.Descriptor {
    val path = System.getProperty("tatrman.proto.baseline")
    val protos =
        FileDescriptorSet
            .parseFrom(File(path).readBytes())
            .fileList
            .associateBy { it.name }
    val built = HashMap<String, Descriptors.FileDescriptor>()

    fun build(name: String): Descriptors.FileDescriptor =
        built.getOrPut(name) {
            val proto: FileDescriptorProto = protos[name] ?: error("$name is not in the baseline")
            Descriptors.FileDescriptor.buildFrom(proto, proto.dependencyList.map { build(it) }.toTypedArray())
        }

    return build(file).findMessageTypeByName(message) ?: error("$message is not in $file")
}
