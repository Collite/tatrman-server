// SPDX-License-Identifier: Apache-2.0
package shared.logging

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * RG-P6 review B — secret-bearing fields must never reach a log sink. The gRPC call
 * interceptor renders payloads with `TextFormat.shortDebugString`, which emits
 * `token: "…"`; [redactSecrets] masks those values in place while keeping the field
 * name visible for diagnostics.
 */
class RedactSecretsTest :
    StringSpec({

        "a resume token in a rendered payload is masked" {
            val rendered = """conversation_id: "c-1" resume { token: "abc.def.ghi" selected_option_id: "M:x" }"""
            val out = redactSecrets(rendered)
            out shouldNotContain "abc.def.ghi"
            out shouldContain "token: \"<redacted>\""
            out shouldContain "selected_option_id: \"M:x\"" // non-secret fields survive
        }

        "resume_token, authorization, secret and password are all masked (case-insensitive)" {
            val rendered = """resume_token: "T" Authorization: "Bearer z" secret: "s" password: "p" api_key: "k""""
            val out = redactSecrets(rendered)
            listOf("\"T\"", "\"Bearer z\"", "\"s\"", "\"p\"", "\"k\"").forEach { out shouldNotContain it }
        }

        "a payload with no secret fields is unchanged" {
            val rendered = """conversation_id: "c-1" fresh { text: "kolik za DF" locale: "cs" }"""
            redactSecrets(rendered) shouldBe rendered
        }

        // LG-P4·S1·T5 — a ttrk- virtual key is masked by value, even bare in an exception message (D-1).
        "a ttrk- virtual key is masked by value in a bare (non-field) message" {
            val plaintext = "ttrk-" + "aB3_dE6-hI9".repeat(3) + "xyz1234"
            val rendered = "key issuance insert failed for $plaintext at team golem"
            val out = redactSecrets(rendered)
            out shouldNotContain plaintext
            out shouldContain "ttrk-<redacted>"
            out shouldContain "team golem" // surrounding diagnostics survive
        }
    })
