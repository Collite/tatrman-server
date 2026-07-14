// SPDX-License-Identifier: Apache-2.0
package org.tatrman.mcp.identity

/** Where the resolved identity came from. */
enum class IdentitySource {
    /** Authorization: Bearer <jwt> — sub/preferred_username extracted. */
    TOKEN,

    /** X-User-Id request header — service-to-service shortcut. */
    HEADER,

    /** Tool-arg `user_id` field. Trusted-network deployments only. */
    EXPLICIT,
}

/**
 * Resolved caller identity. `roles` is the (possibly empty) set of roles a
 * consumer's policy layer (validator / sql-security / admin-gated endpoints) can
 * use for decisions; the `admin:` user_id prefix convention derives a single
 * `admin` role in local dev (see [IdentityResolver]).
 */
data class UserIdentity(
    val id: String,
    val roles: Set<String>,
    val source: IdentitySource,
) {
    val isAdmin: Boolean get() = "admin" in roles
}

/**
 * Which identity sources a door trusts (RG-P6 review A). The `X-User-Id` header
 * and the `user_id` tool-arg are unauthenticated caller-controlled inputs, and the
 * `admin:` id-prefix convention self-grants admin roles — all three are safe only
 * on a trusted network behind an auth terminator. A production door pins
 * [TOKEN_ONLY] so identity comes solely from the (upstream-verified) Bearer token
 * and admin comes only from the JWT's `realm_access.roles`, never a username
 * prefix. [PERMISSIVE] preserves the pre-lift dev-network behavior (all sources +
 * the admin convention) and is the default so existing consumers are unchanged.
 */
data class IdentityPolicy(
    val allowHeaderSource: Boolean,
    val allowArgSource: Boolean,
    val allowAdminConvention: Boolean,
) {
    companion object {
        /** Dev-network default: trust every source, honour the `admin:` convention. */
        val PERMISSIVE = IdentityPolicy(allowHeaderSource = true, allowArgSource = true, allowAdminConvention = true)

        /** Production: only an OBO Bearer token is an identity; admin from JWT roles only. */
        val TOKEN_ONLY = IdentityPolicy(allowHeaderSource = false, allowArgSource = false, allowAdminConvention = false)
    }
}
