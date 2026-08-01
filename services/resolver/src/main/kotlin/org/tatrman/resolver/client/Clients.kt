// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.client

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import org.tatrman.fuzzy.v1.BatchMatchRequest
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyServiceGrpcKt
import org.tatrman.fuzzy.v1.FuzzyStatusRequest
import org.tatrman.fuzzy.v1.FuzzyStatusResponse
import org.tatrman.nlp.v1.AnalyzeRequest
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.NlpServiceGrpcKt
import org.tatrman.nlp.v1.StatusRequest
import org.tatrman.nlp.v1.StatusResponse
import java.util.concurrent.TimeUnit

/**
 * The two upstream contracts the deterministic core consumes. Interfaces (not
 * concrete stubs) so the pipeline is testable with fakes — the Q-20 corpora feed
 * the pipeline through these seams without a live nlp/fuzzy. NEITHER is an LLM;
 * NoLlmDependencyTest guards the module boundary.
 */
interface NlpClient {
    suspend fun analyze(request: AnalyzeRequest): AnalyzeResponse

    /** RS-7 capability matrix — read once at resolve time (branch points in S2). */
    suspend fun getStatus(): StatusResponse
}

interface FuzzyClient {
    /** The one BatchMatch per resolve (B-T1). */
    suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse

    /** Category discovery + staleness (S2: registry snapshot echo). */
    suspend fun getStatus(): FuzzyStatusResponse
}

class GrpcNlpClient(
    host: String,
    port: Int,
    private val deadlineSeconds: Long = 30,
) : NlpClient,
    AutoCloseable {
    private val channel: ManagedChannel = openChannel(host, port)
    private val stub = NlpServiceGrpcKt.NlpServiceCoroutineStub(channel)

    override suspend fun analyze(request: AnalyzeRequest): AnalyzeResponse =
        stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).analyze(request)

    override suspend fun getStatus(): StatusResponse =
        stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).getStatus(StatusRequest.getDefaultInstance())

    override fun close() {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }
}

class GrpcFuzzyClient(
    host: String,
    port: Int,
    private val deadlineSeconds: Long = 30,
) : FuzzyClient,
    AutoCloseable {
    private val channel: ManagedChannel = openChannel(host, port)
    private val stub = FuzzyServiceGrpcKt.FuzzyServiceCoroutineStub(channel)

    override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse =
        stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).batchMatch(request)

    override suspend fun getStatus(): FuzzyStatusResponse =
        stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).getStatus(FuzzyStatusRequest.getDefaultInstance())

    override fun close() {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }
}

private fun openChannel(
    host: String,
    port: Int,
): ManagedChannel =
    ManagedChannelBuilder
        .forAddress(host, port)
        .usePlaintext()
        .keepAliveTime(30, TimeUnit.SECONDS)
        .keepAliveTimeout(10, TimeUnit.SECONDS)
        .keepAliveWithoutCalls(true)
        .build()
