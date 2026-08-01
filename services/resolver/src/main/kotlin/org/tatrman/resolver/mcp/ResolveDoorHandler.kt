// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.mcp.identity.IdentityGate
import org.tatrman.mcp.identity.IdentityPolicy

/**
 * The door's fail-closed edge (RG-P6.S1, H-2). Runs the shared OBO [IdentityGate]
 * BEFORE any resolution work: a spoofing attempt (token vs `user_id` arg) or, when
 * [requireIdentity] is on, a call with no resolvable identity is refused with an
 * `isError` result and never reaches the deterministic core. When the gate allows,
 * it delegates to [door], threading the resolved subject id so it is signed into any
 * resume token (RG-P6 review C). Factored out of the transport so the fail-closed
 * behavior is unit-testable without booting an HTTP server.
 *
 * [policy] pins which identity sources are trusted (RG-P6 review A). The production
 * door passes [IdentityPolicy.TOKEN_ONLY] so a caller-supplied `user_id`/`X-User-Id`
 * or an `admin:` prefix can neither satisfy `require-identity` nor self-grant admin;
 * the dev-network path keeps [IdentityPolicy.PERMISSIVE].
 *
 * OBO note (RS-17 / F-T2): the resolved identity gates access and binds the resume
 * token here; threading the per-user identity into vocabulary visibility is a
 * documented posture, not yet used for scoping.
 */
class ResolveDoorHandler(
    private val door: ResolveDoor,
    private val requireIdentity: Boolean,
    private val policy: IdentityPolicy = IdentityPolicy.PERMISSIVE,
) {
    suspend fun handle(
        args: JsonObject?,
        authorizationHeader: String?,
        userIdHeader: String?,
    ): CallToolResult {
        val argUserId = args.str("user_id")
        return when (
            val decision =
                IdentityGate.decide(
                    authorizationHeader,
                    userIdHeader,
                    argUserId,
                    requireIdentity,
                    policy,
                )
        ) {
            is IdentityGate.Decision.Reject -> identityError(decision.code, decision.message)
            is IdentityGate.Decision.Allow -> door.call(args, callerSubject = decision.identity?.id.orEmpty())
        }
    }

    private fun identityError(
        code: String,
        message: String,
    ): CallToolResult =
        CallToolResult(
            isError = true,
            content = listOf(TextContent(text = message)),
            structuredContent =
                buildJsonObject {
                    put("errorCode", code)
                    put("error", message)
                },
        )
}
