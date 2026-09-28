package com.yashsharma.repointel.ingest;

import com.yashsharma.repointel.config.RepoIntelProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits source files into structurally meaningful chunks.
 *
 * <p>Rather than blindly cutting every N characters, the chunker groups code by
 * top-level declaration boundaries so that a class or function tends to stay
 * intact in a single chunk. Brace-based languages are segmented by tracking brace
 * depth returning to zero; Python is segmented by top-level {@code def}/{@code class}
 * lines. When a single declaration is larger than the configured limit, or a file
 * has no detectable structure, the chunker falls back to an overlapping sliding
 * window so no code is dropped.
 */
@Component
public class CodeChunker {

    private static final Map<String, String> EXTENSION_LANGUAGE = Map.ofEntries(
            Map.entry("java", "java"), Map.entry("py", "python"), Map.entry("js", "javascript"),
            Map.entry("jsx", "javascript"), Map.entry("ts", "typescript"), Map.entry("tsx", "typescript"),
            Map.entry("go", "go"), Map.entry("rb", "ruby"), Map.entry("cs", "csharp"),
            Map.entry("cpp", "cpp"), Map.entry("c", "c"), Map.entry("h", "c"), Map.entry("hpp", "cpp"),
            Map.entry("rs", "rust"), Map.entry("kt", "kotlin"), Map.entry("php", "php"),
            Map.entry("scala", "scala"), Map.entry("swift", "swift")
    );

    // Best-effort symbol name extraction from a declaration's signature line.
    private static final Pattern SYMBOL_PATTERN = Pattern.compile(
            "(?:class|interface|enum|record|struct|def|func|function|fn|void|public|private|protected|static|final|async)?\\s*" +
                    "([A-Za-z_][A-Za-z0-9_]*)\\s*(?:\\(|\\{|:|=|<|extends|implements)");

    private final RepoIntelProperties.Ingest config;
    private final TreeSitterChunker treeSitterChunker;

    public CodeChunker(RepoIntelProperties properties, TreeSitterChunker treeSitterChunker) {
        this.config = properties.ingest();
        this.treeSitterChunker = treeSitterChunker;
    }

    public List<CodeChunk> chunk(String relativePath, String content) {
        String language = languageFor(relativePath);

        // Prefer real AST-based chunking; fall back to heuristics if unavailable.
        List<CodeChunk> astChunks = treeSitterChunker.chunk(relativePath, language, content)
                .filter(list -> !list.isEmpty())
                .orElse(null);
        if (astChunks != null) {
            return enforceMaxSize(astChunks);
        }

        List<CodeChunk> structural = "python".equals(language)
                ? splitByPythonBlocks(relativePath, language, content)
                : splitByBraceBlocks(relativePath, language, content);

        if (structural.isEmpty()) {
            return slidingWindow(relativePath, language, content);
        }
        return enforceMaxSize(structural);
    }

    /**
     * Re-splits any structural chunk that is still larger than the configured limit
     * (for example a minified single line or a very long function) using the
     * overlapping sliding window, so no single embedding covers too much code.
     */
    private List<CodeChunk> enforceMaxSize(List<CodeChunk> chunks) {
        int limit = config.maxChunkChars();
        List<CodeChunk> result = new ArrayList<>();
        for (CodeChunk chunk : chunks) {
            if (chunk.content().length() <= limit) {
                result.add(chunk);
                continue;
            }
            for (CodeChunk piece : slidingWindow(chunk.relativePath(), chunk.language(), chunk.content())) {
                result.add(new CodeChunk(
                        chunk.relativePath(), chunk.language(), chunk.symbol(),
                        chunk.startLine(), chunk.endLine(), piece.content()));
            }
        }
        return result;
    }

    private String languageFor(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0) {
            return "text";
        }
        return EXTENSION_LANGUAGE.getOrDefault(path.substring(dot + 1).toLowerCase(), "text");
    }

    private List<CodeChunk> splitByBraceBlocks(String path, String language, String content) {
        String[] lines = content.split("\n", -1);
        List<CodeChunk> chunks = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        int depth = 0;
        int blockStartLine = 1;
        boolean sawBrace = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (buffer.length() == 0) {
                blockStartLine = i + 1;
            }
            buffer.append(line).append('\n');

            for (int c = 0; c < line.length(); c++) {
                char ch = line.charAt(c);
                if (ch == '{') {
                    depth++;
                    sawBrace = true;
                } else if (ch == '}') {
                    depth = Math.max(0, depth - 1);
                }
            }

            boolean boundary = sawBrace && depth == 0;
            boolean tooBig = buffer.length() >= config.maxChunkChars();
            if (boundary || tooBig) {
                flush(chunks, path, language, blockStartLine, i + 1, buffer.toString());
                buffer.setLength(0);
                sawBrace = false;
            }
        }
        if (buffer.toString().isBlank()) {
            return chunks;
        }
        flush(chunks, path, language, blockStartLine, lines.length, buffer.toString());
        return chunks;
    }

    private List<CodeChunk> splitByPythonBlocks(String path, String language, String content) {
        String[] lines = content.split("\n", -1);
        List<CodeChunk> chunks = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        int blockStartLine = 1;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            boolean topLevelDecl = line.matches("^(def|class|async def)\\s+.*");
            if (topLevelDecl && buffer.length() > 0) {
                flush(chunks, path, language, blockStartLine, i, buffer.toString());
                buffer.setLength(0);
            }
            if (buffer.length() == 0) {
                blockStartLine = i + 1;
            }
            buffer.append(line).append('\n');
            if (buffer.length() >= config.maxChunkChars()) {
                flush(chunks, path, language, blockStartLine, i + 1, buffer.toString());
                buffer.setLength(0);
            }
        }
        if (!buffer.toString().isBlank()) {
            flush(chunks, path, language, blockStartLine, lines.length, buffer.toString());
        }
        return chunks;
    }

    private List<CodeChunk> slidingWindow(String path, String language, String content) {
        List<CodeChunk> chunks = new ArrayList<>();
        int size = config.maxChunkChars();
        int overlap = Math.min(config.chunkOverlapChars(), size / 2);
        int step = Math.max(1, size - overlap);
        for (int start = 0; start < content.length(); start += step) {
            int end = Math.min(content.length(), start + size);
            String slice = content.substring(start, end);
            int startLine = countLines(content, start);
            int endLine = countLines(content, end);
            chunks.add(new CodeChunk(path, language, null, startLine, endLine, slice));
            if (end == content.length()) {
                break;
            }
        }
        return chunks;
    }

    private void flush(List<CodeChunk> chunks, String path, String language,
                       int startLine, int endLine, String text) {
        if (text.isBlank()) {
            return;
        }
        chunks.add(new CodeChunk(path, language, extractSymbol(text), startLine, endLine, text));
    }

    private String extractSymbol(String block) {
        for (String line : block.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("*")
                    || trimmed.startsWith("#") || trimmed.startsWith("/*") || trimmed.startsWith("@")) {
                continue;
            }
            Matcher matcher = SYMBOL_PATTERN.matcher(trimmed);
            if (matcher.find()) {
                return matcher.group(1);
            }
            return null;
        }
        return null;
    }

    private int countLines(String content, int offset) {
        int lines = 1;
        for (int i = 0; i < offset && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }
}
