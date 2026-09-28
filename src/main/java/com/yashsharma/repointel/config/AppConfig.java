package com.yashsharma.repointel.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.io.File;
import java.nio.file.Path;

/**
 * Core beans for the application: the vector store used for semantic retrieval.
 *
 * <p>Development uses {@link SimpleVectorStore}, an in-memory store that persists
 * to a JSON file so ingested repositories survive restarts. Activating the
 * {@code pgvector} profile (built with the {@code pgvector} Maven profile) instead
 * lets Spring AI auto-configure a Postgres/Pgvector-backed store, and this bean
 * backs off automatically.
 */
@Configuration
@EnableConfigurationProperties(RepoIntelProperties.class)
public class AppConfig {

    @Bean
    @Profile("!pgvector")
    public VectorStore vectorStore(EmbeddingModel embeddingModel, RepoIntelProperties properties) {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        File persistFile = Path.of(properties.workDir(), "vector-store.json").toFile();
        if (persistFile.exists()) {
            store.load(persistFile);
        }
        return store;
    }
}
