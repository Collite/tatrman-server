// SPDX-License-Identifier: Apache-2.0
package shared.ktor

import io.ktor.http.*

data class KtorServerConfig(
    val serviceName: String,
    val serverPort: Int,
    val engine: KtorEngine = KtorEngine.CIO,
    val corsAllowedHosts: List<String> = defaultCorsHosts(),
    val corsConfig: CorsConfig = CorsConfig(),
    val jsonConfig: JsonConfig = JsonConfig(),
    val telemetryEnabled: Boolean = true,
    val callLoggingConfig: CallLoggingConfig? = null,
    val forwardedHeaderEnabled: Boolean = false,
    // Netty only; ignored by the CIO branch (CIO has no equivalent knob and is governed by
    // connectionIdleTimeoutSeconds instead).
    //
    // Ktor's own default is 10s, and it applies to any response whose write is still PENDING —
    // which for a streaming response (SSE, chunked) means it caps TIME-TO-FIRST-BYTE. A stream
    // slower than that to produce its first byte has its socket closed with no status line at
    // all. That took the Hartland demo down on 2026-07-29: a cold Themis resolve needs ~19-28s,
    // and Netty reaped the connection at exactly 10.0s while the answer was still being computed.
    //
    // 180s (Bora, 2026-07-29) sits far above every legitimate inter-frame gap in the estate —
    // 36x the 5s SSE heartbeat — while still reaping a wedged connection rather than leaking it.
    // Chosen deliberately generous: the cost of it being too HIGH is a stuck socket held ~3min;
    // the cost of it being too LOW is a truncated answer the application had already computed
    // correctly. Those are not symmetric.
    //
    // See project/server/features/stream-timeouts/ for the full write-up.
    val responseWriteTimeoutSeconds: Int = 180,
) {
    init {
        // Not a range check for tidiness — a footgun guard. Netty's WriteTimeoutHandler maps any
        // value <= 0 to "never schedule a timeout" WITHOUT throwing, and Ktor installs that
        // handler unconditionally (unlike the read timeout, which it guards with `> 0`). So `0`
        // or a typo'd `-1` would silently disable the reaper for every Netty service on this
        // bootstrap, trading a truncation bug for a connection leak, with nothing in any log to
        // say so. Measured on Ktor 3.2.3 / Netty 4.2.9 — ST-P2·S1·T1.
        require(responseWriteTimeoutSeconds > 0) {
            "responseWriteTimeoutSeconds must be > 0 (got $responseWriteTimeoutSeconds). " +
                "Netty treats <= 0 as 'disable the write timeout entirely', which leaks wedged " +
                "connections instead of reaping them. To allow a slow first byte, raise the value " +
                "instead — it caps time-to-first-byte for streaming responses."
        }
    }
}

fun defaultCorsHosts(): List<String> =
    System
        .getenv("KTOR_CORS_ALLOWED_HOSTS")
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: listOf("localhost:5173", "localhost:7010")

data class CorsConfig(
    val allowCredentials: Boolean = true,
    val allowNonSimpleContentTypes: Boolean = true,
    val allowedMethods: List<HttpMethod> = defaultAllowedMethods(),
    val allowedHeaders: List<String> = defaultAllowedHeaders(),
    val exposedHeaders: List<String> = defaultExposedHeaders(),
)

private fun defaultAllowedMethods(): List<HttpMethod> =
    listOf(
        HttpMethod.Options,
        HttpMethod.Get,
        HttpMethod.Post,
        HttpMethod.Put,
        HttpMethod.Delete,
        HttpMethod.Patch,
    )

private fun defaultAllowedHeaders(): List<String> =
    listOf(
        HttpHeaders.Authorization,
        HttpHeaders.ContentType,
        HttpHeaders.Accept,
        HttpHeaders.AccessControlAllowOrigin,
        "X-User-Id",
        "X-Request-Id",
    )

private fun defaultExposedHeaders(): List<String> = emptyList()

data class JsonConfig(
    val prettyPrint: Boolean = true,
    val isLenient: Boolean = true,
    val encodeDefaults: Boolean = true,
    val ignoreUnknownKeys: Boolean = true,
)

data class CallLoggingConfig(
    val level: org.slf4j.event.Level = org.slf4j.event.Level.INFO,
    val customFormat: ((io.ktor.server.request.ApplicationRequest) -> String)? = null,
)
