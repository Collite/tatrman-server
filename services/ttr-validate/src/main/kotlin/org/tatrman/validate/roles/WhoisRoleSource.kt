package org.tatrman.validate.roles

import com.github.benmanes.caffeine.cache.Caffeine
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.identity.domain.UserRecord
import org.tatrman.identity.domain.allRoles
import org.tatrman.identity.domain.keycloakId
import java.time.Duration

/**
 * Looks up the role set whois holds for a **bearer-trusted** Keycloak user id. Returns the
 * enrichment roles (empty when the user is unknown to whois — the bearer floor then stands);
 * throws [RoleSourceUnavailableException] on a transport/parse failure so the caller fails closed.
 */
interface WhoisRoleLookup {
    suspend fun rolesFor(keycloakUserId: String): List<String>
}

/**
 * Enriches the bearer role floor with whois-held roles (the ERP hierarchy the JWT omits). Opt-in
 * via `argos.roleSource = whois`. Identity is never sourced here — `context.user_id` comes from the
 * bearer; whois only widens the role set for that already-trusted id. Results are TTL-cached so the
 * hot path takes the whois hop at most once per user per TTL.
 */
class WhoisRoleSource(
    private val lookup: WhoisRoleLookup,
    cacheTtlSeconds: Long = 300,
    private val bearer: RoleSource = BearerRoleSource(),
) : RoleSource {
    private val cache =
        Caffeine
            .newBuilder()
            .expireAfterWrite(Duration.ofSeconds(cacheTtlSeconds))
            .maximumSize(10_000)
            .build<String, List<String>>()

    override suspend fun resolveRoles(context: PipelineContext): List<String> {
        val bearerRoles = bearer.resolveRoles(context)
        val userId = context.userId
        // No trusted id to key enrichment by → the bearer floor stands (cannot widen blindly).
        if (userId.isBlank()) return bearerRoles
        val enriched = cache.getIfPresent(userId) ?: lookup.rolesFor(userId).also { cache.put(userId, it) }
        if (enriched.isEmpty()) return bearerRoles
        return (bearerRoles + enriched).distinct()
    }
}

/**
 * Default [WhoisRoleLookup] over whois's `GET /whois?user_id=&user_id_type=KEYCLOAK`. Enforces the
 * security envelope: only a record whose KEYCLOAK identity **is** the requested id contributes roles
 * (a record for any other user_id is ignored — whois cannot assert identity for someone else).
 */
class KtorWhoisRoleLookup(
    private val baseUrl: String,
    private val httpClient: HttpClient = defaultClient(),
) : WhoisRoleLookup {
    private val log = LoggerFactory.getLogger(KtorWhoisRoleLookup::class.java)

    override suspend fun rolesFor(keycloakUserId: String): List<String> {
        val records: List<UserRecord> =
            try {
                val resp =
                    httpClient.get("$baseUrl/whois") {
                        parameter("user_id", keycloakUserId)
                        parameter("user_id_type", "KEYCLOAK")
                    }
                if (!resp.status.isSuccess()) {
                    throw RoleSourceUnavailableException("whois returned ${resp.status} for user $keycloakUserId")
                }
                resp.body()
            } catch (c: CancellationException) {
                throw c
            } catch (e: RoleSourceUnavailableException) {
                throw e
            } catch (e: Exception) {
                throw RoleSourceUnavailableException("whois unreachable at $baseUrl: ${e.message}", e)
            }

        // Security envelope: trust only the record that actually IS this keycloak user.
        val match = records.firstOrNull { it.keycloakId == keycloakUserId }
        if (match == null) {
            log.debug("whois returned no record matching keycloak user {} — no enrichment", keycloakUserId)
            return emptyList()
        }
        return match.allRoles
    }

    companion object {
        fun defaultClient(timeoutMillis: Long = 5_000L): HttpClient =
            HttpClient(CIO) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
                install(HttpTimeout) {
                    requestTimeoutMillis = timeoutMillis
                    connectTimeoutMillis = timeoutMillis
                    socketTimeoutMillis = timeoutMillis
                }
            }
    }
}
