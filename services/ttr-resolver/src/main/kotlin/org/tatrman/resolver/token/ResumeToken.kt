// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.token

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** One signed option (contracts §5): the EXACT thing the resolver offered. */
@Serializable
data class ResumeOption(
    val id: String,
    val label: String,
    val targetRef: String? = null,
    val resolvedId: String? = null,
)

/**
 * The resume-token payload (contracts §5). Signed as-is; the `options` are the
 * exact offered set, so a resume can only select from what the resolver actually
 * offered (integrity-bearing under OBO — the agent cannot fabricate "the user
 * chose X", RS-26).
 */
@Serializable
data class ResumePayload(
    val conversationId: String,
    val parseRef: String,
    val options: List<ResumeOption>,
    val issuedAt: Long,
    val keyId: String,
)

/** Thrown/returned on any verification failure — always the RG-RES-002 condition. */
class ResumeTokenException(
    val reason: String,
) : Exception("RG-RES-002: resume token rejected — $reason")

/**
 * The HMAC resume-token codec (RG-P5.S2.T4, RS-26). Ported mechanics:
 * `token = base64url(payload) "." base64url(HMAC-SHA256(key, payload))`.
 *
 * Stateless: any replica with the key set verifies + resumes. Keys rotate by
 * `key_id` — [keys] holds every currently-honoured key (old ones stay during the
 * rotation window), [activeKeyId] is what new tokens are signed with. A failure of
 * any kind (malformed, bad signature, unknown/blocked key) is [ResumeTokenException]
 * carrying RG-RES-002 — never a silent accept (refuse-over-guess).
 */
class ResumeTokenCodec(
    private val keys: Map<String, ByteArray>,
    val activeKeyId: String,
) {
    init {
        require(keys.containsKey(activeKeyId)) { "activeKeyId '$activeKeyId' has no key" }
    }

    fun sign(payload: ResumePayload): String {
        val key = keys[payload.keyId] ?: error("cannot sign with unknown key_id '${payload.keyId}'")
        val json = json.encodeToString(ResumePayload.serializer(), payload).toByteArray(Charsets.UTF_8)
        return b64(json) + "." + b64(hmac(key, json))
    }

    fun verify(token: String): Result<ResumePayload> {
        val parts = token.split('.')
        if (parts.size != 2) return fail("malformed token")
        val jsonBytes = runCatching { unb64(parts[0]) }.getOrElse { return fail("undecodable payload") }
        val sig = runCatching { unb64(parts[1]) }.getOrElse { return fail("undecodable signature") }
        val payload =
            runCatching { json.decodeFromString(ResumePayload.serializer(), String(jsonBytes, Charsets.UTF_8)) }
                .getOrElse { return fail("unparseable payload") }
        val key = keys[payload.keyId] ?: return fail("unknown or blocked key_id '${payload.keyId}'")
        // constant-time comparison — no timing oracle on the signature.
        if (!MessageDigest.isEqual(hmac(key, jsonBytes), sig)) return fail("signature mismatch")
        return Result.success(payload)
    }

    private fun fail(reason: String): Result<ResumePayload> = Result.failure(ResumeTokenException(reason))

    private fun hmac(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray =
        Mac.getInstance(HMAC_ALG).run {
            init(SecretKeySpec(key, HMAC_ALG))
            doFinal(data)
        }

    companion object {
        private const val HMAC_ALG = "HmacSHA256"
        private val json = Json { encodeDefaults = true }
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder = Base64.getUrlDecoder()

        private fun b64(bytes: ByteArray): String = encoder.encodeToString(bytes)

        private fun unb64(s: String): ByteArray = decoder.decode(s)
    }
}
