package org.tatrman.kantheon.kleio.v1

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Kleio/DocWH P5 Stage 5.1 T1 — `kleio/v1` shape guard (contracts §8). The agent
 * proto round-trips; the grounding contract types (GroundedResponse + SourceUse +
 * Status incl. STATUS_NO_GROUNDING) carry over the wire.
 */
class KleioProtoSpec :
    StringSpec({
        "KleioRequest round-trips with notebook + caller" {
            val req =
                KleioRequest
                    .newBuilder()
                    .setId("turn-1")
                    .setQuestion("what does the contract say about churn?")
                    .setNotebookId("nb1")
                    .setCaller(
                        Caller
                            .newBuilder()
                            .setUserId("bora")
                            .setTenantId("t")
                            .build(),
                    ).build()
            val back = KleioRequest.parseFrom(req.toByteArray())
            back.notebookId shouldBe "nb1"
            back.caller.userId shouldBe "bora"
        }

        "GroundedResponse carries SourceUse + the NO_GROUNDING status" {
            val resp =
                GroundedResponse
                    .newBuilder()
                    .setId("r1")
                    .setRequestId("turn-1")
                    .setStatus(Status.STATUS_NO_GROUNDING)
                    .addSourcesUsed(
                        SourceUse
                            .newBuilder()
                            .setSourceId(
                                3,
                            ).setPartId(11)
                            .setPageId(42)
                            .setTitle("Kaufland")
                            .setScore(0.9),
                    ).setResourceUsage(ResourceUsage.newBuilder().setRetrievalCount(8).setTotalLatencyMs(120))
                    .build()
            val back = GroundedResponse.parseFrom(resp.toByteArray())
            back.status shouldBe Status.STATUS_NO_GROUNDING
            back.sourcesUsedList.first().hasPageId() shouldBe true
            back.resourceUsage.retrievalCount shouldBe 8
        }

        "ArtifactRequest carries the four artifact kinds" {
            ArtifactKind.SUMMARY.number shouldBe 1
            ArtifactKind.BRIEFING.number shouldBe 4
            val a =
                ArtifactRequest
                    .newBuilder()
                    .setNotebookId(
                        "nb",
                    ).setKind(ArtifactKind.TIMELINE)
                    .setFocus("Q3")
                    .build()
            ArtifactRequest.parseFrom(a.toByteArray()).kind shouldBe ArtifactKind.TIMELINE
        }
    })
