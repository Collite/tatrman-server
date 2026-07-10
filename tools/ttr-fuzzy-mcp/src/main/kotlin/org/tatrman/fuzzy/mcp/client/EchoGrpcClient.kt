package org.tatrman.fuzzy.mcp.client

import org.tatrman.fuzzy.v1.EchoServiceGrpcKt
import org.tatrman.fuzzy.v1.MatchRequest
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import org.fuzzy.common.FuzzyMatch
import org.fuzzy.common.FuzzyMatchResponse
import org.slf4j.LoggerFactory
import org.tatrman.fuzzy.mcp.telemetry.EchoMcpTelemetry
import java.util.concurrent.TimeUnit

class EchoGrpcClient(
    host: String,
    port: Int,
    private val telemetry: EchoMcpTelemetry? = null,
) : EchoClient {
    private val logger = LoggerFactory.getLogger(EchoGrpcClient::class.java)

    private val channel: ManagedChannel =
        ManagedChannelBuilder
            .forAddress(host, port)
            .usePlaintext()
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(10, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .build()

    private val stub = EchoServiceGrpcKt.EchoServiceCoroutineStub(channel)

    override suspend fun match(
        category: String,
        name: String,
        algorithm: String,
        limit: Int,
    ): FuzzyMatchResponse =
        try {
            logger.info(
                "Calling matcher via gRPC: name='{}', category='{}', algorithm='{}', limit={}",
                name,
                category,
                algorithm,
                limit,
            )

            val requestBuilder =
                MatchRequest
                    .newBuilder()
                    .setQuery(name)
                    .setLimit(limit)

            if (category.isNotBlank()) {
                requestBuilder.setCategory(category)
            }
            if (algorithm.isNotBlank()) {
                requestBuilder.setAlgorithm(algorithm)
            }

            val response = stub.withDeadlineAfter(20, TimeUnit.SECONDS).match(requestBuilder.build())

            if (response.isError) {
                return FuzzyMatchResponse(
                    isError = true,
                    error = response.error,
                )
            }

            val matches =
                response.matchesList.map {
                    FuzzyMatch(
                        candidateId = it.candidateId,
                        candidate = it.candidate,
                        score = it.score,
                        category = it.category,
                    )
                }

            logger.info(
                "matcher gRPC call completed: name='{}', category='{}', results_size={}",
                name,
                category,
                matches.size,
            )

            FuzzyMatchResponse(matches = matches)
        } catch (e: Exception) {
            logger.error(
                "Error calling matcher via gRPC: name='{}', category='{}'",
                name,
                category,
                e,
            )
            FuzzyMatchResponse(isError = true, error = "Error calling gRPC API: ${e.message}")
        }

    override fun getTelemetry(): EchoMcpTelemetry? = telemetry

    override fun close() {
        channel.shutdown()
    }
}
