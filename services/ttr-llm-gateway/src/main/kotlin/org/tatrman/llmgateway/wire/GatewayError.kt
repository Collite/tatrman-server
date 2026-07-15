// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.wire

/**
 * The sealed failure vocabulary (contracts §5.3, architecture §2.3). It is the shared decision
 * language: the retry matrix and fallback triggers dispatch on [retryable]/[chainEligible] (LG-P3),
 * and the transport renders every member as an OpenAI-shaped error body (A-3/D-6, see ErrorRendering).
 * One [ErrorConverter] per provider maps an upstream HTTP status + body/error-frame into one of these.
 */
sealed class GatewayError(
    val retryable: Boolean,
    val chainEligible: Boolean,
) {
    class RateLimit(
        val retryAfterMs: Long?,
    ) : GatewayError(retryable = true, chainEligible = true)

    class Timeout : GatewayError(retryable = true, chainEligible = true) // connect / TTFB only

    class Network : GatewayError(retryable = true, chainEligible = true)

    class Provider5xx(
        val status: Int, // incl. Anthropic 529 — the ORIGINAL upstream status, carried through (FI-6 regression)
    ) : GatewayError(retryable = true, chainEligible = true)

    class Auth(
        val status: Int,
    ) : GatewayError(retryable = false, chainEligible = false)

    class Validation(
        val detail: String,
    ) : GatewayError(retryable = false, chainEligible = false)

    // ⚑ LG-D2 deviation: design lists CONTEXT_LENGTH as plain non-retryable; contracts §5.3 makes it
    // non-retryable but CHAIN-ELIGIBLE (a next chain entry may have a larger context). Revert = one bool.
    class ContextLength : GatewayError(retryable = false, chainEligible = true)

    class ContentFilter : GatewayError(retryable = false, chainEligible = false)

    class BudgetExceeded : GatewayError(retryable = false, chainEligible = false)

    class Internal(
        val cause: Throwable?,
    ) : GatewayError(retryable = false, chainEligible = false)
}

/**
 * Carrier so routes/engine can `throw` a typed failure; the StatusPages handler unwraps it and renders
 * the OpenAI-shaped body. Keeps route code declarative — no manual status/JSON in handlers.
 */
class GatewayException(
    val error: GatewayError,
) : RuntimeException(error::class.simpleName)
