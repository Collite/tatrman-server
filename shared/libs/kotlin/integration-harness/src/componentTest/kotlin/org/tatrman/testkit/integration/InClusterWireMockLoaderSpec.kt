// SPDX-License-Identifier: Apache-2.0
package org.tatrman.testkit.integration

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.testkit.containers.Containers
import org.tatrman.testkit.wiremock.WireMockAdmin
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Stage 2.1 T5 — proves the integration-tier WireMock fixture-load path that runs
 * in-cluster, but against a WireMock **container** standing in for the in-cluster
 * instance (no cluster needed). It drives the load through `ContextHandle` exactly
 * as an integration spec will: `WireMockAdmin(handle.wireMockAdmin)` →
 * `importMappingsFromResource(...)` → assert stub answers → verify journal →
 * reset. The only difference in-cluster is the resolved base URL.
 */
@Tags("component")
class InClusterWireMockLoaderSpec :
    StringSpec({
        "ContextHandle.wireMockAdmin drives the runtime fixture-load against a WireMock container" {
            Containers.wiremock().use { container ->
                container.start()
                val base = "http://${container.host}:${container.getMappedPort(Containers.WIREMOCK_PORT)}"

                // A read-only reader that resolves the `wiremock` Service to the container.
                val reader =
                    object : ClusterReader {
                        override fun resolveNamespace(contextName: String) = "ns"

                        override fun readinessChecks(namespace: String) = emptyList<ReadinessCheck>()

                        override fun isReady(
                            namespace: String,
                            check: ReadinessCheck,
                        ) = true

                        override fun serviceBaseUrl(
                            namespace: String,
                            service: String,
                        ) = if (service == "wiremock") base else null
                    }
                val handle = ContextHandle("query-runquery", "ns", reader)

                val admin = WireMockAdmin(handle.wireMockAdmin)
                admin.reset()
                admin.importMappingsFromResource("wiremock/query-runquery/healthz/mappings.json")

                val http = HttpClient.newHttpClient()
                val res =
                    http.send(
                        HttpRequest.newBuilder(URI.create("$base/healthz")).GET().build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                res.statusCode() shouldBe 200
                res.body() shouldContain "ok"

                admin.requestCount() shouldBeGreaterThanOrEqual 1
                admin.reset()
                admin.requestCount() shouldBe 0
            }
        }
    })
