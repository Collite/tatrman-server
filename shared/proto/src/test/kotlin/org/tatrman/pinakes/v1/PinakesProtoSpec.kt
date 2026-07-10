package org.tatrman.pinakes.v1

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Kleio/DocWH P1 Stage 1.1 T2 — `pinakes/v1` shape guard. Proves the platform-
 * service proto root compiles to Kotlin and that the pipeline/run/lineage types
 * round-trip on the wire (contracts §2). Fourth non-`kantheon.*` proto root.
 */
class PinakesProtoSpec :
    StringSpec({
        "PipelineRun round-trips with StageRecord + RunStatus" {
            val run =
                PipelineRun
                    .newBuilder()
                    .setId("run-1")
                    .setPipelineId("pl-erp")
                    .addAssetIds("asset-9")
                    .setStatus(RunStatus.PARTIAL)
                    .addStageRecords(
                        StageRecord
                            .newBuilder()
                            .setStageId("extract-0")
                            .setKind(StageKind.EXTRACT)
                            .setStatus("SUCCEEDED")
                            .setItemsIn(1)
                            .setItemsOut(12)
                            .setLatencyMs(34)
                            .build(),
                    ).build()
            val back = PipelineRun.parseFrom(run.toByteArray())
            back.status shouldBe RunStatus.PARTIAL
            back.stageRecordsList.first().kind shouldBe StageKind.EXTRACT
            back.stageRecordsList.first().itemsOut shouldBe 12
        }

        "Pipeline carries an ordered stage DAG + a conformed EmbedConfig" {
            val pl =
                Pipeline
                    .newBuilder()
                    .setId("pl-erp")
                    .setDisplayName("ERP export")
                    .setSourceFeed("erp")
                    .addStages(
                        Stage
                            .newBuilder()
                            .setId("s0")
                            .setKind(StageKind.EXTRACT)
                            .setConfigJson("{}"),
                    ).addStages(
                        Stage
                            .newBuilder()
                            .setId("s1")
                            .setKind(StageKind.CHUNK)
                            .setConfigJson("{\"maxWords\":200}"),
                    ).setEmbed(
                        EmbedConfig
                            .newBuilder()
                            .setModelId("bge-m3")
                            .setDimensions(1024)
                            .setModelVersion("1"),
                    ).build()
            val back = Pipeline.parseFrom(pl.toByteArray())
            back.stagesList.map { it.kind } shouldBe listOf(StageKind.EXTRACT, StageKind.CHUNK)
            back.embed.dimensions shouldBe 1024
        }

        "Asset + Lineage carry the catalogue provenance chain" {
            val asset =
                Asset
                    .newBuilder()
                    .setId("asset-9")
                    .setAssetRef("docwh-stage/erp/9.pdf")
                    .setSourceFeed("erp")
                    .setMimeType("application/pdf")
                    .setOriginalName("9.pdf")
                    .build()
            Asset.parseFrom(asset.toByteArray()).sourceFeed shouldBe "erp"
            val lineage =
                Lineage
                    .newBuilder()
                    .setAssetId("asset-9")
                    .addRunIds("run-1")
                    .addSourceIds(3)
                    .addPageIds(42)
                    .build()
            Lineage.parseFrom(lineage.toByteArray()).sourceIdsList shouldBe listOf(3L)
        }
    })
