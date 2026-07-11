package org.tatrman.identity.routes

import org.tatrman.identity.domain.UserSource
import org.tatrman.identity.opa.deepMerge
import org.tatrman.identity.repository.UserRepositoryPort
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.text.plus

class BundleHandler(
    private val userRepository: UserRepositoryPort,
    private val policiesDir: File,
) {
    private val logger = LoggerFactory.getLogger("BundleHandler")

    suspend fun getBundle(
        call: ApplicationCall,
        type: String,
    ) {
        val sourceType =
            try {
                UserSource.valueOf(type.uppercase())
            } catch (e: Exception) {
                call.respondText(
                    text = """{"error": "Invalid type: $type. Must be KEYCLOAK or ERP."}""",
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.BadRequest,
                )
                return
            }

        logger.debug("Generating bundle for type: {}", sourceType)

        val files =
            if (policiesDir.exists()) {
                policiesDir
                    .walkTopDown()
                    .onEnter { !it.name.startsWith(".") }
                    .filter { it.isFile && !it.name.startsWith(".") }
                    .toList()
            } else {
                emptyList()
            }

        val filelist = files.joinToString("\n") { it.name }
        logger.debug("Found {} files for type: {}, {}", files.size, sourceType, filelist)

        val latestFileTime = files.maxOfOrNull { it.lastModified() } ?: 0L
        // TODO add some db change logic ...
        val latestDbChangeTime = ""

        val rawEtag = "$latestFileTime-$latestDbChangeTime"
        val etag =
            '"' +
                MessageDigest
                    .getInstance("MD5")
                    .digest(rawEtag.toByteArray())
                    .joinToString("") { "%02x".format(it) } + '"'

        // --- 2. Cache Check ---
        val ifNoneMatch = call.request.header(HttpHeaders.IfNoneMatch)
        if (ifNoneMatch == etag) {
            call.respond(HttpStatusCode.NotModified)
            return
        }

        // --- 3. Merge JSON Data using kotlinx.serialization ---
        // Start with an empty immutable JsonObject
        var mergedData = JsonObject(emptyMap())

        // A. Parse and merge all .json files from the filesystem
        files.filter { it.extension == "json" }.forEach { file ->
            val fileText = file.readText()
            val fileJsonObj = Json.parseToJsonElement(fileText).jsonObject

            // Reassign the merged result since JsonObjects are immutable
            mergedData = deepMerge(mergedData, fileJsonObj)
        }

        val hierarchyJsonString = fetchHierarchyFromDbAsJsonString(sourceType)
        val hierarchyElement = Json.parseToJsonElement(hierarchyJsonString)

        // Wrap the hierarchy in a "role_hierarchy" root key to match the OPA structure
        val hierarchyWrapper = JsonObject(mapOf("role_hierarchy" to hierarchyElement))
        mergedData = deepMerge(mergedData, hierarchyWrapper)

        logger.debug("Merged bundle data with hierarchy wrapper: {}", mergedData)

        val bundleBytes = mergedData.toString().toByteArray(Charsets.UTF_8)

// --- 4. Build the Tarball ---
        val baos = ByteArrayOutputStream()
        GzipCompressorOutputStream(baos).use { gzipOut ->
            TarArchiveOutputStream(gzipOut).use { tarOut ->

                // A. Add the Manifest
                val manifestBytes =
                    """{ "roots": ["erpsql/authz", "role_hierarchy", "table_permissions"] }"""
                        .toByteArray(Charsets.UTF_8)
                tarOut
                    .putArchiveEntry(
                        TarArchiveEntry(".manifest")
                            .apply { size = manifestBytes.size.toLong() },
                    )
                tarOut.write(manifestBytes)
                tarOut.closeArchiveEntry()

                // B. Add the Merged data.json
                tarOut
                    .putArchiveEntry(
                        TarArchiveEntry("data.json")
                            .apply { size = bundleBytes.size.toLong() },
                    )
                tarOut.write(bundleBytes)
                tarOut.closeArchiveEntry()

                // C. Add all .rego policies (Preserving relative paths)
                files.filter { it.extension == "rego" }.forEach { regoFile ->
                    val regoBytes = regoFile.readBytes()

                    // Keeps directory structure intact inside the bundle
                    val relativePath = regoFile.relativeTo(policiesDir).path.replace("\\", "/")

                    tarOut
                        .putArchiveEntry(
                            TarArchiveEntry(relativePath)
                                .apply { size = regoBytes.size.toLong() },
                        )
                    tarOut.write(regoBytes)
                    tarOut.closeArchiveEntry()
                }
            }
        }
        call.respondBytes(
            bytes = baos.toByteArray(),
            contentType = ContentType("application", "gzip"),
            status = HttpStatusCode.OK,
        )
    }

    private suspend fun fetchHierarchyFromDbAsJsonString(sourceType: UserSource): String =
        if (sourceType == UserSource.ERP) {
            val hierarchy = userRepository.getRoleHierarchy(sourceType)
            buildHierarchyJson(hierarchy)
        } else {
            val uniqueRoles = userRepository.getUniqueRolesByType(sourceType)
            buildFlatRolesJson(uniqueRoles)
        }

    private fun buildHierarchyJson(hierarchy: Map<String, List<String>>): String {
        val entries =
            hierarchy.entries.joinToString(",") { (parent, children) ->
                """"$parent": [${children.joinToString(",") { """"$it"""" }}]"""
            }
        return if (entries.isEmpty()) "{}" else "{$entries}"
    }

    private fun buildFlatRolesJson(roles: List<String>): String {
        val entries = roles.joinToString(",") { """"$it": []""" }
        return if (entries.isEmpty()) "{}" else "{$entries}"
    }
}
