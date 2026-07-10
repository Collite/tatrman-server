package org.tatrman.keycloak.auth

import com.typesafe.config.Config
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.apache.Apache
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class KeycloakTokenProvider private constructor(
    private val httpClient: HttpClient,
    private val tokenUrl: String,
    private val clientId: String,
    private val clientSecret: String,
) : TokenProvider {
    private val logger = LoggerFactory.getLogger(KeycloakTokenProvider::class.java)

    override suspend fun getToken(): String {
        logger.debug("Fetching token from Keycloak at {} for {}", tokenUrl, clientId)
        try {
            val response: TokenResponse =
                httpClient
                    .post(tokenUrl) {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(
                            FormDataContent(
                                Parameters.build {
                                    append("client_id", clientId)
                                    append("client_secret", clientSecret)
                                    append("grant_type", "client_credentials")
                                },
                            ),
                        )
                    }.body()
            logger.debug("Successfully fetched token, expires in {} seconds", response.expiresIn)
            return response.accessToken
        } catch (e: Exception) {
            logger.error("Error fetching token from Keycloak: {}", e.message)
            throw e
        }
    }

    companion object {
        fun fromConfig(
            config: Config,
            path: String = "keycloak",
            timeoutMillis: Long = 30_000L,
        ): KeycloakTokenProvider {
            val host = config.getString("$path.host")
            val port =
                if (!config.getString("$path.port").isNullOrEmpty()) {
                    ":" + config.getString("$path.port")
                } else {
                    ""
                }
            val protocol = config.getString("$path.protocol") ?: "http"
            val realm = config.getString("$path.realm")
            val tokenUrl = "$protocol://$host$port/realms/$realm/protocol/openid-connect/token"
            val clientId = config.getString("$path.client-id")
            val clientSecret = config.getString("$path.client-secret")
            return create(tokenUrl, clientId, clientSecret, timeoutMillis)
        }

        fun create(
            tokenUrl: String,
            clientId: String,
            clientSecret: String,
            timeoutMillis: Long = 30_000L,
        ): KeycloakTokenProvider {
            val httpClient =
                HttpClient(Apache) {
                    install(ContentNegotiation) {
                        json(Json)
                    }
                    install(HttpTimeout) {
                        requestTimeoutMillis = timeoutMillis
                        connectTimeoutMillis = timeoutMillis
                        socketTimeoutMillis = timeoutMillis
                    }
                }
            return KeycloakTokenProvider(httpClient, tokenUrl, clientId, clientSecret)
        }
    }
}
