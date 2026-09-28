package com.yashsharma.repointel.api;

import com.yashsharma.repointel.ingest.IngestionService;
import com.yashsharma.repointel.rag.QueryResponse;
import com.yashsharma.repointel.rag.RagService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST entry points for ingesting repositories and querying them.
 */
@RestController
@RequestMapping("/api")
public class RepoController {

    private final IngestionService ingestionService;
    private final RagService ragService;

    public RepoController(IngestionService ingestionService, RagService ragService) {
        this.ingestionService = ingestionService;
        this.ragService = ragService;
    }

    @PostMapping("/ingest")
    public ResponseEntity<IngestionService.IngestResult> ingest(@Valid @RequestBody IngestRequest request) {
        return ResponseEntity.ok(ingestionService.ingest(request.repoUrl(), request.branch()));
    }

    @PostMapping("/query")
    public ResponseEntity<QueryResponse> query(@Valid @RequestBody QueryRequest request) {
        return ResponseEntity.ok(ragService.answer(request.question(), request.repoId()));
    }
}
