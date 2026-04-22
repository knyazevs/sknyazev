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
 * Сигнал, что модель упала *до* первого эмитнутого токена — можно безопасно
 * попробовать следующую модель из списка fallback'ов. Любая другая ошибка
 * (mid-stream cancel, network drop после эмитов) пробрасывается как есть:
 * fallback в середине ответа дал бы юзеру склейку двух разных ответов.
 */
private class LlmEarlyFailure(message: String) : RuntimeException(message)

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
     * Стримит ответ LLM, пробуя модели по очереди: primary (`model`), затем
     * `fallbackModels`. Переключение возможно только пока ни один токен
     * ещё не эмитнут — это сохраняет инвариант «юзер видит один связный ответ».
     */
    fun streamChat(messages: List<ChatMessage>): Flow<String> = flow {
        val models = buildList {
            add(model)
            addAll(fallbackModels)
        }
        val errors = mutableListOf<String>()
        for ((idx, modelName) in models.withIndex()) {
            try {
                streamFromModel(modelName, messages)
                return@flow
            } catch (e: LlmEarlyFailure) {
                val attempt = idx + 1
                logger.warn { "LLM fallback: '$modelName' failed ($attempt/${models.size}) — ${e.message}" }
                errors += "$modelName: ${e.message}"
            }
        }
        error("All LLM models failed. ${errors.joinToString(" | ")}")
    }

    private suspend fun FlowCollector<String>.streamFromModel(
        modelName: String,
        messages: List<ChatMessage>,
    ) {
        val requestBody = buildJsonObject {
            put("model", modelName)
            put("stream", true)
            putJsonArray("messages") {
                messages.forEach { msg ->
                    addJsonObject {
                        put("role", msg.role)
                        put("content", msg.content)
                    }
                }
            }
        }

        client.preparePost("$baseUrl/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(requestBody.toString())
        }.execute { response ->
            if (response.status.value !in 200..299) {
                val body = response.bodyAsText().take(500)
                throw LlmEarlyFailure("$modelName: ${response.status}: $body")
            }
            val channel: ByteReadChannel = response.bodyAsChannel()
            var emitted = 0
            var firstUnparsed: String? = null
            while (!channel.isClosedForRead) {
                val line = channel.readLine() ?: break
                if (!line.startsWith("data: ")) continue
                val data = line.removePrefix("data: ").trim()
                if (data == "[DONE]") break
                // Пропускаем keep-alive комментарии OpenRouter вида ": OPENROUTER PROCESSING"
                if (data.startsWith(":")) continue
                val token = extractToken(data)
                if (token != null) {
                    emit(token)
                    emitted++
                } else if (firstUnparsed == null) {
                    firstUnparsed = data.take(300)
                }
            }
            if (emitted == 0) {
                // Ответ 2xx, но ни одного content-токена — fallback-able: бросаем
                // LlmEarlyFailure, ни один emit ещё не прошёл, можно пробовать
                // следующую модель в списке.
                throw LlmEarlyFailure(
                    "$modelName returned no content tokens " +
                        "(status=${response.status}, " +
                        "firstUnparsed=${firstUnparsed ?: "<no data lines>"})"
                )
            }
            logger.debug { "streamChat emitted $emitted tokens from $modelName" }
        }
    }

    /** Single non-streaming completion — used by QuestionGuard classifier and suggestions. */
    suspend fun complete(messages: List<ChatMessage>, model: String = classifierModel, maxTokens: Int = 5): String {
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

        return runCatching {
            val obj = json.parseToJsonElement(response.bodyAsText()).jsonObject
            obj["choices"]!!
                .jsonArray[0]
                .jsonObject["message"]!!
                .jsonObject["content"]!!
                .jsonPrimitive.content
                .trim()
        }.getOrDefault("")
    }

    private fun extractToken(data: String): String? = runCatching {
        val obj = json.parseToJsonElement(data).jsonObject
        obj["choices"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("delta")
            ?.jsonObject
            ?.get("content")
            ?.jsonPrimitive
            ?.contentOrNull
    }.getOrNull()
}

@Serializable
data class ChatMessage(val role: String, val content: String)
