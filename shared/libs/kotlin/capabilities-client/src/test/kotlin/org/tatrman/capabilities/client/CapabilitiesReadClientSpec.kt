package org.tatrman.capabilities.client

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private class MutableClock(
    @Volatile var now: Instant,
) : Clock() {
    override fun getZone() = ZoneOffset.UTC

    override fun withZone(z: java.time.ZoneId?) = this

    override fun instant() = now
}

class CapabilitiesReadClientSpec :
    StringSpec({

        lateinit var wm: WireMockServer

        beforeTest {
            wm = WireMockServer(wireMockConfig().dynamicPort())
            wm.start()
        }

        afterTest { wm.stop() }

        "CapabilitiesReadClient caches list_agents within TTL" {
            wm.stubFor(
                get(urlPathMatching("/v1/capabilities/agents"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""{"agents":[],"messages":[]}"""),
                    ),
            )
            val client =
                CapabilitiesReadClient(
                    endpoint = "http://localhost:${wm.port()}",
                    cacheTtlMs = 30_000,
                )
            runBlocking {
                client.listAgents()
                client.listAgents()
                client.listAgents()
            }
            wm.findAll(getRequestedFor(urlPathMatching("/v1/capabilities/agents"))).size shouldBe 1
            client.close()
        }

        "CapabilitiesReadClient invalidates cache after TTL" {
            wm.stubFor(
                get(urlPathMatching("/v1/capabilities/agents"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""{"agents":[],"messages":[]}"""),
                    ),
            )
            val clock = MutableClock(Instant.parse("2026-05-28T12:00:00Z"))
            val client =
                CapabilitiesReadClient(
                    endpoint = "http://localhost:${wm.port()}",
                    cacheTtlMs = 1_000,
                    clock = clock,
                )
            runBlocking {
                client.listAgents()
                clock.now = clock.now.plusSeconds(2)
                client.listAgents()
            }
            wm.findAll(getRequestedFor(urlPathMatching("/v1/capabilities/agents"))).size shouldBe 2
            client.close()
        }

        "CapabilitiesReadClient throws on unreachable endpoint" {
            val client = CapabilitiesReadClient(endpoint = "http://localhost:1", cacheTtlMs = 30_000)
            val thrown = runCatching { runBlocking { client.listAgents() } }.exceptionOrNull()
            thrown.shouldBeInstanceOf<CapabilitiesUnreachableException>()
            client.close()
        }
    })
