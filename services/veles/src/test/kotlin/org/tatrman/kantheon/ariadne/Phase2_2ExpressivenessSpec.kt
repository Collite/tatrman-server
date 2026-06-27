package org.tatrman.kantheon.ariadne

import org.tatrman.ariadne.v1.GetObjectRequest
import org.tatrman.ariadne.v1.GetObjectResponse
import org.tatrman.ariadne.v1.GetRolesForEntityRequest
import org.tatrman.ariadne.v1.ListRolesRequest
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.kantheon.ariadne.graph.ModelGraph
import org.tatrman.kantheon.ariadne.grpc.MetadataServiceImpl
import org.tatrman.kantheon.ariadne.model.Er2CncRoleMapping
import org.tatrman.kantheon.ariadne.model.ModelDescriptor
import org.tatrman.kantheon.ariadne.reconcile.ModelReconciler
import org.tatrman.kantheon.ariadne.registry.MetadataRegistry
import org.tatrman.kantheon.ariadne.source.BuiltinStockSource
import org.tatrman.kantheon.ariadne.source.FileBasedSource
import org.tatrman.kantheon.ariadne.source.LocalFsStorage
import org.tatrman.kantheon.ariadne.source.SourceSnapshot
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.parser.loader.TtrLoader
import org.tatrman.ttr.parser.model.EntityDef
import java.nio.file.Files

/**
 * Phase 2.2 — Model expressiveness.
 *
 * Covers the full chain: BuiltinStockSource auto-loads `cnc.role.*` stock
 * vocabulary; user fixtures attach roles via shorthand and add display_label
 * / value_labels; MetadataServiceImpl serves the new fields on the per-kind
 * detail oneof.
 */
@Suppress("ClassName")
class Phase2_2ExpressivenessSpec :
    StringSpec({

        fun service(userTtr: String? = null): MetadataServiceImpl {
            val sources = mutableListOf<org.tatrman.kantheon.ariadne.source.ModelSource>(BuiltinStockSource())
            if (userTtr != null) {
                val tmp = Files.createTempDirectory("phase22-user")
                Files.writeString(tmp.resolve("user.ttr"), userTtr)
                sources +=
                    FileBasedSource(
                        sourceId = "user-fixture",
                        priority = 100,
                        storage = LocalFsStorage(id = "user-fixture", rootPath = tmp),
                    )
            }
            val reconciler = ModelReconciler(ModelDescriptor(id = "p22", name = "p22", description = ""))
            val snapshots = sources.map { it.load() }
            val result = reconciler.reconcile(snapshots)
            val registry = MetadataRegistry()
            registry.swap(result.model, ModelGraph.build(result.model), result.warnings + result.errors)
            return MetadataServiceImpl(registry)
        }

        // ----- B.2 stock vocabulary -----

        "BuiltinStockSource loads the six stock roles with localised labels" {
            val snap = BuiltinStockSource().load()
            snap.roles.size shouldBe 6
            val factQn =
                QualifiedName
                    .newBuilder()
                    .setPackage("cnc")
                    .setSchemaCode(org.tatrman.plan.v1.SchemaCode.CNC)
                    .setNamespace("role")
                    .setName("fact")
                    .build()
            val fact = snap.roles[factQn]!!
            fact.label.byLanguage["cs"] shouldBe "Faktová entita"
            fact.label.byLanguage["en"] shouldBe "Fact entity"
            snap.protectedQnames shouldContain factQn
        }

        "GetObject(cnc.cnc.role.fact) returns RoleDetail with localised label" {
            val qn =
                QualifiedName
                    .newBuilder()
                    .setPackage("cnc")
                    .setSchemaCode(org.tatrman.plan.v1.SchemaCode.CNC)
                    .setNamespace("role")
                    .setName("fact")
                    .build()
            val r = service().getObject(GetObjectRequest.newBuilder().setQualifiedName(qn).build())
            r.contentCase shouldBe GetObjectResponse.ContentCase.ROLE
            r.role.label.byLanguageMap["cs"] shouldBe "Faktová entita"
            r.objectDescriptor.schemaCode shouldBe SchemaCode.CNC
            r.objectDescriptor.kind shouldBe "role"
        }

        "Reconciler rejects user-source attempts to redefine cnc.cnc.role.fact" {
            val userTtr =
                """
                package cnc
                schema cnc

                def role fact {
                    label { cs: "Hijacked" }
                }
                """.trimIndent()
            val sources =
                listOf<org.tatrman.kantheon.ariadne.source.ModelSource>(
                    BuiltinStockSource(),
                    object : org.tatrman.kantheon.ariadne.source.ModelSource {
                        override fun load(): SourceSnapshot {
                            val pr = TtrLoader.parseString(userTtr, fileLabel = "user.ttr")
                            // Build a minimal snapshot the same way FileBasedSource would
                            // for a single role def, but at lower priority and without
                            // protectedQnames.
                            val rolesMap = mutableMapOf<QualifiedName, org.tatrman.kantheon.ariadne.model.Role>()
                            for (def in pr.definitions.filterIsInstance<org.tatrman.ttr.parser.model.RoleDef>()) {
                                val qn =
                                    QualifiedName
                                        .newBuilder()
                                        .setPackage("cnc")
                                        .setSchemaCode(org.tatrman.plan.v1.SchemaCode.CNC)
                                        .setNamespace("role")
                                        .setName(def.name)
                                        .build()
                                rolesMap[qn] =
                                    org.tatrman.kantheon.ariadne.model.Role(
                                        internalId = "user:${def.name}",
                                        qname = qn,
                                        sourceFile = "user.ttr",
                                        label =
                                            org.tatrman.kantheon.ariadne.model.LocalizedText(
                                                def.label?.byLanguage ?: emptyMap(),
                                            ),
                                    )
                            }
                            return SourceSnapshot(
                                sourceId = "user",
                                priority = 100,
                                version = "v1",
                                roles = rolesMap,
                            )
                        }
                    },
                )
            val reconciler = ModelReconciler(ModelDescriptor(id = "p22", name = "p22", description = ""))
            val result = reconciler.reconcile(sources.map { it.load() })
            val protectedRejection = result.errors.any { it.message.contains("protected qname 'cnc.cnc.role.fact'") }
            protectedRejection shouldBe true
            // Stock label survived.
            val factQn =
                QualifiedName
                    .newBuilder()
                    .setPackage("cnc")
                    .setSchemaCode(org.tatrman.plan.v1.SchemaCode.CNC)
                    .setNamespace("role")
                    .setName("fact")
                    .build()
            val fact = (result.model.schemas["cnc"] as org.tatrman.kantheon.ariadne.model.CncSchema).roles[factQn]!!
            fact.label.byLanguage["cs"] shouldBe "Faktová entita"
        }

        // ----- B.3 / G2 — roles shorthand desugars into Er2CncRoleMapping -----

        "entity with `roles: [fact, transaction]` shorthand emits two mappings" {
            val userTtr =
                """
                schema er

                def entity Objednavka {
                    roles: [fact, transaction]
                }
                """.trimIndent()
            val pr = TtrLoader.parseString(userTtr, fileLabel = "user.ttr")
            pr.ok shouldBe true
            val e = pr.definitions[0] as EntityDef
            e.roles.map { it.path } shouldBe listOf("fact", "transaction")

            // Loading through a real source produces the mappings in the model.
            val svc = service(userTtr)
            val resp =
                svc.getSnapshot(
                    org.tatrman.ariadne.v1.GetSnapshotRequest
                        .getDefaultInstance(),
                )
            val mappingEntries =
                resp.snapshot.objectsList.filter { it.objectDescriptor.kind == "er2cnc_role_mapping" }
            mappingEntries.size shouldBe 2
            mappingEntries.all {
                it.contentCase == org.tatrman.ariadne.v1.ObjectEntry.ContentCase.ER2CNC_ROLE_MAPPING
            } shouldBe
                true
            val factMapping =
                mappingEntries.first { it.er2CncRoleMapping.role.name == "fact" }
            factMapping.er2CncRoleMapping.entity.name shouldBe "Objednavka"
        }

        // ----- C.3 / D.3 — display_label + value_labels round-trip via GetObject -----

        "GetObject(er.entity.Zakaznik) returns EntityDetail.display_label" {
            val userTtr =
                """
                schema er

                def entity Zakaznik {
                    displayLabel { cs: "Zákazník", en: "Customer" }
                }
                """.trimIndent()
            val qn =
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(org.tatrman.plan.v1.SchemaCode.ER)
                    .setNamespace("entity")
                    .setName("Zakaznik")
                    .build()
            val r = service(userTtr).getObject(GetObjectRequest.newBuilder().setQualifiedName(qn).build())
            r.contentCase shouldBe GetObjectResponse.ContentCase.ENTITY
            r.entity.displayLabel.byLanguageMap["cs"] shouldBe "Zákazník"
            r.entity.displayLabel.byLanguageMap["en"] shouldBe "Customer"
        }

        "GetObject(er.attribute.STAV) returns AttributeDetail.value_labels + display_label" {
            val userTtr =
                """
                schema er

                def entity Zakaznik {
                    attributes: [
                        def attribute STAV {
                            type: int
                            displayLabel { cs: "Stav", en: "Status" }
                            valueLabels {
                                "1": { cs: "Aktivní", en: "Active" }
                                "2": { cs: "Neaktivní", en: "Inactive" }
                            }
                        }
                    ]
                }
                """.trimIndent()
            val qn =
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(org.tatrman.plan.v1.SchemaCode.ER)
                    .setNamespace("entity")
                    .setName("Zakaznik.STAV")
                    .build()
            val r = service(userTtr).getObject(GetObjectRequest.newBuilder().setQualifiedName(qn).build())
            r.contentCase shouldBe GetObjectResponse.ContentCase.ATTRIBUTE
            r.attribute.displayLabel.byLanguageMap["cs"] shouldBe "Stav"
            r.attribute.valueLabelsMap["1"]
                ?.byLanguageMap
                ?.get("cs") shouldBe "Aktivní"
            r.attribute.valueLabelsMap["2"]
                ?.byLanguageMap
                ?.get("en") shouldBe "Inactive"
        }

        "missing display_label / value_labels round-trip as empty" {
            val userTtr =
                """
                schema er

                def entity Plain {
                    attributes: [
                        def attribute name { type: text }
                    ]
                }
                """.trimIndent()
            val attrQn =
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(org.tatrman.plan.v1.SchemaCode.ER)
                    .setNamespace("entity")
                    .setName("Plain.name")
                    .build()
            val r = service(userTtr).getObject(GetObjectRequest.newBuilder().setQualifiedName(attrQn).build())
            r.contentCase shouldBe GetObjectResponse.ContentCase.ATTRIBUTE
            r.attribute.displayLabel.byLanguageMap
                .isEmpty() shouldBe true
            r.attribute.valueLabelsMap.isEmpty() shouldBe true
        }

        // ----- B-coupled helper: model carries the right Er2CncRoleMapping -----

        // ----- B.4 — ListRoles + GetRolesForEntity RPCs -----

        "ListRoles returns the six stock roles with their RoleDetail" {
            val r = service().listRoles(ListRolesRequest.getDefaultInstance())
            r.itemsList.size shouldBe 6
            val names = r.itemsList.map { it.objectDescriptor.localName }.toSet()
            names shouldBe setOf("fact", "dimension", "structural", "master", "transaction", "bridge")
            val factEntry = r.itemsList.first { it.objectDescriptor.localName == "fact" }
            factEntry.role.label.byLanguageMap["cs"] shouldBe "Faktová entita"
        }

        "GetRolesForEntity returns the qnames the entity plays" {
            val userTtr =
                """
                schema er

                def entity Objednavka { roles: [fact, transaction] }
                """.trimIndent()
            val target =
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(org.tatrman.plan.v1.SchemaCode.ER)
                    .setNamespace("entity")
                    .setName("Objednavka")
                    .build()
            val r =
                service(userTtr).getRolesForEntity(
                    GetRolesForEntityRequest.newBuilder().setEntity(target).build(),
                )
            val roleNames = r.rolesList.map { it.name }.toSet()
            roleNames shouldBe setOf("fact", "transaction")
        }

        "GetRolesForEntity for an unknown entity returns empty + object_not_found message" {
            val target =
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(org.tatrman.plan.v1.SchemaCode.ER)
                    .setNamespace("entity")
                    .setName("Nope")
                    .build()
            val r =
                service().getRolesForEntity(GetRolesForEntityRequest.newBuilder().setEntity(target).build())
            r.rolesList.isEmpty() shouldBe true
            r.messagesList.any { it.code == "object_not_found" } shouldBe true
        }

        // ----- B-coupled helper: model carries the right Er2CncRoleMapping -----

        "Model.mappings includes the Er2CncRoleMappings authored via shorthand" {
            val userTtr =
                """
                schema er

                def entity Objednavka { roles: [fact] }
                """.trimIndent()
            // Pull the model directly out of the registry to inspect.
            val tmp = Files.createTempDirectory("phase22-mappings")
            Files.writeString(tmp.resolve("user.ttr"), userTtr)
            val sources =
                listOf<org.tatrman.kantheon.ariadne.source.ModelSource>(
                    BuiltinStockSource(),
                    FileBasedSource(
                        sourceId = "user",
                        priority = 100,
                        storage = LocalFsStorage(id = "user", rootPath = tmp),
                    ),
                )
            val result =
                ModelReconciler(ModelDescriptor(id = "p22", name = "p22", description = ""))
                    .reconcile(sources.map { it.load() })
            val factMapping =
                result.model.mappings
                    .filterIsInstance<Er2CncRoleMapping>()
                    .single { it.role.name == "fact" }
            factMapping.entity.name shouldBe "Objednavka"
        }
    })
