// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Collite
package org.tatrman.smoke

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SmokeSpec :
    StringSpec({
        "toolchain, catalog, and CI wiring are alive" {
            smoke() shouldBe true
        }
    })
