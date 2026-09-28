package com.yashsharma.repointel.retrieval;

import com.yashsharma.repointel.config.RepoIntelProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Retrieves the most relevant code chunks for a query by fusing two signals:
 * dense semantic similarity from the vector store and a lightweight lexical
 * (keyword-overlap) score. Combining both recovers exact-identifier matches that
 * pure embeddings often miss, then reranks the union by the fused score.
 */
@Service
public class HybridRetriever {

    private final VectorStore vectorStore;
    private final RepoIntelProperties.Retrieval config;

    public HybridRetriever(VectorStore vectorStore, RepoIntelProperties properties) {
        this.vectorStore = vectorStore;
        this.config = properties.retrieval();
    }

    public List<ScoredChunk> retrieve(String query, String repoId) {
        int topK = config.topK();
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(topK * 4);
        if (repoId != null && !repoId.isBlank()) {
            builder.filterExpression("repoId == '" + repoId + "'");
        }

        List<Document> candidates = vectorStore.similaritySearch(builder.build());
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        Set<String> queryTerms = tokenize(query);
        double keywordWeight = config.keywordWeight();

        List<ScoredChunk> scored = new ArrayList<>();
        for (Document doc : candidates) {
            double semantic = doc.getScore() != null ? doc.getScore() : 0.0;
            double keyword = keywordScore(queryTerms, doc.getText());
            double fused = (1.0 - keywordWeight) * semantic + keywordWeight * keyword;
            scored.add(new ScoredChunk(doc, semantic, keyword, fused));
        }

        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredChunk::fusedScore).reversed())
                .limit(topK)
                .toList();
    }

    private double keywordScore(Set<String> queryTerms, String text) {
        if (queryTerms.isEmpty() || text == null || text.isBlank()) {
            return 0.0;
        }
        Set<String> docTerms = tokenize(text);
        long matches = queryTerms.stream().filter(docTerms::contains).count();
        return (double) matches / queryTerms.size();
    }

    private Set<String> tokenize(String text) {
        return Arrays.stream(text.toLowerCase().split("[^a-z0-9_]+"))
                .filter(t -> t.length() > 2)
                .collect(Collectors.toSet());
    }

    public record ScoredChunk(Document document, double semanticScore, double keywordScore, double fusedScore) {
    }
}
