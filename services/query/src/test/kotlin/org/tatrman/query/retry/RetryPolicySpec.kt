// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.retry

import io.grpc.Status
import io.grpc.StatusException
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import kotlin.random.Random

class RetryPolicySpec :
    StringSpec({
        val noJitter =
            RetryPolicy(
                maxAttempts = 3,
                initialBackoffMillis = 1,
                multiplier = 1.0,
                jitterPercent = 0,
                random = Random(0),
            )

        "retries on UNAVAILABLE and eventually succeeds" {
            runBlocking {
                var calls = 0
                val outcome =
                    noJitter.execute("op") {
                        calls++
                        if (calls < 3) throw StatusException(Status.UNAVAILABLE.withDescription("flapping"))
                        "ok"
                    }
                val s = outcome.shouldBeInstanceOf<RetryOutcome.Success<String>>()
                s.value shouldBe "ok"
                s.attemptsUsed shouldBe 3
                calls shouldBe 3
            }
        }

        "retries on DEADLINE_EXCEEDED" {
            runBlocking {
                var calls = 0
                noJitter.execute("op") {
                    calls++
                    if (calls < 2) throw StatusException(Status.DEADLINE_EXCEEDED)
                    "ok"
                }
                calls shouldBe 2
            }
        }

        "does not retry non-retriable status (INVALID_ARGUMENT)" {
            runBlocking {
                var calls = 0
                val outcome =
                    noJitter.execute("op") {
                        calls++
                        throw StatusException(Status.INVALID_ARGUMENT)
                    }
                outcome.shouldBeInstanceOf<RetryOutcome.Failure>()
                calls shouldBe 1
            }
        }

        "does not retry plain RuntimeException" {
            runBlocking {
                var calls = 0
                val outcome =
                    noJitter.execute("op") {
                        calls++
                        throw RuntimeException("not a status")
                    }
                outcome.shouldBeInstanceOf<RetryOutcome.Failure>()
                calls shouldBe 1
            }
        }

        "gives up after maxAttempts" {
            runBlocking {
                var calls = 0
                val outcome =
                    noJitter.execute("op") {
                        calls++
                        throw StatusException(Status.UNAVAILABLE)
                    }
                val f = outcome.shouldBeInstanceOf<RetryOutcome.Failure>()
                calls shouldBe 3
                f.attemptsUsed shouldBe 3
            }
        }

        "succeeds on first attempt with attemptsUsed = 1" {
            runBlocking {
                val outcome = noJitter.execute("op") { 42 }
                val s = outcome.shouldBeInstanceOf<RetryOutcome.Success<Int>>()
                s.value shouldBe 42
                s.attemptsUsed shouldBe 1
            }
        }
    })
