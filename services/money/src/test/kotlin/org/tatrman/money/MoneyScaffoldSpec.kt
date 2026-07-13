package org.tatrman.money

import org.tatrman.grounding.v1.EntityKind
import org.tatrman.grounding.v1.GetStatusRequest
import org.tatrman.grounding.v1.GroundRequest
import org.tatrman.grounding.v1.GroundResponse
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.money.discover.EmptyMoneyDiscovery
import org.tatrman.money.grpc.MoneyGroundingService

/**
 * A10.2 scaffold DoD — the service boots, GetStatus answers, and an unrecognized Ground span
 * degrades to UNGROUNDABLE (never throws).
 */
class MoneyScaffoldSpec :
    StringSpec({
        val svc = MoneyGroundingService(EmptyMoneyDiscovery, llmFallback = null)

        "GetStatus reports service=money, ready, and capability flags" {
            val s = svc.getStatus(GetStatusRequest.getDefaultInstance())
            s.service shouldBe "money"
            s.ready shouldBe true
            s.capabilitiesMap["metadata"] shouldBe "ok"
            s.capabilitiesMap["llm_fallback"] shouldBe "false"
        }

        "Ground on an unrecognized span degrades to UNGROUNDABLE (scaffold)" {
            val r =
                svc.ground(
                    GroundRequest
                        .newBuilder()
                        .setSpanText("nothing recognizable yet")
                        .setKind(EntityKind.MONEY)
                        .build(),
                )
            r.status shouldBe GroundResponse.Status.UNGROUNDABLE
        }
    })
