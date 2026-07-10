package org.tatrman.kallimachos.v1

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Kleio/DocWH P1 Stage 1.1 T2 — `kallimachos/v1` shape guard. Proves the platform-
 * service proto root compiles to Kotlin and that the corpus + retrieval types
 * round-trip on the wire (contracts §1). Third non-`kantheon.*` proto root after
 * ariadne/charon.
 */
class KallimachosProtoSpec :
    StringSpec({
        "Page round-trips with concept_ref + derived_from_parts" {
            val page =
                Page
                    .newBuilder()
                    .setId(42)
                    .setKind(PageKind.ENTITY)
                    .setTitle("Kaufland")
                    .setContentMd("# Kaufland\n\nA retail chain.")
                    .setConceptRef(
                        ConceptRef
                            .newBuilder()
                            .setEntityType("customer")
                            .setEntityId("wiki:kaufland")
                            .setDisplayLabel("Kaufland")
                            .build(),
                    ).addDerivedFromParts(7)
                    .addDerivedFromParts(8)
                    .build()
            val back = Page.parseFrom(page.toByteArray())
            back.kind shouldBe PageKind.ENTITY
            back.conceptRef.entityType shouldBe "customer"
            back.conceptRef.ariadneQname shouldBe "" // empty in v1 — §6 seam
            back.derivedFromPartsList shouldBe listOf(7L, 8L)
        }

        "ContextChunk round-trips with Citation + RetrievalLead" {
            val chunk =
                ContextChunk
                    .newBuilder()
                    .setPartId(11)
                    .setSourceId(3)
                    .setPageId(42)
                    .setText("…retrieved text…")
                    .setScore(0.87)
                    .setLead(RetrievalLead.GRAPH)
                    .setCitation(
                        Citation
                            .newBuilder()
                            .setSourceId(3)
                            .setPartId(11)
                            .setPageId(42)
                            .setTitle("Kaufland")
                            .setLocator("¶12")
                            .setSourceRef("kallimachos://nb1/3/11")
                            .build(),
                    ).build()
            val back = ContextChunk.parseFrom(chunk.toByteArray())
            back.lead shouldBe RetrievalLead.GRAPH
            back.hasPageId() shouldBe true
            back.citation.sourceRef shouldBe "kallimachos://nb1/3/11"
        }

        "MetadataValue oneof carries single + list" {
            val single = MetadataValue.newBuilder().setSingle("A").build()
            val list = MetadataValue.newBuilder().setList(StringList.newBuilder().addValues("B").addValues("C")).build()
            single.kindCase shouldBe MetadataValue.KindCase.SINGLE
            list.list.valuesList shouldBe listOf("B", "C")
        }

        "EdgeKind + GraphEdge carry the wiki link set" {
            val edge =
                GraphEdge
                    .newBuilder()
                    .setFrom(1)
                    .setTo(2)
                    .setKind(EdgeKind.CONTRADICTS)
                    .setWeight(0.5)
                    .build()
            GraphEdge.parseFrom(edge.toByteArray()).kind shouldBe EdgeKind.CONTRADICTS
        }

        "LoadSourceRequest carries a Source + its Parts (internal write surface)" {
            val req =
                LoadSourceRequest
                    .newBuilder()
                    .setSource(
                        Source
                            .newBuilder()
                            .setId(3)
                            .setTitle("doc")
                            .setEmbeddingStatus(EmbeddingStatus.PENDING),
                    ).addParts(
                        Part
                            .newBuilder()
                            .setId(11)
                            .setSourceId(3)
                            .setIdx(0)
                            .setKind("paragraph")
                            .setContentText("p"),
                    ).build()
            val back = LoadSourceRequest.parseFrom(req.toByteArray())
            back.source.embeddingStatus shouldBe EmbeddingStatus.PENDING
            back.partsCount shouldBe 1
        }
    })
