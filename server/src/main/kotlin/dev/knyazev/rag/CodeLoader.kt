package dev.knyazev.rag

import java.io.File
import org.slf4j.LoggerFactory

/**
 * Loads source code files from the repository into DocumentChunks for RAG indexing.
 *
 * Strategy:
 * - Walks server/, app/src/, scripts/ and config files at repo root
 * - Small files (≤ 80 lines): one chunk = whole file
 * - Larger files: split at top-level Kotlin/TS declaration boundaries
 * - Very large files (> 500 lines): skipped — unlikely to be useful in RAG context
 *
 * Breadcrumb format: "code/path/to/File.kt › fun myFunction(...)"
 */
object CodeLoader {

    private val logger = LoggerFactory.getLogger(CodeLoader::class.java)

    private val INCLUDED_EXTENSIONS = setOf(
        "kt", "kts",
        "ts", "mjs", "js",
        "svelte",
        "toml", "yml", "yaml",
        "json", "md",
        "conf",
    )

    private val EXCLUDED_DIRS = setOf(
        "node_modules", ".git", "build", "dist", ".gradle",
        "out", "generated", ".idea", "cache", ".kotlin",
    )

    private val EXCLUDED_FILES = setOf(
        "bun.lock", "package-lock.json", "yarn.lock",
        "gradlew", "gradlew.bat",
    )

    // Directories relative to repo root to index
    private val SOURCE_DIRS = listOf("server/src", "app/src", "scripts")

    // Config files at repo root
    private val ROOT_FILES = listOf(
        "server/build.gradle.kts",
        "server/.env.example",
        "app/astro.config.mjs",
        "app/package.json",
        "CLAUDE.md",
    )

    private const val SKIP_THRESHOLD = 500  // lines
    private const val SPLIT_THRESHOLD = 80  // lines — whole file below this

    fun load(repoRoot: String): List<DocumentChunk> {
        val root = File(repoRoot)
        val chunks = mutableListOf<DocumentChunk>()

        // Source directories
        for (relDir in SOURCE_DIRS) {
            val dir = File(root, relDir)
            if (!dir.exists()) continue
            dir.walkTopDown()
                .onEnter { it.name !in EXCLUDED_DIRS && !it.name.startsWith(".") }
                .filter { it.isFile && it.extension in INCLUDED_EXTENSIONS }
                .filter { it.name !in EXCLUDED_FILES }
                .sortedBy { it.path }
                .flatMapTo(chunks) { file -> chunkFile(file, root.path) }
        }

        // Selected root-level config files
        for (relPath in ROOT_FILES) {
            val file = File(root, relPath)
            if (file.exists()) chunks.addAll(chunkFile(file, root.path))
        }

        return chunks
    }

    private fun chunkFile(file: File, basePath: String): List<DocumentChunk> {
        val absolutePath = file.canonicalPath
        val relativePath = "code/" + file.relativeTo(File(basePath)).path
        val lines = try {
            file.readLines()
        } catch (_: Exception) {
            return emptyList()
        }

        if (lines.size > SKIP_THRESHOLD) {
            logger.info("Skipping {} — {} lines > SKIP_THRESHOLD ({})", relativePath, lines.size, SKIP_THRESHOLD)
            return emptyList()
        }
        if (lines.size <= SPLIT_THRESHOLD) return listOf(wholeFileChunk(relativePath, absolutePath, lines))

        return when (file.extension) {
            "kt", "kts" -> splitByDeclarations(relativePath, absolutePath, lines, KOTLIN_DECL)
            "ts", "mjs", "js" -> splitByDeclarations(relativePath, absolutePath, lines, TYPESCRIPT_DECL)
            else -> listOf(wholeFileChunk(relativePath, absolutePath, lines))
        }.ifEmpty { listOf(wholeFileChunk(relativePath, absolutePath, lines)) }
    }

    private val KOTLIN_DECL = Regex(
        """^(fun |suspend fun |private fun |internal fun |protected fun |override fun |""" +
        """class |data class |sealed class |abstract class |object |companion object |interface )"""
    )

    private val TYPESCRIPT_DECL = Regex(
        """^(export (async )?function |export const |export class |export interface |""" +
        """export type |export default |async function |function )"""
    )

    private fun splitByDeclarations(path: String, sourceFile: String, lines: List<String>, pattern: Regex): List<DocumentChunk> {
        val chunks = mutableListOf<DocumentChunk>()
        var chunkStart = 0
        var chunkLabel = path

        for (i in lines.indices) {
            val trimmed = lines[i].trimStart()
            if (i > 0 && pattern.containsMatchIn(trimmed)) {
                val content = lines.subList(chunkStart, i).joinToString("\n").trim()
                if (content.isNotBlank()) {
                    chunks.add(DocumentChunk(path, chunkLabel, content, breadcrumb(path, chunkLabel), sourceFile))
                }
                chunkLabel = trimmed.take(80).trimEnd()
                chunkStart = i
            }
        }

        val tail = lines.subList(chunkStart, lines.size).joinToString("\n").trim()
        if (tail.isNotBlank()) {
            chunks.add(DocumentChunk(path, chunkLabel, tail, breadcrumb(path, chunkLabel), sourceFile))
        }

        return chunks
    }

    private fun wholeFileChunk(path: String, sourceFile: String, lines: List<String>): DocumentChunk {
        val content = lines.joinToString("\n").trim()
        return DocumentChunk(path, path, content, path, sourceFile)
    }

    private fun breadcrumb(path: String, label: String): String =
        if (label == path) path else "$path › $label"
}
