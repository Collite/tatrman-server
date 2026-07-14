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
