// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.web

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.tatrman.llmgateway.model.Model
import org.tatrman.llmgateway.model.ModelService

@RestController
@RequestMapping("/api/v1/models")
class ModelController(
    private val modelService: ModelService,
) {
    @GetMapping fun listModels(): Iterable<Model> = modelService.findAll()
}
