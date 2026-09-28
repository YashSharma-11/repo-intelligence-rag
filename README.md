# Repo Intelligence — Codebase-Aware RAG Assistant

Ask natural-language questions about **any GitHub repository** and get grounded,
citation-backed answers. Instead of naive fixed-size chunking, Repo Intelligence
splits code along **structural boundaries** (classes / functions), embeds it,
and answers using a Retrieval-Augmented Generation (RAG) pipeline with
**hybrid retrieval** (semantic + keyword), **relevance grading**, and
**query rewriting** to reduce hallucinations.

> Example: *"Where is authentication handled?"*, *"What does the `IngestionService` do?"*,
> *"How are code chunks scored during retrieval?"*

---

## Why it's different

- **Real tree-sitter AST chunking** — code is parsed into an Abstract Syntax Tree and
  split at true declaration boundaries (each method/function is its own chunk; each class
  contributes a header chunk with its signature and fields). Java and Python use bundled
  tree-sitter grammars; other languages fall back to a structural (brace-depth /
  `def`/`class`) chunker, and any unstructured file falls back to an overlapping sliding
  window so no code is dropped.
- **Hybrid retrieval** — fuses dense semantic similarity with a lexical keyword-overlap
  score so exact identifiers (`SET NX`, `PgVectorStore`) aren't lost by embeddings alone,
  then reranks by the fused score.
- **Grounded generation with citations** — answers strictly from retrieved code and returns
  file paths, symbols, and line ranges that support the answer.
- **Idempotent ingestion** — chunks use deterministic, location-based IDs so re-ingesting a
  repository overwrites prior chunks instead of duplicating them.
- **Query rewriting + relevance grading** — vague questions are rewritten into
  retrieval-focused queries; weakly-scored chunks are dropped so the model says
  "not found" instead of hallucinating.

---

## Architecture

```
                 ┌──────────────┐   clone (JGit)   ┌──────────────┐
  POST /ingest ─▶│ Ingestion    │─────────────────▶│  Git repo    │
                 │ Service      │                  └──────────────┘
                 │              │  structural chunk        │
                 │              │◀─────────────────────────┘
                 │              │  local ONNX embeddings (all-MiniLM-L6-v2)
                 │              │────────────────▶┌──────────────┐
                 └──────────────┘   upsert         │ Vector Store │
                                                   │ Simple (dev) │
                                                   │ Pgvector(prod)│
   POST /query ─▶┌──────────────┐  rewrite query   └──────────────┘
                 │ Rag Service  │  hybrid retrieve         ▲
                 │              │──────────────────────────┘
                 │              │  grade + ground + cite
                 │              │────────────▶ LLM (OpenAI-compatible / Groq)
                 └──────────────┘
```

**Tech:** Java 21 · Spring Boot 3.4 · Spring AI 1.0 · tree-sitter (AST parsing) · JGit ·
ONNX Transformers (local embeddings) · SimpleVectorStore (dev) / Pgvector + Redis (prod) · Docker.

---

## Quick start (development — no Docker, no database)

Development runs fully local: embeddings are computed on-device with a bundled ONNX
model, and vectors are stored in an in-memory `SimpleVectorStore` persisted to disk.
You only need a chat LLM key (any OpenAI-compatible endpoint; Groq has a free tier).

### Prerequisites
- JDK 21
- Maven 3.9+

### 1. Download the embedding model (one time, ~90 MB)
The local ONNX model is gitignored due to its size, so fetch it after cloning:

```bash
# macOS / Linux
./scripts/download-model.sh

# Windows PowerShell
./scripts/download-model.ps1
```

### 2. Configure the LLM
The chat model uses an OpenAI-compatible endpoint. Defaults target Groq:

```bash
# macOS / Linux
export LLM_API_KEY="gsk_your_groq_key"
# optional overrides:
export LLM_BASE_URL="https://api.groq.com/openai"
export LLM_MODEL="llama-3.3-70b-versatile"
```

To use OpenAI instead:
```bash
export LLM_API_KEY="sk-your_openai_key"
export LLM_BASE_URL="https://api.openai.com"
export LLM_MODEL="gpt-4o-mini"
```

### 3. Run
```bash
mvn spring-boot:run
```

The first start loads the local ONNX embedding model from
`src/main/resources/onnx/`. The app listens on `http://localhost:8080`.

### 4. Ingest a repository
```bash
curl -X POST http://localhost:8080/api/ingest \
  -H "Content-Type: application/json" \
  -d '{"repoUrl": "https://github.com/spring-guides/gs-rest-service.git"}'
```
Response:
```json
{ "repoId": "spring-guides_gs-rest-service", "repoUrl": "...", "filesIndexed": 12, "chunksIndexed": 24 }
```

### 5. Ask a question
```bash
curl -X POST http://localhost:8080/api/query \
  -H "Content-Type: application/json" \
  -d '{"question": "What does the REST controller return?", "repoId": "spring-guides_gs-rest-service"}'
```
Response:
```json
{
  "answer": "The controller returns a Greeting record ...",
  "citations": [
    { "path": "src/main/java/.../GreetingController.java", "symbol": "GreetingController", "startLine": 10, "endLine": 24, "score": 0.72 }
  ],
  "searchQuery": "REST controller return type Greeting endpoint"
}
```

---

## Production setup (Pgvector + Redis via Docker)

The `pgvector` Maven profile swaps the in-memory store for a Postgres/Pgvector-backed
vector store and adds Redis. The dev build stays database-free.

```bash
# 1. Start Postgres (pgvector) + Redis
docker compose up -d

# 2. Run with the pgvector profile
mvn -Ppgvector spring-boot:run -Dspring-boot.run.profiles=pgvector
```

Or build and run everything as a container:
```bash
docker build --build-arg MAVEN_PROFILE=pgvector -t repo-intel-rag .
docker run --rm -p 8080:8080 \
  -e LLM_API_KEY=$LLM_API_KEY \
  -e POSTGRES_URL=jdbc:postgresql://host.docker.internal:5432/repointel \
  repo-intel-rag
```

---

## Web UI (React frontend)

A React (Vite) frontend in [`frontend/`](frontend) provides a simple interface: paste a
GitHub repo URL to index it, then ask questions and see grounded answers with citations.

```bash
# 1. Start the backend (see Quick start above) so it's running on :8080
#    with your LLM_API_KEY set.

# 2. In another terminal, start the frontend
cd frontend
npm install
npm run dev
```

Open http://localhost:5173. The Vite dev server proxies `/api` calls to the backend on
`:8080`, so there are no CORS issues in development. For a production build, run
`npm run build` (outputs static files to `frontend/dist/`) and serve them behind the API.

---

## API

| Method | Path          | Body                                   | Description                           |
|--------|---------------|----------------------------------------|---------------------------------------|
| POST   | `/api/ingest` | `{ "repoUrl": "...", "branch": "..." }`| Clone + index a public Git repository |
| POST   | `/api/query`  | `{ "question": "...", "repoId": "..." }`| Ask a grounded question about a repo  |

---

## Configuration

Key properties (see `src/main/resources/application.yml`):

| Property                                   | Default                       | Description                                 |
|--------------------------------------------|-------------------------------|---------------------------------------------|
| `LLM_API_KEY` (env)                        | —                             | OpenAI-compatible chat API key              |
| `LLM_BASE_URL` (env)                       | `https://api.groq.com/openai` | Chat endpoint base URL                      |
| `LLM_MODEL` (env)                          | `llama-3.3-70b-versatile`     | Chat model name                             |
| `repo-intel.ingest.max-chunk-chars`        | `1600`                        | Target structural chunk size                |
| `repo-intel.retrieval.top-k`               | `8`                           | Chunks fed to the LLM                        |
| `repo-intel.retrieval.keyword-weight`      | `0.3`                         | Weight of lexical score in hybrid fusion    |

---

## Testing

```bash
mvn test
```

Unit tests cover the AST/structural chunker (Java/Python splitting via tree-sitter,
symbol detection, sliding-window fallback).

---

## Roadmap

- Extend tree-sitter grammars to more languages (JS/TS, Go, Rust, C#).
- LLM-based relevance grading (Corrective-RAG) in addition to score thresholds.
- Redis-backed embedding + answer cache; streaming answers in the web UI.
