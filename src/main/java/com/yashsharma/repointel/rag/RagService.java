package com.yashsharma.repointel.rag;

import com.yashsharma.repointel.retrieval.HybridRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Core Retrieval-Augmented Generation flow:
 * <ol>
 *   <li><b>Query rewriting</b> - expands the user question into a retrieval-focused
 *       query so vague questions still match relevant code.</li>
 *   <li><b>Hybrid retrieval</b> - fetches candidate chunks (semantic + keyword).</li>
 *   <li><b>Relevance grading</b> - drops weakly-scored chunks to reduce noise and
 *       hallucination; if nothing clears the bar the assistant says so.</li>
 *   <li><b>Grounded generation</b> - answers strictly from retrieved code and returns
 *       citations pointing at file paths and line ranges.</li>
 * </ol>
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    private static final double RELEVANCE_THRESHOLD = 0.15;

    private static final String ANSWER_SYSTEM_PROMPT = """
            You are Repo Intelligence, an assistant that answers questions about a specific code repository.
            Use ONLY the provided code context to answer. If the context is insufficient, say you could not
            find it in the indexed code rather than guessing. Reference files and symbols by name when useful.
            Be concise and technical. Format code identifiers with backticks.
            """;

    private static final String REWRITE_SYSTEM_PROMPT = """
            Rewrite the user's question into a concise search query optimized for retrieving relevant source
            code (include likely identifiers, symbols, and technical keywords). Return only the query text.
            """;

    private final ChatClient chatClient;
    private final HybridRetriever retriever;

    public RagService(ChatModel chatModel, HybridRetriever retriever) {
        this.chatClient = ChatClient.create(chatModel);
        this.retriever = retriever;
    }

    public QueryResponse answer(String question, String repoId) {
        String searchQuery = rewriteQuery(question);
        List<HybridRetriever.ScoredChunk> retrieved = retriever.retrieve(searchQuery, repoId);

        List<HybridRetriever.ScoredChunk> relevant = retrieved.stream()
                .filter(c -> c.fusedScore() >= RELEVANCE_THRESHOLD)
                .toList();
        if (relevant.isEmpty() && !retrieved.isEmpty()) {
            // Keep the strongest few so we can still attempt a grounded answer.
            relevant = retrieved.subList(0, Math.min(3, retrieved.size()));
        }
        if (relevant.isEmpty()) {
            return new QueryResponse(
                    "I could not find anything relevant in the indexed code for this repository.",
                    List.of(), searchQuery);
        }

        String context = buildContext(relevant);
        String answer = chatClient.prompt()
                .system(ANSWER_SYSTEM_PROMPT)
                .user(u -> u.text("""
                        Question:
                        {question}

                        Code context:
                        {context}
                        """).param("question", question).param("context", context))
                .call()
                .content();

        List<Citation> citations = relevant.stream().map(this::toCitation).toList();
        return new QueryResponse(answer, citations, searchQuery);
    }

    private String rewriteQuery(String question) {
        try {
            String rewritten = chatClient.prompt()
                    .system(REWRITE_SYSTEM_PROMPT)
                    .user(question)
                    .call()
                    .content();
            if (rewritten != null && !rewritten.isBlank()) {
                return rewritten.trim();
            }
        } catch (Exception e) {
            log.warn("Query rewrite failed, using original question", e);
        }
        return question;
    }

    private String buildContext(List<HybridRetriever.ScoredChunk> chunks) {
        List<String> blocks = new ArrayList<>();
        for (HybridRetriever.ScoredChunk chunk : chunks) {
            Document doc = chunk.document();
            String path = String.valueOf(doc.getMetadata().getOrDefault("path", "unknown"));
            Object start = doc.getMetadata().get("startLine");
            Object end = doc.getMetadata().get("endLine");
            blocks.add("File: " + path + " (lines " + start + "-" + end + ")\n" + doc.getText());
        }
        return blocks.stream().collect(Collectors.joining("\n\n---\n\n"));
    }

    private Citation toCitation(HybridRetriever.ScoredChunk chunk) {
        Document doc = chunk.document();
        return new Citation(
                String.valueOf(doc.getMetadata().getOrDefault("path", "unknown")),
                doc.getMetadata().get("symbol") != null ? String.valueOf(doc.getMetadata().get("symbol")) : null,
                asInt(doc.getMetadata().get("startLine")),
                asInt(doc.getMetadata().get("endLine")),
                round(chunk.fusedScore())
        );
    }

    private int asInt(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
