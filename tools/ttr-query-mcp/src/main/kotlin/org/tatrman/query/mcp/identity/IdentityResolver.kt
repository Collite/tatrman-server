// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.mcp.identity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/**
 * Resolves caller identity from one of three sources, in priority order:
 *
 *  1. `Authorization: Bearer <jwt>` — preferred for production. Extracts the
 *     `preferred_username` claim, falling back to `sub`. Roles come from
 *     `realm_access.roles` (Keycloak convention).
 *  2. `X-User-Id` request header — service-to-service shortcut used by the
 *     existing v0 MCP servers.
 *  3. Tool-arg `user_id` — trusted-network shortcut.
 *
 * **v1 limitation.** This decodes the JWT *without verifying its signature*.
 * Production deployments are expected to terminate inbound auth at an
 * ingress / sidecar that validates the token before it reaches query-mcp;
 * this resolver only extracts claims for downstream context-passing.
 * Signature validation lives outside this service. Documented in
 * tools/query-mcp/docs/technical/query-mcp-service.md.
 */
object IdentityResolver {
    private val parser =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    /**
     * @param authorizationHeader value of `Authorization` request header (may be null)
     * @param userIdHeader value of `X-User-Id` request header (may be null)
     * @param argUserId `user_id` field from MCP tool args (may be null)
     * @return resolved identity or null when nothing usable was provided
     */
    fun resolve(
        authorizationHeader: String?,
        userIdHeader: String?,
        argUserId: String?,
    ): UserIdentity? {
        val fromToken = authorizationHeader?.let { tryParseToken(it) }
        if (fromToken != null) return fromToken
        val fromHeader = userIdHeader?.takeIf { it.isNotBlank() }
        if (fromHeader != null) {
            return UserIdentity(
                id = fromHeader,
                roles = rolesFromIdConvention(fromHeader),
                source = IdentitySource.HEADER,
            )
        }
        val fromArg = argUserId?.takeIf { it.isNotBlank() }
        if (fromArg != null) {
            return UserIdentity(id = fromArg, roles = rolesFromIdConvention(fromArg), source = IdentitySource.EXPLICIT)
        }
        return null
    }

    /**
     * Public token-only parser used by the transport to detect a token-vs-arg identity conflict
     * before falling into the priority-order [resolve] path (DF-Q03). Returns null on absent /
     * malformed / non-Bearer headers — caller treats null as "no token in play".
     */
    fun parseTokenOrNull(authorizationHeader: String?): UserIdentity? = authorizationHeader?.let { tryParseToken(it) }

    private fun tryParseToken(header: String): UserIdentity? {
        val token =
            header
                .removePrefix("Bearer ")
                .removePrefix("bearer ")
                .trim()
                .takeIf { it.isNotEmpty() && it != header.trim() } ?: return null
        val parts = token.split(".")
        if (parts.size < 2) return null
        val payloadJson =
            runCatching {
                val decoded = Base64.getUrlDecoder().decode(parts[1].padEnd(((parts[1].length + 3) / 4) * 4, '='))
                parser.parseToJsonElement(String(decoded, Charsets.UTF_8))
            }.getOrNull() ?: return null
        if (payloadJson !is JsonObject) return null
        val id =
            payloadJson["preferred_username"]?.asStringOrNull()
                ?: payloadJson["sub"]?.asStringOrNull()
                ?: return null
        val roles =
            buildSet {
                addAll(rolesFromIdConvention(id))
                payloadJson["realm_access"]?.let { ra ->
                    if (ra is JsonObject) {
                        ra["roles"]?.let { rolesEl ->
                            runCatching { rolesEl.jsonArray }.getOrNull()?.forEach { role ->
                                role.asStringOrNull()?.let { add(it) }
                            }
                        }
                    }
                }
            }
        return UserIdentity(id = id, roles = roles, source = IdentitySource.TOKEN)
    }

    /**
     * Local-dev admin shortcut: a user_id prefixed `admin:` is treated as an admin and emits both
     * `admin` (legacy / `UserIdentity.isAdmin`) and `query-platform-admin` (DF-V02 canonical role
     * the validator gates `apply_security = false` on). Production callers carry these roles from
     * the JWT's `realm_access.roles` and don't rely on this convention.
     */
    private fun rolesFromIdConvention(userId: String): Set<String> =
        if (userId.startsWith("admin:")) setOf("admin", "query-platform-admin") else emptySet()

    private fun JsonElement.asStringOrNull(): String? =
        runCatching {
            val p = jsonPrimitive
            if (p is JsonPrimitive && p.isString) p.content else null
        }.getOrNull()
}
