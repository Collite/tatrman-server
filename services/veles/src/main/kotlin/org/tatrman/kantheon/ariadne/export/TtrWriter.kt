package org.tatrman.kantheon.ariadne.export

import org.tatrman.kantheon.ariadne.model.Model
import org.tatrman.ttr.writer.TtrRenderer

object TtrWriter {
    /**
     * Renders the full model as a single concatenated TTR string (all schemas
     * merged, no schema directive headers). Useful for diagnostics and simple
     * single-file snapshots; production export uses [MetadataExportRoutes] for
     * the tarball form with per-schema partitioning.
     */
    fun writeModel(model: Model): String {
        val bundle = ModelToDefinitions.convert(model)
        val sb = StringBuilder()
        for (file in bundle.files) {
            sb.append(TtrRenderer.renderFile(file.schemaCode, file.namespace, file.definitions))
        }
        return sb.toString()
    }
}
