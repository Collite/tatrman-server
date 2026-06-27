package org.tatrman.kantheon.themis.v1

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.common.v1.AgentId
import org.tatrman.kantheon.common.v1.HandoffContext

/**
 * Phase 3 Stage 3.1 — proto round-trip + backward-compat for the routing
 * extensions. themis.proto has no java_multiple_files, so the generated types
 * nest under the outer class [Themis]. AgentId / HandoffContext live in
 * common/v1 (D2 move, 2026-06-12).
 *
 * The additions are wire-compatible with the v0.1.0 proto: every new field uses a
 * previously-unused number (profile=7 since mode=6; refusal=6 in the outcome oneof
 * since trace_id=4/elapsed_ms=5; intent_kind=10/routing=11; multi_question=8;
 * picked_agent=4).
 */
class ThemisProtoExtensionsSpec :
    StringSpec({

        "RoutingDecision round-trips through proto" {
            val rd =
                Themis.RoutingDecision
                    .newBuilder()
                    .setChosenAgentId(AgentId.newBuilder().setValue("pythia").build())
                    .setConfidence(0.84)
                    .setRationale("RCA → Pythia per Layer 1 rule")
                    .setNeedsUserPick(false)
                    .setLayerHit(1)
                    .addAlternates(
                        Themis.AgentAlternate
                            .newBuilder()
                            .setAgentId(AgentId.newBuilder().setValue("golem-erp").build())
                            .setScore(0.41)
                            .setWhy("procedural fallback"),
                    ).build()

            val parsed = Themis.RoutingDecision.parseFrom(rd.toByteArray())

            parsed.chosenAgentId.value shouldBe "pythia"
            parsed.layerHit shouldBe 1
            parsed.confidence shouldBe 0.84
            parsed.alternatesList shouldHaveSize 1
            parsed.alternatesList
                .first()
                .agentId.value shouldBe "golem-erp"
        }

        "ResolveResponse refusal outcome populated" {
            val refusal =
                Themis.RefusalWithGaps
                    .newBuilder()
                    .setRationale("Entity 'Foo' could not be resolved")
                    .addGaps(
                        Themis.Gap
                            .newBuilder()
                            .setKind(Themis.GapKind.ENTITY_UNMAPPED)
                            .setDescription("Foo: no match in customer namespace")
                            .setSuggestedAction("Did you mean Ford?"),
                    ).build()

            val resp =
                Themis.ResolveResponse
                    .newBuilder()
                    .setRefusal(refusal)
                    .build()
            val parsed = Themis.ResolveResponse.parseFrom(resp.toByteArray())

            parsed.outcomeCase shouldBe Themis.ResolveResponse.OutcomeCase.REFUSAL
            parsed.refusal.gapsList
                .first()
                .kind shouldBe Themis.GapKind.ENTITY_UNMAPPED
            parsed.refusal.gapsList
                .first()
                .suggestedAction shouldBe "Did you mean Ford?"
        }

        "Resolution carries intent_kind + routing additively" {
            val resolution =
                Themis.Resolution
                    .newBuilder()
                    .setFunctionId("listUnpaidInvoices")
                    .setIntentKind(Themis.IntentKind.RCA)
                    .setRouting(
                        Themis.RoutingDecision
                            .newBuilder()
                            .setChosenAgentId(AgentId.newBuilder().setValue("pythia").build())
                            .setLayerHit(1),
                    ).build()

            val parsed = Themis.Resolution.parseFrom(resolution.toByteArray())

            parsed.intentKind shouldBe Themis.IntentKind.RCA
            parsed.hasRouting() shouldBe true
            parsed.routing.chosenAgentId.value shouldBe "pythia"
        }

        "a v0.1.0-shaped Resolution (no routing fields) deserialises with defaults — additive compat" {
            // Simulate an older producer that only set the shipped fields.
            val legacy =
                Themis.Resolution
                    .newBuilder()
                    .setFunctionId("foo")
                    .setArgsJson("{}")
                    .setConfidence(0.9)
                    .build()

            val parsed = Themis.Resolution.parseFrom(legacy.toByteArray())

            parsed.functionId shouldBe "foo"
            parsed.intentKind shouldBe Themis.IntentKind.INTENT_KIND_UNSPECIFIED
            parsed.hasRouting() shouldBe false
        }

        "AwaitingClarification.kind = MultiQuestionDetected" {
            val mq =
                Themis.MultiQuestionDetected
                    .newBuilder()
                    .addSubQuestions("Co je objednávka 12345?")
                    .addSubQuestions("A jaké je její celkové množství?")
                    .setDecomposition(Themis.Decomposition.SPLIT)
                    .setDecompositionRationale("two disjoint intents")
                    .build()

            val await =
                Themis.AwaitingClarification
                    .newBuilder()
                    .setQuestion("Detected two independent clauses")
                    .setMultiQuestion(mq)
                    .build()

            val parsed = Themis.AwaitingClarification.parseFrom(await.toByteArray())

            parsed.kindCase shouldBe Themis.AwaitingClarification.KindCase.MULTI_QUESTION
            parsed.multiQuestion.subQuestionsList shouldHaveSize 2
            parsed.multiQuestion.decomposition shouldBe Themis.Decomposition.SPLIT
        }

        "ResolveRequest routing extensions coexist with mode (field 6)" {
            val req =
                Themis.ResolveRequest
                    .newBuilder()
                    .setConversationId("c-1")
                    .setMode(Themis.ResolveMode.RESOLVE_MODE_NORMAL)
                    .setProfile(Themis.Profile.CHAT_QUICK)
                    .setRoutingHint(AgentId.newBuilder().setValue("pythia").build())
                    .setPriorContext(
                        HandoffContext.newBuilder().setSourceAgentId("golem-erp").build(),
                    ).build()

            val parsed = Themis.ResolveRequest.parseFrom(req.toByteArray())

            parsed.mode shouldBe Themis.ResolveMode.RESOLVE_MODE_NORMAL
            parsed.profile shouldBe Themis.Profile.CHAT_QUICK
            parsed.routingHint.value shouldBe "pythia"
            parsed.priorContext.sourceAgentId shouldBe "golem-erp"
        }

        "ResumeAnswer.picked_agent carries the Layer-3 chip pick" {
            val resume =
                Themis.ResumeAnswer
                    .newBuilder()
                    .setToken("hmac.blob")
                    .setPickedAgent(AgentId.newBuilder().setValue("golem-erp").build())
                    .build()

            val parsed = Themis.ResumeAnswer.parseFrom(resume.toByteArray())

            parsed.token shouldBe "hmac.blob"
            parsed.pickedAgent.value shouldBe "golem-erp"
        }
    })
