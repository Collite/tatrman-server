// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.governance

import java.security.SecureRandom

/**
 * Minimal ULID (Crockford base32, 48-bit ms timestamp + 80-bit `SecureRandom` payload) for `vk_<ulid>`
 * key ids (contracts §3). Lexicographically time-sortable; NOT monotonic within a single millisecond
 * (fresh randomness per call — collision odds are negligible and ids need only be unique, not ordered).
 * No third-party ULID dependency is on the classpath, so this is the recorded local implementation.
 */
object Ulid {
    private const val ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ" // Crockford base32
    private val rng = SecureRandom()

    fun next(nowMs: Long = System.currentTimeMillis()): String {
        val sb = StringBuilder(26)
        // 48-bit timestamp → 10 base32 chars (top 2 bits of the 50 encoded are zero for real epochs).
        var t = nowMs and 0xFFFF_FFFF_FFFFL
        val time = CharArray(10)
        for (i in 9 downTo 0) {
            time[i] = ENCODING[(t and 0x1F).toInt()]
            t = t shr 5
        }
        sb.append(time)
        // 80-bit randomness → exactly 16 base32 chars (80 / 5 = 16, no remainder).
        val rand = ByteArray(10).also { rng.nextBytes(it) }
        var acc = 0
        var bits = 0
        for (b in rand) {
            acc = (acc shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(ENCODING[(acc ushr bits) and 0x1F])
            }
        }
        return sb.toString()
    }
}
