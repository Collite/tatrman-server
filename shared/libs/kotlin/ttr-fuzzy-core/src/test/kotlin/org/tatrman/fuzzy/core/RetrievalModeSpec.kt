// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** FZ-P2 T4 — `fuzzy.token-based.retrieval` parsing: both values, default, and garbage. */
class RetrievalModeSpec :
    StringSpec({
        "parses the two known values (case- and separator-insensitive)" {
            RetrievalMode.fromString("legacy") shouldBe RetrievalMode.LEGACY
            RetrievalMode.fromString("index-first") shouldBe RetrievalMode.INDEX_FIRST
            RetrievalMode.fromString("INDEX-FIRST") shouldBe RetrievalMode.INDEX_FIRST
            RetrievalMode.fromString("index_first") shouldBe RetrievalMode.INDEX_FIRST
            RetrievalMode.fromString(" Index-First ") shouldBe RetrievalMode.INDEX_FIRST
        }

        "blank or null ⇒ LEGACY (silent default, like AlgorithmType.fromString)" {
            RetrievalMode.fromString(null) shouldBe RetrievalMode.LEGACY
            RetrievalMode.fromString("") shouldBe RetrievalMode.LEGACY
            RetrievalMode.fromString("   ") shouldBe RetrievalMode.LEGACY
        }

        "garbage ⇒ LEGACY" {
            RetrievalMode.fromString("opensearch") shouldBe RetrievalMode.LEGACY
            RetrievalMode.fromString("index first please") shouldBe RetrievalMode.LEGACY
        }
    })
