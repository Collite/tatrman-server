package org.tatrman.identity.client

import org.tatrman.keycloak.auth.TokenProvider
import org.tatrman.identity.domain.UserSource
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.apache.Apache
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class KeycloakClient(
    private val baseUrl: String,
    private val realm: String,
    private val tokenProvider: TokenProvider,
    private val httpClient: HttpClient =
        HttpClient(Apache) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            install(Auth) {
                bearer {
                    loadTokens {
                        val token = runBlocking { tokenProvider.getToken() }
                        BearerTokens(token, "")
                    }
                }
            }
        },
    private val timeoutMillis: Long = 30_000L,
) : UserSourceClient {
    private val logger = LoggerFactory.getLogger("KeycloakClient")
    private val adminUrl = "$baseUrl/admin/realms/$realm"

    override val source: UserSource = UserSource.KEYCLOAK

    suspend fun getUserById(userId: String): Result<GenericUser> =
        try {
            logger.debug("Fetching user by id: {}", userId)
            val user: GenericUser =
                httpClient
                    .get("$adminUrl/users/$userId") {
                        contentType(ContentType.Application.Json)
                    }.body()
            Result.success(user)
        } catch (e: Exception) {
            logger.error("Error fetching user {}: {}", userId, e.message)
            Result.failure(e)
        }

    suspend fun getUserByEmail(email: String): Result<GenericUser> =
        try {
            logger.debug("Fetching user by email: {}", email)
            val users: List<GenericUser> =
                httpClient
                    .get("$adminUrl/users") {
                        parameter("email", email)
                        parameter("exact", "true")
                        contentType(ContentType.Application.Json)
                    }.body()
            if (users.isNotEmpty()) {
                Result.success(users.first())
            } else {
                Result.failure(NoSuchElementException("User not found with email: $email"))
            }
        } catch (e: Exception) {
            logger.error("Error fetching user by email {}: {}", email, e.message)
            Result.failure(e)
        }

    override suspend fun fetchUsers(): List<GenericUser> {
        logger.debug("Fetching all users from Keycloak realm: {}, full url: {}", realm, adminUrl)
        return httpClient
            .get("$adminUrl/users") {
                parameter("max", 1000)
                contentType(ContentType.Application.Json)
            }.body()
    }

    override suspend fun fetchRoles(): List<GenericRole> {
        logger.debug("Fetching all roles from Keycloak realm: {}", realm)
        val roleRepresentations: List<KeycloakRoleRepresentation> =
            httpClient
                .get("$adminUrl/roles") {
                    parameter("max", 1000)
                    contentType(ContentType.Application.Json)
                }.body()
        return roleRepresentations.map { role ->
            GenericRole(
                id = role.id ?: role.name,
                code = role.name,
                description = role.description,
                source = UserSource.KEYCLOAK,
            )
        }
    }

    override suspend fun fetchRoleHierarchy(): List<Pair<String, String>> {
        logger.debug("Fetching role hierarchy from Keycloak realm: {}", realm)
        val allRoles: List<KeycloakRoleRepresentation> =
            httpClient
                .get("$adminUrl/roles") {
                    parameter("max", 1000)
                    contentType(ContentType.Application.Json)
                }.body()

        val hierarchy = mutableListOf<Pair<String, String>>()
        for (role in allRoles) {
            try {
                val composites: List<KeycloakRoleRepresentation> =
                    httpClient
                        .get("$adminUrl/roles/${role.name}/composites") {
                            contentType(ContentType.Application.Json)
                        }.body()
                for (composite in composites) {
                    hierarchy.add(composite.name to role.name)
                }
            } catch (e: Exception) {
                logger.warn("Could not fetch composites for role {}: {}", role.name, e.message)
            }
        }
        logger.debug("Fetched {} role hierarchy entries", hierarchy.size)
        return hierarchy
    }

    override suspend fun fetchUserRoleMappings(): List<Pair<String, String>> {
        logger.debug("Fetching user-role mappings from Keycloak realm: {}", realm)
        val users = fetchUsers()
        val mappings = mutableListOf<Pair<String, String>>()

        for (user in users) {
            try {
                val roleMappings: List<KeycloakRoleRepresentation> =
                    httpClient
                        .get("$adminUrl/users/${user.id}/role-mappings") {
                            contentType(ContentType.Application.Json)
                        }.body()
                for (role in roleMappings) {
                    mappings.add(user.id to (role.id ?: role.name))
                }
            } catch (e: Exception) {
                logger.warn("Could not fetch role mappings for user {}: {}", user.id, e.message)
            }
        }
        logger.debug("Fetched {} user-role mappings", mappings.size)
        return mappings
    }

    fun close() {
        httpClient.close()
    }

    companion object {
        fun fromConfig(
            config: com.typesafe.config.Config,
            tokenProvider: TokenProvider,
            path: String = "keycloak",
            timeoutMillis: Long = 30_000L,
        ): KeycloakClient {
            val keycloakConfig = config.getConfig(path)
            val host = keycloakConfig.getString("host")
            val port = keycloakConfig.getString("port")
            val protocol = keycloakConfig.getString("protocol")
            val realm = keycloakConfig.getString("realm")
            val baseUrl = "$protocol://$host:$port"
            val httpClient =
                HttpClient(Apache) {
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true })
                    }
                    install(HttpTimeout) {
                        requestTimeoutMillis = timeoutMillis
                        connectTimeoutMillis = timeoutMillis
                        socketTimeoutMillis = timeoutMillis
                    }
                    install(Auth) {
                        bearer {
                            loadTokens {
                                val token = runBlocking { tokenProvider.getToken() }
                                BearerTokens(token, "")
                            }
                        }
                    }
                }
            return KeycloakClient(baseUrl, realm, tokenProvider, httpClient, timeoutMillis)
        }
    }
}

@Serializable
private data class KeycloakRoleRepresentation(
    val id: String? = null,
    val name: String,
    val description: String? = null,
)
