// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.proto

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.tatrman.fuzzy.v1.MatchRequest
import org.tatrman.nlp.v1.AnalyzeRequest

/**
 * RG-P0.S3.T4/T5 — proto rename compile-smoke.
 *
 * The `com.tatrman.* → org.tatrman.*` proto package rename was completed by the
 * SV-P2 client-name scrub; this repo has zero `com.tatrman` occurrences. This
 * test pins that end state: the renamed `org.tatrman.{fuzzy,nlp}.v1` generated
 * types are importable and constructible from a consumer module. If a proto
 * regressed to the old package, this file would not compile.
 *
 * (The `grounding.v1` / `resolver.v1` protos are net-new — created in RG-P3/RG-P5,
 * not renamed here — so there is nothing to smoke-test for them yet. The grounding
 * `ResponseMessage` import wart (RS-21 fix-at-rename) is likewise deferred to the
 * RG-P3 extraction, where the grounding proto first lands.)
 */
class ProtoPackageSmokeSpec :
    FunSpec({

        test("org.tatrman.fuzzy.v1 generated types are importable + constructible") {
            val req = MatchRequest.newBuilder().build()
            req::class.java.packageName shouldBe "org.tatrman.fuzzy.v1"
            req::class.java.name shouldStartWith "org.tatrman.fuzzy.v1."
        }

        test("org.tatrman.nlp.v1 generated types are importable + constructible") {
            val req = AnalyzeRequest.newBuilder().build()
            req::class.java.packageName shouldBe "org.tatrman.nlp.v1"
            req::class.java.name shouldStartWith "org.tatrman.nlp.v1."
        }
    })
