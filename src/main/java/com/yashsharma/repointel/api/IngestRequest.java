package com.yashsharma.repointel.api;

import jakarta.validation.constraints.NotBlank;

/**
 * Request to ingest (clone + index) a Git repository.
 *
 * @param repoUrl HTTPS URL of a public Git repository
 * @param branch  optional branch to clone; defaults to the repository's default branch
 */
public record IngestRequest(@NotBlank String repoUrl, String branch) {
}
