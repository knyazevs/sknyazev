package dev.knyazev.llm

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

private val logger = KotlinLogging.logger("OpenRouterClient")

/**
 * Сигнал, что модель упала *до* первого эмитнутого события — можно безопасно
 * попробовать следующую модель из списка fallback'ов. Любая другая ошибка
 * (mid-stream cancel, network drop после эмитов) пробрасывается как есть:
 * fallback в середине ответа дал бы юзеру склейку двух разных ответов.
 */
private class LlmEarlyFailure(message: String) : RuntimeException(message)

/**
 * Модели, у которых через OpenRouter доступен tool-calling (ADR-024). Модели вне
 * списка получают запрос без `tools` — B-часть UI-блоков отключается, C-часть
 * (блоки из markdown) работает независимо.
 */
private val TOOL_CAPABLE_MODELS = setOf(
    "anthropic/claude-opus-4-7",
    "anthropic/claude-sonnet-4-6",
    "anthropic/claude-3-7-sonnet",
    "anthropic/claude-3-5-sonnet",
    "openai/gpt-4o",
    "openai/gpt-4o-mini",
    "openai/gpt-4.1",
    "openai/gpt-4.1-mini",
)

class OpenRouterClient(
    private val apiKey: String,
    private val model: String,
    private val fallbackModels: List<String> = emptyList(),
    private val classifierModel: String = "openai/gpt-4o-mini",
    private val baseUrl: String = "https://openrouter.ai/api/v1",
) {
    /** Exposed for SuggestionsService to use the same cheap model. */
    val classifierModelName: String get() = classifierModel

    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient() {
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            socketTimeoutMillis = 60_000
        }
    }

    /**
     * Стримит ответ LLM: токены текста + вызовы инструментов (ADR-024). Пробует модели по
     * очереди: primary (`model`), затем `fallbackModels`. Переключение возможно только пока
     * ни одного события ещё не эмитнуто — это сохраняет инвариант «юзер видит один связный
     * ответ» (ADR-023). Если модель не поддерживает tools — `tools` не передаются, B-блоки
     * от неё не приходят, но токены и C-блоки работают.
     */
    fun streamChat(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition> = emptyList(),
    ): Flow<ChatEvent> = flow {
        val models = buildList {
            add(model)
            addAll(fallbackModels)
        }
        println("[LLM] streamChat chain=${models.joinToString(" → ")}")
        val errors = mutableListOf<String>()
        for ((idx, modelName) in models.withIndex()) {
            try {
                streamFromModel(modelName, messages, tools)
                return@flow
            } catch (e: LlmEarlyFailure) {
                val attempt = idx + 1
                logger.warn { "LLM fallback: '$modelName' failed ($attempt/${models.size}) — ${e.message}" }
                errors += "$modelName: ${e.message}"
            }
        }
        error("All LLM models failed. ${errors.joinToString(" | ")}")
    }

    private class ToolCallAccumulator(
        var id: String? = null,
        var name: String? = null,
        val argsBuilder: StringBuilder = StringBuilder(),
    )

    private suspend fun FlowCollector<ChatEvent>.streamFromModel(
        modelName: String,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
    ) {
        val useTools = tools.isNotEmpty() && modelName in TOOL_CAPABLE_MODELS
        val totalContentChars = messages.sumOf { it.content.length }
        println(
            "[LLM] streamFromModel model=$modelName messages=${messages.size} " +
                "totalContent=$totalContentChars chars (~${totalContentChars / 4} tokens) " +
                "tools=$useTools"
        )

        val requestBody = buildJsonObject {
            put("model", modelName)
            put("stream", true)
            // Anthropic через OpenRouter требует max_tokens, иначе иногда отвечает пустым
            // SSE без data-строк (status 200, 0 токенов). Для остальных это просто верхняя
            // граница — обычный ответ сильно короче.
            put("max_tokens", 4000)
            putJsonArray("messages") {
                messages.forEach { msg ->
                    addJsonObject {
                        put("role", msg.role)
                        put("content", msg.content)
                    }
                }
            }
            if (useTools) {
                putJsonArray("tools") {
                    tools.forEach { tool ->
                        addJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", tool.parametersSchema)
                            }
                        }
                    }
                }
                put("tool_choice", "auto")
            }
        }

        client.preparePost("$baseUrl/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(requestBody.toString())
        }.execute { response ->
            println("[LLM] $modelName response status=${response.status}")
            if (response.status.value !in 200..299) {
                val body = response.bodyAsText().take(500)
                println("[LLM] $modelName non-2xx body=$body")
                throw LlmEarlyFailure("$modelName: ${response.status}: $body")
            }
            val channel: ByteReadChannel = response.bodyAsChannel()
            var emitted = 0
            var firstUnparsed: String? = null
            val allLines = StringBuilder()
            val toolAccumulators = mutableMapOf<Int, ToolCallAccumulator>()

            while (!channel.isClosedForRead) {
                val line = channel.readLine() ?: break
                if (allLines.length < 2000) allLines.appendLine(line)
                if (!line.startsWith("data: ")) continue
                val data = line.removePrefix("data: ").trim()
                if (data == "[DONE]") break
                // Пропускаем keep-alive комментарии OpenRouter вида ": OPENROUTER PROCESSING"
                if (data.startsWith(":")) continue

                val parsed = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull()
                if (parsed == null) {
                    if (firstUnparsed == null) firstUnparsed = data.take(300)
                    continue
                }
                val choice = parsed["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
                val delta = choice["delta"]?.jsonObject

                if (delta != null) {
                    delta["content"]?.jsonPrimitive?.contentOrNull?.let { content ->
                        emit(ChatEvent.Token(content))
                        emitted++
                    }
                    delta["tool_calls"]?.jsonArray?.forEach { tcElem ->
                        val tc = tcElem.jsonObject
                        val index = tc["index"]?.jsonPrimitive?.intOrNull ?: 0
                        val acc = toolAccumulators.getOrPut(index) { ToolCallAccumulator() }
                        tc["id"]?.jsonPrimitive?.contentOrNull?.let { acc.id = it }
                        tc["function"]?.jsonObject?.let { fn ->
                            fn["name"]?.jsonPrimitive?.contentOrNull?.let { acc.name = it }
                            fn["arguments"]?.jsonPrimitive?.contentOrNull?.let { acc.argsBuilder.append(it) }
                        }
                    }
                }

                val finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
                if (finishReason == "tool_calls" || (finishReason != null && toolAccumulators.isNotEmpty())) {
                    for ((_, acc) in toolAccumulators) {
                        val name = acc.name ?: continue
                        emit(ChatEvent.ToolCall(id = acc.id ?: "", name = name, arguments = acc.argsBuilder.toString()))
                        emitted++
                    }
                    toolAccumulators.clear()
                }
            }

            if (emitted == 0) {
                println(
                    "[LLM] $modelName produced 0 events. status=${response.status}\n" +
                        "  firstUnparsed=${firstUnparsed ?: "<no data lines>"}\n" +
                        "  raw body (up to 2000 chars):\n${allLines}"
                )
                throw LlmEarlyFailure(
                    "$modelName returned no content tokens " +
                        "(status=${response.status}, " +
                        "firstUnparsed=${firstUnparsed ?: "<no data lines>"})"
                )
            }
            println("[LLM] $modelName emitted $emitted events (tools=$useTools)")
        }
    }

    /** Single non-streaming completion — used by QuestionRouter classifier and suggestions. */
    suspend fun complete(messages: List<ChatMessage>, model: String = classifierModel, maxTokens: Int = 5): String {
        println("[LLM-complete] model=$model maxTokens=$maxTokens messages=${messages.size}")
        val requestBody = buildJsonObject {
            put("model", model)
            put("stream", false)
            put("max_tokens", maxTokens)
            putJsonArray("messages") {
                messages.forEach { msg ->
                    addJsonObject {
                        put("role", msg.role)
                        put("content", msg.content)
                    }
                }
            }
        }

        val response = client.post("$baseUrl/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(requestBody.toString())
        }
        println("[LLM-complete] model=$model status=${response.status}")

        val bodyText = response.bodyAsText()
        return runCatching {
            val obj = json.parseToJsonElement(bodyText).jsonObject
            val content = obj["choices"]!!
                .jsonArray[0]
                .jsonObject["message"]!!
                .jsonObject["content"]!!
                .jsonPrimitive.content
                .trim()
            println("[LLM-complete] model=$model content='$content'")
            content
        }.getOrElse {
            println("[LLM-complete] model=$model parse FAILED: ${it.message}. body (up to 500 chars): ${bodyText.take(500)}")
            ""
        }
    }
}

@Serializable
data class ChatMessage(val role: String, val content: String)
