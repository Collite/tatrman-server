// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.resolver.pipeline.UniversalClassifier
import org.tatrman.resolver.v1.UniversalEntityType

/**
 * The CNEC-code precedence (SV-P3·F1). The ttr-nlp NameTag front collapses both
 * objects/other-proper names (`o*`, domain-eligible) and numbers (`n*`, universal MISC)
 * into the single coarse label "MISC", so the resolver must classify on the raw CNEC
 * container code (carried in `normalized_value` as `cnec:<code>`) to keep them apart —
 * otherwise the RG hero's `op`-tagged "Octavie" binds as a universal MISC instead of
 * reaching `er.product`.
 */
class UniversalClassifierTest :
    StringSpec({

        "cnec:op (object / other-proper name) is domain-eligible, NOT universal MISC — the hero fix" {
            UniversalClassifier.classify("MISC", "cnec:op") shouldBe null
            UniversalClassifier.isUniversal("MISC", "cnec:op") shouldBe false
        }

        "cnec:no (number) stays universal MISC — the q20 '12345' case is preserved" {
            UniversalClassifier.classify("MISC", "cnec:no") shouldBe UniversalEntityType.MISC
            UniversalClassifier.isUniversal("MISC", "cnec:no") shouldBe true
        }

        "the genuine universal CNEC containers still classify by their letter" {
            UniversalClassifier.classify("PERSON", "cnec:ps") shouldBe UniversalEntityType.PERSON
            UniversalClassifier.classify("LOCATION", "cnec:gu") shouldBe UniversalEntityType.LOCATION
            UniversalClassifier.classify("DATE", "cnec:tf") shouldBe UniversalEntityType.DATE
        }

        "cnec:if (institution) is domain-eligible (gated by fuzzy over declared vocabulary)" {
            UniversalClassifier.classify("ORGANIZATION", "cnec:if") shouldBe null
        }

        "the raw cnec code wins even when the coarse label would say universal" {
            // label 'MISC' alone → universal; the cnec:op code overrides it to domain-eligible.
            UniversalClassifier.classify("MISC", "") shouldBe UniversalEntityType.MISC
            UniversalClassifier.classify("MISC", "cnec:op") shouldBe null
        }

        "entities without a cnec code fall back to the coarse label" {
            UniversalClassifier.classify("PERSON") shouldBe UniversalEntityType.PERSON
            UniversalClassifier.classify("DATE") shouldBe UniversalEntityType.DATE
            UniversalClassifier.classify("ORG") shouldBe null
            UniversalClassifier.classify("") shouldBe null
        }
    })
