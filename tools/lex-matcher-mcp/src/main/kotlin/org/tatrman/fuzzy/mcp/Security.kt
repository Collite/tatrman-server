// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.mcp

import com.typesafe.config.Config
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.*
import org.slf4j.LoggerFactory

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
        if (call.request.httpMethod == HttpMethod.Options) {
            return@intercept
        }

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

fun ApplicationCall.getUserId(): String? = attributes.getOrNull(USER_ID_ATTRIBUTE)
