// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.governance

import org.tatrman.llmgateway.auth.sha256Hex
import java.security.SecureRandom
import java.util.Base64

/**
 * `ttrk-` virtual-key minting (D-1). A key is `ttrk-` + 43 base64url chars from **256 bits** of
 * `SecureRandom`; only its SHA-256 hex is ever persisted ([hash]) — the plaintext lives solely in the
 * issuance return value and is never logged or stored. [FORMAT] is the shape both issuance and the
 * logging scrub-mask match on.
 */
object KeyMint {
    const val PREFIX = "ttrk-"

    /** Full-key shape: prefix + 43 unpadded base64url chars (32 bytes → 43 chars). */
    val FORMAT = Regex("^ttrk-[A-Za-z0-9_-]{43}$")

    private val rng = SecureRandom()
    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    /** A fresh plaintext key. Hold it only long enough to return it to the caller. */
    fun generate(): String {
        val bytes = ByteArray(32) // 256 bits
        rng.nextBytes(bytes)
        return PREFIX + encoder.encodeToString(bytes)
    }

    /** SHA-256 hex of a presented/generated key — the only form that touches storage. */
    fun hash(rawKey: String): String = sha256Hex(rawKey)
}
