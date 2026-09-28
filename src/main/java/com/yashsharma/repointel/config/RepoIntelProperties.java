package com.yashsharma.repointel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Strongly-typed configuration for ingestion and retrieval, bound from the
 * {@code repo-intel.*} properties in application.yml.
 */
@ConfigurationProperties(prefix = "repo-intel")
public record RepoIntelProperties(
        String workDir,
        Ingest ingest,
        Retrieval retrieval
) {

    public record Ingest(
            List<String> codeExtensions,
            long maxFileSizeBytes,
            int maxChunkChars,
            int chunkOverlapChars
    ) {
    }

    public record Retrieval(
            int topK,
            double keywordWeight
    ) {
    }
}
