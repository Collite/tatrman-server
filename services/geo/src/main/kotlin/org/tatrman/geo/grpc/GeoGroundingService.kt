package org.tatrman.geo.grpc

import com.google.protobuf.util.JsonFormat
import org.tatrman.grounding.v1.ClarificationOption
import org.tatrman.grounding.v1.GeoPoint
import org.tatrman.grounding.v1.GetStatusRequest
import org.tatrman.grounding.v1.GetStatusResponse
import org.tatrman.grounding.v1.GroundRequest
import org.tatrman.grounding.v1.GroundResponse
import org.tatrman.grounding.v1.GroundingResult
import org.tatrman.grounding.v1.Normalized
import org.tatrman.common.v1.ResponseMessage
import org.tatrman.common.v1.Severity
import io.grpc.Status
import io.grpc.StatusException
import org.slf4j.LoggerFactory
import org.tatrman.geo.client.GatewayResponseFormat
import org.tatrman.geo.client.LlmGatewayClient
import org.tatrman.geo.discover.GeoDiscovery
import org.tatrman.geo.obs.GeoMetrics
import org.tatrman.geo.parse.GeoQuery
import org.tatrman.geo.parse.GeoSpanParser
import org.tatrman.geo.recipe.GeoRecipeBuilder
import org.tatrman.geo.resolve.PlaceResolution
import org.tatrman.geo.resolve.PlaceResolver

/**
 * `services/geo` — the LOCATION grounding service (feature-grounding A9). Parse → resolve the
 * anchor place → recipe: distance → FilterRecipe over `geo_distance_m` (A9.5); containment →
 * bbox-prefilter FilterRecipe + polygon WKT in `Normalized.shape` (A9.4). Ambiguous place →
 * AWAITING_CLARIFICATION; unknown place / no boundary / no geo columns → UNGROUNDABLE. A span the
 * rules parser can't classify at all is routed to the llm-gateway fallback ([llmFallback],
 * `source: LLM`) for weird phrasings (A9.6); a genuine resolution gap stays UNGROUNDABLE.
 */
class GeoGroundingService(
    private val discovery: GeoDiscovery,
    private val placeResolver: PlaceResolver,
    private val llmFallback: LlmGatewayClient?,
    private val llmModel: String = "claude-haiku-4-5",
    private val metrics: GeoMetrics = GeoMetrics.noop(),
) : org.tatrman.grounding.v1.GroundingServiceGrpcKt.GroundingServiceCoroutineImplBase() {
    private val logger = LoggerFactory.getLogger(GeoGroundingService::class.java)
    private val parser = GeoSpanParser()
    private val recipeBuilder = GeoRecipeBuilder(discovery)

    override suspend fun ground(request: GroundRequest): GroundResponse {
        val startNanos = System.nanoTime()
        val response =
            try {
                groundInternal(request)
            } catch (e: StatusException) {
                // A dependency outage (geocoder UNAVAILABLE) is surfaced in metrics before it
                // propagates as a gRPC error, so a silent geocoder failure is visible on dashboards.
                metrics.recordGround(
                    outcome = e.status.code.name,
                    source = "NONE",
                    latencyMs = (System.nanoTime() - startNanos) / 1_000_000.0,
                )
                throw e
            }
        val source = if (response.hasResult()) response.result.source.name else "NONE"
        metrics.recordGround(
            outcome = response.status.name,
            source = source,
            latencyMs = (System.nanoTime() - startNanos) / 1_000_000.0,
        )
        logger.debug(
            "geo ground span='{}' pkg='{}' → outcome={}",
            request.spanText,
            request.getPackage(),
            response.status.name,
        )
        return response
    }

    private suspend fun groundInternal(request: GroundRequest): GroundResponse =
        when (val query = parser.parse(request.spanText)) {
            is GeoQuery.Distance -> handleDistance(query, request)
            is GeoQuery.Containment -> handleContainment(query, request)
            null -> fallback(request, "could not recognize a location expression in '${request.spanText}'")
        }

    /** Resolve the anchor, honouring a clarification choice ([GroundRequest.clarificationAnswerId]). */
    private suspend fun resolvePlace(
        name: String,
        request: GroundRequest,
    ): PlaceResolution {
        val choice = request.clarificationAnswerId
        return if (choice.isNotEmpty()) {
            placeResolver.resolveChoice(name, request.getPackage(), choice)
        } else {
            placeResolver.resolve(name, request.getPackage())
        }
    }

    private fun unavailable(reason: String): Nothing =
        throw StatusException(Status.UNAVAILABLE.withDescription("geo place resolution unavailable: $reason"))

    private suspend fun handleContainment(
        query: GeoQuery.Containment,
        request: GroundRequest,
    ): GroundResponse =
        when (val resolution = resolvePlace(query.place, request)) {
            is PlaceResolution.Found ->
                recipeBuilder
                    .buildContainment(resolution.place, request.getPackage())
                    ?.let {
                        GroundResponse
                            .newBuilder()
                            .setStatus(GroundResponse.Status.OK)
                            .setResult(it)
                            .build()
                    }
                    ?: ungroundable(
                        "no boundary for '${query.place}', or package '${request.getPackage()}' has no geo columns",
                    )
            is PlaceResolution.ModelPoi ->
                ungroundable(
                    "'${query.place}' resolves to a model POI (a point), which has no boundary for containment",
                )
            is PlaceResolution.Ambiguous -> awaitingClarification(resolution)
            is PlaceResolution.Unavailable -> unavailable(resolution.reason)
            PlaceResolution.Unknown -> ungroundable("could not resolve the place '${query.place}'")
        }

    private suspend fun handleDistance(
        query: GeoQuery.Distance,
        request: GroundRequest,
    ): GroundResponse {
        if (query.radiusMeters <= 0.0) {
            return ungroundable("distance query needs a radius, e.g. \"within 20 km of …\"")
        }
        val placeName = if (query.here) request.context.herePlaceRef else query.place
        if (placeName.isNullOrBlank()) {
            return ungroundable("no anchor place (here_place_ref is empty)")
        }
        return when (val resolution = resolvePlace(placeName, request)) {
            is PlaceResolution.Found ->
                recipeBuilder
                    .buildDistance(query, resolution.place, request.getPackage())
                    ?.let { ok(it) }
                    ?: ungroundable("package '${request.getPackage()}' has no geo_lat/geo_lon columns")
            is PlaceResolution.ModelPoi ->
                recipeBuilder
                    .buildDistanceToPoi(query, resolution.poi, request.getPackage())
                    ?.let { ok(it) }
                    ?: ungroundable("package '${request.getPackage()}' has no geo_lat/geo_lon columns")
            is PlaceResolution.Ambiguous -> awaitingClarification(resolution)
            is PlaceResolution.Unavailable -> unavailable(resolution.reason)
            PlaceResolution.Unknown -> ungroundable("could not resolve the place '$placeName'")
        }
    }

    private fun ok(result: org.tatrman.grounding.v1.GroundingResult): GroundResponse =
        GroundResponse
            .newBuilder()
            .setStatus(GroundResponse.Status.OK)
            .setResult(result)
            .build()

    private fun awaitingClarification(ambiguous: PlaceResolution.Ambiguous): GroundResponse {
        val options =
            ambiguous.candidates.map { candidate ->
                ClarificationOption
                    .newBuilder()
                    .setId(candidate.id)
                    .setLabel(candidate.label)
                    .setNormalized(
                        Normalized
                            .newBuilder()
                            .setPoint(GeoPoint.newBuilder().setLat(candidate.lat).setLon(candidate.lon)),
                    ).build()
            }
        return GroundResponse
            .newBuilder()
            .setStatus(GroundResponse.Status.AWAITING_CLARIFICATION)
            .addAllOptions(options)
            .build()
    }

    override suspend fun getStatus(request: GetStatusRequest): GetStatusResponse {
        val metadataOk = runCatching { discovery.probeReady() }.getOrDefault(false)
        return GetStatusResponse
            .newBuilder()
            .setReady(metadataOk)
            .setService("geo")
            .putCapabilities("metadata", if (metadataOk) "ok" else "down")
            .putCapabilities("llm_fallback", (llmFallback != null).toString())
            .build()
    }

    // ----- llm-gateway fallback (A9.6) -----

    private suspend fun fallback(
        request: GroundRequest,
        reason: String,
    ): GroundResponse {
        val client = llmFallback ?: return ungroundable(reason)
        return try {
            val raw =
                client.chat(
                    model = llmModel,
                    system = LLM_SYSTEM_PROMPT,
                    user = llmUserPrompt(request),
                    responseFormat = GatewayResponseFormat("json_object"),
                )
            parseLlmResult(raw)
                ?.let {
                    GroundResponse
                        .newBuilder()
                        .setStatus(GroundResponse.Status.OK)
                        .setResult(it.toBuilder().setSource(GroundingResult.Source.LLM))
                        .build()
                } ?: ungroundable("$reason; llm fallback returned an invalid GroundingResult")
        } catch (e: Exception) {
            logger.warn("geo llm fallback failed for span '{}': {}", request.spanText, e.message)
            ungroundable("$reason; llm fallback failed")
        }
    }

    /**
     * Parse the gateway's JSON into a GroundingResult and structurally validate it. Full Translator
     * round-trip re-validation is deferred here on purpose (as in chrono A8.6): the service holds no
     * ModelHandle, and Golem's free-SQL tier already post-parses the recipe against the plan.
     */
    private fun parseLlmResult(raw: String): GroundingResult? {
        val result =
            runCatching {
                val builder = GroundingResult.newBuilder()
                JsonFormat.parser().ignoringUnknownFields().merge(raw, builder)
                builder.build()
            }.getOrElse {
                logger.warn("geo llm fallback: unparseable GroundingResult JSON: {}", it.message)
                return null
            }
        val structurallyValid =
            result.hasNormalized() &&
                result.applicationCase != GroundingResult.ApplicationCase.APPLICATION_NOT_SET &&
                result.sqlPreview.isNotEmpty()
        return result.takeIf { structurallyValid }
    }

    private fun llmUserPrompt(request: GroundRequest): String =
        """
        Span: "${request.spanText}"
        Question: "${request.questionText}"
        here_place_ref: ${request.context.herePlaceRef}
        package: ${request.getPackage()}
        """.trimIndent()

    private fun ungroundable(message: String): GroundResponse =
        GroundResponse
            .newBuilder()
            .setStatus(GroundResponse.Status.UNGROUNDABLE)
            .addMessages(
                ResponseMessage
                    .newBuilder()
                    .setSeverity(Severity.INFO)
                    .setCode("ungroundable")
                    .setHumanMessage(message),
            ).build()

    private companion object {
        const val LLM_SYSTEM_PROMPT =
            "You ground a location expression to a JSON GroundingResult (org.tatrman.grounding.v1). " +
                "Return ONLY the JSON object: a `normalized` with either a `point` {lat, lon, radius_m} " +
                "for a distance query or a `shape` {wkt} for a containment query, a `filter` recipe " +
                "(condition + parameters + anchorColumn) over the `geo_distance_m` catalog function or a " +
                "lat/lon bounding box, `sqlPreview` (Calcite SQL, double-quoted identifiers, named params " +
                "{name}), `confidence` 0..1, and a one-line `explanation`."
    }
}
