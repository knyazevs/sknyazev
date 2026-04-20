package dev.knyazev.plugins

import dev.knyazev.api.asrRoutes
import dev.knyazev.api.autocompleteRoutes
import dev.knyazev.api.chatRoutes
import dev.knyazev.api.sessionRoutes
import dev.knyazev.api.suggestionsRoutes
import dev.knyazev.api.ttsRoutes
import dev.knyazev.guard.QuestionGuard
import dev.knyazev.llm.OpenAiClient
import dev.knyazev.llm.OpenRouterClient
import dev.knyazev.rag.RagPipeline
import dev.knyazev.rag.SuggestionsService
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun Application.configureRouting(
    ragPipeline: RagPipeline,
    openAiClient: OpenAiClient,
    openRouterClient: OpenRouterClient,
    questionGuard: QuestionGuard,
    suggestionsService: SuggestionsService,
    sessionSecret: String,
) {
    routing {
        get("/api/health") {
            call.respond(buildJsonObject { put("status", "ok") })
        }

        // Public: token issuance — rate-limited only
        rateLimit(SESSION_RATE_LIMIT) {
            sessionRoutes(sessionSecret)
        }

        // Protected: every downstream API requires a valid session token
        sessionAuth {
            suggestionsRoutes(suggestionsService)
            autocompleteRoutes(openRouterClient)
            rateLimit(CHAT_RATE_LIMIT) {
                chatRoutes(ragPipeline, questionGuard, suggestionsService)
            }
            rateLimit(MEDIA_RATE_LIMIT) {
                ttsRoutes(openAiClient)
                asrRoutes(openAiClient)
            }
        }
    }
}
