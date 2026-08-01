// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.auth

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import org.tatrman.llmgateway.config.GovernanceConfig
import org.tatrman.llmgateway.wire.GatewayError
import org.tatrman.llmgateway.wire.GatewayException
import java.security.MessageDigest

/** The identity a validated `ttrk-` key resolves to (D-1/D-2: key → team is the attribution primary).
 *  Per-key budget/rpm overrides (min-wins, D-3) ride along for the LG-P4·S2 admission checks; the
 *  config-backed interim leaves them null. */
data class KeyPrincipal(
    val keyId: String,
    val team: String,
    val budgetUsdOverride: Double? = null,
    val rpmOverride: Int? = null,
)

/**
 * Data-plane key validation (D-1): the gateway validates `ttrk-` keys ITSELF (east-west traffic
 * bypasses Envoy, so injected identity headers are never trusted). This is the seam; the interim impl
 * accepts seeded keys from config, PG-backed issued-key validation + revocation lands LG-P4·S1.
 */
fun interface KeyValidator {
    /** Return the principal for a valid key, or null if unknown/revoked. */
    fun validate(rawKey: String): KeyPrincipal?
}

/**
 * Interim validator: accepts the seeded `ttrk-` keys imported into `governance.yaml` (G-3), matched by
 * SHA-256 (keys are never stored plaintext, D-1). Empty seeded set ⇒ nothing validates (the pre-cutover
 * state until Bora imports the hashes).
 */
class ConfigKeyValidator(
    governance: GovernanceConfig,
) : KeyValidator {
    private val byHash = governance.keys.associateBy({ it.sha256.lowercase() }, { it })

    override fun validate(rawKey: String): KeyPrincipal? {
        val seeded = byHash[sha256Hex(rawKey)] ?: return null
        return KeyPrincipal(keyId = seeded.name, team = seeded.team)
    }
}

fun sha256Hex(s: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }

/** Extract a `Bearer ttrk-…` token, or null when absent/blank. */
fun ApplicationCall.bearerToken(): String? =
    request.headers[HttpHeaders.Authorization]
        ?.takeIf { it.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true) }
        ?.substring(7)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

/**
 * Require a valid data-plane key; throws [GatewayException] → 401 `invalid_api_key` (contracts §1.7)
 * when the key is absent, malformed, or unknown/revoked.
 */
fun ApplicationCall.requireKey(validator: KeyValidator): KeyPrincipal {
    val token = bearerToken() ?: throw GatewayException(GatewayError.Auth(401))
    return validator.validate(token) ?: throw GatewayException(GatewayError.Auth(401))
}
