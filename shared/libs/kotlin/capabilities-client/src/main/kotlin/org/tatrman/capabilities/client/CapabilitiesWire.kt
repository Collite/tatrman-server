// SPDX-License-Identifier: Apache-2.0
package org.tatrman.capabilities.client

import com.google.protobuf.MessageOrBuilder
import com.google.protobuf.util.JsonFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.capabilities.v1.AgentCapability
import org.tatrman.capabilities.v1.Capability
import org.tatrman.capabilities.v1.ToolCapability

/**
 * Shared JSON adapter — mirrors `tools/capabilities-mcp/api/CapabilityJson` so
 * the client and server agree on the wire shape (`{kind:"tool"|"agent", ...}`,
 * camelCase keys, `messages: []` envelope).
 */
internal object CapabilitiesWire {
    private val protoPrinter: JsonFormat.Printer =
        JsonFormat.printer().alwaysPrintFieldsWithNoPresence().omittingInsignificantWhitespace()

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    fun capabilityToJson(cap: Capability): JsonObject =
        buildJsonObject {
            when {
                cap.hasTool() -> {
                    put("kind", JsonPrimitive("tool"))
                    put("tool", protoToJson(cap.tool))
                }
                cap.hasAgent() -> {
                    put("kind", JsonPrimitive("agent"))
                    put("agent", protoToJson(cap.agent))
                }
                else -> error("Capability oneof must be set")
            }
        }

    fun toolToJson(t: ToolCapability): JsonObject = protoToJson(t)

    fun agentToJson(a: AgentCapability): JsonObject = protoToJson(a)

    private fun protoToJson(msg: MessageOrBuilder): JsonObject =
        json.decodeFromString(JsonObject.serializer(), protoPrinter.print(msg))
}
