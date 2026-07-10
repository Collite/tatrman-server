package org.tatrman.kantheon.argos.roles

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import kotlinx.coroutines.runBlocking

/**
 * Component-tier spec for the default whois lookup against a mocked whois (WireMock). Proves the
 * security envelope (only the record that IS the requested keycloak user contributes roles) and
 * the fail-closed posture (a non-2xx whois → RoleSourceUnavailableException, not an empty set).
 */
class KtorWhoisRoleLookupSpec :
    StringSpec({

        fun startWiremock(): WireMockServer =
            WireMockServer(WireMockConfiguration.options().dynamicPort()).also { it.start() }

        fun lookup(wm: WireMockServer): KtorWhoisRoleLookup =
            KtorWhoisRoleLookup(baseUrl = "http://localhost:${wm.port()}")

        // A whois UserRecord whose KEYCLOAK identity is `kcId` and ERP identity carries hierarchy roles.
        fun record(kcId: String): String =
            """
            [
              {
                "internalId": 1,
                "email": "ops@x.cz",
                "identities": {
                  "KEYCLOAK": { "userId": "$kcId", "userName": "ops", "active": true, "userRoles": ["analyst"] },
                  "ERP": { "userId": "ERP1", "active": true, "userRoles": ["analyst", "approver"] }
                }
              }
            ]
            """.trimIndent()

        "a matching record contributes its union of roles (incl. ERP-hierarchy)" {
            val wm = startWiremock()
            try {
                wm.stubFor(get(urlPathEqualTo("/whois")).willReturn(okJson(record("ops-kc"))))
                val roles = runBlocking { lookup(wm).rolesFor("ops-kc") }
                roles shouldContainExactlyInAnyOrder listOf("analyst", "approver")
            } finally {
                wm.stop()
            }
        }

        "security envelope: a record for a DIFFERENT keycloak user is ignored (no enrichment)" {
            val wm = startWiremock()
            try {
                wm.stubFor(get(urlPathEqualTo("/whois")).willReturn(okJson(record("someone-else"))))
                val roles = runBlocking { lookup(wm).rolesFor("ops-kc") }
                roles.shouldBeEmpty()
            } finally {
                wm.stop()
            }
        }

        "an empty whois result yields no enrichment" {
            val wm = startWiremock()
            try {
                wm.stubFor(get(urlPathEqualTo("/whois")).willReturn(okJson("[]")))
                runBlocking { lookup(wm).rolesFor("ops-kc") }.shouldBeEmpty()
            } finally {
                wm.stop()
            }
        }

        "a non-2xx whois fails closed (RoleSourceUnavailableException)" {
            val wm = startWiremock()
            try {
                wm.stubFor(get(urlPathEqualTo("/whois")).willReturn(aResponse().withStatus(503)))
                shouldThrow<RoleSourceUnavailableException> {
                    runBlocking { lookup(wm).rolesFor("ops-kc") }
                }
            } finally {
                wm.stop()
            }
        }
    })
