// SPDX-License-Identifier: Apache-2.0
package org.tatrman.charon.core

/**
 * A stand-in resolver for the CH-D8 plugin-dir host spec. [ConnectionSecretResolverPluginDirSpec]
 * writes a `META-INF/services` jar naming this class, so `discover(pluginDir = …)` loads it exactly
 * as it would a mounted platform adapter jar. Kept a top-level public class with a no-arg ctor so
 * `ServiceLoader` can instantiate it.
 */
class PluginDirTestResolver : ConnectionSecretResolver {
    override fun resolve(name: String): String? = if (name == "TTR_CONN_PLUGIN") "from-plugin" else null
}
