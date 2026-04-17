package dev.knyazev.rag

import dev.knyazev.llm.ChatMessage
import dev.knyazev.llm.OpenRouterClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import org.slf4j.LoggerFactory

data class RagResult(
    /** Unique filePaths of retrieved chunks — emitted to client as source references. */
    val sources: List<String>,
    val stream: Flow<String>,
)

private const val DENSE_CANDIDATE_POOL = 20
private const val BM25_CANDIDATE_POOL = 20
private const val RRF_K = 60
private const val SIMILARITY_THRESHOLD = 0.008f  // applied to RRF score, not cosine
private const val DENSE_COSINE_THRESHOLD = 0.35f
private const val FINAL_TOP_K = 5

private val SYSTEM_PROMPT = """
Ты — интерпретационный слой персонального профиля Сергея Князева.

Правила (строго):
- Отвечай ТОЛЬКО на основе предоставленных фрагментов документации.
- Если информации недостаточно — явно скажи об этом и предложи посмотреть конкретный документ.
- Не добавляй факты, которых нет в источнике. Не «продавай» автора.
- Отвечай на том языке, на котором задан вопрос.
- Используй markdown в ответах: **жирный** для акцентов, заголовки для структуры, списки где уместно.

Цитирование источников (обязательно):
Каждый фрагмент контекста пронумерован: [1], [2], и т.д.
В тексте ответа ставь номера источников в квадратных скобках сразу после утверждения,
которое опирается на этот источник. Пример: «Сергей использует Kotlin/Ktor [1] и Svelte 5 [3].»
Можно ссылаться на несколько источников: [1][3]. Не группируй все ссылки в конце — ставь по месту.
НЕ используй markdown-ссылки вида [текст](путь) для источников — только номера [1], [2].

Формат ответа (обязательно):
Сначала дай краткий ответ — 1-3 предложения, суть вопроса.
Затем поставь маркер ---DETAIL--- на отдельной строке.
После маркера дай развёрнутый ответ с деталями, примерами и ссылками на источники.
Краткий ответ должен быть самодостаточным — читатель может не открывать подробности.
""".trimIndent()

private val NO_CONTEXT_PROMPT = """
Ты — интерпретационный слой персонального профиля Сергея Князева.
В текущей документации не найдено релевантной информации по данному вопросу.
Честно сообщи пользователю об этом и предложи задать более конкретный вопрос.
""".trimIndent()

class RagPipeline(
    private val embeddingService: EmbeddingService,
    private val vectorStore: VectorStore,
    private val bm25Index: BM25Index,
    private val hydeService: HydeService,
    private val openRouterClient: OpenRouterClient,
) {
    private val logger = LoggerFactory.getLogger(RagPipeline::class.java)

    suspend fun ask(question: String): RagResult = coroutineScope {
        // Step 1: HyDE — reformulate question into hypothetical answer for better embedding alignment
        val hydeText = hydeService.reformulate(question)

        // Step 2: Parallel dense + BM25 retrieval
        val denseDeferred = async {
            val queryVector = embeddingService.embed(hydeText)
            vectorStore.searchWithScores(queryVector, DENSE_CANDIDATE_POOL, DENSE_COSINE_THRESHOLD)
        }
        val bm25Deferred = async {
            bm25Index.search(question, BM25_CANDIDATE_POOL)
        }

        val denseResults = denseDeferred.await()
        val bm25Results = bm25Deferred.await()

        logger.debug("Dense: ${denseResults.size} candidates, BM25: ${bm25Results.size} candidates")

        // Step 3: RRF fusion + threshold filter → final top-K
        val finalChunks = RrfFusion.fuse(
            denseResults = denseResults,
            bm25Results = bm25Results,
            topK = FINAL_TOP_K,
            threshold = SIMILARITY_THRESHOLD,
            k = RRF_K,
        )

        logger.debug("RRF fused to ${finalChunks.size} chunks for question: \"$question\"")

        // Step 4: Collect unique source paths (ordered — indices match [1], [2], … in prompt)
        val sources = finalChunks.map { it.entry.filePath }.distinct()

        // Step 5: Build prompt with numbered sources and stream LLM response
        val messages = if (finalChunks.isEmpty()) {
            listOf(
                ChatMessage("system", NO_CONTEXT_PROMPT),
                ChatMessage("user", question),
            )
        } else {
            val context = finalChunks.mapIndexed { idx, scored ->
                val sourceIdx = sources.indexOf(scored.entry.filePath) + 1
                "[$sourceIdx] Источник: ${scored.entry.breadcrumb}\n\n${scored.entry.content}"
            }.joinToString("\n\n---\n\n")
            listOf(
                ChatMessage("system", SYSTEM_PROMPT),
                ChatMessage("user", "Контекст из документации:\n\n$context\n\n---\n\nВопрос: $question"),
            )
        }

        RagResult(sources = sources, stream = openRouterClient.streamChat(messages))
    }
}
