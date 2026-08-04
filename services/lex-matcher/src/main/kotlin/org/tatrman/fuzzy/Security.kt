// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy

import com.typesafe.config.Config
import io.grpc.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import org.slf4j.LoggerFactory
import shared.ktor.adminApiKeys as sharedAdminApiKeys
import shared.ktor.adminOnly as sharedAdminOnly
import shared.ktor.isAdminAuthorized as sharedIsAdminAuthorized

val USER_ID_ATTRIBUTE = AttributeKey<String>("userId")

fun Application.configureSecurity(config: Config) {
    val securityEnabled = config.getBoolean("security.enabled")

    if (!securityEnabled) {
        log.info("Security is disabled")
        return
    }

    val serviceName = config.getString("security.service.name")
    val logger = LoggerFactory.getLogger("security")

    intercept(ApplicationCallPipeline.Plugins) {
        val userId = call.request.headers["X-User-ID"]
        if (userId != null) {
            call.attributes.put(USER_ID_ATTRIBUTE, userId)
        }

        logger.info(
            "request method={} uri={} userId={}",
            call.request.httpMethod.value,
            call.request.uri,
            userId ?: "none",
        )
    }
}

fun ApplicationCall.getUserId(): String? = attributes.getOrNull(USER_ID_ATTRIBUTE)

/**
 * S-3 admin authorization (RG-P2.S2.T6): operator endpoints (`/refresh`) require an `admin` role —
 * never unauthenticated in the open offering.
 *
 * ⚑ RV-P1.6: the rule MOVED to `shared.ktor.AdminGate` and these three are thin delegates. The
 * grounding kernels (chrono/money/geo) grew a `/refresh` of their own, and a security decision
 * copied per service is a security decision that drifts per service. lex-matcher keeps the names
 * so its routes and its `AdminGateTest` are unchanged; the behaviour is now single-sourced.
 */
fun isAdminAuthorized(
    roles: List<String>,
    apiKey: String?,
    adminApiKeys: List<String>,
): Boolean = sharedIsAdminAuthorized(roles, apiKey, adminApiKeys)

fun adminApiKeys(config: Config): List<String> = sharedAdminApiKeys(config)

fun Route.adminOnly(
    config: Config,
    build: Route.() -> Unit,
) = sharedAdminOnly(config, build)

fun Route.secured(
    config: Config,
    build: Route.() -> Unit,
) {
    val securityEnabled = config.getBoolean("security.enabled")
    val validApiKeys =
        try {
            config
                .getString("security.api-keys")
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        } catch (e: com.typesafe.config.ConfigException) {
            emptyList()
        }

    if (securityEnabled) {
        intercept(ApplicationCallPipeline.Call) {
            val apiKey = call.request.headers["X-API-Key"]
            val authHeader = call.request.headers["Authorization"]

            val hasValidApiKey = apiKey != null && validApiKeys.contains(apiKey)
            val hasValidAuth = authHeader != null && authHeader.startsWith("Bearer ")

            if (!hasValidApiKey && !hasValidAuth) {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid API Key / Authorization")
                finish()
            }
        }
    }
    build()
}

class SecurityInterceptor(
    private val config: Config,
) : ServerInterceptor {
    private val logger = LoggerFactory.getLogger(SecurityInterceptor::class.java)

    override fun <ReqT : Any, RespT : Any> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        val securityEnabled = config.getBoolean("security.enabled")
        if (!securityEnabled) return next.startCall(call, headers)

        val validApiKeys =
            try {
                config
                    .getString("security.api-keys")
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            } catch (e: com.typesafe.config.ConfigException) {
                emptyList()
            }

        val userIdKey = Metadata.Key.of("X-User-ID", Metadata.ASCII_STRING_MARSHALLER)
        val apiKeyMetadataKey = Metadata.Key.of("X-API-Key", Metadata.ASCII_STRING_MARSHALLER)
        val authMetadataKey = Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER)

        val userId = headers.get(userIdKey)
        val apiKey = headers.get(apiKeyMetadataKey)
        val authHeader = headers.get(authMetadataKey)

        logger.info(
            "gRPC request method={} uri={} userId={}",
            call.methodDescriptor?.fullMethodName ?: "unknown",
            "/",
            userId ?: "none",
        )

        val hasValidApiKey = apiKey != null && validApiKeys.contains(apiKey)
        val hasValidAuth = authHeader != null && authHeader.startsWith("Bearer ")

        if (!hasValidApiKey && !hasValidAuth) {
            call.close(Status.UNAUTHENTICATED.withDescription("Missing or invalid API Key / Authorization"), headers)
            return object : ServerCall.Listener<ReqT>() {}
        }

        return next.startCall(call, headers)
    }
}
