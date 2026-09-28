package com.yashsharma.repointel.ingest;

/**
 * A single unit of code selected for embedding and retrieval.
 *
 * @param relativePath path of the source file relative to the repository root
 * @param language     detected language (by file extension)
 * @param symbol       best-effort enclosing symbol (class/function) name, or {@code null}
 * @param startLine    1-based start line of the chunk in the file
 * @param endLine      1-based end line of the chunk in the file
 * @param content      the raw source text of the chunk
 */
public record CodeChunk(
        String relativePath,
        String language,
        String symbol,
        int startLine,
        int endLine,
        String content
) {
}
