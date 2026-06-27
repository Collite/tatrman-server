package org.tatrman.kantheon.argos.client

import org.tatrman.kantheon.argos.policy.PolicyEngine
import org.tatrman.security.v1.EvaluatePoliciesRequest
import org.tatrman.security.v1.EvaluatePoliciesResponse

/**
 * Thin contract over policy evaluation's `EvaluatePolicies`.
 *
 * The SecurityApplier stage depends on this interface — never on a concrete engine —
 * so unit tests can pass a stub implementation. Since fork Stage 3.2 the production
 * wiring is [LocalPolicyClient]: policy evaluation runs **in-process** inside Argos
 * (the sql-security gRPC service was folded in; contracts §1). The former
 * `GrpcSecurityClient` + its remote channel are gone.
 */
fun interface SecurityClient {
    suspend fun evaluatePolicies(request: EvaluatePoliciesRequest): EvaluatePoliciesResponse
}

/**
 * In-process [SecurityClient] backed by the folded [PolicyEngine]. No network hop:
 * Argos evaluates row-level predicates + column rules itself, keying on the bearer
 * roles carried in `EvaluatePoliciesRequest.context.auth_roles`.
 */
class LocalPolicyClient(
    private val engine: PolicyEngine,
) : SecurityClient {
    override suspend fun evaluatePolicies(request: EvaluatePoliciesRequest): EvaluatePoliciesResponse =
        engine.evaluatePolicies(request)
}
