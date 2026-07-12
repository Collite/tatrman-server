// SPDX-License-Identifier: Apache-2.0
package dummy

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class DummyTest :
    StringSpec({
        "dummy test should pass" {
            true shouldBe true
        }
    })
