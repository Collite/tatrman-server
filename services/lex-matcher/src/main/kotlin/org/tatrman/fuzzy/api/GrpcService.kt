// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.api

import org.tatrman.fuzzy.core.AlgorithmType
import org.tatrman.fuzzy.core.CascadeStep
import org.tatrman.fuzzy.core.FuzzyMatchResult
import org.tatrman.fuzzy.core.FuzzyMatcher
import org.tatrman.fuzzy.core.SourceTag as CoreSourceTag
import org.tatrman.fuzzy.core.SpanQuery
import org.tatrman.fuzzy.core.StringRepository
import org.tatrman.fuzzy.core.cascadeFrom
import org.tatrman.fuzzy.telemetry.FuzzyTelemetry
import org.tatrman.fuzzy.v1.BatchMatchRequest
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.CategoryStatus
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.FuzzyMatchResponse
import org.tatrman.fuzzy.v1.FuzzyServiceGrpcKt
import org.tatrman.fuzzy.v1.FuzzyStatusRequest
import org.tatrman.fuzzy.v1.FuzzyStatusResponse
import org.tatrman.fuzzy.v1.LoaderWarning
import org.tatrman.fuzzy.v1.MatchRequest
import org.tatrman.fuzzy.v1.Provenance as ProtoProvenance
import org.tatrman.fuzzy.v1.SourceTag as ProtoSourceTag
import org.slf4j.LoggerFactory

class GrpcService(
    private val fuzzyMatcher: FuzzyMatcher,
    private val repository: StringRepository,
    private val telemetry: FuzzyTelemetry? = null,
) : FuzzyServiceGrpcKt.FuzzyServiceCoroutineImplBase() {
    private val logger = LoggerFactory.getLogger(GrpcService::class.java)

    override suspend fun match(request: MatchRequest): FuzzyMatchResponse {
        try {
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
            return buildResponse(outcome.matches, outcome.matchedAlgorithm?.name ?: "", repository.vocabularyVersion())
        } catch (e: Exception) {
            logger.error("Error processing gRPC match request", e)
            return FuzzyMatchResponse
                .newBuilder()
                .setIsError(true)
                .setError(e.message ?: "Unknown gRPC error")
                .build()
        }
    }

    override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse =
        try {
            val spans = request.spansList.map { SpanQuery(it.query, it.categoriesList.toList(), it.limit) }
            val batch = fuzzyMatcher.batchMatch(spans)
            val results =
                batch.results.map { span ->
                    buildResponse(span.matches, span.matchedAlgorithm?.name ?: "", batch.vocabularyVersion)
                }
            BatchMatchResponse.newBuilder().addAllResults(results).build()
        } catch (e: Exception) {
            logger.error("Error processing gRPC batchMatch request", e)
            // Whole-call failure still returns a shaped response (empty results).
            BatchMatchResponse.newBuilder().build()
        }

    override suspend fun getStatus(request: FuzzyStatusRequest): FuzzyStatusResponse {
        val builder =
            FuzzyStatusResponse
                .newBuilder()
                .setReady(repository.isCatalogReady())
                .setVocabularyVersion(repository.vocabularyVersion())
        repository.categoryStatuses().forEach { s ->
            builder.addCategories(
                CategoryStatus
                    .newBuilder()
                    .setCategory(s.category)
                    .setSource(
                        if (s.source ==
                            CoreSourceTag.VOCABULARY
                        ) {
                            ProtoSourceTag.VOCABULARY
                        } else {
                            ProtoSourceTag.MEMBER
                        },
                    ).setSize(s.size)
                    .setLoadedAtEpochMs(s.loadedAtEpochMs)
                    .build(),
            )
        }
        repository.loaderWarnings().forEach { w ->
            builder.addWarnings(
                LoaderWarning
                    .newBuilder()
                    .setCode(w.code)
                    .setCategory(w.category)
                    .setMessage(w.message)
                    .build(),
            )
        }
        return builder.build()
    }

    private fun buildResponse(
        matches: List<FuzzyMatchResult>,
        matchedAlgorithm: String,
        vocabularyVersion: String,
    ): FuzzyMatchResponse =
        FuzzyMatchResponse
            .newBuilder()
            .addAllMatches(matches.map { it.toProto() })
            .setMatchedAlgorithm(matchedAlgorithm)
            .setVocabularyVersion(vocabularyVersion)
            .build()

    private fun FuzzyMatchResult.toProto(): FuzzyMatch {
        val b =
            FuzzyMatch
                .newBuilder()
                .setCandidateId(candidateId)
                .setCandidate(candidate)
                .setScore(score)
                .setCategory(category)
                .setSource(
                    when (source) {
                        CoreSourceTag.MEMBER -> ProtoSourceTag.MEMBER
                        CoreSourceTag.VOCABULARY -> ProtoSourceTag.VOCABULARY
                    },
                ).setProvenance(
                    ProtoProvenance
                        .newBuilder()
                        .setProducer(provenance.producer)
                        .setMethod(provenance.method)
                        .setRawScore(provenance.rawScore)
                        .build(),
                )
        targetRef?.let { b.setTargetRef(it) }
        return b.build()
    }
}
