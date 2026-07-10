package org.tatrman.fuzzy.api

import org.tatrman.fuzzy.core.AlgorithmType
import org.tatrman.fuzzy.core.CascadeStep
import org.tatrman.fuzzy.core.EchoMatcher
import org.tatrman.fuzzy.core.cascadeFrom
import org.tatrman.fuzzy.telemetry.EchoTelemetry
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.FuzzyMatchResponse
import org.tatrman.fuzzy.v1.FuzzyServiceGrpcKt
import org.tatrman.fuzzy.v1.MatchRequest
import org.slf4j.LoggerFactory

class GrpcService(
    private val fuzzyMatcher: EchoMatcher,
    private val telemetry: EchoTelemetry? = null,
) : FuzzyServiceGrpcKt.FuzzyServiceCoroutineImplBase() {
    private val logger = LoggerFactory.getLogger(GrpcService::class.java)

    override suspend fun match(request: MatchRequest): FuzzyMatchResponse {
        try {
            logger.info(
                "Incoming gRPC match request: query='{}', category='{}', algorithm='{}', limit={}",
                request.query,
                if (request.hasCategory()) request.category else null,
                if (request.hasAlgorithm()) request.algorithm else null,
                request.limit,
            )

            val steps =
                cascadeFrom(
                    request.algorithmsList.map { CascadeStep(AlgorithmType.fromString(it.algorithm), it.minScore) },
                    if (request.hasAlgorithm()) request.algorithm else null,
                )

            val outcome =
                fuzzyMatcher.matchCascade(
                    query = request.query,
                    category = if (request.hasCategory()) request.category else null,
                    steps = steps,
                    limit = if (request.limit > 0) request.limit else 10,
                )

            logger.info(
                "gRPC match request completed: query='{}', category='{}', matchedAlgorithm='{}', results_count={}",
                request.query,
                if (request.hasCategory()) request.category else null,
                outcome.matchedAlgorithm?.name ?: "",
                outcome.matches.size,
            )

            val matches =
                outcome.matches.map {
                    FuzzyMatch
                        .newBuilder()
                        .setCandidateId(it.candidateId)
                        .setCandidate(it.candidate)
                        .setScore(it.score)
                        .setCategory(it.category)
                        .build()
                }

            return FuzzyMatchResponse
                .newBuilder()
                .addAllMatches(matches)
                .setMatchedAlgorithm(outcome.matchedAlgorithm?.name ?: "")
                .build()
        } catch (e: Exception) {
            logger.error("Error processing gRPC match request", e)
            return FuzzyMatchResponse
                .newBuilder()
                .setIsError(true)
                .setError(e.message ?: "Unknown gRPC error")
                .build()
        }
    }
}
