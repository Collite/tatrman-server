// SPDX-License-Identifier: Apache-2.0
package org.tatrman.mcp.identity

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.Base64

/**
 * The shared OBO identity gate (RG-P6.S1, lifted from the query-mcp edge;
 * kantheon-security §2 / §2.1). Agents must call with the user's OBO token;
 * the gate fails closed on missing identity and on token-vs-arg spoofing.
 */
class IdentityGateSpec :
    StringSpec({

        fun makeJwt(payload: String): String {
            val header =
                Base64.getUrlEncoder().withoutPadding().encodeToString("""{"alg":"none"}""".toByteArray())
            val body = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
            return "$header.$body.signature-ignored"
        }

        // (a) valid user bearer → identity with user_id + auth_roles from realm_access.roles
        "valid user bearer is allowed with user_id and realm roles" {
            val token = makeJwt("""{"preferred_username":"alice","realm_access":{"roles":["analyst","viewer"]}}""")
            val decision = IdentityGate.decide("Bearer $token", null, null, requireIdentity = true)
            val allow = decision.shouldBeInstanceOf<IdentityGate.Decision.Allow>()
            val identity = allow.identity.shouldBeInstanceOf<UserIdentity>()
            identity.id shouldBe "alice"
            identity.source shouldBe IdentitySource.TOKEN
            identity.roles shouldContain "analyst"
            identity.roles shouldContain "viewer"
        }

        // (b) no token (and identity required) → fail closed, Rule-6 missing_user_identity
        "no identity supplied is rejected fail-closed when identity is required" {
            val decision = IdentityGate.decide(null, null, null, requireIdentity = true)
            val reject = decision.shouldBeInstanceOf<IdentityGate.Decision.Reject>()
            reject.code shouldBe "missing_user_identity"
        }

        // (c) service-account-shaped token (no user claim) → resolves to nothing → rejected.
        // Agents must call with the user's OBO token, never service identity.
        "service-account token with no user claim is rejected (never service identity)" {
            // A client-credentials/service token shaped with no preferred_username and no sub.
            val svcToken = makeJwt("""{"clientId":"svc-pythia","typ":"Bearer","azp":"svc-pythia"}""")
            val decision = IdentityGate.decide("Bearer $svcToken", null, null, requireIdentity = true)
            val reject = decision.shouldBeInstanceOf<IdentityGate.Decision.Reject>()
            reject.code shouldBe "missing_user_identity"
        }

        // Spoof guard: a valid token plus a conflicting user_id arg is rejected.
        "token identity conflicting with a user_id arg is rejected" {
            val token = makeJwt("""{"preferred_username":"alice","realm_access":{"roles":["analyst"]}}""")
            val decision = IdentityGate.decide("Bearer $token", null, argUserId = "bob", requireIdentity = true)
            val reject = decision.shouldBeInstanceOf<IdentityGate.Decision.Reject>()
            reject.code shouldBe "identity_conflict"
        }

        // Dev-network path: identity not required + none supplied → allowed with null identity.
        "no identity is allowed (null) when identity is not required (dev-network path)" {
            val decision = IdentityGate.decide(null, null, null, requireIdentity = false)
            val allow = decision.shouldBeInstanceOf<IdentityGate.Decision.Allow>()
            allow.identity shouldBe null
        }
    })
