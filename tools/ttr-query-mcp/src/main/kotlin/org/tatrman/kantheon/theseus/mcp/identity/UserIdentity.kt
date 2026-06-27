package org.tatrman.kantheon.theseus.mcp.identity

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
 * Resolved caller identity. `roles` is the (possibly empty) set of roles
 * the validator / sql-security can use for policy decisions; v1 derives a
 * single `admin` role from the `admin:` user_id prefix convention used by
 * the validator (Phase 1.6).
 */
data class UserIdentity(
    val id: String,
    val roles: Set<String>,
    val source: IdentitySource,
) {
    val isAdmin: Boolean get() = "admin" in roles
}
