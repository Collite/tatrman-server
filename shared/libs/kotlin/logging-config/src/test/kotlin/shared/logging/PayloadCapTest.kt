// SPDX-License-Identifier: Apache-2.0
package shared.logging

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * The payload cap and its `GRPC_LOG_PAYLOAD_MAX_CHARS` override.
 *
 * The parse function is tested rather than the env var itself, deliberately: a JVM cannot set its
 * own environment, and a test that reached for a reflection hack would be testing the hack. What
 * matters is the rule — a bad value must never take a service down over a *logging* knob, and it
 * must never silently become a smaller cap either, because a too-small cap looks exactly like a
 * short payload in the log.
 */
class PayloadCapTest :
    StringSpec({

        "absent ⇒ the default" {
            payloadCapFrom(null) shouldBe DEFAULT_MAX_PAYLOAD_CHARS
        }

        "a positive value is honoured, whitespace and all" {
            payloadCapFrom("20000") shouldBe 20000
            payloadCapFrom("  12000  ") shouldBe 12000
        }

        "junk, empty, zero and negative all fall back — never a smaller cap, never a crash" {
            payloadCapFrom("") shouldBe DEFAULT_MAX_PAYLOAD_CHARS
            payloadCapFrom("lots") shouldBe DEFAULT_MAX_PAYLOAD_CHARS
            payloadCapFrom("0") shouldBe DEFAULT_MAX_PAYLOAD_CHARS
            payloadCapFrom("-1") shouldBe DEFAULT_MAX_PAYLOAD_CHARS
            payloadCapFrom("4e3") shouldBe DEFAULT_MAX_PAYLOAD_CHARS
        }
    })
