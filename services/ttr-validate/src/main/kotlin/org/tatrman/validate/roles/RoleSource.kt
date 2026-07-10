package org.tatrman.validate.roles

import org.tatrman.plan.v1.PipelineContext

/**
 * Resolves the **effective** role set Argos drives RLS + admin-bypass with. Identity is always
 * the bearer-trusted `PipelineContext.user_id` / `auth_roles` (resolved at the theseus-mcp edge);
 * a RoleSource may only *add* roles to that floor, never remove them and never assert identity.
 *
 * Two implementations (fork Stage 5.3, additive):
 *  - [BearerRoleSource] — the Phase-3 default: roles are exactly the forwarded bearer's.
 *  - [WhoisRoleSource]  — opt-in (`argos.roleSource = whois`): enriches the bearer floor with the
 *    ERP role hierarchy the Keycloak token doesn't carry, keyed by the bearer-trusted user_id.
 */
interface RoleSource {
    suspend fun resolveRoles(context: PipelineContext): List<String>
}

/** Phase-3 behaviour: the effective roles ARE the forwarded bearer's `auth_roles`. */
class BearerRoleSource : RoleSource {
    override suspend fun resolveRoles(context: PipelineContext): List<String> = context.authRolesList.toList()
}

/**
 * Thrown when a role-enrichment source cannot be reached. Argos **fails closed** on this
 * (kantheon-security §2.1): it surfaces a Rule-6 error and returns no plan — it does NOT fall
 * back to a wider (or any) role set.
 */
class RoleSourceUnavailableException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
