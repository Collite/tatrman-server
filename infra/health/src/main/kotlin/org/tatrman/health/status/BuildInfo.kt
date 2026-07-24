// SPDX-License-Identifier: Apache-2.0
package org.tatrman.health.status

import java.util.Properties

/**
 * FO-P5.S2.T1 — the open Server's build version. The whole open Server is a single release train
 * (`tatrman-server.version`, every module built together), so there is one version, not per-service
 * versions. Gradle `processResources` filters it into `build.properties` on the classpath; absent that
 * (an un-filtered dev classpath) it reads "unknown".
 */
object BuildInfo {
    val serverVersion: String by lazy {
        BuildInfo::class.java
            .getResourceAsStream("/build.properties")
            ?.use { stream -> Properties().apply { load(stream) }.getProperty("version") }
            ?.takeIf { it.isNotBlank() && !it.contains("\${") }
            ?: "unknown"
    }
}
