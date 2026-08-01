// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.governance

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.tatrman.llmgateway.auth.sha256Hex

/**
 * LG-P4·S1·T1 — key minting (D-1): `ttrk-` + 43 base64url chars from 256 bits of SecureRandom; the stored
 * form is SHA-256 hex; a tampered key hashes differently; generations don't collide (sanity, not proof).
 */
class KeyGenerationSpec :
    StringSpec({

        "a generated key matches the ttrk- + 43 base64url-char format" {
            repeat(50) { KeyMint.generate() shouldMatch KeyMint.FORMAT }
        }

        "the stored form is SHA-256 hex (64 lowercase hex chars) of the plaintext" {
            val key = KeyMint.generate()
            KeyMint.hash(key) shouldBe sha256Hex(key)
            KeyMint.hash(key) shouldMatch Regex("^[0-9a-f]{64}$")
        }

        "a tampered key does not hash to the original's stored form" {
            val key = KeyMint.generate()
            val tampered = key.dropLast(1) + if (key.last() == 'A') 'B' else 'A'
            (KeyMint.hash(tampered) == KeyMint.hash(key)) shouldBe false
        }

        "two generations never collide (1000-sample sanity)" {
            val n = 1000
            (1..n).map { KeyMint.generate() }.toSet() shouldHaveSize n
        }

        "vk_ ulid ids are 26-char Crockford base32 and unique across a batch" {
            val ids = (1..1000).map { Ulid.next() }
            ids.forEach { it shouldMatch Regex("^[0-9A-HJKMNP-TV-Z]{26}$") }
            ids.toSet() shouldHaveSize ids.size
        }
    })
