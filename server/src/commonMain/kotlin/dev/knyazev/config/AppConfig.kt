package dev.knyazev.config

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
    /** HMAC secret for session tokens. Required in production. */
    val sessionSecret: String,
) {
    companion object {
        fun fromEnv(): AppConfig = AppConfig(
            port = EnvReader.get("PORT")?.toIntOrNull() ?: 8080,
            openRouterApiKey = EnvReader.get("OPENROUTER_API_KEY") ?: "",
            openAiApiKey = EnvReader.get("OPENAI_API_KEY") ?: "",
            llmModel = EnvReader.get("LLM_MODEL") ?: "anthropic/claude-opus-4-7",
            classifierModel = EnvReader.get("CLASSIFIER_MODEL") ?: "openai/gpt-4o-mini",
            llmBaseUrl = EnvReader.get("LLM_BASE_URL") ?: "https://openrouter.ai/api/v1",
            embeddingBaseUrl = EnvReader.get("EMBEDDING_BASE_URL") ?: "https://api.openai.com/v1",
            embeddingModel = EnvReader.get("EMBEDDING_MODEL") ?: "text-embedding-3-small",
            docsPath = EnvReader.get("DOCS_PATH") ?: "../docs",
            codePath = EnvReader.get("CODE_PATH") ?: "..",
            cachePath = EnvReader.get("CACHE_PATH") ?: ".embedding-cache.json",
            corsAllowedOrigin = EnvReader.get("CORS_ALLOWED_ORIGIN") ?: "http://localhost:4321,https://knyazevs.github.io",
            sessionSecret = EnvReader.get("SESSION_SECRET")
                ?: error("SESSION_SECRET must be set (32+ random bytes, hex/base64). Generate with: openssl rand -hex 32"),
        )
    }
}
