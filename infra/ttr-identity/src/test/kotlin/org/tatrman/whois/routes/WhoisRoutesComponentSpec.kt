package org.tatrman.whois.routes

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.tatrman.whois.repository.UserRepositoryJson
import java.io.File

/**
 * Component-tier spec (mocked: JSON repository over the bundled `whois.json` fixture, no DB).
 * Exercises the public REST surface Argos + ops consume. True e2e against a live Keycloak/PG
 * on K3s is deferred to the separate integration-test suite (planning-conventions §4).
 */
class WhoisRoutesComponentSpec :
    StringSpec({

        fun jsonRepo(): UserRepositoryJson =
            UserRepositoryJson(ConfigFactory.parseString("""whois { jsonFilePath = "" }"""))
                .also { it.load() }

        fun testApp(block: suspend (io.ktor.client.HttpClient) -> Unit) =
            testApplication {
                val repo = jsonRepo()
                val bundleHandler = BundleHandler(repo, File("build/tmp/whois-empty-policies"))
                application {
                    install(ContentNegotiation) { json() }
                    routing { configureRouting(repo, bundleHandler) }
                }
                block(client)
            }

        "GET /health returns ok" {
            testApp { client ->
                val resp = client.get("/health")
                resp.status shouldBe HttpStatusCode.OK
                resp.bodyAsText() shouldContain "ok"
            }
        }

        "GET /ready reports the loaded user count" {
            testApp { client ->
                val resp = client.get("/ready")
                resp.status shouldBe HttpStatusCode.OK
                resp.bodyAsText() shouldContain "\"users\":\"5\""
            }
        }

        "GET /whois by user_id + type resolves the user" {
            testApp { client ->
                val resp = client.get("/whois?user_id=bora-kc-id&user_id_type=KEYCLOAK")
                resp.status shouldBe HttpStatusCode.OK
                resp.bodyAsText() shouldContain "boris.perusic@dolphinconsulting.cz"
            }
        }

        "GET /whois by email resolves the user" {
            testApp { client ->
                val resp = client.get("/whois?email=jirikrov@seznam.cz")
                resp.status shouldBe HttpStatusCode.OK
                resp.bodyAsText() shouldContain "Jiri"
            }
        }

        "GET /whois by internal_id resolves the user" {
            testApp { client ->
                val resp = client.get("/whois?internal_id=3")
                resp.status shouldBe HttpStatusCode.OK
                resp.bodyAsText() shouldContain "Jan"
            }
        }

        "GET /whois by generic id matches across identities" {
            testApp { client ->
                val resp = client.get("/whois?id=ERP005")
                resp.status shouldBe HttpStatusCode.OK
                resp.bodyAsText() shouldContain "pavel.drha@kantheon.example"
            }
        }

        "GET /whois with no query param is a 400" {
            testApp { client ->
                client.get("/whois").status shouldBe HttpStatusCode.BadRequest
            }
        }

        "GET /whois with an invalid user_id_type is a 400" {
            testApp { client ->
                client.get("/whois?user_id=x&user_id_type=BOGUS").status shouldBe HttpStatusCode.BadRequest
            }
        }
    })
