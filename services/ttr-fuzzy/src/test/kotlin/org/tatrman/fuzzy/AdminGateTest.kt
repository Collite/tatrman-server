// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * RG-P2.S2.T6 — the S-3 admin gate on `/refresh`. Authority is an `admin` role
 * (X-Roles, set by the gateway after H-2 OBO) OR an admin API key. An
 * unauthenticated caller is refused; an admin caller is allowed.
 */
class AdminGateTest :
    StringSpec({

        val adminKeys = listOf("admin-key-1", "admin-key-2")

        "an unauthenticated caller (no role, no key) is refused" {
            isAdminAuthorized(roles = emptyList(), apiKey = null, adminApiKeys = adminKeys) shouldBe false
        }

        "a non-admin role is refused" {
            isAdminAuthorized(
                roles = listOf("reader", "writer"),
                apiKey = "some-key",
                adminApiKeys = adminKeys,
            ) shouldBe
                false
        }

        "the admin role authorizes (case-insensitive, trimmed)" {
            isAdminAuthorized(roles = listOf("reader", " Admin "), apiKey = null, adminApiKeys = adminKeys) shouldBe
                true
        }

        "an admin API key authorizes" {
            isAdminAuthorized(roles = emptyList(), apiKey = "admin-key-2", adminApiKeys = adminKeys) shouldBe true
        }

        "a wrong API key is refused" {
            isAdminAuthorized(roles = emptyList(), apiKey = "not-an-admin-key", adminApiKeys = adminKeys) shouldBe false
        }

        "with no admin keys configured, only the role authorizes" {
            isAdminAuthorized(roles = listOf("admin"), apiKey = null, adminApiKeys = emptyList()) shouldBe true
            isAdminAuthorized(roles = emptyList(), apiKey = "anything", adminApiKeys = emptyList()) shouldBe false
        }
    })
