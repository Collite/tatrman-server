// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.export

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.tatrman.ttr.metadata.export.ExportBundle
import org.tatrman.ttr.metadata.export.GraphDotExporter
import org.tatrman.ttr.metadata.export.ModelToDefinitions
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.slf4j.LoggerFactory
import org.tatrman.ttr.parser.loader.TtrLoader
import org.tatrman.ttr.writer.TtrRenderer
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

private val log = LoggerFactory.getLogger("org.tatrman.veles.export.MetadataExportRoutes")

private val CONTENT_TYPE_TAR_GZ = ContentType.parse("application/x-tar+gzip")

fun Route.metadataExportRoutes(registry: MetadataRegistry) {
    get("/model/export") {
        val snap = registry.read()
        if (snap == null) {
            call.respondText(
                """{"error":"model not loaded"}""",
                ContentType.Application.Json,
                HttpStatusCode.ServiceUnavailable,
            )
            return@get
        }

        val etag = snap.model.version.value
        val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]
        if (ifNoneMatch == etag) {
            call.response.status(HttpStatusCode.NotModified)
            return@get
        }

        val validate = call.request.queryParameters["validate"] != "false"
        val bundle = ModelToDefinitions.convert(snap.model)

        if (validate) {
            val validationErrors =
                bundle.files.flatMap { file ->
                    val content =
                        TtrRenderer.renderFile(
                            file.schemaCode,
                            file.namespace,
                            file.definitions,
                            file.packageName,
                            file.imports,
                        )
                    val result = TtrLoader.parseString(content, fileLabel = file.filename)
                    result.errors.map { it.toString() }
                }
            if (validationErrors.isNotEmpty()) {
                log.error("TTR export self-validation failed:\n{}", validationErrors.joinToString("\n"))
                val body = JsonObject(mapOf("errors" to JsonArray(validationErrors.map { JsonPrimitive(it) })))
                call.respondText(body.toString(), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                return@get
            }
        }

        val tarball = buildTarGz(bundle)
        val filename = "model-${bundle.modelId}-${bundle.modelVersion}.tar.gz"

        call.response.header(HttpHeaders.ETag, etag)
        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"$filename\"")
        call.respondBytes(tarball, CONTENT_TYPE_TAR_GZ, HttpStatusCode.OK)
    }

    // Phase 09 B4 / DF-M15 — Graphviz DOT export of the current ModelGraph snapshot. Returns
    // text/vnd.graphviz which downstream tools (`dot -Tsvg`, online viewers) consume directly.
    get("/model/export/dot") {
        val snap = registry.read()
        if (snap == null) {
            call.respondText(
                """{"error":"model not loaded"}""",
                ContentType.Application.Json,
                HttpStatusCode.ServiceUnavailable,
            )
            return@get
        }
        val etag = snap.model.version.value
        val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]
        if (ifNoneMatch == etag) {
            call.response.status(HttpStatusCode.NotModified)
            return@get
        }
        val dot = GraphDotExporter.export(snap.graph)
        call.response.header(HttpHeaders.ETag, etag)
        call.respondText(dot, ContentType.parse("text/vnd.graphviz"), HttpStatusCode.OK)
    }
}

private fun buildTarGz(bundle: ExportBundle): ByteArray {
    val baos = ByteArrayOutputStream()
    GZIPOutputStream(baos).use { gzos ->
        TarArchiveOutputStream(gzos).use { taos ->
            taos.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
            for (file in bundle.files) {
                val content =
                    TtrRenderer.renderFile(
                        file.schemaCode,
                        file.namespace,
                        file.definitions,
                        file.packageName,
                        file.imports,
                    )
                val bytes = content.toByteArray(Charsets.UTF_8)
                // Package = directory (§4.4): place `package er` files under `er/` so the
                // extracted tree reloads without a package-declaration-mismatch.
                val entryPath =
                    if (file.packageName != null) {
                        "${bundle.modelId}/${file.packageName}/${file.filename}"
                    } else {
                        "${bundle.modelId}/${file.filename}"
                    }
                val entry = TarArchiveEntry(entryPath)
                entry.size = bytes.size.toLong()
                taos.putArchiveEntry(entry)
                taos.write(bytes)
                taos.closeArchiveEntry()
            }
        }
    }
    return baos.toByteArray()
}
