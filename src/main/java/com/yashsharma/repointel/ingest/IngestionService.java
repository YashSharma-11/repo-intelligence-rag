package com.yashsharma.repointel.ingest;

import com.yashsharma.repointel.config.RepoIntelProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Orchestrates end-to-end ingestion of a repository: clone, walk source files,
 * chunk them structurally, and upsert the chunks into the vector store as
 * embedded {@link Document}s tagged with repository, path, symbol and line metadata.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final GitRepoService gitRepoService;
    private final CodeChunker codeChunker;
    private final VectorStore vectorStore;
    private final RepoIntelProperties properties;
    private final RepoIntelProperties.Ingest config;

    public IngestionService(GitRepoService gitRepoService,
                            CodeChunker codeChunker,
                            VectorStore vectorStore,
                            RepoIntelProperties properties) {
        this.gitRepoService = gitRepoService;
        this.codeChunker = codeChunker;
        this.vectorStore = vectorStore;
        this.properties = properties;
        this.config = properties.ingest();
    }

    public IngestResult ingest(String repoUrl, String branch) {
        Path repoRoot = gitRepoService.clone(repoUrl, branch);
        String repoId = repoRoot.getFileName().toString();

        // Make re-ingestion idempotent: drop any previously indexed chunks for this repo.
        try {
            var filter = new org.springframework.ai.vectorstore.filter.FilterExpressionBuilder()
                    .eq("repoId", repoId).build();
            vectorStore.delete(filter);
        } catch (RuntimeException e) {
            log.debug("No existing chunks to delete for {} ({})", repoId, e.getMessage());
        }

        List<Document> documents = new ArrayList<>();
        int filesIndexed = 0;

        try (Stream<Path> walk = Files.walk(repoRoot)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                    .filter(this::isIndexableCodeFile)
                    .toList();

            for (Path file : files) {
                String relativePath = repoRoot.relativize(file).toString().replace('\\', '/');
                String content;
                try {
                    content = Files.readString(file);
                } catch (IOException e) {
                    log.debug("Skipping unreadable file {}", relativePath);
                    continue;
                }
                if (content.isBlank()) {
                    continue;
                }
                for (CodeChunk chunk : codeChunker.chunk(relativePath, content)) {
                    documents.add(toDocument(repoId, repoUrl, chunk));
                }
                filesIndexed++;
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to walk repository: " + repoRoot, e);
        }

        if (!documents.isEmpty()) {
            // Batch to keep embedding calls bounded in memory.
            int batchSize = 100;
            for (int i = 0; i < documents.size(); i += batchSize) {
                vectorStore.add(documents.subList(i, Math.min(documents.size(), i + batchSize)));
            }
            persist();
        }

        log.info("Ingested repo {}: {} files, {} chunks", repoId, filesIndexed, documents.size());
        return new IngestResult(repoId, repoUrl, filesIndexed, documents.size());
    }

    private void persist() {
        if (vectorStore instanceof SimpleVectorStore simpleStore) {
            try {
                File persistFile = Path.of(properties.workDir(), "vector-store.json").toFile();
                Files.createDirectories(persistFile.getParentFile().toPath());
                simpleStore.save(persistFile);
            } catch (IOException e) {
                log.warn("Failed to persist vector store", e);
            }
        }
    }

    private boolean isIndexableCodeFile(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        String pathStr = file.toString().replace('\\', '/');
        if (pathStr.contains("/.git/") || pathStr.contains("/node_modules/")
                || pathStr.contains("/target/") || pathStr.contains("/build/")
                || pathStr.contains("/dist/") || pathStr.contains("/vendor/")) {
            return false;
        }
        boolean hasCodeExtension = config.codeExtensions().stream().anyMatch(name::endsWith);
        if (!hasCodeExtension) {
            return false;
        }
        try {
            return Files.size(file) <= config.maxFileSizeBytes();
        } catch (IOException e) {
            return false;
        }
    }

    private Document toDocument(String repoId, String repoUrl, CodeChunk chunk) {
        Map<String, Object> metadata = new java.util.HashMap<>();
        metadata.put("repoId", repoId);
        metadata.put("repoUrl", repoUrl);
        metadata.put("path", chunk.relativePath());
        metadata.put("language", chunk.language());
        metadata.put("startLine", chunk.startLine());
        metadata.put("endLine", chunk.endLine());
        if (chunk.symbol() != null) {
            metadata.put("symbol", chunk.symbol());
        }
        // Prepend a compact header so the embedding captures location context too.
        String header = "// " + chunk.relativePath()
                + (chunk.symbol() != null ? " :: " + chunk.symbol() : "")
                + " (lines " + chunk.startLine() + "-" + chunk.endLine() + ")\n";
        // Deterministic id keyed by location so re-ingesting a repo overwrites prior
        // chunks (stable across restarts and stores) instead of duplicating them.
        String id = repoId + ":" + chunk.relativePath() + ":" + chunk.startLine() + "-" + chunk.endLine();
        return new Document(id, header + chunk.content(), metadata);
    }

    public record IngestResult(String repoId, String repoUrl, int filesIndexed, int chunksIndexed) {
    }
}
