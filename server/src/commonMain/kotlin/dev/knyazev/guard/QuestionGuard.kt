package dev.knyazev.guard

import dev.knyazev.llm.ChatMessage
import dev.knyazev.llm.OpenRouterClient
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

/**
 * Validates that a question is about the person / their project.
 * Uses a cheap LLM call for binary YES/NO classification.
 *
 * Fail-open with circuit breaker: transient classifier errors allow the request through,
 * but when failures persist (≥ [FAILURE_THRESHOLD] consecutive), the breaker trips and
 * rejects all requests for [COOLDOWN_MS] — preventing unbounded traffic from reaching
 * paid downstreams when our own guard is broken. The breaker auto-resets after cooldown.
 */
class QuestionGuard(
    private val openRouterClient: OpenRouterClient,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {

    private val consecutiveFailures = atomic(0)
    private val openedAtMs = atomic(0L)

    suspend fun isRelevant(question: String): Boolean {
        if (isBreakerOpen()) {
            logger.warn { "QuestionGuard breaker OPEN — rejecting request (cooldown active)" }
            return false
        }

        val messages = listOf(
            ChatMessage(
                role = "system",
                content = SYSTEM_PROMPT,
            ),
            ChatMessage(
                role = "user",
                content = question,
            ),
        )

        return runCatching {
            val answer = openRouterClient.complete(messages)
            consecutiveFailures.value = 0
            val relevant = answer.uppercase().startsWith("YES")
            if (!relevant) {
                logger.info { "QuestionGuard rejected: \"$question\" → \"$answer\"" }
            }
            relevant
        }.getOrElse { e ->
            val failures = consecutiveFailures.incrementAndGet()
            if (failures >= FAILURE_THRESHOLD) {
                openedAtMs.value = clock()
                logger.error { "QuestionGuard breaker TRIPPED after $failures consecutive failures — fail-closed for ${COOLDOWN_MS}ms" }
                return false
            }
            logger.warn { "QuestionGuard failed (fail-open, $failures/$FAILURE_THRESHOLD): ${e.message}" }
            true
        }
    }

    private fun isBreakerOpen(): Boolean {
        val openedAt = openedAtMs.value
        if (openedAt == 0L) return false
        if (clock() - openedAt >= COOLDOWN_MS) {
            openedAtMs.value = 0L
            consecutiveFailures.value = 0
            logger.info { "QuestionGuard breaker RESET after cooldown" }
            return false
        }
        return true
    }

    companion object {
        private const val FAILURE_THRESHOLD = 5
        private const val COOLDOWN_MS = 60_000L

        private val SYSTEM_PROMPT = """
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
        """.trimIndent()
    }
}
