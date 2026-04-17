package dev.knyazev.guard

import dev.knyazev.llm.ChatMessage
import dev.knyazev.llm.OpenRouterClient
import org.slf4j.LoggerFactory

/**
 * Validates that a question is about the person / their project.
 * Uses a cheap LLM call for binary YES/NO classification.
 *
 * Fail-open: if the classifier itself fails, the request is allowed through
 * so that a transient API error doesn't break the main user journey.
 */
class QuestionGuard(private val openRouterClient: OpenRouterClient) {

    private val logger = LoggerFactory.getLogger(QuestionGuard::class.java)

    suspend fun isRelevant(question: String): Boolean {
        val messages = listOf(
            ChatMessage(
                role = "system",
                content = """
                    You are a relevance filter for a personal portfolio chatbot. Answer only YES or NO.
                    The chatbot answers questions about a specific person named Sergey Knyazev.
                    Questions may be in any language (Russian, English, etc.).

                    Answer YES if the question is about:
                    - this person (Sergey / Сергей / he / him / автор / он)
                    - his skills, stack, technologies, experience, background
                    - his projects, architecture, decisions, code
                    - his career, education, goals, opinions
                    - this portfolio, chatbot, or the project itself

                    Answer NO only if the question is clearly unrelated to any person:
                    general knowledge, coding help unrelated to Sergey, creative writing, math, etc.

                    When in doubt — answer YES.
                """.trimIndent(),
            ),
            ChatMessage(
                role = "user",
                content = question,
            ),
        )

        return runCatching {
            val answer = openRouterClient.complete(messages)
            val relevant = answer.uppercase().startsWith("YES")
            if (!relevant) {
                logger.info("QuestionGuard rejected: \"$question\" → \"$answer\"")
            }
            relevant
        }.getOrElse { e ->
            // Fail open — classifier error must not block legitimate users
            logger.warn("QuestionGuard failed (fail-open): ${e.message}")
            true
        }
    }
}
