package com.yashsharma.repointel.api;

import jakarta.validation.constraints.NotBlank;

/**
 * Request to ask a natural-language question about a previously ingested repository.
 *
 * @param question the user's question
 * @param repoId   optional repository id (returned by ingest) to scope retrieval
 */
public record QueryRequest(@NotBlank String question, String repoId) {
}
