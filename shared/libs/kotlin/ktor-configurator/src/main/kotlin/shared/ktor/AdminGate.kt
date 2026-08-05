// SPDX-License-Identifier: Apache-2.0
package shared.ktor

import com.typesafe.config.Config
import com.typesafe.config.ConfigException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * S-3 admin authorization for operator endpoints (`/refresh`), shared by every service that has
 * one.
 *
 * The rule, and the reason it lives here rather than in one service: an operator endpoint is
 * **never unauthenticated in the open offering**, and that is a single decision, not one per
 * service. lex-matcher established it (RG-P2.S2.T6) and delegates to this; the RV-P1.6 grounding
 * kernels (chrono, money, geo) load their `ground:` slice through the same kind of hook and are
 * gated by the same rule.
 *
 * Authority is either an `admin` role on the `X-Roles` header (set by the gateway after H-2 OBO)
 * OR an admin API key on `X-API-Key`.
 */
fun isAdminAuthorized(
    roles: List<String>,
    apiKey: String?,
    adminApiKeys: List<String>,
): Boolean =
    roles.any { it.trim().equals("admin", ignoreCase = true) } ||
        (apiKey != null && adminApiKeys.contains(apiKey))

/** The configured admin keys, or none — an absent key list means role-only authority, not open. */
fun adminApiKeys(config: Config): List<String> =
    try {
        config
            .getString("security.admin-api-keys")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    } catch (e: ConfigException) {
        emptyList()
    }

/**
 * Gates [build] behind the admin role (S-3). Refuses (403) any caller lacking admin authority —
 * enforced **regardless of `security.enabled`**, so an operator endpoint is never open.
 *
 * Scope the route narrowly (`route("/refresh") { adminOnly(config) { post { … } } }`): the
 * interceptor is installed on the route it is called on, and installing it at the root would gate
 * `/health` and `/ready` with it (SV-P3·F1).
 */
fun Route.adminOnly(
    config: Config,
    build: Route.() -> Unit,
) {
    val adminKeys = adminApiKeys(config)
    intercept(ApplicationCallPipeline.Call) {
        val roles = call.request.header("X-Roles")?.split(",") ?: emptyList()
        val apiKey = call.request.header("X-API-Key")
        if (!isAdminAuthorized(roles, apiKey, adminKeys)) {
            call.respond(HttpStatusCode.Forbidden, "admin role required")
            finish()
        }
    }
    build()
}
