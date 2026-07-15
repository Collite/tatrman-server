// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.admin

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.llmgateway.governance.KeyMint
import org.tatrman.llmgateway.module
import org.testcontainers.containers.PostgreSQLContainer
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.Date

/**
 * LG-P4·S3·T2/T6 — the admin key API through the wire (contracts §1.8), Keycloak-JWT gated. No token → 401;
 * a token without the admin role → 403; issue → 201 with the plaintext exactly once; list never leaks
 * hash/plaintext; unknown team → 400; DELETE → 204 and a key revoked before first use stops validating.
 * RS256 keys are generated in-test; real Postgres (Testcontainers).
 */
class AdminApiSpec :
    StringSpec({

        val pgc =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("llmgateway")
                .withUsername("tatrman")
                .withPassword("tatrman")

        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val alg = Algorithm.RSA256(kp.public as RSAPublicKey, kp.private as RSAPrivateKey)
        val iss = "https://kc/realms/tatrman"
        val aud = "llm-gateway"

        fun token(roles: List<String>): String =
            JWT
                .create()
                .withSubject("admin")
                .withIssuer(iss)
                .withAudience(aud)
                .withExpiresAt(Date(System.currentTimeMillis() + 3_600_000))
                .withClaim("realm_access", mapOf<String, Any>("roles" to roles))
                .sign(alg)

        val adminJwt = token(listOf("llm-gateway-admin"))
        lateinit var cfg: Config

        beforeSpec {
            pgc.start()
            cfg =
                ConfigFactory
                    .parseString(
                        """
                        db { enabled = true, host = "${pgc.host}", port = "${pgc.firstMappedPort}", database = "${pgc.databaseName}", user = "${pgc.username}", password = "${pgc.password}" }
                        admin {
                            enabled = true
                            issuer = "$iss"
                            audience = "$aud"
                            role = "llm-gateway-admin"
                            realmPublicKey = "${Base64.getEncoder().encodeToString(kp.public.encoded)}"
                        }
                        """.trimIndent(),
                    ).withFallback(ConfigFactory.load())
                    .resolve()
        }
        afterSpec { pgc.stop() }

        fun issueBody(
            team: String,
            name: String,
        ) = """{"team":"$team","name":"$name"}"""

        "admin key lifecycle: auth gates, issue-once, no-leak list, unknown team, revoke" {
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(cfg) } // governance.yaml teams (golem…) upserted at boot

                // no token → 401
                client.post("/admin/keys") { setBody(issueBody("golem", "k")) }.status shouldBe
                    HttpStatusCode.Unauthorized

                // token without the admin role → 403
                client
                    .post("/admin/keys") {
                        header(HttpHeaders.Authorization, "Bearer ${token(listOf("plain-user"))}")
                        setBody(issueBody("golem", "k"))
                    }.status shouldBe HttpStatusCode.Forbidden

                // unknown team → 400
                client
                    .post("/admin/keys") {
                        header(HttpHeaders.Authorization, "Bearer $adminJwt")
                        setBody(issueBody("no-such-team", "k"))
                    }.status shouldBe HttpStatusCode.BadRequest

                // issue → 201 with the plaintext exactly once, in ttrk- format
                val created =
                    client.post("/admin/keys") {
                        header(HttpHeaders.Authorization, "Bearer $adminJwt")
                        setBody(issueBody("golem", "golem-prod"))
                    }
                created.status shouldBe HttpStatusCode.Created
                val createdJson = Json.parseToJsonElement(created.bodyAsText()).jsonObject
                val plaintext = createdJson["key"]!!.jsonPrimitive.content
                plaintext shouldMatch KeyMint.FORMAT
                val keyId = createdJson["id"]!!.jsonPrimitive.content

                // list never leaks plaintext or hash
                val listed =
                    client
                        .get("/admin/keys?team=golem") { header(HttpHeaders.Authorization, "Bearer $adminJwt") }
                        .bodyAsText()
                listed shouldContain "golem-prod"
                listed shouldNotContain plaintext
                listed shouldNotContain KeyMint.hash(plaintext)
                Json
                    .parseToJsonElement(listed)
                    .jsonObject["data"]!!
                    .jsonArray
                    .first()
                    .jsonObject
                    .containsKey("key_hash") shouldBe false

                // the issued key works on the data plane immediately
                client
                    .get("/v1/models") { header(HttpHeaders.Authorization, "Bearer $plaintext") }
                    .status shouldBe HttpStatusCode.OK

                // DELETE → 204, and a key revoked before its first use never validates
                val doomed =
                    Json
                        .parseToJsonElement(
                            client
                                .post("/admin/keys") {
                                    header(HttpHeaders.Authorization, "Bearer $adminJwt")
                                    setBody(issueBody("golem", "doomed"))
                                }.bodyAsText(),
                        ).jsonObject
                val doomedId = doomed["id"]!!.jsonPrimitive.content
                val doomedKey = doomed["key"]!!.jsonPrimitive.content
                client
                    .delete("/admin/keys/$doomedId") { header(HttpHeaders.Authorization, "Bearer $adminJwt") }
                    .status shouldBe HttpStatusCode.NoContent
                client
                    .get("/v1/models") { header(HttpHeaders.Authorization, "Bearer $doomedKey") }
                    .status shouldBe HttpStatusCode.Unauthorized

                // sanity: revoking the same id again is idempotent (still 204)
                client
                    .delete("/admin/keys/$keyId") { header(HttpHeaders.Authorization, "Bearer $adminJwt") }
                    .status shouldBe HttpStatusCode.NoContent
            }
        }
    })
