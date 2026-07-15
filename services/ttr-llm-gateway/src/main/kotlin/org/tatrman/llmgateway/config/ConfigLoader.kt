// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.config

import com.charleskorn.kaml.Yaml
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

/** Thrown at startup when config validation fails — carries ALL errors (contracts §2.1). */
class ConfigValidationException(
    val errors: List<String>,
) : RuntimeException("gateway config invalid:\n  - " + errors.joinToString("\n  - "))

/** The validated, loaded static config the inference engine reads (contracts §2). */
data class GatewayConfig(
    val catalog: Catalog,
    val providers: ProvidersConfig,
    val governance: GovernanceConfig,
)

/**
 * LG-P1·S1 — loads + validates the three static config files. Decodes `catalog.yaml`/`governance.yaml`
 * with kaml and `providers.conf` with typesafe-config, then runs [ConfigValidator]; throws
 * [ConfigValidationException] with every error if invalid (fail startup, not first request).
 */
object ConfigLoader {
    private val yaml = Yaml.default

    fun load(
        catalogYaml: String,
        providersConf: Config,
        governanceYaml: String,
    ): GatewayConfig {
        val catalog = yaml.decodeFromString(Catalog.serializer(), catalogYaml)
        val providers = providersFrom(providersConf)
        val governance = yaml.decodeFromString(GovernanceConfig.serializer(), governanceYaml)

        val errors = ConfigValidator.validate(catalog, providers, governance)
        if (errors.isNotEmpty()) throw ConfigValidationException(errors)
        return GatewayConfig(catalog, providers, governance)
    }

    /** Load the packaged config from the classpath (`/catalog.yaml`, `providers.conf`, `/governance.yaml`). */
    fun loadFromResources(): GatewayConfig =
        load(
            catalogYaml = resource("/catalog.yaml"),
            providersConf = ConfigFactory.parseResources("providers.conf").resolve(),
            governanceYaml = resource("/governance.yaml"),
        )

    private fun resource(path: String): String =
        ConfigLoader::class.java.getResource(path)?.readText()
            ?: error("missing config resource on classpath: $path")
}
