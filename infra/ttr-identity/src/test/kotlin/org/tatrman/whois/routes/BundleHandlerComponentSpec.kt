package org.tatrman.whois.routes

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.tatrman.whois.repository.UserRepositoryJson
import java.io.ByteArrayInputStream
import java.nio.file.Files

/**
 * Component-tier spec for the OPA bundle server. Drives a real policies directory (rego + json)
 * through the tar.gz builder and asserts the bundle shape — manifest, merged data.json, and the
 * preserved .rego policy. JSON repository (no DB) supplies the flat-roles hierarchy.
 */
class BundleHandlerComponentSpec :
    StringSpec({

        fun jsonRepo(): UserRepositoryJson =
            UserRepositoryJson(ConfigFactory.parseString("""whois { jsonFilePath = "" }"""))
                .also { it.load() }

        fun tarEntryNames(gz: ByteArray): List<String> {
            val names = mutableListOf<String>()
            TarArchiveInputStream(GzipCompressorInputStream(ByteArrayInputStream(gz))).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    names.add(entry.name)
                    entry = tar.nextEntry
                }
            }
            return names
        }

        "GET /bundle/KEYCLOAK/roles.tar.gz returns a well-formed OPA bundle" {
            val policiesDir = Files.createTempDirectory("whois-policies").toFile()
            policiesDir.resolve("authz.rego").writeText("package erpsql.authz\n\ndefault allow = false\n")
            policiesDir.resolve("extra.json").writeText("""{ "table_permissions": { "orders": ["read"] } }""")

            testApplication {
                val repo = jsonRepo()
                application {
                    install(ContentNegotiation) { json() }
                    routing { configureRouting(repo, BundleHandler(repo, policiesDir)) }
                }
                val resp = client.get("/bundle/KEYCLOAK/roles.tar.gz")
                resp.status shouldBe HttpStatusCode.OK
                val names = tarEntryNames(resp.bodyAsBytes())
                names shouldContainAll listOf(".manifest", "data.json", "authz.rego")
            }
        }

        "GET /bundle/{type} rejects an unknown type with 400" {
            val policiesDir = Files.createTempDirectory("whois-policies-empty").toFile()
            testApplication {
                val repo = jsonRepo()
                application {
                    install(ContentNegotiation) { json() }
                    routing { configureRouting(repo, BundleHandler(repo, policiesDir)) }
                }
                client.get("/bundle/BOGUS/roles.tar.gz").status shouldBe HttpStatusCode.BadRequest
            }
        }
    })
