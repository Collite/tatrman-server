package org.tatrman.capabilities.client

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.tatrman.capabilities.v1.AgentKind
import org.tatrman.capabilities.v1.Capability
import org.tatrman.capabilities.v1.ToolCapability
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private fun toolCap(id: String): Capability =
    Capability
        .newBuilder()
        .setTool(
            ToolCapability
                .newBuilder()
                .setCapabilityId(id)
                .setCategory(id.substringBefore(':') + ".*")
                .setVersion(id.substringAfter(':', "v1")),
        ).build()

private fun agentCap(
    id: String,
    kind: AgentKind,
): Capability =
    Capability
        .newBuilder()
        .setAgent(
            org.tatrman.capabilities.v1.AgentCapability
                .newBuilder()
                .setAgentId(id)
                .setAgentKind(kind),
        ).build()

private suspend fun eventually(
    timeout: kotlin.time.Duration,
    check: () -> Unit,
) {
    val deadline = System.currentTimeMillis() + timeout.inWholeMilliseconds
    var last: Throwable? = null
    while (System.currentTimeMillis() < deadline) {
        try {
            check()
            return
        } catch (e: Throwable) {
            last = e
            kotlinx.coroutines.delay(50.milliseconds.inWholeMilliseconds)
        }
    }
    throw last ?: AssertionError("eventually timed out")
}

class CapabilitiesClientSpec :
    StringSpec({

        lateinit var wm: WireMockServer

        beforeTest {
            wm = WireMockServer(wireMockConfig().dynamicPort())
            wm.start()
        }

        afterTest {
            wm.stop()
        }

        "startupRegister succeeds + schedules heartbeat" {
            wm.stubFor(
                post(urlPathMatching("/v1/capabilities/register"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""{"registrationId":"abc","messages":[]}"""),
                    ),
            )
            wm.stubFor(
                post(urlPathMatching("/v1/capabilities/abc/heartbeat"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""{"acceptedAt":"2026-05-28T12:00:00Z","messages":[]}"""),
                    ),
            )

            val handle =
                CapabilitiesClient.startupRegister(
                    capability = toolCap("theseus.query:v1"),
                    endpoint = "http://localhost:${wm.port()}",
                    heartbeatIntervalMs = 50,
                )

            runBlocking {
                withTimeout(3.seconds) {
                    eventually(3.seconds) {
                        handle.registrationId shouldBe "abc"
                        handle.lastHeartbeatStatus shouldBe HeartbeatStatus.OK
                        val received =
                            wm.findAll(postRequestedFor(urlPathMatching("/v1/capabilities/abc/heartbeat"))).size
                        received shouldBeGreaterThanOrEqual 1
                    }
                }
            }
            handle.shutdown()
        }

        "startupRegister with unreachable endpoint warns-and-continues" {
            val handle =
                CapabilitiesClient.startupRegister(
                    capability = agentCap("pythia", AgentKind.INVESTIGATOR),
                    // port 1 is unreachable
                    endpoint = "http://localhost:1",
                    heartbeatIntervalMs = 50,
                    backoffSequence = longArrayOf(50, 50, 50),
                )

            runBlocking {
                // Sleep one tick so the background coroutine has a chance to attempt once.
                kotlinx.coroutines.delay(300)
            }
            handle.registrationId.shouldBeNull()
            handle.lastHeartbeatStatus shouldBeIn
                listOf(
                    HeartbeatStatus.NEVER_REGISTERED,
                    HeartbeatStatus.FAILED,
                )
            handle.shutdown()
        }

        "exponential backoff retries register on transient failure" {
            val scenario = "register-retry"
            wm.stubFor(
                post(urlPathMatching("/v1/capabilities/register"))
                    .inScenario(scenario)
                    .whenScenarioStateIs(STARTED)
                    .willReturn(aResponse().withStatus(500))
                    .willSetStateTo("ok"),
            )
            wm.stubFor(
                post(urlPathMatching("/v1/capabilities/register"))
                    .inScenario(scenario)
                    .whenScenarioStateIs("ok")
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""{"registrationId":"abc","messages":[]}"""),
                    ),
            )
            wm.stubFor(
                post(urlPathMatching("/v1/capabilities/abc/heartbeat"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""{"acceptedAt":"2026-05-28T12:00:00Z","messages":[]}"""),
                    ),
            )

            val handle =
                CapabilitiesClient.startupRegister(
                    capability = toolCap("theseus.query:v1"),
                    endpoint = "http://localhost:${wm.port()}",
                    heartbeatIntervalMs = 1_000,
                    backoffSequence = longArrayOf(50, 100, 200),
                )

            runBlocking {
                withTimeout(3.seconds) {
                    eventually(3.seconds) {
                        handle.registrationId shouldBe "abc"
                    }
                }
            }
            handle.shutdown()
        }
    })
