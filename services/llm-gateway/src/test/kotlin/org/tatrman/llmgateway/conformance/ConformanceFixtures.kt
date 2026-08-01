// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.conformance

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable

/**
 * LG-P0·S2·T2 — the SSE/HTTP conformance fixture format + loader.
 *
 * A fixture is a pair under `src/test/resources/conformance/sse/`:
 *   - `<name>.sse` (byte-exact SSE body) **or** `<name>.http` (raw upstream HTTP error response)
 *   - `<name>.meta.yaml` ([ConformanceFixtureMeta]) — provenance + the observation script a correct
 *     LG-P2 tap must produce, plus how to fragment the bytes on replay.
 *
 * These files are **data**. Each LG-P2 per-provider suite replays them and asserts its tap emits the
 * declared `expected` observations; S2 only proves the format loads and the server replays the bytes
 * verbatim. `expected` entries are intentionally opaque strings here — LG-P2 binds them to the real
 * `StreamObservation` / `GatewayError` types (which do not exist yet).
 */
@Serializable
data class ConformanceFixtureMeta(
    val name: String,
    val kind: String, // "sse" | "http"
    val origin: String, // "synthetic" | "captured" — never claim captured for hand-authored bytes
    val description: String,
    val splitOffsets: List<Int> = emptyList(), // absolute byte offsets to fragment an .sse body
    val splitInsideChar: String? = null, // resolve one split to land inside this char's UTF-8 bytes
    val httpStatus: Int? = null, // .http fixtures: the upstream status
    val retryAfterSeconds: Int? = null, // .http: value of the Retry-After header, if present
    val expectedError: String? = null, // .http: the GatewayError member LG-P2 must map to
    val expected: List<String> = emptyList(), // the observation script (opaque until LG-P2 binds it)
)

data class ConformanceFixture(
    val meta: ConformanceFixtureMeta,
    val body: ByteArray,
) {
    /** Byte offsets at which [body] should be fragmented on replay (resolves [ConformanceFixtureMeta.splitInsideChar]). */
    fun resolvedSplitOffsets(): List<Int> {
        val fromChar: List<Int> =
            meta.splitInsideChar?.let { ch ->
                val marker = ch.encodeToByteArray()
                require(marker.size >= 2) { "splitInsideChar '$ch' is single-byte — cannot split inside it" }
                val at = byteIndexOf(body, marker)
                require(at >= 0) { "splitInsideChar '$ch' not found in ${meta.name}" }
                listOf(at + marker.size / 2) // land strictly inside the codepoint
            } ?: emptyList()
        return (meta.splitOffsets + fromChar).sorted()
    }

    override fun equals(other: Any?) =
        other is ConformanceFixture && meta == other.meta && body.contentEquals(other.body)

    override fun hashCode() = 31 * meta.hashCode() + body.contentHashCode()
}

object ConformanceFixtures {
    private const val ROOT = "/conformance/sse"

    /** The full named set (task S2·T2). */
    val ALL =
        listOf(
            "done-terminator",
            "utf8-split",
            "toolcall-deltas",
            "error-frame-midstream",
            "usage-final-chunk",
            "usage-absent",
            "heartbeat-comments",
            "retry-after-429",
            "anthropic-529",
        )

    fun load(name: String): ConformanceFixture {
        val meta = loadMeta(name)
        val ext = if (meta.kind == "http") "http" else "sse"
        val body = resource("$name.$ext").readBytes()
        return ConformanceFixture(meta, body)
    }

    /** Build a fixture server that replays an SSE fixture, honoring its declared fragmentation. */
    fun sseServer(fixture: ConformanceFixture): SseFixtureServer {
        require(fixture.meta.kind == "sse") { "${fixture.meta.name} is not an SSE fixture" }
        val offsets = fixture.resolvedSplitOffsets()
        return SseFixtureServer.start {
            if (offsets.isEmpty()) bytes(fixture.body) else splitAt(fixture.body, *offsets.toIntArray())
        }
    }

    private fun loadMeta(name: String): ConformanceFixtureMeta =
        Yaml.default.decodeFromString(ConformanceFixtureMeta.serializer(), resource("$name.meta.yaml").readText())

    private fun resource(file: String) =
        ConformanceFixtures::class.java.getResource("$ROOT/$file")
            ?: error("missing conformance fixture resource: $ROOT/$file")
}

private fun byteIndexOf(
    haystack: ByteArray,
    needle: ByteArray,
): Int {
    outer@ for (i in 0..haystack.size - needle.size) {
        for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
        return i
    }
    return -1
}
