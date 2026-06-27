package org.tatrman.prometheus.model

import com.charleskorn.kaml.Yaml
import jakarta.annotation.PostConstruct
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import java.io.File

@Serializable
data class Model(
    val name: String,
    val fullName: String? = null,
    val description: String? = null,
    val provider: String, // openai, ollama, etc.
    @SerialName("type") val modelType: String? = null, // chat, embedding
    val tags: List<String> = emptyList(),
    @Serializable(with = ConfigSerializer::class) val config: Map<String, Any?>? = null,
    /**
     * Phase 09 A3 — pricing in USD per million tokens. `inputCost` covers prompt / input tokens,
     * `outputCost` covers completion / output tokens. Embedding models use only `inputCost`.
     *
     * The legacy single-value [cost] field is kept for back-compat with older `models.yaml`
     * entries that didn't split input vs output; when the split fields are absent, callers
     * fall back to `cost` for both sides (rough but better than zero).
     */
    val inputCost: Double? = null,
    val outputCost: Double? = null,
    val cost: Double? = null,
)

@Serializable
data class ModelsConfig(
    val models: List<Model>,
)

object ConfigSerializer : KSerializer<Map<String, Any?>?> {
    private val elementSerializer = MapSerializer(String.serializer(), JsonElement.serializer())

    override val descriptor: SerialDescriptor = elementSerializer.descriptor

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(
        encoder: Encoder,
        value: Map<String, Any?>?,
    ) {
        if (value == null) {
            encoder.encodeNull()
            return
        }
        val elements = value.mapValues { (_, entry) -> anyToJsonElement(entry) }
        encoder.encodeSerializableValue(elementSerializer, elements)
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun deserialize(decoder: Decoder): Map<String, Any?>? {
        if (!decoder.decodeNotNullMark()) {
            decoder.decodeNull()
            return null
        }
        val elements = decoder.decodeSerializableValue(elementSerializer)
        return elements.mapValues { (_, entry) -> jsonElementToAny(entry) }
    }
}

private fun anyToJsonElement(value: Any?): JsonElement =
    when (value) {
        null -> JsonNull
        is JsonElement -> value
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        is Map<*, *> ->
            JsonObject(
                value.entries.associate { (key, entry) ->
                    key.toString() to anyToJsonElement(entry)
                },
            )
        is List<*> -> JsonArray(value.map { entry -> anyToJsonElement(entry) })
        else -> JsonPrimitive(value.toString())
    }

internal fun jsonElementToAny(element: JsonElement): Any? =
    when (element) {
        JsonNull -> null
        is JsonPrimitive ->
            element.booleanOrNull
                ?: element.longOrNull ?: element.doubleOrNull ?: element.content
        is JsonObject -> element.mapValues { (_, entry) -> jsonElementToAny(entry) }
        is JsonArray -> element.map { entry -> jsonElementToAny(entry) }
    }

@Component
class ModelRepository {
    private val logger = LoggerFactory.getLogger(ModelRepository::class.java)

    private var models: List<Model> = emptyList()

    @Value("\${prometheus.models-config-path:/etc/prometheus/models.yaml}")
    private lateinit var modelsConfigPath: String

    @PostConstruct
    fun init() {
        val content: String? =
            try {
                val configFile = File(modelsConfigPath)
                if (configFile.exists()) {
                    logger.info("Loading models from file: {}", modelsConfigPath)
                    configFile.readText()
                } else {
                    logger.info(
                        "Models config file not found at {}, falling back to classpath resource",
                        modelsConfigPath,
                    )
                    val resource = ClassPathResource("models.yaml")
                    if (resource.exists()) {
                        resource.inputStream.bufferedReader().use { it.readText() }
                    } else {
                        null
                    }
                }
            } catch (e: Exception) {
                logger.error(
                    "Failed to load models config from {}: {}",
                    modelsConfigPath,
                    e.message,
                )
                null
            }

        if (content != null) {
            val config = Yaml.default.decodeFromString(ModelsConfig.serializer(), content)
            models = config.models
            logger.info("Loaded {} models", models.size)
        } else {
            logger.warn("WARNING: models.yaml not found in any location!")
        }
    }

    fun findAll(): List<Model> = models

    fun findByName(name: String): Model? = models.find { it.name == name }

    fun findType(type: String): List<Model> = models.filter { it.modelType == type }
}
