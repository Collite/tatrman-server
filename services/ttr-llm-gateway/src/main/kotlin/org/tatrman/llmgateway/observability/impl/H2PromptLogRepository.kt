// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.observability.impl

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.tatrman.llmgateway.observability.PromptLog
import org.tatrman.llmgateway.observability.PromptLogRepository

@Repository
@ConditionalOnProperty(name = ["llm.storage.type"], havingValue = "h2")
class H2PromptLogRepository(
    private val jdbcTemplate: JdbcTemplate,
) : PromptLogRepository {
    override fun <S : PromptLog> save(entity: S): S {
        val sql =
            """
            INSERT INTO prompt_logs (user_id, model_name, provider, prompt_text, response_text, status, tokens_prompt, tokens_completion, duration_ms, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()

        jdbcTemplate.update(
            sql,
            entity.userId,
            entity.modelName,
            entity.provider,
            entity.promptText,
            entity.responseText,
            entity.status,
            entity.tokensPrompt,
            entity.tokensCompletion,
            entity.durationMs,
            entity.createdAt,
        )
        return entity
    }

    override fun <S : PromptLog> saveAll(entities: Iterable<S>): Iterable<S> {
        entities.forEach { save(it) }
        return entities
    }
}
