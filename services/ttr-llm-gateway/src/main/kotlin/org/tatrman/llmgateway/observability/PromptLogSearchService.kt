// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.observability

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import java.sql.ResultSet

@Service
class PromptLogSearchService(
    private val jdbcTemplate: JdbcTemplate,
) {
    private val mapper =
        RowMapper { rs: ResultSet, _ ->
            PromptLog(
                id = rs.getLong("id"),
                userId = rs.getString("user_id"),
                modelName = rs.getString("model_name"),
                provider = rs.getString("provider"),
                promptText = rs.getString("prompt_text"),
                responseText = rs.getString("response_text"),
                tokensPrompt = rs.getInt("tokens_prompt"),
                tokensCompletion = rs.getInt("tokens_completion"),
                durationMs = rs.getLong("duration_ms"),
                status = rs.getString("status"),
                createdAt = rs.getTimestamp("created_at")?.toInstant(),
            )
        }

    fun search(
        query: String,
        limit: Int = 50,
    ): List<PromptLog> {
        // Plainto_tsquery is safer for user input than to_tsquery
        val sql =
            """
            SELECT * FROM prompt_logs 
            WHERE tsv @@ plainto_tsquery('english', ?) 
            ORDER BY created_at DESC 
            LIMIT ?
            """.trimIndent()

        return jdbcTemplate.query(sql, mapper, query, limit)
    }
}
