// SPDX-License-Identifier: Apache-2.0
package org.tatrman.capabilities.client

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.tatrman.capabilities.v1.Capability

private val logger = KotlinLogging.logger {}

/**
 * Drop-in startup hook for any service to register itself with kantheon's
 * capabilities-mcp. See `docs/architecture/themis/contracts.md` §4.
 */
object CapabilitiesClient {
    /**
     * Register with capabilities-mcp at [endpoint] and schedule periodic
     * heartbeat at [heartbeatIntervalMs].
     *
     * **Warn-and-continue.** On registration failure (capabilities-mcp
     * unreachable, 5xx, transport error) the call returns immediately with
     * `registrationId = null` and `lastHeartbeatStatus = NEVER_REGISTERED`,
     * and a background coroutine keeps retrying with exponential backoff
     * (1s, 2s, 4s, … cap 60s) so the caller's service ALWAYS starts.
     */
    fun startupRegister(
        capability: Capability,
        endpoint: String,
        heartbeatIntervalMs: Long = 30_000,
        otelTracer: Tracer? = null,
        scope: CoroutineScope? = null,
        httpClient: HttpClient? = null,
        backoffSequence: LongArray = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000, 32_000, 60_000),
    ): CapabilitiesClientHandle {
        val ownsScope = scope == null
        val activeScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ownsClient = httpClient == null
        val client =
            httpClient ?: HttpClient(CIO) {
                install(ContentNegotiation) { json() }
            }

        val handle = CapabilitiesClientHandle(scope = activeScope, ownsScope = ownsScope)

        val registerJob =
            activeScope.launch {
                registerWithBackoff(
                    capability = capability,
                    endpoint = endpoint,
                    client = client,
                    handle = handle,
                    backoffSequence = backoffSequence,
                )
                if (handle.registrationId != null) {
                    launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        heartbeatLoop(endpoint, client, handle, heartbeatIntervalMs)
                    }
                }
            }
        handle.jobs =
            listOf(registerJob).plus(
                if (ownsClient) listOf<Job>(closeOnCancel(activeScope, client)) else emptyList(),
            )
        return handle
    }

    private suspend fun registerWithBackoff(
        capability: Capability,
        endpoint: String,
        client: HttpClient,
        handle: CapabilitiesClientHandle,
        backoffSequence: LongArray,
    ) {
        var attempt = 0
        while (handle.registrationId == null && coroutineScopeStillActive()) {
            try {
                val rid = postRegister(client, endpoint, capability)
                handle.registrationId = rid
                handle.lastHeartbeatStatus = HeartbeatStatus.OK
                logger.info { "registered with capabilities-mcp at $endpoint: registrationId=$rid" }
                return
            } catch (e: Throwable) {
                attempt++
                handle.lastHeartbeatStatus = HeartbeatStatus.FAILED
                val delayMs = backoffSequence[(attempt - 1).coerceAtMost(backoffSequence.size - 1)]
                logger.warn(e) {
                    "register failed (attempt $attempt) at $endpoint; " +
                        "retrying in ${delayMs}ms — service continues regardless"
                }
                delay(delayMs)
            }
        }
    }

    private suspend fun heartbeatLoop(
        endpoint: String,
        client: HttpClient,
        handle: CapabilitiesClientHandle,
        intervalMs: Long,
    ) {
        while (coroutineScopeStillActive()) {
            delay(intervalMs)
            val rid = handle.registrationId ?: continue
            try {
                postHeartbeat(client, endpoint, rid)
                handle.lastHeartbeatStatus = HeartbeatStatus.OK
            } catch (e: Throwable) {
                handle.lastHeartbeatStatus = HeartbeatStatus.STALE
                logger.warn(e) { "heartbeat failed for $rid; will retry on next tick" }
            }
        }
    }

    private suspend fun postRegister(
        client: HttpClient,
        endpoint: String,
        capability: Capability,
    ): String {
        val body =
            buildJsonObject {
                put("capability", CapabilitiesWire.capabilityToJson(capability))
            }
        val resp: HttpResponse =
            client.post("${endpoint.trimEnd('/')}/v1/capabilities/register") {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        check(resp.status == HttpStatusCode.OK) { "register returned ${resp.status}" }
        val parsed: JsonObject = resp.body()
        return parsed["registrationId"]!!.jsonPrimitive.content
    }

    private suspend fun postHeartbeat(
        client: HttpClient,
        endpoint: String,
        registrationId: String,
    ) {
        val resp: HttpResponse =
            client.post("${endpoint.trimEnd('/')}/v1/capabilities/$registrationId/heartbeat")
        check(resp.status == HttpStatusCode.OK) { "heartbeat returned ${resp.status}" }
    }

    private fun closeOnCancel(
        scope: CoroutineScope,
        client: HttpClient,
    ): Job =
        scope.launch {
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                client.close()
            }
        }

    private suspend fun coroutineScopeStillActive(): Boolean =
        kotlinx.coroutines.currentCoroutineContext()[Job]?.isActive ?: true
}
