package dev.knyazev

import dev.knyazev.config.AppConfig
import dev.knyazev.guard.QuestionGuard
import dev.knyazev.llm.OpenAiClient
import dev.knyazev.llm.OpenRouterClient
import dev.knyazev.plugins.configureCors
import dev.knyazev.plugins.configureRateLimit
import dev.knyazev.plugins.configureRouting
import dev.knyazev.plugins.configureSerialization
import dev.knyazev.rag.BM25Index
import dev.knyazev.rag.CodeLoader
import dev.knyazev.rag.DocumentLoader
import dev.knyazev.rag.EmbeddingCache
import dev.knyazev.rag.EmbeddingService
import dev.knyazev.rag.HydeService
import dev.knyazev.rag.InMemoryVectorStore
import dev.knyazev.rag.RagPipeline
import dev.knyazev.rag.SuggestionsService
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory

fun main() {
    val logger = LoggerFactory.getLogger("Application")
    val config = AppConfig.fromEnv()

    val openAiClient = OpenAiClient(
        apiKey = config.openAiApiKey,
        baseUrl = config.embeddingBaseUrl,
        embeddingModel = config.embeddingModel,
    )
    val openRouterClient = OpenRouterClient(
        apiKey = config.openRouterApiKey,
        model = config.llmModel,
        classifierModel = config.classifierModel,
        baseUrl = config.llmBaseUrl,
    )

    // RAG components (ADR-16, ADR-19)
    val embeddingService = EmbeddingService(openAiClient)
    val vectorStore = InMemoryVectorStore()
    val bm25Index = BM25Index()
    val hydeService = HydeService(openRouterClient)
    val ragPipeline = RagPipeline(embeddingService, vectorStore, bm25Index, hydeService, openRouterClient)

    // Startup: load docs + code → embed (contextual) → index BM25
    logger.info("Loading documents from ${config.docsPath}...")
    val documents = DocumentLoader.load(config.docsPath)
    logger.info("Loaded ${documents.size} document chunks")

    logger.info("Loading code from ${config.codePath}...")
    val codeChunks = CodeLoader.load(config.codePath)
    logger.info("Loaded ${codeChunks.size} code chunks")

    val allChunks = documents + codeChunks
    val cacheResult = EmbeddingCache.load(allChunks, config.embeddingModel, config.cachePath)

    // Load cached vectors
    cacheResult.cached.forEach { vectorStore.index(it) }

    // Embed only new/changed chunks
    if (cacheResult.toEmbed.isNotEmpty()) {
        logger.info("Embedding ${cacheResult.toEmbed.size} chunks via API (${cacheResult.cached.size} from cache)...")
        val newEntries = embeddingService.embedChunks(cacheResult.toEmbed, idOffset = cacheResult.cached.size)
        newEntries.forEach { vectorStore.index(it) }

        // Save updated cache only when something changed
        EmbeddingCache.save(vectorStore.allEntries(), allChunks, config.embeddingModel, config.cachePath)
    } else {
        logger.info("All ${cacheResult.cached.size} vectors loaded from cache — no API calls needed")
    }

    // BM25 index is built from already-indexed VectorStore entries (no extra API calls)
    bm25Index.index(vectorStore.allEntries())
    logger.info("Indexing complete: ${vectorStore.allEntries().size} chunks in vector store + BM25")

    // API protection (ADR-17, ADR-18)
    val questionGuard = QuestionGuard(openRouterClient)

    // Suggestions — defaults are set eagerly in SuggestionsService; LLM-backed initial
    // list is generated in the background so the HTTP server starts without blocking
    // on OpenRouter availability.
    val suggestionsService = SuggestionsService(openRouterClient)
    val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    startupScope.launch {
        runCatching {
            withTimeout(10_000) { suggestionsService.generateInitial(vectorStore) }
        }.onFailure { logger.warn("Initial suggestions generation skipped: ${it.message}") }
    }

    embeddedServer(CIO, port = config.port) {
        configureSerialization()
        configureCors(config.corsAllowedOrigin)
        configureRateLimit()
        configureRouting(ragPipeline, openAiClient, openRouterClient, questionGuard, suggestionsService)
    }.start(wait = true)
}
