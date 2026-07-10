package org.tatrman.kantheon.hebe.v1

import com.google.protobuf.Timestamp
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.common.v1.ResponseMessage
import org.tatrman.kantheon.common.v1.Severity

/**
 * Round-trip spec for `hebe/v1` (Hebe P4 S4.1 T2), mirroring CapabilitiesProtoSpec.
 * Locks the boundary-crossing types: the `KantheonQuestionBody` fields, the
 * `RoutineRun` lifecycle incl. `AWAITING_AGENT`, `DeliveryRecord`, and Rule 6
 * (`messages = 99`).
 */
class HebeProtoSpec :
    StringSpec({

        "KantheonQuestionBody carries question / session_ref / delivery_channels / routing_hint" {
            val body =
                KantheonQuestionBody
                    .newBuilder()
                    .setQuestion("How did revenue trend last quarter?")
                    .setSessionRef("sess-weekly-revenue")
                    .addDeliveryChannels("telegram")
                    .addDeliveryChannels("web")
                    .setRoutingHint("golem-erp")
                    .build()

            val parsed = KantheonQuestionBody.parseFrom(body.toByteArray())
            parsed.question shouldBe "How did revenue trend last quarter?"
            parsed.sessionRef shouldBe "sess-weekly-revenue"
            parsed.deliveryChannelsList shouldContainExactly listOf("telegram", "web")
            parsed.routingHint shouldBe "golem-erp"
        }

        "Routine wraps a KantheonQuestionBody in the oneof" {
            val routine =
                Routine
                    .newBuilder()
                    .setRoutineId("r1")
                    .setName("Weekly revenue")
                    .setCron("0 3 * * 1")
                    .setEnabled(true)
                    .setBody(
                        RoutineBody
                            .newBuilder()
                            .setKantheon(KantheonQuestionBody.newBuilder().setQuestion("q").build()),
                    ).build()

            val parsed = Routine.parseFrom(routine.toByteArray())
            parsed.body.hasKantheon() shouldBe true
            parsed.body.kantheon.question shouldBe "q"
            parsed.cron shouldBe "0 3 * * 1"
        }

        "RoutineRun round-trips with AWAITING_AGENT + a DeliveryRecord" {
            val run =
                RoutineRun
                    .newBuilder()
                    .setRunId("run-1")
                    .setRoutineId("r1")
                    .setStatus(RunStatus.AWAITING_AGENT)
                    .setTurnRef("turn-abc")
                    .setStartedAt(Timestamp.newBuilder().setSeconds(1_700_000_000).build())
                    .addDeliveries(
                        DeliveryRecord
                            .newBuilder()
                            .setChannel("telegram")
                            .setOk(true)
                            .build(),
                    ).build()

            val parsed = RoutineRun.parseFrom(run.toByteArray())
            parsed.status shouldBe RunStatus.AWAITING_AGENT
            parsed.turnRef shouldBe "turn-abc"
            parsed.deliveriesList.single().channel shouldBe "telegram"
            parsed.deliveriesList.single().ok shouldBe true
        }

        "field 99 carries ResponseMessage (Rule 6)" {
            val run =
                RoutineRun
                    .newBuilder()
                    .setRunId("run-1")
                    .addMessages(
                        ResponseMessage
                            .newBuilder()
                            .setSeverity(Severity.WARNING)
                            .setHumanMessage("queued, will run when reconnected"),
                    ).build()

            val parsed = RoutineRun.parseFrom(run.toByteArray())
            parsed.messagesList.single().severity shouldBe Severity.WARNING
            parsed.messagesList.single().humanMessage shouldBe "queued, will run when reconnected"
        }
    })
