package dev.knyazev.api

import dev.knyazev.guard.QuestionGuard
import dev.knyazev.rag.RagPipeline
import dev.knyazev.rag.SuggestionsService
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.catch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.serializer

@Serializable
data class ChatRequest(val question: String)

fun Route.chatRoutes(ragPipeline: RagPipeline, questionGuard: QuestionGuard, suggestionsService: SuggestionsService) {
    post("/api/chat") {
        val request = call.receive<ChatRequest>()
        val question = request.question.trim()

        if (question.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "question must not be blank"))
            return@post
        }

        if (!questionGuard.isRelevant(question)) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Этот чатбот отвечает только на вопросы о Сергее Князеве и его проекте.")
            )
            return@post
        }

        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
            val result = ragPipeline.ask(question)
            var fullAnswer = ""
            result.stream
                .catch { e ->
                    val errorEvent = "data: {\"error\":\"${e.message?.replace("\"", "'")}\"}\n\n"
                    writeStringUtf8(errorEvent)
                }
                .collect { token ->
                    fullAnswer += token
                    val escaped = Json.encodeToString(String.serializer(), token)
                    writeStringUtf8("data: {\"token\":$escaped}\n\n")
                    flush()
                }
            if (result.sources.isNotEmpty()) {
                val sourcesJson = result.sources.joinToString(",") { src ->
                    Json.encodeToString(String.serializer(), src)
                }
                writeStringUtf8("data: {\"sources\":[$sourcesJson]}\n\n")
                flush()
            }
            // Follow-up suggestions based on the summary part of the answer
            val summaryPart = fullAnswer.substringBefore("---DETAIL---").trim()
            val followUps = suggestionsService.generateFollowUps(question, summaryPart)
            if (followUps.isNotEmpty()) {
                val suggestionsJson = followUps.joinToString(",") { s ->
                    Json.encodeToString(String.serializer(), s)
                }
                writeStringUtf8("data: {\"suggestions\":[$suggestionsJson]}\n\n")
                flush()
            }
            writeStringUtf8("data: [DONE]\n\n")
            flush()
        }
    }
}
