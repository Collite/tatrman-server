// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.admin

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Keycloak-JWT gate for the admin key API (D-5, contracts §1.8). Verifies an RS256 bearer against the
 * realm's public key (base64 X.509 SubjectPublicKeyInfo — what Keycloak's *Realm Settings → Keys* shows),
 * checks issuer/audience, and requires the realm role [requiredRole] (`llm-gateway-admin`, created in
 * Keycloak by Bora — LGQ-2). **In-service verification is the ONLY gate**: like the data plane (D-1), any
 * Envoy-injected identity header is ignored here — defense in depth (DQ-1).
 *
 * ⚑ The realm key is configured statically (`admin.realmPublicKey`); JWKS auto-rotation is a follow-up —
 * on a Keycloak key roll the config value must be updated + redeployed.
 */
class AdminAuth(
    issuer: String?,
    audience: String?,
    realmPublicKeyBase64: String,
    private val requiredRole: String = "llm-gateway-admin",
) {
    sealed interface Result {
        data class Ok(
            val subject: String,
        ) : Result

        data object NoToken : Result // 401

        data object Invalid : Result // 401 — bad signature / issuer / audience / expiry

        data object Forbidden : Result // 403 — valid token, missing the admin role
    }

    private val verifier: JWTVerifier =
        run {
            val keyBytes = Base64.getDecoder().decode(realmPublicKeyBase64.trim())
            val pub = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes)) as RSAPublicKey
            var v = JWT.require(Algorithm.RSA256(pub, null))
            if (!issuer.isNullOrBlank()) v = v.withIssuer(issuer)
            if (!audience.isNullOrBlank()) v = v.withAudience(audience)
            v.acceptLeeway(30).build()
        }

    fun authenticate(bearerToken: String?): Result {
        val token = bearerToken ?: return Result.NoToken
        val decoded =
            try {
                verifier.verify(token)
            } catch (e: JWTVerificationException) {
                return Result.Invalid
            }
        return if (requiredRole in realmRoles(decoded)) Result.Ok(decoded.subject ?: "?") else Result.Forbidden
    }

    private fun realmRoles(jwt: DecodedJWT): List<String> {
        val realmAccess = jwt.getClaim("realm_access").asMap() ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        return (realmAccess["roles"] as? List<String>) ?: emptyList()
    }
}
