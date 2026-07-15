// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.conformance

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * LG-P0·S2·T3 — the consumer contract corpus: one entry per consumer×shape, git-tracked under
 * `contract-diff/corpus/`. Request bodies are reconstructed from each consumer's real source (marked
 * `origin: reconstructed-from-source`); response captures against a live 1.x are added later by the
 * documented manual procedure (`contract-diff/README.md`). `readsResponseFields` is the load-bearing
 * column — it says which response fields each consumer actually depends on, so the diff can rank hits.
 */
@Serializable
data class CorpusEntry(
    val consumer: String,
    val shape: String,
    val method: String,
    val path: String,
    val headers: Map<String, String> = emptyMap(),
    val requestBody: JsonObject,
    val readsResponseFields: List<String> = emptyList(),
    val origin: String,
    val sources: List<String> = emptyList(),
    val notes: String? = null,
)

object ContractCorpus {
    private val json = Json { ignoreUnknownKeys = true }

    /** Resolve the corpus dir whether the test's working dir is the module or the repo root. */
    fun dir(): File =
        listOf(
            File("contract-diff/corpus"),
            File("services/ttr-llm-gateway/contract-diff/corpus"),
        ).firstOrNull { it.isDirectory }
            ?: error("contract-diff/corpus not found (cwd=${File(".").absolutePath})")

    fun load(): List<CorpusEntry> =
        (dir().listFiles { f -> f.extension == "json" } ?: emptyArray())
            .sortedBy { it.name }
            .map { json.decodeFromString(CorpusEntry.serializer(), it.readText()) }
}
