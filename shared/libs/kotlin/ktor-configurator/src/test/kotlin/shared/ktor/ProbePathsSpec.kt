// SPDX-License-Identifier: Apache-2.0
package shared.ktor

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Probe-path suppression for the request log.
 *
 * The contract worth pinning is the *exact* match: `/health` is suppressed but `/healthcheck` is
 * not. A prefix match here would silently swallow real application routes, and the only symptom
 * would be logs that are missing entries — the hardest kind of bug to notice.
 */
class ProbePathsSpec :
    StringSpec({

        "the standard probe endpoints are suppressed" {
            listOf("/health", "/ready", "/healthz", "/readyz", "/livez", "/metrics").forEach {
                ProbePaths.isProbe(it) shouldBe true
            }
        }

        "a trailing slash still counts as the same probe" {
            ProbePaths.isProbe("/health/") shouldBe true
        }

        "matching is exact — a route that merely starts with a probe name still logs" {
            listOf("/healthcheck/detail", "/readiness-report", "/v1/health", "/metrics-export")
                .forEach { ProbePaths.isProbe(it) shouldBe false }
        }

        "ordinary traffic is never suppressed" {
            listOf("/", "/v1/resolve", "/v1/chat/stream").forEach {
                ProbePaths.isProbe(it) shouldBe false
            }
        }
    })
