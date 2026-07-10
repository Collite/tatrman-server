package org.tatrman.query.mcp.integration

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.tatrman.testkit.integration.ContextHandle
import java.util.Base64

// Helpers for the `query-runquery` integration specs: minting OBO bearers and
// calling the MCP `query` tool over real StreamableHTTP through the
// ContextHandle-resolved query-mcp URL.

/**
 * Mints an **unsigned** JWT (`header.payload.sig`). query-mcp's IdentityResolver
 * base64url-decodes only the payload in v1 — signature verification is the
 * ingress/sidecar's job — so an unsigned token carrying the right claims is
 * sufficient to exercise identity end-to-end. Roles land in `realm_access.roles`
 * (Keycloak shape), id in `preferred_username`.
 */
fun unsignedJwt(
    username: String,
    roles: List<String>,
): String {
    val enc = Base64.getUrlEncoder().withoutPadding()

    fun seg(s: String) = enc.encodeToString(s.toByteArray())
    val header = seg("""{"alg":"none","typ":"JWT"}""")
    val rolesJson = roles.joinToString(",") { "\"$it\"" }
    val payload = seg("""{"preferred_username":"$username","realm_access":{"roles":[$rolesJson]}}""")
    return "$header.$payload.sig"
}

/**
 * Opens an MCP StreamableHTTP client to the context's query-mcp, calls the
 * `query` tool with [arguments], and returns the [CallToolResult]. When [bearer]
 * is set it travels as `Authorization: Bearer …` on every request (the OBO token).
 *
 * [callTimeout] bounds the whole MCP call. It defaults generously (3 min) because a real
 * analytical query — e.g. a TPC-DS SF1 join+aggregate over millions of rows — on a cold JVM in a
 * CPU-throttled test namespace can take far longer than the ktor CIO engine's 15 s default request
 * timeout (which otherwise surfaces as `HttpRequestTimeoutException` at the first heavy query). The
 * CIO engine-level timeout is disabled here so this MCP-level timeout is the single governing bound.
 */
suspend fun ContextHandle.callQuery(
    arguments: Map<String, Any?>,
    bearer: String? = null,
    callTimeout: Duration = 3.minutes,
): CallToolResult {
    val http =
        HttpClient(CIO) {
            // 0 = no engine-level request timeout; the MCP RequestOptions timeout below governs.
            engine { requestTimeout = 0 }
        }
    try {
        val client =
            http.mcpStreamableHttp("${url("query-mcp")}/mcp") {
                bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            }
        try {
            return client.callTool("query", arguments, options = RequestOptions(timeout = callTimeout))
        } finally {
            client.close()
        }
    } finally {
        http.close()
    }
}

/** A `SELECT`-shaped `query` argument map (the common happy-path case). */
fun sqlQueryArgs(
    sql: String,
    rowLimit: Int = 100,
): Map<String, Any?> =
    mapOf(
        "source" to sql,
        "source_language" to "sql",
        "format" to "json",
        "row_limit" to rowLimit,
    )

/**
 * `structuredContent.ok` — true on a successful envelope, false on an error
 * envelope (or when the field is absent / not a JSON boolean). Reads the field as
 * a real JSON boolean rather than a stringly compare, so a `true` literal and a
 * `"true"` string don't diverge.
 */
fun CallToolResult.ok(): Boolean = (structuredContent?.get("ok") as? JsonPrimitive)?.booleanOrNull == true

/** `structuredContent.rowCount` as an Int (or null when absent). */
fun CallToolResult.rowCount(): Int? = (structuredContent?.get("rowCount") as? JsonPrimitive)?.content?.toIntOrNull()

/** Column names from `structuredContent.columns[].name`. */
fun CallToolResult.columnNames(): List<String> {
    val cols = structuredContent?.get("columns") as? JsonArray ?: return emptyList()
    return cols.mapNotNull { ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.content }
}

/**
 * The error code from the first `messages[]` entry of an error envelope, if any.
 * Prefers `structuredContent.messages[]`, but falls back to parsing the text
 * content block as an envelope JSON — an MCP error result (`isError=true`) may
 * carry the envelope as text only, with `structuredContent` null. Returns null if
 * neither path yields a code.
 */
fun CallToolResult.firstMessageCode(): String? =
    firstMessageCodeFrom(structuredContent)
        ?: firstMessageCodeFrom(bodyText().asJsonObjectOrNull())

private fun firstMessageCodeFrom(envelope: JsonObject?): String? {
    val messages = envelope?.get("messages") as? JsonArray ?: return null
    val first = messages.firstOrNull() as? JsonObject ?: return null
    return (first["code"] as? JsonPrimitive)?.content
}

/** Parse a text content block as a JSON object, or null if it isn't one. */
private fun String.asJsonObjectOrNull(): JsonObject? =
    takeIf { it.isNotBlank() }
        ?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

/** The text payload of the first content block — for `format=json`, the rows-array JSON string. */
fun CallToolResult.bodyText(): String = (content.firstOrNull() as? TextContent)?.text ?: ""
