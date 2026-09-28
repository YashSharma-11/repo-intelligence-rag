package com.yashsharma.repointel.rag;

import java.util.List;

/**
 * Result of a repository question: the grounded answer, the citations that
 * support it, and the rewritten query that was actually used for retrieval.
 */
public record QueryResponse(String answer, List<Citation> citations, String searchQuery) {
}
