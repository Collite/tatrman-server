package org.tatrman.kantheon.ariadne.source

import org.tatrman.plan.v1.QualifiedName
import org.tatrman.kantheon.ariadne.model.AreaRecord
import org.tatrman.kantheon.ariadne.model.Attribute
import org.tatrman.kantheon.ariadne.model.Cardinality
import org.tatrman.kantheon.ariadne.model.DbColumn
import org.tatrman.kantheon.ariadne.model.DbForeignKey
import org.tatrman.kantheon.ariadne.model.DbProcedure
import org.tatrman.kantheon.ariadne.model.DbTable
import org.tatrman.kantheon.ariadne.model.DbView
import org.tatrman.kantheon.ariadne.model.Entity
import org.tatrman.kantheon.ariadne.model.Er2CncRoleMapping
import org.tatrman.kantheon.ariadne.model.Er2DbAttributeMapping
import org.tatrman.kantheon.ariadne.model.Er2DbEntityMapping
import org.tatrman.kantheon.ariadne.model.Er2DbRelationMapping
import org.tatrman.kantheon.ariadne.model.AttributeMappingTarget
import org.tatrman.kantheon.ariadne.model.LocalizedText
import org.tatrman.kantheon.ariadne.model.LocalizedTextList
import org.tatrman.kantheon.ariadne.model.MappingSource
import org.tatrman.kantheon.ariadne.model.MappingTarget
import org.tatrman.kantheon.ariadne.model.Query
import org.tatrman.kantheon.ariadne.model.QueryParameterDef
import org.tatrman.kantheon.ariadne.model.Relation
import org.tatrman.kantheon.ariadne.model.Role
import org.tatrman.kantheon.ariadne.model.SearchHints
import org.tatrman.ttr.parser.loader.ParseError
import org.tatrman.ttr.parser.loader.ParseWarning
import org.tatrman.ttr.parser.loader.TtrLoader
import org.tatrman.ttr.parser.model.AreaDef
import org.tatrman.ttr.parser.model.ColumnDef as TtrColumnDef
import org.tatrman.ttr.parser.model.Definition
import org.tatrman.ttr.parser.model.EntityDef
import org.tatrman.ttr.parser.model.Er2CncRoleDef
import org.tatrman.ttr.parser.model.Er2DbAttributeDef
import org.tatrman.ttr.parser.model.Er2DbEntityDef
import org.tatrman.ttr.parser.model.Er2DbRelationDef
import org.tatrman.ttr.parser.model.FkDef
import org.tatrman.ttr.parser.model.LocalizedStringValue
import org.tatrman.ttr.parser.model.BindingColumnBareId
import org.tatrman.ttr.parser.model.BindingColumnObject
import org.tatrman.ttr.parser.model.BindingPropertyBareId
import org.tatrman.ttr.parser.model.BindingPropertyBlock
import org.tatrman.ttr.parser.model.PropertyValue
import org.tatrman.ttr.parser.model.QueryDef
import org.tatrman.ttr.parser.model.Reference as TtrReference
import org.tatrman.ttr.parser.model.RelationDef
import org.tatrman.ttr.parser.model.RoleDef
import org.tatrman.ttr.parser.model.TableDef
import org.tatrman.ttr.parser.model.TargetObjectValue
import org.tatrman.ttr.parser.model.TargetReferenceValue
import org.tatrman.ttr.parser.model.ViewDef
import java.nio.file.Files
import java.nio.file.Path

/**
 * One contributor of model fragments.
 *
 * Round 4 §4.C: orthogonal Storage × Parser × LiveDatabase sources. The
 * Phase 1.2 v1.2 MVP ships [FileBasedSource] backed by [DslParser] over
 * [LocalFsStorage]. Future work plugs in `GitArchiveStorage`, `S3Storage`,
 * `LiveDatabaseSource`, `YamlImportParser` without changing this surface.
 */
fun interface ModelSource {
    fun load(): SourceSnapshot
}

/**
 * One source's contribution. Reconciler merges multiple snapshots into a
 * single [org.tatrman.kantheon.ariadne.model.Model].
 */
data class SourceSnapshot(
    val sourceId: String,
    val priority: Int,
    val version: String,
    val tables: Map<QualifiedName, DbTable> = emptyMap(),
    val views: Map<QualifiedName, DbView> = emptyMap(),
    val procedures: Map<QualifiedName, DbProcedure> = emptyMap(),
    val foreignKeys: Map<QualifiedName, DbForeignKey> = emptyMap(),
    val entities: Map<QualifiedName, Entity> = emptyMap(),
    val relations: Map<QualifiedName, Relation> = emptyMap(),
    val mappings: List<org.tatrman.kantheon.ariadne.model.Mapping> = emptyList(),
    val queries: Map<QualifiedName, Query> = emptyMap(),
    /** Phase 2.2 — `cnc.role.*` objects contributed by this source. */
    val roles: Map<QualifiedName, Role> = emptyMap(),
    /** v2.2 — `def drill_map` objects (namespace `query.drill.*`). */
    val drillMaps: Map<QualifiedName, org.tatrman.kantheon.ariadne.model.DrillMap> = emptyMap(),
    /** Golem P4 S4.2 — `def area` blocks from `.ttrm` files, keyed by bare area name. */
    val areas: Map<String, AreaRecord> = emptyMap(),
    val warnings: List<LoadWarning> = emptyList(),
    val errors: List<LoadWarning> = emptyList(),
    /**
     * Phase 2.2 — qnames this source treats as protected. The reconciler
     * rejects any later source that attempts to redefine one of these qnames.
     * Used by the built-in stock-roles source to lock `cnc.role.fact` etc.
     */
    val protectedQnames: Set<QualifiedName> = emptySet(),
    /**
     * Per-file context collected during load. Used by the reconciler to run
     * reference resolution across all files at once (post-load pass).
     * Each entry carries the computed package (from directory path),
     * declared package (from `package` directive, may be null), imports,
     * and the raw definitions before they are turned into model objects.
     */
    val loadedFiles: List<LoadedFile> = emptyList(),
)

data class LoadWarning(
    val sourceId: String,
    val file: String,
    val line: Int,
    val column: Int,
    val message: String,
)

// =============================================================================
// Storage layer
// =============================================================================

interface ModelStorage {
    val id: String

    fun fetchVersion(): String

    fun listFiles(
        extensions: List<String>,
        prefixes: List<String> = emptyList(),
    ): List<StorageFile>

    fun read(file: StorageFile): String
}

data class StorageFile(
    val path: String,
    val sizeBytes: Long,
    val rootPath: Path? = null,
)

data class LoadedFile(
    val storageFile: StorageFile,
    val computedPackage: String,
    val declaredPackage: String?,
    val imports: List<org.tatrman.ttr.parser.model.ImportStatement>,
    val definitions: List<org.tatrman.ttr.parser.model.Definition>,
    val schemaCode: String,
    val namespace: String,
)

private fun listFilesMatching(
    rootPath: Path,
    extensions: List<String>,
    prefixes: List<String>,
): List<StorageFile> =
    Files
        .walk(rootPath)
        .filter { file ->
            Files.isRegularFile(file) &&
                extensions.any { ext -> file.fileName.toString().endsWith(".$ext") } &&
                (prefixes.isEmpty() || prefixes.any { prefix -> file.fileName.toString().startsWith(prefix) })
        }.sorted()
        .map { StorageFile(path = it.toString(), sizeBytes = Files.size(it), rootPath = rootPath) }
        .toList()

/** Read every `.ttr` file under [rootPath]. Recursive. */
class LocalFsStorage(
    override val id: String,
    private val rootPath: Path,
) : ModelStorage {
    override fun fetchVersion(): String =
        Files
            .walk(rootPath)
            .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".ttr") }
            .sorted()
            .map { "$it=${Files.getLastModifiedTime(it).toMillis()}" }
            .toList()
            .joinToString(separator = ";")
            .let { it.hashCode().toString(16) }

    override fun listFiles(
        extensions: List<String>,
        prefixes: List<String>,
    ): List<StorageFile> = listFilesMatching(rootPath, extensions, prefixes)

    override fun read(file: StorageFile): String = Files.readString(Path.of(file.path))
}

/** Read every `.ttr` resource matching [resourcePrefix] from the classpath. */
class ClasspathStorage(
    override val id: String,
    private val resourcePrefix: String,
) : ModelStorage {
    override fun fetchVersion(): String = "$resourcePrefix-classpath-static"

    override fun listFiles(
        extensions: List<String>,
        prefixes: List<String>,
    ): List<StorageFile> {
        val cl = Thread.currentThread().contextClassLoader
        val rootUrl = cl.getResource(resourcePrefix) ?: return emptyList()
        if (rootUrl.protocol != "file") return emptyList()
        return listFilesMatching(Path.of(rootUrl.toURI()), extensions, prefixes)
    }

    override fun read(file: StorageFile): String = Files.readString(Path.of(file.path))
}

// =============================================================================
// File-based source — composes Storage + DslParser
// =============================================================================

class FileBasedSource(
    private val sourceId: String,
    private val priority: Int,
    private val storage: ModelStorage,
    private val schemaCodeOverride: String? = null,
) : ModelSource {
    override fun load(): SourceSnapshot {
        val version = storage.fetchVersion()
        // Golem P4 S4.2 — area files carry a `.ttrm` extension (directive-less,
        // `def area` blocks). Walk both so they load alongside the model `.ttr` files.
        val files = storage.listFiles(listOf("ttr", "ttrm"))
        val warnings = mutableListOf<LoadWarning>()
        val errors = mutableListOf<LoadWarning>()
        val loadedFiles = mutableListOf<LoadedFile>()

        val tables = mutableMapOf<QualifiedName, DbTable>()
        val views = mutableMapOf<QualifiedName, DbView>()
        val procedures = mutableMapOf<QualifiedName, DbProcedure>()
        val foreignKeys = mutableMapOf<QualifiedName, DbForeignKey>()
        val entities = mutableMapOf<QualifiedName, Entity>()
        val relations = mutableMapOf<QualifiedName, Relation>()
        val mappings = mutableListOf<org.tatrman.kantheon.ariadne.model.Mapping>()
        val queries = mutableMapOf<QualifiedName, Query>()
        val roles = mutableMapOf<QualifiedName, Role>()
        val drillMaps = mutableMapOf<QualifiedName, org.tatrman.kantheon.ariadne.model.DrillMap>()
        val areas = mutableMapOf<String, AreaRecord>()

        for (file in files) {
            val content = storage.read(file)
            val pr = TtrLoader.parseString(content, fileLabel = file.path)
            errors += pr.errors.map { it.toLoadWarning(sourceId) }
            warnings += pr.warnings.map { it.toLoadWarning(sourceId) }
            if (!pr.ok) continue

            // yaml-converter-inline-split Stage 3 / item 2: a file with no model
            // directive is directive-less — each def derives its schema+namespace from
            // its kind (see [schemaNamespaceForKind]). The published symbol table also
            // derives per-kind from an empty file schema, so LoadedFile carries "".
            // NOTE (2026-07-05, modeler 0.8.4 / grammar 4.0 qname-redesign): the AST
            // renamed `schemaDirective: SchemaDirective(schemaCode, namespace)` →
            // `modelDirective: ModelDirective(modelCode, schema)` — the directive is now
            // `model <code> (schema <id>)?`. Same semantics, new spelling.
            val directiveSchema = schemaCodeOverride ?: pr.modelDirective?.modelCode
            val directiveLess = directiveSchema == null
            val schemaCode = directiveSchema ?: ""
            val namespace =
                if (directiveLess) "" else (pr.modelDirective?.schema ?: defaultNamespaceFor(directiveSchema!!))
            val declaredPackage = pr.packageName
            val computedPackage = computePackageFromPath(file, storage)
            if (declaredPackage != null && declaredPackage != computedPackage) {
                errors +=
                    LoadWarning(
                        sourceId = sourceId,
                        file = file.path,
                        line = -1,
                        column = -1,
                        message =
                            "ttr/package-declaration-mismatch: declared '$declaredPackage' != directory computed '$computedPackage'",
                    )
            }

            // A5 Task 4 — wrong-file-kind: def-kind must belong to the declared schema.
            // The mapping kinds (er2db_*, er2cnc_role) and query are schema-pinned (always
            // map/query) and are not constrained by the file's schema directive; they are
            // excluded from this check.  See Stage 09 decision record.
            // Directive-less files (Stage 3) legitimately mix kinds (db.ttr holds tables +
            // queries; er.ttr holds entities + relations; cnc.ttr holds roles + er2cnc),
            // so the check applies only when a schema is declared.
            if (!directiveLess) {
                val schemaKindAllowlist =
                    mapOf(
                        "db" to setOf("table", "view", "procedure", "foreign_key"),
                        "er" to setOf("entity", "relation"),
                        "cnc" to setOf("role"),
                    )
                for (def in pr.definitions) {
                    val defKind = definitionKind(def)
                    val allowedKinds = schemaKindAllowlist[schemaCode.lowercase()]
                    if (allowedKinds != null && defKind !in allowedKinds) {
                        errors +=
                            LoadWarning(
                                sourceId = sourceId,
                                file = file.path,
                                line = -1,
                                column = -1,
                                message =
                                    "ttr/wrong-file-kind: '$defKind' definition not allowed in 'schema $schemaCode' file",
                            )
                    }
                }
            }
            for (def in pr.definitions) {
                // Golem P4 S4.2 — `def area` blocks live in directive-less `.ttrm` files and
                // are not model objects (no qname / schema). Intercept them before the
                // schema/namespace derivation and stash them on the snapshot's `areas` map.
                if (def is AreaDef) {
                    areas[def.name] = AreaRecord(def.name, def.description ?: "", def.tags, def.packages)
                    continue
                }
                // Directive-less ⇒ derive (schema, namespace) per def kind; otherwise use
                // the file-level directive values for every def (legacy behaviour).
                val (defSchema, defNs) = if (directiveLess) schemaNamespaceForKind(def) else (schemaCode to namespace)
                ingestDefinition(
                    def,
                    defSchema,
                    defNs,
                    file.path,
                    tables,
                    views,
                    procedures,
                    foreignKeys,
                    entities,
                    relations,
                    mappings,
                    queries,
                    roles,
                    drillMaps,
                )
            }
            loadedFiles.add(
                LoadedFile(
                    storageFile = file,
                    computedPackage = computedPackage,
                    declaredPackage = declaredPackage,
                    imports = pr.imports,
                    definitions = pr.definitions,
                    schemaCode = schemaCode,
                    namespace = namespace,
                ),
            )
        }

        return SourceSnapshot(
            sourceId = sourceId,
            priority = priority,
            version = version,
            tables = tables,
            views = views,
            procedures = procedures,
            foreignKeys = foreignKeys,
            entities = entities,
            relations = relations,
            mappings = mappings,
            queries = queries,
            roles = roles,
            drillMaps = drillMaps,
            areas = areas,
            warnings = warnings,
            errors = errors,
            loadedFiles = loadedFiles.toList(),
        )
    }

    private fun computePackageFromPath(
        file: StorageFile,
        storage: ModelStorage,
    ): String {
        val rootPath = file.rootPath ?: return ""
        val filePath = Path.of(file.path)
        val rootUri = rootPath.toUri()
        val fileUri = filePath.toUri()
        val relativized =
            try {
                rootUri.relativize(fileUri)
            } catch (e: Exception) {
                return ""
            }
        val pathStr = relativized.path ?: return ""
        val parts = pathStr.split("/")
        return parts.dropLast(1).joinToString(".")
    }

    private fun defaultNamespaceFor(schemaCode: String): String =
        when (schemaCode) {
            "db" -> "dbo"
            "er" -> "entity"
            "query" -> "query"
            "map" -> "map"
            else -> ""
        }

    /**
     * Directive-less schema+namespace for a definition, keyed on its kind — the
     * loader-side counterpart of the published `defaultSchemaForKind` (item 2).
     * Reproduces the per-bucket conventions the directive-ful files used, so a
     * directive-less file reloads to the same model qnames: db objects under
     * `db.dbo`, entities under `er.entity`, relations under `er.relation`, roles
     * under `cnc.role`. Query and the mapping kinds are schema-pinned inside
     * [ingestDefinition] (always `query` / `map`), so their pair is unused.
     */
    private fun schemaNamespaceForKind(def: Definition): Pair<String, String> =
        when (definitionKind(def)) {
            "table", "view", "procedure", "foreign_key" -> "db" to "dbo"
            "entity" -> "er" to "entity"
            "relation" -> "er" to "relation"
            "role" -> "cnc" to "role"
            "query" -> "query" to "query"
            else -> "" to "" // mappings / drill_maps: ingest hardcodes their schema
        }

    private fun ingestDefinition(
        def: Definition,
        schemaCode: String,
        namespace: String,
        sourceFile: String,
        tables: MutableMap<QualifiedName, DbTable>,
        views: MutableMap<QualifiedName, DbView>,
        procedures: MutableMap<QualifiedName, DbProcedure>,
        foreignKeys: MutableMap<QualifiedName, DbForeignKey>,
        entities: MutableMap<QualifiedName, Entity>,
        relations: MutableMap<QualifiedName, Relation>,
        mappings: MutableList<org.tatrman.kantheon.ariadne.model.Mapping>,
        queries: MutableMap<QualifiedName, Query>,
        roles: MutableMap<QualifiedName, Role>,
        drillMaps: MutableMap<QualifiedName, org.tatrman.kantheon.ariadne.model.DrillMap>,
    ) {
        when (def) {
            is TableDef -> {
                val qn = qname(schemaCode, namespace, def.name)
                tables[qn] =
                    DbTable(
                        internalId = idFor("db.table", qn),
                        qname = qn,
                        description = def.description ?: "",
                        tags = def.tags,
                        sourceFile = sourceFile,
                        primaryKey = def.primaryKey,
                        columns =
                            def.columns.map { col ->
                                val colQn = qname(schemaCode, namespace, "${def.name}.${col.name}")
                                ttrColumnToDbColumn(colQn, qn, col, sourceFile)
                            },
                    )
            }
            is ViewDef -> {
                val qn = qname(schemaCode, namespace, def.name)
                views[qn] =
                    DbView(
                        internalId = idFor("db.view", qn),
                        qname = qn,
                        description = def.description ?: "",
                        tags = def.tags,
                        sourceFile = sourceFile,
                        columns =
                            def.columns.map {
                                ttrColumnToDbColumn(
                                    qname(schemaCode, namespace, "${def.name}.${it.name}"),
                                    qn,
                                    it,
                                    sourceFile,
                                )
                            },
                        definitionSql = def.definitionSql ?: "",
                    )
            }
            is FkDef -> {
                val qn = qname(schemaCode, namespace, def.name)
                foreignKeys[qn] =
                    DbForeignKey(
                        internalId = idFor("db.fk", qn),
                        qname = qn,
                        description = def.description ?: "",
                        tags = def.tags,
                        sourceFile = sourceFile,
                        fromColumns = qnameList(def.from),
                        toColumns = qnameList(def.to),
                    )
            }
            is EntityDef -> {
                val qn = qname(schemaCode, namespace, def.name)
                entities[qn] =
                    Entity(
                        internalId = idFor("er.entity", qn),
                        qname = qn,
                        description = def.description ?: "",
                        tags = def.tags,
                        sourceFile = sourceFile,
                        labelPlural = def.labelPlural ?: "",
                        nameAttribute = def.nameAttribute?.path ?: "",
                        codeAttribute = def.codeAttribute?.path ?: "",
                        aliases = def.aliases,
                        attributes =
                            def.attributes.map { a ->
                                val attrQn = qname(schemaCode, namespace, "${def.name}.${a.name}")
                                Attribute(
                                    internalId = idFor("er.attribute", attrQn),
                                    qname = attrQn,
                                    description = a.description ?: "",
                                    tags = a.tags,
                                    sourceFile = sourceFile,
                                    entity = qn,
                                    type = a.type?.name ?: "text",
                                    isKey = a.isKey,
                                    nullable = a.optional,
                                    displayLabel = a.displayLabel.toLocalizedText(),
                                    valueLabels = a.valueLabels.mapValues { (_, v) -> v.toLocalizedText() },
                                    search = a.search.toSearchHints(),
                                )
                            },
                        displayLabel = def.displayLabel.toLocalizedText(),
                        search = def.search.toSearchHints(),
                    )
                // `roles: [fact, dimension]` shorthand desugars to one
                // Er2CncRoleMapping per listed role. Bare names resolve to the
                // 4-part `cnc.cnc.role.<name>` (package `cnc`); qname-prefixed
                // forms (e.g. `cnc.cnc.role.fact`) pass through unchanged.
                def.roles.forEach { roleRef ->
                    val roleQn =
                        if (roleRef.path.split(".").size == 1) {
                            qname("cnc", "role", roleRef.path, "cnc")
                        } else {
                            Reference.toQname(roleRef.path, "cnc", "role")
                        }
                    val mappingQn =
                        qname(
                            "map",
                            "er2cnc_role",
                            "${def.name}__${roleQn.name}",
                        )
                    mappings +=
                        Er2CncRoleMapping(
                            internalId = idFor("map.er2cnc_role", mappingQn),
                            qname = mappingQn,
                            description = "",
                            tags = emptyList(),
                            sourceFile = sourceFile,
                            entity = qn,
                            role = roleQn,
                        )
                }
                // v2.1 — synthesise er2db_* mappings from any inline `mapping:` blocks
                // on the entity itself and on its attributes. These coexist with any
                // explicit `def er2db_*` declarations; the duplicate-mapping validator
                // catches collisions where the inline and explicit forms target the
                // same qname.
                synthesiseInlineEntityMappings(def, qn, sourceFile, mappings)
                synthesiseInlineAttributeMappings(def, qn, sourceFile, mappings)
            }
            is RelationDef -> {
                val qn = qname(schemaCode, namespace, def.name)
                relations[qn] =
                    Relation(
                        internalId = idFor("er.relation", qn),
                        qname = qn,
                        description = def.description ?: "",
                        tags = def.tags,
                        sourceFile = sourceFile,
                        fromEntity =
                            (def.from as? PropertyValue.IdValue)?.ref?.path?.let {
                                Reference.toQname(
                                    it,
                                    schemaCode,
                                    namespace,
                                )
                            }
                                ?: qn,
                        toEntity =
                            (def.to as? PropertyValue.IdValue)?.ref?.path?.let {
                                Reference.toQname(
                                    it,
                                    schemaCode,
                                    namespace,
                                )
                            }
                                ?: qn,
                        cardinality = Cardinality(0, -1, 0, -1),
                    )
                // v2.1 — inline `mapping: <fkRef>` or `mapping: { fk: <fkRef> }` on a
                // relation synthesises an Er2DbRelationMapping with mappingSource=Inline.
                synthesiseInlineRelationMapping(def, schemaCode, namespace, sourceFile, mappings)
            }
            is QueryDef -> {
                val qn = qname("query", "query", def.name)
                queries[qn] =
                    Query(
                        internalId = idFor("query", qn),
                        qname = qn,
                        description = def.description ?: "",
                        tags = def.tags,
                        sourceFile = sourceFile,
                        sourceLanguage = def.language ?: "SQL",
                        sourceText = def.sourceText ?: "",
                        search = def.search.toSearchHints(),
                        parameters =
                            def.parameters.mapNotNull { p ->
                                val obj = (p as? PropertyValue.ObjectValue)?.entries ?: return@mapNotNull null
                                QueryParameterDef(
                                    name = (obj["name"] as? PropertyValue.IdValue)?.ref?.path ?: return@mapNotNull null,
                                    type = (obj["type"] as? PropertyValue.IdValue)?.ref?.path ?: "text",
                                    label = (obj["label"] as? PropertyValue.StringValue)?.raw ?: "",
                                )
                            },
                    )
            }
            is Er2DbEntityDef -> {
                val qn = qname("map", "er2db_entity", def.name)
                val target =
                    when (val t = def.target) {
                        is TargetObjectValue -> {
                            val entries = t.obj.entries
                            when {
                                entries.containsKey("table") ->
                                    MappingTarget.Table(refToQname(entries["table"], "db", "dbo"))
                                entries.containsKey("view") ->
                                    MappingTarget.View(refToQname(entries["view"], "db", "dbo"))
                                entries.containsKey("sqlQuery") ->
                                    MappingTarget.SqlQuery(refToQname(entries["sqlQuery"], "query", "query"))
                                else -> null
                            }
                        }
                        // v2.1: bare-ref form `target: db.dbo.T` ⇒ implicit table mapping.
                        is TargetReferenceValue ->
                            MappingTarget.Table(refToQname(t.ref, "db", "dbo"))
                        null -> null
                    } ?: return
                mappings +=
                    Er2DbEntityMapping(
                        internalId = idFor("map.er2db_entity", qn),
                        qname = qn,
                        description = def.description ?: "",
                        tags = def.tags,
                        sourceFile = sourceFile,
                        entity = def.entity?.path?.let { Reference.toQname(it, "er", "entity") } ?: return,
                        target = target,
                    )
            }
            is Er2DbAttributeDef -> {
                val qn = qname("map", "er2db_attribute", def.name)
                val target =
                    when (val t = def.target) {
                        is TargetObjectValue -> {
                            val entries = t.obj.entries
                            when {
                                entries.containsKey("column") ->
                                    AttributeMappingTarget.Column(refToQname(entries["column"], "db", "dbo"))
                                entries.containsKey("expression") ->
                                    AttributeMappingTarget.Expression(
                                        (entries["expression"] as? PropertyValue.StringValue)?.raw ?: "",
                                    )
                                else -> null
                            }
                        }
                        // v2.1: bare-ref form `target: db.dbo.T.COL` ⇒ implicit column mapping.
                        is TargetReferenceValue ->
                            AttributeMappingTarget.Column(refToQname(t.ref, "db", "dbo"))
                        null -> null
                    } ?: return
                mappings +=
                    Er2DbAttributeMapping(
                        internalId = idFor("map.er2db_attribute", qn),
                        qname = qn,
                        description = def.description ?: "",
                        tags = def.tags,
                        sourceFile = sourceFile,
                        attribute = def.attribute?.path?.let { Reference.toQname(it, "er", "entity") } ?: return,
                        target = target,
                    )
            }
            is Er2DbRelationDef -> {
                val qn = qname("map", "er2db_relation", def.name)
                mappings +=
                    Er2DbRelationMapping(
                        internalId = idFor("map.er2db_relation", qn),
                        qname = qn,
                        description = def.description ?: "",
                        tags = def.tags,
                        sourceFile = sourceFile,
                        relation = def.relation?.path?.let { Reference.toQname(it, "er", "entity") } ?: return,
                        foreignKey = def.fk?.path?.let { Reference.toQname(it, "db", "dbo") } ?: return,
                    )
            }
            is RoleDef -> {
                // `def role X` blocks live under `cnc.cnc.role.*` regardless of the
                // declaring file's schema directive — the conceptual layer is
                // always rooted at `cnc.cnc.role` (package + schema + namespace).
                val qn = qname("cnc", "role", def.name, "cnc")
                roles[qn] =
                    Role(
                        internalId = idFor("cnc.role", qn),
                        qname = qn,
                        description = def.description ?: "",
                        tags = def.tags,
                        sourceFile = sourceFile,
                        label = def.label.toLocalizedText(),
                        search = def.search.toSearchHints(),
                    )
            }
            is org.tatrman.ttr.parser.model.DrillMapDef -> {
                val qn = qname(schemaCode, namespace, def.name)
                val fromQn =
                    def.from?.path?.let { Reference.toQname(it, "query", "query") }
                        ?: return
                val toQn =
                    def.to?.path?.let { Reference.toQname(it, "query", "query") }
                        ?: return
                val rawDisplay = def.display.toLocalizedText()
                // OQ-03.A — when `display` is absent the loader supplies a default. We don't
                // yet have `to`'s description here (cross-package, resolved later); fall back
                // to plain "Detail" / "Detail" for now and let a later pass enrich if needed.
                val display =
                    if (rawDisplay.isEmpty) {
                        LocalizedText(byLanguage = mapOf("cs" to "Detail", "en" to "Detail"))
                    } else {
                        rawDisplay
                    }
                drillMaps[qn] =
                    org.tatrman.kantheon.ariadne.model.DrillMap(
                        internalId = idFor("query.drill_map", qn),
                        qname = qn,
                        description = def.description ?: "",
                        tags = def.tags,
                        sourceFile = sourceFile,
                        fromPattern = fromQn,
                        toPattern = toQn,
                        argMapping = def.args,
                        explicit = true,
                        overrideAuto = def.overrideAuto,
                        display = display,
                    )
            }
            is Er2CncRoleDef -> {
                val mappingQn = qname("map", "er2cnc_role", def.name)
                mappings +=
                    Er2CncRoleMapping(
                        internalId = idFor("map.er2cnc_role", mappingQn),
                        qname = mappingQn,
                        description = def.description ?: "",
                        tags = def.tags,
                        sourceFile = sourceFile,
                        entity =
                            def.entity?.toQualifiedName(defaultSchema = "er", defaultNamespace = "entity")
                                ?: return,
                        role =
                            def.role?.let { ref ->
                                if (ref.path.split(".").size == 1) {
                                    qname("cnc", "role", ref.path, "cnc")
                                } else {
                                    Reference.toQname(ref.path, "cnc", "role")
                                }
                            } ?: return,
                    )
            }
            else -> Unit
        }
    }

    // ----- v2.1: inline-mapping synthesisers -----

    /**
     * Materialises an entity-level inline `mapping: { target: ..., columns: { ... } }`
     * block into an Er2DbEntityMapping (and one Er2DbAttributeMapping per columns
     * entry) tagged with [MappingSource.Inline]. Qnames mirror the explicit
     * `def er2db_*` shape (no package; `map` schema; `er2db_entity` / `er2db_attribute`
     * namespace) so the duplicate-mapping validator finds collisions.
     */
    private fun synthesiseInlineEntityMappings(
        def: EntityDef,
        entityQn: QualifiedName,
        sourceFile: String,
        mappings: MutableList<org.tatrman.kantheon.ariadne.model.Mapping>,
    ) {
        val mapping = def.binding as? BindingPropertyBlock ?: return
        val target =
            when (val t = mapping.target) {
                is TargetObjectValue -> {
                    val entries = t.obj.entries
                    when {
                        entries.containsKey("table") ->
                            MappingTarget.Table(refToQname(entries["table"], "db", "dbo"))
                        entries.containsKey("view") ->
                            MappingTarget.View(refToQname(entries["view"], "db", "dbo"))
                        entries.containsKey("sqlQuery") ->
                            MappingTarget.SqlQuery(refToQname(entries["sqlQuery"], "query", "query"))
                        else -> null
                    }
                }
                is TargetReferenceValue ->
                    MappingTarget.Table(refToQname(t.ref, "db", "dbo"))
                null -> null
            }

        // Entity-level mapping: only synthesise if a target was provided. A pure
        // `columns: { ... }` block without a target wouldn't yield a usable entity
        // mapping; the column entries are still synthesised below.
        if (target != null) {
            val qn = qname("map", "er2db_entity", def.name)
            mappings +=
                Er2DbEntityMapping(
                    internalId = idFor("map.er2db_entity", qn),
                    qname = qn,
                    description = "",
                    tags = emptyList(),
                    sourceFile = sourceFile,
                    mappingSource = MappingSource.Inline("entity"),
                    entity = entityQn,
                    target = target,
                )
        }

        // Each `columns: { id_artiklu: IDZBOZI, ... }` entry → one Er2DbAttributeMapping.
        for (col in mapping.columns) {
            val attrTarget = inlineColumnToAttributeTarget(col.value) ?: continue
            val attrQn = qname("map", "er2db_attribute", "${def.name}.${col.name}")
            val attrEntityQn =
                qname(entityQn.schemaCode.name.lowercase(), entityQn.namespace, "${def.name}.${col.name}")
            mappings +=
                Er2DbAttributeMapping(
                    internalId = idFor("map.er2db_attribute", attrQn),
                    qname = attrQn,
                    description = "",
                    tags = emptyList(),
                    sourceFile = sourceFile,
                    mappingSource = MappingSource.Inline("entity"),
                    attribute = attrEntityQn,
                    target = attrTarget,
                )
        }
    }

    /**
     * Materialises `mapping:` on each `def attribute X { mapping: ... }` nested
     * inside an entity. Qname mirrors `<entityName>.<attrName>` to match the
     * explicit `def er2db_attribute <entity>.<attr>` convention.
     */
    private fun synthesiseInlineAttributeMappings(
        def: EntityDef,
        entityQn: QualifiedName,
        sourceFile: String,
        mappings: MutableList<org.tatrman.kantheon.ariadne.model.Mapping>,
    ) {
        for (attr in def.attributes) {
            val attrMapping = attr.binding ?: continue
            val attrTarget =
                when (attrMapping) {
                    is BindingPropertyBareId ->
                        AttributeMappingTarget.Column(
                            refToQname(attrMapping.id, "db", "dbo"),
                        )
                    is BindingPropertyBlock -> {
                        when (val t = attrMapping.target) {
                            is TargetObjectValue -> {
                                val entries = t.obj.entries
                                when {
                                    entries.containsKey("column") ->
                                        AttributeMappingTarget.Column(refToQname(entries["column"], "db", "dbo"))
                                    entries.containsKey("expression") ->
                                        AttributeMappingTarget.Expression(
                                            (entries["expression"] as? PropertyValue.StringValue)?.raw ?: "",
                                        )
                                    else -> null
                                }
                            }
                            is TargetReferenceValue ->
                                AttributeMappingTarget.Column(
                                    refToQname(t.ref, "db", "dbo"),
                                )
                            null -> null
                        }
                    }
                } ?: continue
            val attrQn = qname("map", "er2db_attribute", "${def.name}.${attr.name}")
            val attrEntityQn =
                qname(
                    entityQn.schemaCode.name.lowercase(),
                    entityQn.namespace,
                    "${def.name}.${attr.name}",
                )
            mappings +=
                Er2DbAttributeMapping(
                    internalId = idFor("map.er2db_attribute", attrQn),
                    qname = attrQn,
                    description = "",
                    tags = emptyList(),
                    sourceFile = sourceFile,
                    mappingSource = MappingSource.Inline("attribute"),
                    attribute = attrEntityQn,
                    target = attrTarget,
                )
        }
    }

    /**
     * Materialises a relation-level `mapping: <fkRef>` (bare form) or
     * `mapping: { fk: <fkRef> }` (block form) into an Er2DbRelationMapping.
     */
    private fun synthesiseInlineRelationMapping(
        def: RelationDef,
        schemaCode: String,
        namespace: String,
        sourceFile: String,
        mappings: MutableList<org.tatrman.kantheon.ariadne.model.Mapping>,
    ) {
        val mapping = def.binding ?: return
        val fkRef =
            when (mapping) {
                is BindingPropertyBareId -> mapping.id
                is BindingPropertyBlock -> mapping.fk ?: return
            }
        val qn = qname("map", "er2db_relation", def.name)
        mappings +=
            Er2DbRelationMapping(
                internalId = idFor("map.er2db_relation", qn),
                qname = qn,
                description = "",
                tags = emptyList(),
                sourceFile = sourceFile,
                mappingSource = MappingSource.Inline("relation"),
                relation = qname(schemaCode, namespace, def.name),
                foreignKey = Reference.toQname(fkRef.path, "db", "dbo"),
            )
    }

    /**
     * Converts one entry in a `columns: { ... }` map into an
     * [AttributeMappingTarget]. Handles all three column-value shapes the walker
     * produces (bare id, wrapped `{ target: bareId }` / `{ target: { column: ... } }`,
     * and plain `{ column: ... }`).
     */
    private fun inlineColumnToAttributeTarget(
        value: org.tatrman.ttr.parser.model.BindingColumnValue,
    ): AttributeMappingTarget? =
        when (value) {
            is BindingColumnBareId ->
                AttributeMappingTarget.Column(refToQname(value.id, "db", "dbo"))
            is BindingColumnObject -> {
                val entries = value.obj.entries
                // The walker wraps form (b) `{ target: <inner> }` and form (c) the same way;
                // peel one layer of `target:` if present.
                val inner =
                    (entries["target"] as? PropertyValue.ObjectValue)?.entries
                        ?: (entries["target"] as? PropertyValue.IdValue)?.let {
                            return@let mapOf("column" to it as PropertyValue)
                        }
                        ?: entries
                when {
                    inner.containsKey("column") ->
                        AttributeMappingTarget.Column(refToQname(inner["column"], "db", "dbo"))
                    inner.containsKey("expression") ->
                        AttributeMappingTarget.Expression(
                            (inner["expression"] as? PropertyValue.StringValue)?.raw ?: "",
                        )
                    else -> null
                }
            }
        }

    private fun ttrColumnToDbColumn(
        qn: QualifiedName,
        tableQn: QualifiedName,
        col: TtrColumnDef,
        sourceFile: String,
    ): DbColumn =
        DbColumn(
            internalId = idFor("db.column", qn),
            qname = qn,
            description = col.description ?: "",
            tags = col.tags,
            sourceFile = sourceFile,
            table = tableQn,
            dataType = col.type?.name ?: "varchar",
            nullable = col.optional,
            isPrimaryKey = col.isKey,
            search = col.search.toSearchHints(),
        )

    private fun qnameList(v: PropertyValue?): List<QualifiedName> =
        when (v) {
            is PropertyValue.IdValue -> listOf(Reference.toQname(v.ref.path, "db", "dbo"))
            is PropertyValue.ListValue ->
                v.items.mapNotNull {
                    (it as? PropertyValue.IdValue)?.ref?.path?.let { p ->
                        Reference.toQname(p, "db", "dbo")
                    }
                }
            else -> emptyList()
        }

    private fun refToQname(
        v: PropertyValue?,
        defaultSchema: String,
        defaultNamespace: String,
    ): QualifiedName =
        when (v) {
            is PropertyValue.IdValue -> Reference.toQname(v.ref.path, defaultSchema, defaultNamespace)
            else -> qname(defaultSchema, defaultNamespace, "<unresolved>")
        }

    // Overload for the common case where the caller already holds a TtrReference
    // (avoids wrapping it in a PropertyValue.IdValue, which now requires
    // parts + source on the published org.tatrman model).
    private fun refToQname(
        ref: TtrReference,
        defaultSchema: String,
        defaultNamespace: String,
    ): QualifiedName = Reference.toQname(ref.path, defaultSchema, defaultNamespace)

    companion object {
        fun qname(
            schemaCode: String,
            namespace: String,
            name: String,
            `package`: String = "",
        ): QualifiedName =
            QualifiedName
                .newBuilder()
                .setPackage(`package`)
                .setSchemaCode(
                    try {
                        org.tatrman.plan.v1.SchemaCode
                            .valueOf(schemaCode.uppercase())
                    } catch (
                        e: Exception,
                    ) {
                        org.tatrman.plan.v1.SchemaCode.SCHEMA_CODE_UNSPECIFIED
                    },
                ).setNamespace(namespace)
                .setName(name)
                .build()

        fun idFor(
            prefix: String,
            qn: QualifiedName,
        ): String {
            val packageSegment = if (qn.`package`.isNotEmpty()) "${qn.`package`}." else ""
            return "$prefix:${packageSegment}${qn.schemaCode}.${qn.namespace}.${qn.name}"
        }
    }
}

private fun ParseError.toLoadWarning(sourceId: String): LoadWarning =
    LoadWarning(sourceId = sourceId, file = file, line = line, column = column, message = message)

private fun ParseWarning.toLoadWarning(sourceId: String): LoadWarning =
    LoadWarning(sourceId = sourceId, file = file, line = line, column = column, message = message)

private fun definitionKind(def: org.tatrman.ttr.parser.model.Definition): String =
    when (def) {
        is org.tatrman.ttr.parser.model.TableDef -> "table"
        is org.tatrman.ttr.parser.model.ViewDef -> "view"
        is org.tatrman.ttr.parser.model.ProcedureDef -> "procedure"
        is org.tatrman.ttr.parser.model.FkDef -> "foreign_key"
        is org.tatrman.ttr.parser.model.EntityDef -> "entity"
        is org.tatrman.ttr.parser.model.AttributeDef -> "attribute"
        is org.tatrman.ttr.parser.model.RelationDef -> "relation"
        is org.tatrman.ttr.parser.model.Er2DbEntityDef -> "er2db_entity_mapping"
        is org.tatrman.ttr.parser.model.Er2DbAttributeDef -> "er2db_attribute_mapping"
        is org.tatrman.ttr.parser.model.Er2DbRelationDef -> "er2db_relation_mapping"
        is org.tatrman.ttr.parser.model.RoleDef -> "role"
        is org.tatrman.ttr.parser.model.Er2CncRoleDef -> "er2cnc_role_mapping"
        is org.tatrman.ttr.parser.model.QueryDef -> "query"
        is org.tatrman.ttr.parser.model.DrillMapDef -> "drill_map"
        is org.tatrman.ttr.parser.model.ColumnDef -> "column"
        else -> "def"
    }

object Reference {
    /**
     * Best-effort qname resolution from a dotted path. The metadata service's
     * reconciler does the real work; this is the source-layer fallback.
     *
     * A 4+-part path is ambiguous and is disambiguated on whether `parts[1]` is a
     * recognised schema token (db/er/cnc/ws/obj):
     *  - **package.schema.namespace.name** — stock vocabulary, e.g. `cnc.cnc.role.master`
     *    (`parts[1] == "cnc"` is a schema token).
     *  - **schema.namespace.name** where `name` itself contains dots — e.g. the entity-attribute
     *    references `er.entity.účetní_středisko.kód_střediska` and column targets
     *    `db.dbo.QSTRED_DF.KOD_STR` (`parts[1]` is `entity`/`dbo`, not a schema token). The name is
     *    everything after the namespace, so the qname matches the attribute's/column's canonical
     *    `qname(schema, namespace, "<owner>.<member>")` — see GH #53 (these previously misparsed to
     *    `package=schema-token, schemaCode=UNSPECIFIED`, so fuzzy attributes silently lost their
     *    er2db column mapping).
     * A 3-part path is `schema.namespace.name`; 2-part and 1-part fall back to the defaults.
     */
    fun toQname(
        path: String,
        defaultSchema: String,
        defaultNamespace: String,
    ): QualifiedName {
        val parts = path.split(".")
        return when (parts.size) {
            1 -> FileBasedSource.qname(defaultSchema, defaultNamespace, parts[0])
            2 -> FileBasedSource.qname(defaultSchema, parts[0], parts[1])
            3 -> FileBasedSource.qname(parts[0], parts[1], parts[2])
            else ->
                if (org.tatrman.plan.v1
                        .parseSchemaCode(parts[1]) != null
                ) {
                    // package.schema.namespace.name (stock vocabulary)
                    FileBasedSource.qname(parts[1], parts[2], parts.drop(3).joinToString("."), parts[0])
                } else {
                    // schema.namespace.name — the name carries the remaining dotted segments
                    FileBasedSource.qname(parts[0], parts[1], parts.drop(2).joinToString("."))
                }
        }
    }
}

// ----- Phase 2.2 helpers -----

/** Convert a parser-side localised-string carrier into the model layer's. */
internal fun LocalizedStringValue?.toLocalizedText(): LocalizedText =
    this?.let { LocalizedText(byLanguage = it.byLanguage) } ?: LocalizedText.EMPTY

// ----- Search feature helpers -----

internal fun org.tatrman.ttr.parser.model.LocalizedStringListValue?.toLocalizedTextList(): LocalizedTextList =
    this?.let {
        org.tatrman.kantheon.ariadne.model
            .LocalizedTextList(byLanguage = it.byLanguage)
    }
        ?: org.tatrman.kantheon.ariadne.model.LocalizedTextList.EMPTY

internal fun org.tatrman.ttr.parser.model.SearchHintsValue?.toSearchHints(): SearchHints {
    if (this == null) return org.tatrman.kantheon.ariadne.model.SearchHints.EMPTY
    return org.tatrman.kantheon.ariadne.model.SearchHints(
        searchable = searchable,
        fuzzy = fuzzy,
        keywords = keywords.toLocalizedTextList(),
        patterns = patterns,
        descriptions = descriptions.toLocalizedTextList(),
        examples = examples,
        aliases = aliases,
    )
}

/** Resolve a parser-side dotted Reference to a [QualifiedName]. */
internal fun TtrReference.toQualifiedName(
    defaultSchema: String,
    defaultNamespace: String,
): QualifiedName = Reference.toQname(path, defaultSchema, defaultNamespace)
