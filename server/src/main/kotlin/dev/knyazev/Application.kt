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

    // RAG components (ADR-20, ADR-23)
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

    // API protection (ADR-21, ADR-22)
    val questionGuard = QuestionGuard(openRouterClient)

    // Suggestions — generate initial suggestions from indexed content
    val suggestionsService = SuggestionsService(openRouterClient)
    kotlinx.coroutines.runBlocking { suggestionsService.generateInitial(vectorStore) }

    embeddedServer(CIO, port = config.port) {
        configureSerialization()
        configureCors(config.corsAllowedOrigin)
        configureRateLimit()
        configureRouting(ragPipeline, openAiClient, questionGuard, suggestionsService)
    }.start(wait = true)
}
