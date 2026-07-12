// SPDX-License-Identifier: Apache-2.0
package shared.logging

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class StackTraceUtilTest :
    StringSpec({
        "formatStackTrace returns formatted stack trace within limit" {
            val exception = RuntimeException("Test error")
            val result = StackTraceUtil.formatStackTrace(exception, 300)
            result shouldContain "RuntimeException"
            result shouldContain "Test error"
            (result.length <= 300) shouldBe true
        }
    })
