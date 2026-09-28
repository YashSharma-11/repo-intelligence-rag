package com.yashsharma.repointel.rag;

/**
 * A pointer to the code that grounded part of an answer.
 *
 * @param path       source file path within the repository
 * @param symbol     enclosing symbol name, if detected
 * @param startLine  1-based start line
 * @param endLine    1-based end line
 * @param score      fused relevance score of the supporting chunk
 */
public record Citation(String path, String symbol, int startLine, int endLine, double score) {
}
