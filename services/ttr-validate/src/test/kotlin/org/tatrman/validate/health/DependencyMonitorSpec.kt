// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate.health

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

class DependencyMonitorSpec :
    StringSpec({

        // Poll the condition on a tight interval until it holds or we time out — a small,
        // dependency-free stand-in for kotest's `eventually` so the background-loop assertions
        // aren't timing-flaky.
        suspend fun awaitTrue(
            timeoutMs: Long = 2_000,
            condition: () -> Boolean,
        ): Boolean =
            withTimeoutOrNull(timeoutMs) {
                while (!condition()) delay(10)
                true
            } ?: false

        fun monitor(vararg deps: DependencyMonitor.Dependency) =
            DependencyMonitor(
                dependencies = deps.toList(),
                pollIntervalMs = 20,
                backoffBaseMs = 10,
                backoffMaxMs = 40,
            )

        "is NOT ready before the first probe and becomes ready once both succeed" {
            val m =
                monitor(
                    DependencyMonitor.Dependency("metadata") { true },
                    DependencyMonitor.Dependency("sql-security") { true },
                )
            m.ready() shouldBe false // nothing probed yet

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                m.start(scope)
                awaitTrue { m.ready() } shouldBe true
                m.down() shouldBe emptyList()
            } finally {
                scope.cancel()
            }
        }

        "gates readiness while a dependency is down and re-readies (exp backoff) on recovery" {
            val securityUp = AtomicBoolean(false)
            val m =
                monitor(
                    DependencyMonitor.Dependency("metadata") { true },
                    DependencyMonitor.Dependency("sql-security") {
                        // Throwing counts as down — exactly how a gRPC UNAVAILABLE surfaces.
                        if (securityUp.get()) true else error("sql-security unavailable")
                    },
                )

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                m.start(scope)
                // metadata is up but security keeps failing → never ready, security listed as down.
                awaitTrue { m.statuses()["metadata"] == true } shouldBe true
                m.ready() shouldBe false
                m.down() shouldBe listOf("sql-security")

                // Recovery: the monitor keeps retrying with backoff, so it flips to ready on its own.
                securityUp.set(true)
                awaitTrue { m.ready() } shouldBe true
                m.down() shouldBe emptyList()
            } finally {
                scope.cancel()
            }
        }
    })
