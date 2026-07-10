package org.tatrman.prometheus.web

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.tatrman.prometheus.model.Model
import org.tatrman.prometheus.model.ModelService

@RestController
@RequestMapping("/api/v1/models")
class ModelController(
    private val modelService: ModelService,
) {
    @GetMapping fun listModels(): Iterable<Model> = modelService.findAll()
}
