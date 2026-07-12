// SPDX-License-Identifier: Apache-2.0
package org.tatrman.identity.routes

import org.tatrman.identity.domain.UserSource
import org.tatrman.identity.repository.UserRepositoryPort
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("WhoisRoutes")

class WhoisHandler(
    private val userRepository: UserRepositoryPort,
) {
    suspend fun getWhois(call: ApplicationCall) {
        val internalId = call.parameters["internal_id"]
        val userId = call.parameters["user_id"]
        val userIdType = call.parameters["user_id_type"]
        val email = call.parameters["email"]
        val id = call.parameters["id"]

        val result =
            when {
                internalId != null -> {
                    logger.debug("Looking up user by internal_id: {}", internalId)
                    val user = userRepository.findByInternalId(internalId.toLongOrNull() ?: 0)
                    if (user != null) listOf(user) else emptyList()
                }
                userId != null && userIdType != null -> {
                    logger.debug("Looking up user by user_id: {} and type: {}", userId, userIdType)
                    val type =
                        try {
                            UserSource.valueOf(userIdType.uppercase())
                        } catch (e: Exception) {
                            null
                        }
                    if (type != null) {
                        val user = userRepository.findByUserId(type, userId)
                        if (user != null) listOf(user) else emptyList()
                    } else {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            buildJsonObject { put("error", JsonPrimitive("Invalid user_id_type")) },
                        )
                        return
                    }
                }
                email != null -> {
                    logger.debug("Looking up user by email: {}", email)
                    val user = userRepository.findByEmail(email)
                    if (user != null) listOf(user) else emptyList()
                }
                id != null -> {
                    logger.debug("Looking up user by generic id: {}", id)
                    userRepository.searchById(id)
                }
                else -> {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        buildJsonObject {
                            put(
                                "error",
                                JsonPrimitive(
                                    "Missing query param. Use ?internal_id=, " +
                                        "?user_id&user_id_type=, ?email=, or ?id=",
                                ),
                            )
                        },
                    )
                    return
                }
            }

        logger.debug("Found {} users", result.size)
        call.respond(HttpStatusCode.OK, result)
    }
}

fun Route.configureRouting(
    userRepository: UserRepositoryPort,
    bundleHandler: BundleHandler,
) {
    val handler = WhoisHandler(userRepository)

    get("/health") {
        call.respond(buildJsonObject { put("status", JsonPrimitive("ok")) })
    }

    get("/ready") {
        val users = userRepository.getAllUsers()
        call.respond(
            buildJsonObject {
                put("status", JsonPrimitive("ready"))
                put("users", JsonPrimitive(users.size.toString()))
            },
        )
    }

    get("/whois") {
        handler.getWhois(call)
    }

    get("/bundle/{type}/roles.tar.gz") {
        bundleHandler.getBundle(call, call.parameters["type"] ?: "")
    }
}
