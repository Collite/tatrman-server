package org.tatrman.kantheon.ariadne.refresh

import org.tatrman.plan.v1.QualifiedName
import org.tatrman.kantheon.ariadne.model.ModelDescriptor
import org.tatrman.kantheon.ariadne.reconcile.ModelReconciler
import org.tatrman.kantheon.ariadne.registry.MetadataRegistry
import org.tatrman.kantheon.ariadne.source.ModelSource
import org.tatrman.kantheon.ariadne.source.SourceSnapshot
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking

class MetadataRefresherSpec :
    StringSpec({

        fun snapshot(
            id: String,
            version: String,
            tableName: String = "customers",
        ): SourceSnapshot =
            SourceSnapshot(
                sourceId = id,
                priority = 100,
                version = version,
                tables =
                    mapOf(
                        QualifiedName
                            .newBuilder()
                            .setSchemaCode(org.tatrman.plan.v1.SchemaCode.DB)
                            .setNamespace("dbo")
                            .setName(tableName)
                            .build() to
                            org.tatrman.kantheon.ariadne.model.DbTable(
                                internalId = "$id.$tableName",
                                qname =
                                    QualifiedName
                                        .newBuilder()
                                        .setSchemaCode(org.tatrman.plan.v1.SchemaCode.DB)
                                        .setNamespace("dbo")
                                        .setName(tableName)
                                        .build(),
                            ),
                    ),
            )

        /** Mutable in-test source whose successive `load()` calls return scripted snapshots. */
        class ScriptedSource(
            private val snapshots: List<SourceSnapshot>,
        ) : ModelSource {
            private var index = 0

            override fun load(): SourceSnapshot {
                val s = snapshots[index.coerceAtMost(snapshots.size - 1)]
                index++
                return s
            }
        }

        fun freshRegistry(): MetadataRegistry = MetadataRegistry()

        fun freshReconciler(): ModelReconciler = ModelReconciler(ModelDescriptor(id = "test", name = "test"))

        "first refresh swaps the snapshot and reports new_version + snapshot_swapped" {
            val src = ScriptedSource(listOf(snapshot("s1", "v1")))
            val registry = freshRegistry()
            val recon = freshReconciler()
            val refresher = MetadataRefresher(listOf(src), listOf("s1"), recon, registry)

            val results = runBlocking { refresher.refresh(sourceId = "", force = false) }
            results.size shouldBe 1
            val r = results.single()
            r.sourceId shouldBe "s1"
            r.success shouldBe true
            r.oldVersion shouldBe ""
            r.newVersion shouldBe "v1"
            r.snapshotSwapped shouldBe true
            r.newModelVersion shouldBe (
                registry
                    .read()!!
                    .model.version.value
            )
        }

        "refresh with no version change does NOT swap the snapshot" {
            val src = ScriptedSource(listOf(snapshot("s1", "v1"), snapshot("s1", "v1")))
            val registry = freshRegistry()
            val recon = freshReconciler()
            val refresher = MetadataRefresher(listOf(src), listOf("s1"), recon, registry)

            runBlocking { refresher.refresh("", false) } // initial swap
            val results = runBlocking { refresher.refresh("", false) }
            results.single().snapshotSwapped shouldBe false
        }

        "refresh picks up a changed source version" {
            val src = ScriptedSource(listOf(snapshot("s1", "v1"), snapshot("s1", "v2", tableName = "orders")))
            val registry = freshRegistry()
            val recon = freshReconciler()
            val refresher = MetadataRefresher(listOf(src), listOf("s1"), recon, registry)

            runBlocking { refresher.refresh("", false) }
            val modelV1 =
                registry
                    .read()!!
                    .model.version.value
            val results = runBlocking { refresher.refresh("", false) }
            val r = results.single()
            r.success shouldBe true
            r.oldVersion shouldBe "v1"
            r.newVersion shouldBe "v2"
            r.snapshotSwapped shouldBe true
            registry
                .read()!!
                .model.version.value shouldBe r.newModelVersion
            (
                registry
                    .read()!!
                    .model.version.value != modelV1
            ) shouldBe true
        }

        "unknown source_id → success=false with unknown_source_id" {
            val src = ScriptedSource(listOf(snapshot("s1", "v1")))
            val refresher = MetadataRefresher(listOf(src), listOf("s1"), freshReconciler(), freshRegistry())

            val results = runBlocking { refresher.refresh("ghost", false) }
            results.single().success shouldBe false
            results.single().errorMessage shouldContain "unknown_source_id"
        }

        "source load failure returns success=false + error_message; other sources still report" {
            val ok = ScriptedSource(listOf(snapshot("ok", "v1")))
            val bad =
                object : ModelSource {
                    override fun load(): SourceSnapshot = throw RuntimeException("boom")
                }
            val refresher =
                MetadataRefresher(
                    listOf(ok, bad),
                    listOf("ok", "bad"),
                    freshReconciler(),
                    freshRegistry(),
                )
            val results = runBlocking { refresher.refresh("", false) }
            results.size shouldBe 2
            results.first { it.sourceId == "ok" }.success shouldBe true
            val badResult = results.first { it.sourceId == "bad" }
            badResult.success shouldBe false
            badResult.errorMessage shouldContain "boom"
        }

        "per-source refresh reloads only the named source; others reuse cached snapshots" {
            val srcA = ScriptedSource(listOf(snapshot("a", "vA1", "tableA"), snapshot("a", "vA2", "tableA")))
            val srcB = ScriptedSource(listOf(snapshot("b", "vB1", "tableB"), snapshot("b", "vB-WRONG", "tableB")))
            val refresher =
                MetadataRefresher(
                    listOf(srcA, srcB),
                    listOf("a", "b"),
                    freshReconciler(),
                    freshRegistry(),
                )

            // Initial: load both (each ScriptedSource returns its first snapshot).
            runBlocking { refresher.refresh("", false) }

            // Now refresh just `a`. srcB.load() should NOT be called (it would return vB-WRONG).
            val results = runBlocking { refresher.refresh("a", false) }
            results.first { it.sourceId == "a" }.newVersion shouldBe "vA2"
            // reused cache, not the scripted second value
            results.first { it.sourceId == "b" }.newVersion shouldBe "vB1"
        }
    })
