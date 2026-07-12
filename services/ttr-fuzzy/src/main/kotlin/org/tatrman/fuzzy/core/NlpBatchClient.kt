// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import org.tatrman.nlp.v1.BatchLemmatizeRequest
import org.tatrman.nlp.v1.NlpServiceGrpcKt
import java.util.concurrent.TimeUnit

/**
 * The lemma-axis's link to `ttr-nlp` — RG-P2.S1.T4 repoints it from the old
 * `POST /v1/analyze` HTTP path to RG-P1's **gRPC `BatchLemmatize`** (batched:
 * ONE rpc for N tokens, never per-string HTTP — the pilot's `nlp.enabled=false`
 * cause). Injectable so [NlpLemmatizer] is unit-testable without a channel.
 *
 * @return positional lemma lists — `results[i]` is the lemma tokens of `texts[i]`.
 */
interface NlpBatchClient {
    suspend fun batchLemmatize(
        texts: List<String>,
        language: String,
    ): List<List<String>>

    fun close() {}
}

/** gRPC-backed [NlpBatchClient] over `org.tatrman.nlp.v1.NlpService.BatchLemmatize`. */
class GrpcNlpBatchClient(
    host: String,
    port: Int,
    private val deadlineSeconds: Long = 5,
) : NlpBatchClient {
    private val channel: ManagedChannel =
        ManagedChannelBuilder
            .forAddress(host, port)
            .usePlaintext()
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(10, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .build()

    private val stub = NlpServiceGrpcKt.NlpServiceCoroutineStub(channel)

    override suspend fun batchLemmatize(
        texts: List<String>,
        language: String,
    ): List<List<String>> {
        if (texts.isEmpty()) return emptyList()
        val request =
            BatchLemmatizeRequest
                .newBuilder()
                .addAllTexts(texts)
                .setLanguage(language)
                .build()
        val response = stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).batchLemmatize(request)
        return response.resultsList.map { it.lemmasList.toList() }
    }

    override fun close() {
        channel.shutdown()
    }
}
