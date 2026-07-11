package org.tatrman.fuzzy.api

import com.typesafe.config.Config
import org.tatrman.fuzzy.core.AlgorithmType
import org.tatrman.fuzzy.core.CascadeStep
import org.tatrman.fuzzy.core.FuzzyMatcher
import org.tatrman.fuzzy.core.cascadeFrom
import org.tatrman.fuzzy.secured
import org.tatrman.fuzzy.core.StringRepository
import org.tatrman.fuzzy.telemetry.FuzzyTelemetry
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.request.receive
import io.ktor.server.response.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.fuzzy.common.FuzzyMatch
import org.fuzzy.common.FuzzyMatchResponse
import org.slf4j.LoggerFactory
import shared.logging.LoggingUtils

@Serializable
data class MatchRequestDto(
    val query: String,
    val category: String? = null,
    val algorithm: String = "TATRMAN",
    val limit: Int = 10,
    // Optional algorithm cascade; when non-empty it overrides `algorithm`.
    val algorithms: List<AlgorithmSpecDto> = emptyList(),
)

@Serializable
data class AlgorithmSpecDto(
    val algorithm: String,
    val minScore: Double,
)

fun Application.configureRoutes(
    fuzzyMatcher: FuzzyMatcher,
    stringRepository: StringRepository,
    telemetry: FuzzyTelemetry,
    config: Config,
) {
    val logger = LoggerFactory.getLogger("fuzzy.routes")

    routing {
        secured(config) {
            post("/match") {
                try {
                    val request = call.receive<MatchRequestDto>()
                    val userId = call.request.header("X-User-Id") ?: "unknown"
                    logger.info(
                        "Incoming match request: query='{}', category='{}', algorithm='{}', limit={} userId={}",
                        request.query,
                        request.category,
                        request.algorithm,
                        request.limit,
                        userId,
                    )

                    val category = if (request.category.isNullOrBlank()) null else request.category
                    val steps =
                        cascadeFrom(
                            request.algorithms.map { CascadeStep(AlgorithmType.fromString(it.algorithm), it.minScore) },
                            request.algorithm,
                        )

                    val outcome =
                        fuzzyMatcher.matchCascade(
                            query = request.query,
                            category = category,
                            steps = steps,
                            limit = if (request.limit > 0) request.limit else 10,
                        )

                    logger.info(
                        "Match request completed: query='{}', category='{}', matchedAlgorithm='{}', results_count={} userId={}",
                        request.query,
                        request.category,
                        outcome.matchedAlgorithm?.name ?: "",
                        outcome.matches.size,
                        userId,
                    )

                    val matches =
                        outcome.matches.map {
                            FuzzyMatch(
                                candidateId = it.candidateId,
                                candidate = it.candidate,
                                score = it.score,
                                category = it.category,
                            )
                        }

                    call.respond(
                        FuzzyMatchResponse(
                            matches = matches,
                            matchedAlgorithm = outcome.matchedAlgorithm?.name ?: "",
                        ),
                    )
                } catch (e: Exception) {
                    LoggingUtils.logError(logger, "Error processing match request", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        FuzzyMatchResponse(isError = true, error = e.message ?: "Unknown error"),
                    )
                }
            }
        }
        // Unauthenticated, cluster-internal (see DF decision: /refresh has no auth) — deliberately
        // OUTSIDE the `secured` block. Forces an immediate reload of the candidate cache from its
        // loader (metadata-backed in production) instead of waiting for the background interval.
        // Golem's /v2/refresh calls this in parallel with the translator→query-runner chain, since
        // fuzzy depends on metadata directly.
        post("/refresh") {
            runCatching { stringRepository.forceRefresh() }.fold(
                onSuccess = {
                    call.respond(buildJsonObject { put("status", JsonPrimitive("ok")) })
                },
                onFailure = { e ->
                    LoggingUtils.logError(logger, "Error processing refresh request", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        buildJsonObject {
                            put("status", JsonPrimitive("failed"))
                            put("error", JsonPrimitive(e.message ?: "Unknown error"))
                        },
                    )
                },
            )
        }
    }
}
