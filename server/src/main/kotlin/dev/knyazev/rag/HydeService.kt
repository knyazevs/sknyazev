package dev.knyazev.rag

import dev.knyazev.llm.ChatMessage
import dev.knyazev.llm.OpenRouterClient
import org.slf4j.LoggerFactory

/**
 * HyDE — Hypothetical Document Embeddings (ADR-23).
 *
 * Generates a short hypothetical answer to the user's question using a cheap LLM.
 * The hypothetical answer is semantically closer to relevant documentation chunks
 * than the raw question, closing the query-document semantic gap.
 *
 * Fail-open: on any error, the original question is returned unchanged.
 */
class HydeService(private val openRouterClient: OpenRouterClient) {

    private val logger = LoggerFactory.getLogger(HydeService::class.java)

    suspend fun reformulate(question: String): String {
        return runCatching {
            val messages = listOf(
                ChatMessage(
                    role = "system",
                    content = """
                        You are a professional profile document assistant.
                        Write 2-3 sentences as if this were a factual answer from someone's
                        professional documentation or CV. Be specific and use professional language.
                        Do NOT say you don't know — generate a plausible answer based on the question.
                    """.trimIndent(),
                ),
                ChatMessage(
                    role = "user",
                    content = question,
                ),
            )
            val hypothetical = openRouterClient.complete(messages)
            logger.debug("HyDE reformulated: \"$question\" → \"$hypothetical\"")
            hypothetical.ifBlank { question }
        }.getOrElse { e ->
            logger.warn("HyDE failed (fail-open): ${e.message}")
            question
        }
    }
}
