package org.tatrman.kantheon.testkit

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.kantheon.testkit.containers.Containers
import org.tatrman.kantheon.testkit.wiremock.WireMockAdmin
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Stage 1.2 T4 — proves the WireMock-as-container runtime fixture-load path that
 * the integration tier reuses in-cluster: boot an empty WireMock container, push
 * mappings via `/__admin/mappings/import`, hit the stubbed endpoint, verify the
 * request journal, and confirm `reset` clears it.
 */
@Tags("component")
class WireMockContainerHelperSpec :
    StringSpec({
        "fixtures load at runtime, the stub answers, and reset clears the journal" {
            Containers.wiremock().use { container ->
                container.start()
                val base = "http://${container.host}:${container.getMappedPort(Containers.WIREMOCK_PORT)}"
                val admin = WireMockAdmin(base)
                val http = HttpClient.newHttpClient()

                admin.reset()
                admin.importMappingsFromResource("wiremock/testkit/hello/mappings.json")

                val res =
                    http.send(
                        HttpRequest.newBuilder(URI.create("$base/hello")).GET().build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                res.statusCode() shouldBe 200
                res.body() shouldContain "world"

                // The journal recorded the call we just made.
                admin.requestCount() shouldBeGreaterThanOrEqual 1

                // Reset wipes mappings + journal — no leak across scenarios.
                admin.reset()
                admin.requestCount() shouldBe 0
            }
        }

        "the pinned Postgres factory boots and exposes a JDBC URL" {
            Containers.postgres().use { pg ->
                pg.start()
                pg.isRunning shouldBe true
                pg.jdbcUrl shouldContain "jdbc:postgresql://"
            }
        }
    })
