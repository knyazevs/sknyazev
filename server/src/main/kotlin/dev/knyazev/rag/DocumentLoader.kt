package dev.knyazev.rag

import java.io.File

data class DocumentChunk(
    val filePath: String,
    val sectionTitle: String,
    val content: String,
    /** Full breadcrumb path used in contextual embedding: "path/file.md › H1 title › Section title" */
    val breadcrumb: String,
    /** Absolute path to the source file this chunk was derived from — used for per-file cache invalidation. */
    val sourceFile: String,
)

object DocumentLoader {

    fun load(docsPath: String): List<DocumentChunk> {
        val docsDir = File(docsPath)
        require(docsDir.exists() && docsDir.isDirectory) {
            "Docs directory not found: $docsPath"
        }

        return docsDir
            .walkTopDown()
            .filter { it.isFile && it.extension == "md" }
            .filter { !it.name.startsWith(".") }
            .sortedBy { it.path }
            .flatMap { file -> chunkFile(file, docsPath) }
            .toList()
    }

    private fun chunkFile(file: File, basePath: String): List<DocumentChunk> {
        val absolutePath = file.canonicalPath
        val relativePath = file.relativeTo(File(basePath)).path
        val lines = file.readLines()
        val chunks = mutableListOf<DocumentChunk>()

        // Track document-level title (first H1) for breadcrumb context
        var documentTitle: String? = null
        var currentSection = relativePath
        val currentLines = mutableListOf<String>()

        for (line in lines) {
            when {
                line.startsWith("# ") && documentTitle == null -> {
                    // First H1 = document title, not a chunk boundary
                    documentTitle = line.removePrefix("# ").trim()
                }
                line.startsWith("## ") || line.startsWith("### ") -> {
                    flushChunk(relativePath, absolutePath, currentSection, currentLines, chunks, documentTitle)
                    currentSection = line.trimStart('#').trim()
                }
                else -> currentLines.add(line)
            }
        }
        flushChunk(relativePath, absolutePath, currentSection, currentLines, chunks, documentTitle)

        return chunks
    }

    private fun flushChunk(
        filePath: String,
        sourceFile: String,
        sectionTitle: String,
        lines: MutableList<String>,
        chunks: MutableList<DocumentChunk>,
        documentTitle: String?,
    ) {
        val content = lines.joinToString("\n").trim()
        if (content.isNotBlank()) {
            val breadcrumb = buildBreadcrumb(filePath, documentTitle, sectionTitle)
            chunks.add(DocumentChunk(filePath, sectionTitle, content, breadcrumb, sourceFile))
        }
        lines.clear()
    }

    private fun buildBreadcrumb(filePath: String, documentTitle: String?, sectionTitle: String): String {
        val parts = mutableListOf(filePath)
        if (documentTitle != null) parts.add(documentTitle)
        if (sectionTitle != filePath) parts.add(sectionTitle)
        return parts.joinToString(" › ")
    }
}
