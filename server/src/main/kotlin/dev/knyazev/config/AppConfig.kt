package dev.knyazev.config

import io.github.cdimascio.dotenv.dotenv

private val env = dotenv {
    ignoreIfMissing = true  // в prod (.env нет) читаем из реального окружения
}

private fun env(key: String): String? = env[key]

data class AppConfig(
    val port: Int,
    val openRouterApiKey: String,
    val openAiApiKey: String,
    val llmModel: String,
    val classifierModel: String,
    /** Base URL for the chat-completions LLM (OpenAI-compatible). Default: OpenRouter. */
    val llmBaseUrl: String,
    /** Base URL for embeddings/TTS/ASR (OpenAI-compatible). Default: OpenAI. */
    val embeddingBaseUrl: String,
    val embeddingModel: String,
    val docsPath: String,
    val codePath: String,
    val cachePath: String,
    val corsAllowedOrigin: String,
) {
    companion object {
        fun fromEnv(): AppConfig = AppConfig(
            port = env("PORT")?.toIntOrNull() ?: 8080,
            openRouterApiKey = env("OPENROUTER_API_KEY") ?: "",
            openAiApiKey = env("OPENAI_API_KEY") ?: "",
            llmModel = env("LLM_MODEL") ?: "anthropic/claude-3-haiku",
            classifierModel = env("CLASSIFIER_MODEL") ?: "openai/gpt-4o-mini",
            llmBaseUrl = env("LLM_BASE_URL") ?: "https://openrouter.ai/api/v1",
            embeddingBaseUrl = env("EMBEDDING_BASE_URL") ?: "https://api.openai.com/v1",
            embeddingModel = env("EMBEDDING_MODEL") ?: "text-embedding-3-small",
            docsPath = env("DOCS_PATH") ?: "../docs",
            codePath = env("CODE_PATH") ?: "..",
            cachePath = env("CACHE_PATH") ?: ".embedding-cache.json",
            corsAllowedOrigin = env("CORS_ALLOWED_ORIGIN") ?: "http://localhost:4321,https://knyazevs.github.io",
        )
    }
}
