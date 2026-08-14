// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.client

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import org.tatrman.fuzzy.v1.BatchMatchRequest
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyServiceGrpcKt
import org.tatrman.fuzzy.v1.FuzzyStatusRequest
import org.tatrman.fuzzy.v1.FuzzyStatusResponse
import org.tatrman.fuzzy.v1.LookupRequest
import org.tatrman.fuzzy.v1.LookupResponse
import org.tatrman.nlp.v1.AnalyzeRequest
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.NlpServiceGrpcKt
import org.tatrman.nlp.v1.StatusRequest
import org.tatrman.nlp.v1.StatusResponse
import shared.logging.OutgoingCallLoggingInterceptor
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

    /**
     * RV-P2.3 — one narrowing question (contracts §1 addendum, FROZEN at P1.4 T5).
     *
     * Deliberately NOT a batch, and the difference from [batchMatch] is the whole point of the
     * rung: `BatchMatch` asks the broad pass's question about every span at once and can express
     * nothing but term+categories+limit, while a lookup round asks a narrow question the planner
     * chose — scoped by target class, optionally overriding the authored method — about one span
     * it decided was worth asking about again.
     *
     * Defaulted to "no vocabulary answered" so a test double that never exercises rounds does not
     * have to implement it. [GrpcFuzzyClient] always overrides; a fake that returns this default
     * simply makes the loop a no-op, which is exactly what an estate with nothing to narrow does.
     */
    suspend fun lookup(request: LookupRequest): LookupResponse = LookupResponse.getDefaultInstance()

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

    override suspend fun lookup(request: LookupRequest): LookupResponse =
        stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).lookup(request)

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
        // TG-P0-F3 (2026-08-14) — log what the core asks nlp and lex-matcher, and what they
        // answer. DEBUG-only (`LOG_LEVEL=DEBUG`), payloads redacted and capped by the interceptor.
        //
        // ⚑ Why it matters more here than anywhere else it is already used (validate, chrono,
        // translate, dispatch, geo): the resolver's own gRPC surface logs the LATTICE it produced,
        // but a lattice with no operator bound is ambiguous — it does not distinguish "lex-matcher
        // returned nothing" from "it returned a match the core discarded". D2 sat on exactly that
        // ambiguity for a day. This one line separates the two.
        .intercept(OutgoingCallLoggingInterceptor())
        .keepAliveTime(30, TimeUnit.SECONDS)
        .keepAliveTimeout(10, TimeUnit.SECONDS)
        .keepAliveWithoutCalls(true)
        .build()
