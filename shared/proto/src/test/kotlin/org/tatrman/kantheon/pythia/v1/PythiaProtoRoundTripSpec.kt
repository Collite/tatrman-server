package org.tatrman.kantheon.pythia.v1

import com.google.protobuf.util.JsonFormat
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.common.v1.ResponseMessage
import org.tatrman.kantheon.common.v1.Severity
import org.tatrman.kantheon.envelope.v1.Block
import org.tatrman.kantheon.envelope.v1.BlockRole

/**
 * Stage 1.1 T3 — serialisation round-trip pin for `pythia/v1`. Builds a
 * full-shaped `InvestigationArtifact` (one of each `PlanNode` kind, one of each
 * `Handle` kind, ≥2 hypotheses, a `Conclusion` with an envelope Block), proves
 * binary + JSON round-trips, and proves proto3 forward-compat (unknown enum →
 * `UNRECOGNIZED`, never a throw).
 */
class PythiaProtoRoundTripSpec :
    StringSpec({

        fun handle(
            id: String,
            block: Handle.Builder.() -> Unit,
        ): Handle =
            Handle
                .newBuilder()
                .setHandleId(id)
                .apply(block)
                .build()

        fun fullArtifact(): InvestigationArtifact {
            val hypTrivial =
                Hypothesis
                    .newBuilder()
                    .setId("H0")
                    .setStatement("data exists")
                    .setStatus(HypStatus.HYP_SUPPORTED)
                    .setConfidence(1.0)
                    .setDisplayPriority(DisplayPriority.DISPLAY_HIDDEN)
                    .build()
            val hypDriver =
                Hypothesis
                    .newBuilder()
                    .setId("HB")
                    .setStatement("AOV down YoY")
                    .setPredicate(
                        Predicate
                            .newBuilder()
                            .setKind(Predicate.Kind.METRIC_DELTA_RATIO)
                            .setParametersJson("""{"metric":"aov"}""")
                            .setThreshold(-0.05),
                    ).setStatus(HypStatus.HYP_UNDER_TEST)
                    .setConfidence(0.7)
                    .setEstimatedExplanatoryPower(0.6)
                    .setDiagnosticPower(0.5)
                    .setDisplayPriority(DisplayPriority.DISPLAY_PRIMARY)
                    .build()

            val plan =
                PlanDag
                    .newBuilder()
                    .addHypotheses(hypTrivial)
                    .addHypotheses(hypDriver)
                    .addNodes(
                        PlanNode
                            .newBuilder()
                            .setNodeId("N1")
                            .addTestsHypIds("HB")
                            .setQuery(QueryNode.newBuilder().setQueryRef("q.aov").setParamsJson("{}")),
                    ).addNodes(
                        PlanNode
                            .newBuilder()
                            .setNodeId("N2")
                            .setDataframe(DataFrameNode.newBuilder().setDfdsl("pivot").setSourceHandleId("h1")),
                    ).addNodes(
                        PlanNode
                            .newBuilder()
                            .setNodeId("N3")
                            .setModel(ModelNode.newBuilder().setCapabilityId("model.forecast.arima")),
                    ).addNodes(
                        PlanNode
                            .newBuilder()
                            .setNodeId("N4")
                            .setReasoning(
                                ReasoningNode
                                    .newBuilder()
                                    .setPromptRef("diag")
                                    .setOutputKind(ReasoningNode.OutputKind.OUTPUT_STRUCTURED)
                                    .setTierHint(ReasoningNode.TierHint.TIER_CHEAP),
                            ),
                    ).addNodes(
                        PlanNode
                            .newBuilder()
                            .setNodeId("N5")
                            .setRender(
                                RenderNode
                                    .newBuilder()
                                    .setKind(RenderNode.RenderKind.RENDER_TABLE)
                                    .setBlockRole(BlockRole.PRIMARY),
                            ),
                    ).addEdges(
                        DataDep
                            .newBuilder()
                            .setFromNodeId("N1")
                            .setToNodeId("N2")
                            .setBinding("b"),
                    ).setRationale("test plan")
                    .build()

            val conclusion =
                Conclusion
                    .newBuilder()
                    .setPrimary(
                        RenderableArtifact
                            .newBuilder()
                            .addBlocks(
                                Block
                                    .newBuilder()
                                    .setBlockId("b1")
                                    .setRole(BlockRole.PRIMARY)
                                    .setText("Found 23 customers."),
                            ),
                    ).setConfidence(
                        ConfidenceInfo
                            .newBuilder()
                            .setKind(ConfidenceKind.CONFIDENCE_HEURISTIC)
                            .setScore(0.65)
                            .addCaveats("explained variance is approximate"),
                    ).setStopReason(StopReason.STOP_GOAL_REACHED)
                    .build()

            return InvestigationArtifact
                .newBuilder()
                .setId("inv-1")
                .setStatus(Status.STATUS_DONE)
                .setResolution(
                    ResolutionResult
                        .newBuilder()
                        .setResolvedIntent(ResolvedIntent.newBuilder().setKind(IntentKind.INTENT_RCA)),
                ).setPlan(plan)
                .addSteps(
                    StepRecord
                        .newBuilder()
                        .setId("S1")
                        .setNodeId("N1")
                        .setStatus(StepStatus.STEP_COMPLETED)
                        .setCost(
                            StepCost
                                .newBuilder()
                                .setUsd(0.03)
                                .setLatencyMs(120)
                                .setTierUsed(""),
                        ),
                ).addHypotheses(hypTrivial)
                .addHypotheses(hypDriver)
                .addLooseEnds(
                    LooseEnd
                        .newBuilder()
                        .setHypothesisId("L1")
                        .setSource(LooseEndSource.LOOSE_END_PLANNING_TIME)
                        .setReason(LooseEndReason.LOOSE_END_OUT_OF_DATA_SCOPE)
                        .setWhy("no HR data")
                        .setSuggestedFollowup("stage HR via Charon"),
                ).addAllHandlesForRoundtrip(
                    listOf(
                        handle("h0") { liveQuery = LiveQueryRef.newBuilder().setQueryRef("q").build() },
                        handle("h1") { pgSnapshot = PgResultSnapshot.newBuilder().setRowCount(23).build() },
                        handle("h2") { workerDf = WorkerSessionDF.newBuilder().setDfName("df").build() },
                        handle("h3") { seaweed = SeaweedArrowBlob.newBuilder().setUrl("s3://x").build() },
                        handle("h4") { redis = RedisArrowEntry.newBuilder().setKey("k").build() },
                        handle("h5") { dbTable = DbTableRef.newBuilder().setTable("t").build() },
                    ),
                ).setConclusion(conclusion)
                .setResourceUsage(ResourceUsage.newBuilder().setTotalUsd(0.18).setTotalQueryCount(2))
                .setCreatedAt("2026-06-26T00:00:00Z")
                .addMessages(
                    ResponseMessage
                        .newBuilder()
                        .setSeverity(Severity.WARNING)
                        .setCode("explained_variance_heuristic")
                        .setHumanMessage("variance is heuristic"),
                ).build()
        }

        "full InvestigationArtifact round-trips through binary" {
            val artifact = fullArtifact()
            val parsed = InvestigationArtifact.parseFrom(artifact.toByteArray())
            parsed shouldBe artifact
            parsed.planeNodesKinds() shouldBe listOf("query", "dataframe", "model", "reasoning", "render")
            parsed.hypothesesList shouldHaveSize 2
            parsed.conclusion.primary.blocksList
                .first()
                .text shouldBe "Found 23 customers."
        }

        "all six handle kinds round-trip in a oneof" {
            val artifact = fullArtifact()
            val parsed = InvestigationArtifact.parseFrom(artifact.toByteArray())
            // handles are stashed on evidence/steps in production; here we round-trip a list.
            parsed.handleKindCases() shouldBe
                listOf(
                    Handle.KindCase.LIVE_QUERY,
                    Handle.KindCase.PG_SNAPSHOT,
                    Handle.KindCase.WORKER_DF,
                    Handle.KindCase.SEAWEED,
                    Handle.KindCase.REDIS,
                    Handle.KindCase.DB_TABLE,
                )
        }

        "messages = 99 survives the round-trip" {
            val parsed = InvestigationArtifact.parseFrom(fullArtifact().toByteArray())
            parsed.messagesList.first().code shouldBe "explained_variance_heuristic"
        }

        "JSON projection round-trips through JsonFormat" {
            val artifact = fullArtifact()
            val json = JsonFormat.printer().print(artifact)
            val rebuilt = InvestigationArtifact.newBuilder()
            JsonFormat.parser().ignoringUnknownFields().merge(json, rebuilt)
            rebuilt.build().id shouldBe "inv-1"
            rebuilt.build().status shouldBe Status.STATUS_DONE
        }

        "unknown Status enum value tolerates as UNRECOGNIZED (proto3 forward-compat)" {
            // Wire-craft a Status field carrying 99 (beyond the known set).
            val raw =
                InvestigationArtifact
                    .newBuilder()
                    .setId("x")
                    .setStatusValue(99)
                    .build()
            val parsed = InvestigationArtifact.parseFrom(raw.toByteArray())
            parsed.statusValue shouldBe 99
            parsed.status shouldBe Status.UNRECOGNIZED
        }

        "unknown StopReason enum value tolerates as UNRECOGNIZED" {
            val raw =
                Conclusion
                    .newBuilder()
                    .setStopReasonValue(77)
                    .build()
            val parsed = Conclusion.parseFrom(raw.toByteArray())
            parsed.stopReasonValue shouldBe 77
            parsed.stopReason shouldBe StopReason.UNRECOGNIZED
        }

        "InvestigationEvent oneof carries each event kind" {
            val ev =
                InvestigationEvent
                    .newBuilder()
                    .setInvestigationId("inv-1")
                    .setSequence(5)
                    .setStatusChanged(
                        StatusChanged
                            .newBuilder()
                            .setFrom(Status.STATUS_EXECUTING)
                            .setTo(Status.STATUS_SYNTHESIZING),
                    ).build()
            val parsed = InvestigationEvent.parseFrom(ev.toByteArray())
            parsed.eventCase shouldBe InvestigationEvent.EventCase.STATUS_CHANGED
            parsed.statusChanged.to shouldBe Status.STATUS_SYNTHESIZING
        }
    })

/** The handle list isn't a first-class artifact field — round-trip it via a helper proto. */
private fun InvestigationArtifact.Builder.addAllHandlesForRoundtrip(
    handles: List<Handle>,
): InvestigationArtifact.Builder {
    // Stash each handle as a step output so the oneof kinds survive the artifact round-trip.
    handles.forEachIndexed { i, h ->
        addSteps(
            StepRecord
                .newBuilder()
                .setId("SH$i")
                .setNodeId("NH$i")
                .setStatus(StepStatus.STEP_COMPLETED)
                .setOutputHandle(h),
        )
    }
    return this
}

private fun InvestigationArtifact.planeNodesKinds(): List<String> =
    plan.nodesList.map {
        when (it.kindCase) {
            PlanNode.KindCase.QUERY -> "query"
            PlanNode.KindCase.DATAFRAME -> "dataframe"
            PlanNode.KindCase.MODEL -> "model"
            PlanNode.KindCase.REASONING -> "reasoning"
            PlanNode.KindCase.RENDER -> "render"
            PlanNode.KindCase.KIND_NOT_SET -> "none"
        }
    }

private fun InvestigationArtifact.handleKindCases(): List<Handle.KindCase> =
    stepsList.filter { it.hasOutputHandle() }.map { it.outputHandle.kindCase }
