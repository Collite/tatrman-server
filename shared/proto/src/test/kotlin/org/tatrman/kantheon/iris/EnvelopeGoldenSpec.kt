package org.tatrman.kantheon.iris

import com.google.protobuf.util.JsonFormat
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.common.v1.AgentId
import org.tatrman.kantheon.common.v1.BlockProvenance
import org.tatrman.kantheon.common.v1.HandoffContext
import org.tatrman.kantheon.common.v1.ViewProvenance
import org.tatrman.kantheon.envelope.v1.Block
import org.tatrman.kantheon.envelope.v1.BlockRole
import org.tatrman.kantheon.envelope.v1.Chip
import org.tatrman.kantheon.envelope.v1.FormatEnvelope
import org.tatrman.kantheon.envelope.v1.FormatKind
import org.tatrman.kantheon.envelope.v1.FormatSpec
import org.tatrman.kantheon.envelope.v1.InvestigateChip
import org.tatrman.kantheon.envelope.v1.PlanSource
import org.tatrman.kantheon.envelope.v1.PromptChip
import org.tatrman.kantheon.envelope.v1.RoutingPickChip
import org.tatrman.kantheon.envelope.v1.TableDetails
import org.tatrman.kantheon.envelope.v1.TableHeader
import org.tatrman.kantheon.iris.v1.ChatResumeRequest
import org.tatrman.kantheon.iris.v1.DoneEvent
import org.tatrman.kantheon.iris.v1.ErrorEvent
import org.tatrman.kantheon.iris.v1.IrisStreamEvent
import org.tatrman.kantheon.iris.v1.StepEvent
import org.tatrman.kantheon.iris.v1.ThinkingEvent
import org.tatrman.kantheon.iris.v1.ToolCallEvent

/**
 * Iris Stage 1.1 — KT golden round-trip for envelope/v1 + iris/v1.
 *
 * Proves the JVM JSON path iris-bff relies on: a typed message prints to
 * proto-canonical JSON and parses back identically (proto → JSON → proto), the
 * additive PD-1/PD-9 fields (InvestigateChip, BlockProvenance) survive, and the
 * parser accepts BOTH proto-canonical camelCase and the legacy v2 snake_case
 * field names (the audit-path tolerance, contracts §3.1). Enum *values* must be
 * proto names (UPPERCASE) on the wire — the v2 lowercase→UPPERCASE remap is the
 * normalisation shim's job (TS side here; GolemV2Client BFF-side, Stage 1.3).
 */
class EnvelopeGoldenSpec :
    StringSpec({

        val printer = JsonFormat.printer()
        // Strict parser: NO ignoringUnknownFields(), so any unrecognized field
        // name throws. The golden tests build typed messages whose every field
        // name the proto must recognize — a renamed/dropped documented field
        // must fail here, not be silently swallowed (review FIX 2c).
        val parser = JsonFormat.parser()

        fun roundTrip(env: FormatEnvelope): FormatEnvelope {
            val json = printer.print(env)
            val b = FormatEnvelope.newBuilder()
            parser.merge(json, b)
            return b.build()
        }

        "table envelope round-trips through proto-canonical JSON unchanged" {
            val env =
                FormatEnvelope
                    .newBuilder()
                    .apply {
                        bubbleId = "b-0001"
                        turnId = "t-0001"
                        threadId = "s-0001"
                        contentJson = """[{"customer":"Kaufland ČR v.o.s.","revenue":1820345.5}]"""
                        format =
                            FormatSpec
                                .newBuilder()
                                .setKind(FormatKind.TABLE)
                                .setTable(
                                    TableDetails
                                        .newBuilder()
                                        .addHeaders(TableHeader.newBuilder().setName("customer").setTitle("Zákazník")),
                                ).build()
                        planSource = PlanSource.PATTERN
                        planScore = 0.93
                        createdAt = "2026-06-17T09:00:00Z"
                        agentVersion = "golem-v2@1.4.0"
                    }.build()

            roundTrip(env) shouldBe env
        }

        "chips round-trip across all three oneof arms (prompt / routing / investigate)" {
            val env =
                FormatEnvelope
                    .newBuilder()
                    .apply {
                        bubbleId = "b"
                        turnId = "t"
                        threadId = "s"
                        format = FormatSpec.newBuilder().setKind(FormatKind.PLAINTEXT).build()
                        planSource = PlanSource.PATTERN
                        planScore = 0.5
                        createdAt = "2026-06-17T09:06:00Z"
                        agentVersion = "golem-v2@1.4.0"
                        addChips(
                            Chip.newBuilder().setPrompt(
                                PromptChip
                                    .newBuilder()
                                    .setDisplay("Tržby")
                                    .setPrompt("Ukaž tržby")
                                    .setSource("static"),
                            ),
                        )
                        addChips(
                            Chip.newBuilder().setRouting(
                                RoutingPickChip
                                    .newBuilder()
                                    .setAgentId(AgentId.newBuilder().setValue("pythia"))
                                    .setLabel("Pythia")
                                    .setWhy("analytical, cross-domain"),
                            ),
                        )
                        addChips(
                            Chip.newBuilder().setInvestigate(
                                InvestigateChip
                                    .newBuilder()
                                    .setHandoff(
                                        HandoffContext
                                            .newBuilder()
                                            .setSourceAgentId("golem-erp")
                                            .setUserQuestion("proč klesly tržby?")
                                            .setView(ViewProvenance.newBuilder().setPatternId("revenue-by-month")),
                                    ).setProposedQuestion("Investigate the revenue drop")
                                    .setLabel("Investigate this"),
                            ),
                        )
                    }.build()

            val rt = roundTrip(env)
            rt shouldBe env
            rt.chipsList[0].hasPrompt() shouldBe true
            rt.chipsList[1]
                .routing.agentId.value shouldBe "pythia"
            rt.chipsList[2]
                .investigate.handoff.sourceAgentId shouldBe "golem-erp"
        }

        "Block carries BlockProvenance (PD-9) through the round-trip" {
            val block =
                Block
                    .newBuilder()
                    .setBlockId("blk-1")
                    .setRole(BlockRole.PRIMARY)
                    .setText("Tržby vzrostly o 15 %.")
                    .setFormat(FormatSpec.newBuilder().setKind(FormatKind.MARKDOWN))
                    .setProvenance(
                        BlockProvenance
                            .newBuilder()
                            .setProducingAgentId("golem-erp")
                            .setView(ViewProvenance.newBuilder().setPatternId("revenue-by-month").setTotalRows(12))
                            .addSourceTables("sales"),
                    ).build()

            val json = printer.print(block)
            val b = Block.newBuilder()
            parser.merge(json, b)
            val rt = b.build()
            rt shouldBe block
            rt.provenance.producingAgentId shouldBe "golem-erp"
            rt.provenance.view.totalRows shouldBe 12L
        }

        "parser accepts proto-canonical camelCase field names" {
            val json =
                """
                {"bubbleId":"b-c","turnId":"t-c","threadId":"s-c",
                 "format":{"kind":"TABLE"},"planSource":"AMEND","planScore":0.7,
                 "createdAt":"2026-06-17T09:01:00Z","agentVersion":"golem-v2@1.4.0"}
                """.trimIndent()
            val b = FormatEnvelope.newBuilder()
            parser.merge(json, b)
            val env = b.build()
            env.bubbleId shouldBe "b-c"
            env.planSource shouldBe PlanSource.AMEND
            env.format.kind shouldBe FormatKind.TABLE
        }

        "parser tolerates legacy v2 snake_case field names + additive-field absence" {
            // snake_case names (proto3 JSON accepts the original field name) with
            // proto-name enum VALUES. No provenance / investigate chip present.
            val json =
                """
                {"bubble_id":"b-s","turn_id":"t-s","thread_id":"s-s",
                 "content_json":"[]","format":{"kind":"CHART"},
                 "plan_source":"FREE_SQL","plan_score":0.0,
                 "created_at":"2026-06-17T09:03:00Z","agent_version":"golem-v2@1.4.0"}
                """.trimIndent()
            val b = FormatEnvelope.newBuilder()
            parser.merge(json, b)
            val env = b.build()
            // Assert EVERY fed field survives the round-trip (not just a few),
            // so a silent drop of any snake_case name fails the test (FIX 2c).
            // The strict parser (no ignoringUnknownFields) also makes an
            // unrecognized name throw rather than be swallowed.
            env.bubbleId shouldBe "b-s"
            env.turnId shouldBe "t-s"
            env.threadId shouldBe "s-s"
            env.contentJson shouldBe "[]"
            env.format.kind shouldBe FormatKind.CHART
            env.planSource shouldBe PlanSource.FREE_SQL
            env.planScore shouldBe 0.0
            env.createdAt shouldBe "2026-06-17T09:03:00Z"
            env.agentVersion shouldBe "golem-v2@1.4.0"
            env.hasAgentId() shouldBe false
            env.chipsList.none { it.hasInvestigate() } shouldBe true
        }

        "IrisStreamEvent round-trips an envelope arm and a done arm" {
            val env =
                FormatEnvelope
                    .newBuilder()
                    .apply {
                        bubbleId = "b"
                        turnId = "t"
                        threadId = "s"
                        format = FormatSpec.newBuilder().setKind(FormatKind.PLAINTEXT).build()
                        planSource = PlanSource.PATTERN
                        planScore = 1.0
                        createdAt = "2026-06-17T09:00:00Z"
                        agentVersion = "golem-v2@1.4.0"
                    }.build()

            val evEnvelope =
                IrisStreamEvent
                    .newBuilder()
                    .setTurnId("t")
                    .setSequence(9)
                    .setEnvelope(env)
                    .build()
            val evDone =
                IrisStreamEvent
                    .newBuilder()
                    .setTurnId("t")
                    .setSequence(10)
                    .setDone(DoneEvent.newBuilder().setOutcome("done"))
                    .build()

            for (ev in listOf(evEnvelope, evDone)) {
                val b = IrisStreamEvent.newBuilder()
                parser.merge(printer.print(ev), b)
                b.build() shouldBe ev
            }
        }

        "IrisStreamEvent round-trips the remaining oneof arms (step / tool_call / thinking / error)" {
            val evStep =
                IrisStreamEvent
                    .newBuilder()
                    .setTurnId("t")
                    .setSequence(1)
                    .setStep(
                        StepEvent
                            .newBuilder()
                            .setNode("resolve")
                            .setPhase("completed")
                            .setDetailJson("""{"rows":12}""")
                            .setLatencyMs(42),
                    ).build()
            val evToolCall =
                IrisStreamEvent
                    .newBuilder()
                    .setTurnId("t")
                    .setSequence(2)
                    .setToolCall(
                        ToolCallEvent
                            .newBuilder()
                            .setTool("theseus-mcp")
                            .setPhase("started")
                            .setSummary("running query"),
                    ).build()
            val evThinking =
                IrisStreamEvent
                    .newBuilder()
                    .setTurnId("t")
                    .setSequence(3)
                    .setThinking(ThinkingEvent.newBuilder().setTextDelta("uvažuji…"))
                    .build()
            val evError =
                IrisStreamEvent
                    .newBuilder()
                    .setTurnId("t")
                    .setSequence(4)
                    .setError(
                        ErrorEvent
                            .newBuilder()
                            .setCode("E_TIMEOUT")
                            .setMessage("worker timed out")
                            .setRecoverable(true),
                    ).build()

            for (ev in listOf(evStep, evToolCall, evThinking, evError)) {
                val b = IrisStreamEvent.newBuilder()
                parser.merge(printer.print(ev), b)
                b.build() shouldBe ev
            }

            // Each arm sets the expected event case + a representative field survives.
            roundTripStreamEvent(evStep).let {
                it.eventCase shouldBe IrisStreamEvent.EventCase.STEP
                it.step.node shouldBe "resolve"
                it.step.latencyMs shouldBe 42L
            }
            roundTripStreamEvent(evToolCall).let {
                it.eventCase shouldBe IrisStreamEvent.EventCase.TOOL_CALL
                it.toolCall.tool shouldBe "theseus-mcp"
            }
            roundTripStreamEvent(evThinking).let {
                it.eventCase shouldBe IrisStreamEvent.EventCase.THINKING
                it.thinking.textDelta shouldBe "uvažuji…"
            }
            roundTripStreamEvent(evError).let {
                it.eventCase shouldBe IrisStreamEvent.EventCase.ERROR
                it.error.code shouldBe "E_TIMEOUT"
            }
        }

        "ChatResumeRequest round-trips both answer oneof arms (selected_option_id / free_text_answer)" {
            val selected =
                ChatResumeRequest
                    .newBuilder()
                    .setSessionId("s-1")
                    .setResumeToken("rt-1")
                    .setSelectedOptionId("opt-7")
                    .build()
            val freeText =
                ChatResumeRequest
                    .newBuilder()
                    .setSessionId("s-2")
                    .setResumeToken("rt-2")
                    .setFreeTextAnswer("Kaufland ČR v.o.s.")
                    .build()

            roundTripResume(selected).let {
                it shouldBe selected
                it.answerCase shouldBe ChatResumeRequest.AnswerCase.SELECTED_OPTION_ID
                it.selectedOptionId shouldBe "opt-7"
            }
            roundTripResume(freeText).let {
                it shouldBe freeText
                it.answerCase shouldBe ChatResumeRequest.AnswerCase.FREE_TEXT_ANSWER
                it.freeTextAnswer shouldBe "Kaufland ČR v.o.s."
            }
        }
    })

private val printer = JsonFormat.printer()
private val strictParser = JsonFormat.parser()

private fun roundTripStreamEvent(ev: IrisStreamEvent): IrisStreamEvent {
    val b = IrisStreamEvent.newBuilder()
    strictParser.merge(printer.print(ev), b)
    return b.build()
}

private fun roundTripResume(req: ChatResumeRequest): ChatResumeRequest {
    val b = ChatResumeRequest.newBuilder()
    strictParser.merge(printer.print(req), b)
    return b.build()
}
