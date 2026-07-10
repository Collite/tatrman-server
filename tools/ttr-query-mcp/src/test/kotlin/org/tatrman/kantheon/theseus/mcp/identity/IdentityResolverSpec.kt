package org.tatrman.kantheon.theseus.mcp.identity

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.util.Base64

class IdentityResolverSpec :
    StringSpec({

        fun makeJwt(payload: String): String {
            val header =
                Base64.getUrlEncoder().withoutPadding().encodeToString("""{"alg":"none"}""".toByteArray())
            val body = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
            return "$header.$body.signature-ignored"
        }

        "Bearer token wins over header and arg" {
            val token =
                makeJwt(
                    """{"preferred_username":"alice","realm_access":{"roles":["analyst"]}}""",
                )
            val id =
                IdentityResolver.resolve(
                    authorizationHeader = "Bearer $token",
                    userIdHeader = "bob",
                    argUserId = "carol",
                )
            id?.id shouldBe "alice"
            id?.source shouldBe IdentitySource.TOKEN
            id?.roles?.shouldContain("analyst")
        }

        "Falls back to sub when preferred_username absent" {
            val token = makeJwt("""{"sub":"sub-bob"}""")
            val id = IdentityResolver.resolve("Bearer $token", null, null)
            id?.id shouldBe "sub-bob"
        }

        "Header used when no token" {
            val id = IdentityResolver.resolve(null, "bob", "carol")
            id?.id shouldBe "bob"
            id?.source shouldBe IdentitySource.HEADER
        }

        "Arg used when no token and no header" {
            val id = IdentityResolver.resolve(null, null, "carol")
            id?.id shouldBe "carol"
            id?.source shouldBe IdentitySource.EXPLICIT
        }

        "Returns null when nothing supplied" {
            IdentityResolver.resolve(null, null, null) shouldBe null
            IdentityResolver.resolve("", "", "") shouldBe null
        }

        "admin: prefix marks identity as admin (v1 convention)" {
            val id = IdentityResolver.resolve(null, "admin:bora", null)!!
            id.isAdmin shouldBe true
        }

        "admin: prefix also surfaces the DF-V02 canonical 'query-platform-admin' role" {
            // So that the validator's auth_roles-based bypass check works in local dev where the
            // admin: shortcut stands in for a real Keycloak admin role.
            val id = IdentityResolver.resolve(null, "admin:bora", null)!!
            id.roles shouldContain "query-platform-admin"
        }

        "parseTokenOrNull extracts a token identity (used by the transport for DF-Q03 conflict detection)" {
            val token = makeJwt("""{"preferred_username":"alice","realm_access":{"roles":["analyst"]}}""")
            val id = IdentityResolver.parseTokenOrNull("Bearer $token")
            id?.id shouldBe "alice"
            id?.source shouldBe IdentitySource.TOKEN
            IdentityResolver.parseTokenOrNull(null) shouldBe null
            IdentityResolver.parseTokenOrNull("Basic abc") shouldBe null
            IdentityResolver.parseTokenOrNull("Bearer not.a.jwt") shouldBe null
        }

        "Bearer prefix is case-insensitive and trims whitespace" {
            val token = makeJwt("""{"sub":"sub-bob"}""")
            IdentityResolver.resolve("bearer  $token", null, null)?.id shouldBe "sub-bob"
        }

        "Malformed token gracefully falls through to next source" {
            // 'Bearer' present but payload is garbage — drop to header.
            val id = IdentityResolver.resolve("Bearer not.a.jwt", "header-fallback", null)
            id?.id shouldBe "header-fallback"
            id?.source shouldBe IdentitySource.HEADER
        }

        "Header-only Authorization without 'Bearer ' is ignored" {
            val id = IdentityResolver.resolve("Basic abcdef==", "header-fallback", null)
            id?.id shouldBe "header-fallback"
        }
    })
