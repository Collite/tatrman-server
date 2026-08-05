// SPDX-License-Identifier: Apache-2.0
package org.tatrman.proto

import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorSet
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

/**
 * **The wire-compatibility gate (RV-P2.1.T7).** Every `org.tatrman.*` proto in this repo is
 * checked, message by message and field by field, against a checked-in baseline of what has
 * already shipped. Anything that is not purely **additive** fails the build.
 *
 * This is the J-v2 discipline the effort has been holding by hand: before this, nothing in the
 * repo checked wire compatibility — no buf config, no descriptor snapshot, no CI job — and a
 * renamed field or a re-used tag number would have reached consumers (kantheon, `golem-py`, the
 * Python wheel) as a silent break. It rides `:shared:proto:check`, so it runs everywhere the
 * build runs, including the existing CI `build` job, and needs no tool that is not already here:
 * neither `buf` nor `protoc` is installable on a dev laptop in this repo, protoc arriving only
 * through the Gradle plugin.
 *
 * **What counts as breaking**, and why each one is on the list:
 *
 * | change | breaks |
 * |---|---|
 * | a message or enum disappears | every client that names it |
 * | a field disappears | readers of that field; the tag is then free to be reused for another type |
 * | a field changes type or label (`optional`→`repeated`, `string`→`bytes`) | decoding, silently |
 * | a field changes number | the wire is tag-keyed: the old tag now decodes as something else |
 * | a field changes NAME | proto3 JSON is name-keyed — the REST/MCP surface breaks even though the binary wire does not |
 * | an enum value disappears or changes number | the same, one level down |
 * | a service or rpc disappears, or is renamed | the gRPC method path is `/pkg.Service/Method` — the call 404s |
 * | an rpc changes request/response type or streaming | the call connects and then fails to decode |
 *
 * The last two rows arrived with the p2-1 review: the first cut walked messages and enums
 * only, so deleting `rpc Resolve` from `ResolverService` — the very door this effort ships —
 * passed the gate clean. A guard over the payloads of calls that no longer exist is not a
 * guard over the wire contract.
 *
 * Adding messages, fields, enums, enum values, rpcs and whole services is fine, which is
 * exactly what this list permits and what `ResolutionState` did.
 *
 * **To accept a deliberate wire change**: `just proto-baseline`, then commit the regenerated
 * `compat/wire-baseline.desc` *with* the change. The regeneration is the acceptance — the same
 * discipline as the helm golden templates. The baseline is deliberately not auto-refreshed.
 */
class ProtoCompatSpec :
    StringSpec({

        "every shipped proto is still there, unchanged, with only additions on top (J-v2)" {
            val baseline = load(System.getProperty("tatrman.proto.baseline"))
            val current = load(System.getProperty("tatrman.proto.descriptorSet"))

            breaksBetween(baseline, current).shouldBeEmpty()
        }

        "...and the guard itself catches each break it claims to" {
            // A gate whose failure mode is untested is a gate nobody can trust. One synthetic
            // delta per row of the table above, against one synthetic baseline.
            val baseline = mapOf("t.proto" to shipped())

            breaksBetween(baseline, mapOf()) shouldBe listOf("t.proto: the whole file is gone")
            breaksBetween(baseline, mapOf("t.proto" to shipped { it.clearMessageType() }))
                .single() shouldContain "message Thing was removed"
            breaksBetween(baseline, mapOf("t.proto" to shipped { it.dropField("label") }))
                .single() shouldContain "Thing.label (#2) was removed"
            breaksBetween(baseline, mapOf("t.proto" to shipped { it.renameField("label", "title") }))
                .single() shouldContain "renamed label -> title"
            breaksBetween(
                baseline,
                mapOf("t.proto" to shipped { it.retypeField("label", FieldDescriptorProto.Type.TYPE_BYTES) }),
            ).single() shouldContain "changed type"
            breaksBetween(
                baseline,
                mapOf("t.proto" to shipped { it.relabelField("label", FieldDescriptorProto.Label.LABEL_REPEATED) }),
            ).single() shouldContain "changed label"
            breaksBetween(baseline, mapOf("t.proto" to shipped { it.renumberEnumValue("KIND_B", 7) }))
                .single() shouldContain "value KIND_B moved 2 -> 7"

            // the CALLS (p2-1 review) — every one of these leaves the messages untouched, which
            // is exactly why the message walk above cannot see them.
            breaksBetween(baseline, mapOf("t.proto" to shipped { it.clearService() }))
                .single() shouldContain "service ThingService was removed"
            breaksBetween(baseline, mapOf("t.proto" to shipped { it.dropRpc("Get") }))
                .single() shouldContain "rpc ThingService.Get was removed"
            breaksBetween(baseline, mapOf("t.proto" to shipped { it.renameRpc("Get", "Fetch") }))
                // a rename reads as a removal: the OLD path is what every client calls
                .single() shouldContain "rpc ThingService.Get was removed"
            breaksBetween(baseline, mapOf("t.proto" to shipped { it.retypeRpc("Get", output = ".t.Other") }))
                .single() shouldContain "changed signature"
            breaksBetween(baseline, mapOf("t.proto" to shipped { it.restreamRpc("Get") }))
                .single() shouldContain "changed streaming"

            // ...and that an ADDITION is not a break, which is the other half of the promise.
            breaksBetween(baseline, mapOf("t.proto" to shipped { it.addField("extra", 9) })).shouldBeEmpty()
            breaksBetween(baseline, mapOf("t.proto" to shipped { it.addRpc("List") })).shouldBeEmpty()
        }
    })

/**
 * In-repo protos only. Everything under `google/` is upstream, and the `plan`, `transdsl` and
 * `dfdsl` trees are owned by the `ttr-plan-proto` artifact (the same exclusion
 * `noTransferredProtoClasses` makes) — they arrive through `includeImports`, not ours to guard.
 */
private val FOREIGN = listOf("google/", "org/tatrman/plan/", "org/tatrman/transdsl/", "org/tatrman/dfdsl/")

/** Everything about `current` that is not purely additive on `baseline`, in report order. */
fun breaksBetween(
    baseline: Map<String, FileDescriptorProto>,
    current: Map<String, FileDescriptorProto>,
): List<String> {
    val breaks = mutableListOf<String>()
    for ((file, old) in baseline) {
        val new = current[file]
        if (new == null) {
            breaks += "$file: the whole file is gone"
            continue
        }
        val newMessages = messages(new)
        for ((name, oldMessage) in messages(old)) {
            val newMessage = newMessages[name]
            if (newMessage == null) {
                breaks += "$file: message $name was removed"
                continue
            }
            breaks += fieldBreaks(file, name, oldMessage, newMessage)
        }
        val newEnums = enums(new)
        for ((name, oldEnum) in enums(old)) {
            val newEnum = newEnums[name]
            if (newEnum == null) {
                breaks += "$file: enum $name was removed"
                continue
            }
            val values = newEnum.valueList.associate { it.name to it.number }
            for (value in oldEnum.valueList) {
                when (values[value.name]) {
                    null -> breaks += "$file: enum $name lost value ${value.name}"
                    value.number -> Unit
                    else ->
                        breaks +=
                            "$file: enum $name value ${value.name} moved " +
                            "${value.number} -> ${values[value.name]}"
                }
            }
        }
        breaks += serviceBreaks(file, old, new)
    }
    return breaks
}

/**
 * The CALLS, not just their payloads. A gRPC method is addressed by `/package.Service/Method`,
 * so removing or renaming either end of that path breaks every client at connect time — and a
 * changed request/response type or streaming mode breaks it one step later, at decode. None of
 * it shows up in the message walk above, because the messages themselves are still there.
 */
private fun serviceBreaks(
    file: String,
    old: FileDescriptorProto,
    new: FileDescriptorProto,
): List<String> {
    val breaks = mutableListOf<String>()
    val newServices = new.serviceList.associateBy { it.name }
    for (service in old.serviceList) {
        val current = newServices[service.name]
        if (current == null) {
            breaks += "$file: service ${service.name} was removed"
            continue
        }
        val methods = current.methodList.associateBy { it.name }
        for (method in service.methodList) {
            val now = methods[method.name]
            val path = "${service.name}.${method.name}"
            if (now == null) {
                breaks += "$file: rpc $path was removed"
                continue
            }
            if (now.inputType != method.inputType || now.outputType != method.outputType) {
                breaks +=
                    "$file: rpc $path changed signature " +
                    "(${method.inputType} -> ${method.outputType}) to " +
                    "(${now.inputType} -> ${now.outputType})"
            }
            if (now.clientStreaming != method.clientStreaming || now.serverStreaming != method.serverStreaming) {
                breaks +=
                    "$file: rpc $path changed streaming " +
                    "(client=${method.clientStreaming}, server=${method.serverStreaming}) to " +
                    "(client=${now.clientStreaming}, server=${now.serverStreaming})"
            }
        }
    }
    return breaks
}

private fun load(path: String?): Map<String, FileDescriptorProto> {
    requireNotNull(path) { "descriptor set path not set — see :shared:proto test config" }
    val file = File(path)
    require(file.isFile) { "descriptor set $path does not exist" }
    return FileDescriptorSet
        .parseFrom(file.readBytes())
        .fileList
        .filterNot { descriptor -> FOREIGN.any { descriptor.name.startsWith(it) } }
        .associateBy { it.name }
}

/** Every message in a file, nested ones included, keyed by their dotted path. */
private fun messages(file: FileDescriptorProto): Map<String, DescriptorProto> {
    val out = LinkedHashMap<String, DescriptorProto>()

    fun walk(
        prefix: String,
        message: DescriptorProto,
    ) {
        val name = if (prefix.isEmpty()) message.name else "$prefix.${message.name}"
        out[name] = message
        message.nestedTypeList.forEach { walk(name, it) }
    }
    file.messageTypeList.forEach { walk("", it) }
    return out
}

private fun enums(file: FileDescriptorProto): Map<String, EnumDescriptorProto> {
    val out = LinkedHashMap<String, EnumDescriptorProto>()
    file.enumTypeList.forEach { out[it.name] = it }
    messages(file).forEach { (prefix, message) ->
        message.enumTypeList.forEach { out["$prefix.${it.name}"] = it }
    }
    return out
}

private fun fieldBreaks(
    file: String,
    message: String,
    old: DescriptorProto,
    new: DescriptorProto,
): List<String> {
    val breaks = mutableListOf<String>()
    val byNumber = new.fieldList.associateBy { it.number }
    val byName = new.fieldList.associateBy { it.name }
    for (field in old.fieldList) {
        val sameNumber = byNumber[field.number]
        if (sameNumber == null) {
            breaks += "$file: $message.${field.name} (#${field.number}) was removed"
            continue
        }
        if (sameNumber.name != field.name) {
            // proto3 JSON is keyed by name, so a rename breaks the MCP/REST surface even
            // though the binary encoding would survive it.
            breaks += "$file: $message #${field.number} renamed ${field.name} -> ${sameNumber.name}"
        }
        if (sameNumber.type != field.type || sameNumber.typeName != field.typeName) {
            breaks +=
                "$file: $message.${field.name} changed type " +
                "${typeOf(field)} -> ${typeOf(sameNumber)}"
        }
        if (sameNumber.label != field.label) {
            breaks += "$file: $message.${field.name} changed label ${field.label} -> ${sameNumber.label}"
        }
        val sameName = byName[field.name]
        if (sameName != null && sameName.number != field.number) {
            breaks += "$file: $message.${field.name} moved to #${sameName.number}"
        }
    }
    return breaks
}

private fun typeOf(field: FieldDescriptorProto): String = field.typeName.ifBlank { field.type.name }

// --- the guard's own fixtures -------------------------------------------------------
//
// One synthetic "already shipped" file, plus the mutations that model each breaking change.
// Synthetic on purpose: the real protos must not have to grow a breakage for the gate to be
// testable, and a hand-built descriptor is the same shape protoc emits.

private fun shipped(mutate: (FileDescriptorProto.Builder) -> Unit = {}): FileDescriptorProto {
    val builder =
        FileDescriptorProto
            .newBuilder()
            .setName("t.proto")
            .addMessageType(
                DescriptorProto
                    .newBuilder()
                    .setName("Thing")
                    .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_STRING))
                    .addField(field("label", 2, FieldDescriptorProto.Type.TYPE_STRING)),
            ).addEnumType(
                EnumDescriptorProto
                    .newBuilder()
                    .setName("Kind")
                    .addValue(EnumValueDescriptorProto.newBuilder().setName("KIND_A").setNumber(1))
                    .addValue(EnumValueDescriptorProto.newBuilder().setName("KIND_B").setNumber(2)),
            ).addService(
                ServiceDescriptorProto
                    .newBuilder()
                    .setName("ThingService")
                    .addMethod(rpc("Get")),
            )
    mutate(builder)
    return builder.build()
}

private fun rpc(
    name: String,
    input: String = ".t.Thing",
    output: String = ".t.Thing",
    serverStreaming: Boolean = false,
): MethodDescriptorProto =
    MethodDescriptorProto
        .newBuilder()
        .setName(name)
        .setInputType(input)
        .setOutputType(output)
        .setClientStreaming(false)
        .setServerStreaming(serverStreaming)
        .build()

private fun field(
    name: String,
    number: Int,
    type: FieldDescriptorProto.Type,
): FieldDescriptorProto =
    FieldDescriptorProto
        .newBuilder()
        .setName(name)
        .setNumber(number)
        .setType(type)
        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
        .build()

private fun FileDescriptorProto.Builder.editField(
    name: String,
    edit: (FieldDescriptorProto.Builder) -> Unit,
) {
    val message = getMessageTypeBuilder(0)
    val index = message.fieldBuilderList.indexOfFirst { it.name == name }
    edit(message.getFieldBuilder(index))
}

private fun FileDescriptorProto.Builder.dropField(name: String) {
    val message = getMessageTypeBuilder(0)
    message.removeField(message.fieldBuilderList.indexOfFirst { it.name == name })
}

private fun FileDescriptorProto.Builder.renameField(
    name: String,
    to: String,
) = editField(name) { it.name = to }

private fun FileDescriptorProto.Builder.retypeField(
    name: String,
    type: FieldDescriptorProto.Type,
) = editField(name) { it.type = type }

private fun FileDescriptorProto.Builder.relabelField(
    name: String,
    label: FieldDescriptorProto.Label,
) = editField(name) { it.label = label }

private fun FileDescriptorProto.Builder.addField(
    name: String,
    number: Int,
) {
    getMessageTypeBuilder(0).addField(field(name, number, FieldDescriptorProto.Type.TYPE_STRING))
}

private fun FileDescriptorProto.Builder.renumberEnumValue(
    name: String,
    to: Int,
) {
    val enum = getEnumTypeBuilder(0)
    enum.getValueBuilder(enum.valueBuilderList.indexOfFirst { it.name == name }).number = to
}

private fun FileDescriptorProto.Builder.editRpc(
    name: String,
    edit: (MethodDescriptorProto.Builder) -> Unit,
) {
    val service = getServiceBuilder(0)
    edit(service.getMethodBuilder(service.methodBuilderList.indexOfFirst { it.name == name }))
}

private fun FileDescriptorProto.Builder.dropRpc(name: String) {
    val service = getServiceBuilder(0)
    service.removeMethod(service.methodBuilderList.indexOfFirst { it.name == name })
}

private fun FileDescriptorProto.Builder.renameRpc(
    name: String,
    to: String,
) = editRpc(name) { it.name = to }

private fun FileDescriptorProto.Builder.retypeRpc(
    name: String,
    output: String,
) = editRpc(name) { it.outputType = output }

private fun FileDescriptorProto.Builder.restreamRpc(name: String) = editRpc(name) { it.serverStreaming = true }

private fun FileDescriptorProto.Builder.addRpc(name: String) {
    getServiceBuilder(0).addMethod(rpc(name))
}
