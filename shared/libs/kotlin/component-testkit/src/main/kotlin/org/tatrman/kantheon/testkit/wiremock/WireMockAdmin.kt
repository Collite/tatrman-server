package org.tatrman.kantheon.testkit.wiremock

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Drives a running WireMock instance over its `/__admin` API — the exact
 * fixture-load protocol the integration tier reuses in-cluster (testing
 * contracts §3.2). In the component tier the instance is a Testcontainers
 * container ([org.tatrman.kantheon.testkit.containers.Containers.wiremock]); in
 * the integration tier it is the in-cluster `wiremock.<ns>.svc`. Only the base
 * URL differs.
 *
 * Fixtures are **pushed at runtime** (never baked into the image): mappings live
 * with the test as JSON, loaded via `POST /__admin/mappings/import`, with a
 * `POST /__admin/reset` between scenarios so nothing leaks across suites.
 *
 * @param baseUrl the WireMock root, e.g. `http://localhost:32873` (no trailing slash).
 */
class WireMockAdmin(
    baseUrl: String,
) {
    private val adminBase = baseUrl.trimEnd('/') + "/__admin"
    private val http: HttpClient = HttpClient.newHttpClient()

    /** Clear all stub mappings *and* the request journal. Call between scenarios. */
    fun reset() {
        send(post("$adminBase/reset", ""))
    }

    /**
     * Import stub mappings. [mappingsJson] is either a single WireMock stub
     * object or an `{"mappings":[...]}` envelope — both accepted by `/import`.
     */
    fun importMappings(mappingsJson: String) {
        val res = send(post("$adminBase/mappings/import", mappingsJson))
        check(res.statusCode() in 200..299) {
            "WireMock mappings import failed (${res.statusCode()}): ${res.body()}"
        }
    }

    /** Load mappings from a classpath resource (e.g. `wiremock/<context>/<scenario>/mappings.json`). */
    fun importMappingsFromResource(resourcePath: String) {
        val json =
            this::class.java.classLoader
                .getResourceAsStream(resourcePath)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: error("WireMock fixture resource not found on classpath: $resourcePath")
        importMappings(json)
    }

    /** Number of requests in the journal — `meta.total` from `GET /__admin/requests`. */
    fun requestCount(): Int {
        val res = send(get("$adminBase/requests"))
        val root = Json.parseToJsonElement(res.body()).jsonObject
        return root["meta"]
            ?.jsonObject
            ?.get("total")
            ?.jsonPrimitive
            ?.intOrNull
            ?: 0
    }

    private fun get(url: String): HttpRequest = HttpRequest.newBuilder(URI.create(url)).GET().build()

    private fun post(
        url: String,
        body: String,
    ): HttpRequest =
        HttpRequest
            .newBuilder(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

    private fun send(req: HttpRequest): HttpResponse<String> = http.send(req, HttpResponse.BodyHandlers.ofString())
}
