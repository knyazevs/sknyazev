package dev.knyazev.api

import dev.knyazev.llm.OpenAiClient
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class AsrResponse(val text: String)

fun Route.asrRoutes(openAiClient: OpenAiClient) {
    post("/api/asr") {
        val multipart = call.receiveMultipart()
        var audioBytes: ByteArray? = null
        var filename = "audio.webm"

        multipart.forEachPart { part ->
            if (part is PartData.FileItem && part.name == "audio") {
                filename = part.originalFileName ?: filename
                audioBytes = part.streamProvider().readBytes()
            }
            part.dispose()
        }

        if (audioBytes == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "audio part is required"))
            return@post
        }

        val text = openAiClient.speechToText(audioBytes!!, filename)
        call.respond(AsrResponse(text))
    }
}
