package com.yashsharma.repointel.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterJava;
import org.treesitter.TreeSitterPython;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Chunks source code using a real tree-sitter Abstract Syntax Tree.
 *
 * <p>The AST lets us cut code at true declaration boundaries - each method,
 * function, constructor, or declaration-only type becomes its own chunk with an
 * accurate symbol name and line range. This is far more precise than counting
 * braces or characters. Languages without a bundled grammar (or any parse/native
 * failure) return {@link Optional#empty()} so the caller can fall back to the
 * heuristic {@link CodeChunker}.
 */
@Component
public class TreeSitterChunker {

    private static final Logger log = LoggerFactory.getLogger(TreeSitterChunker.class);

    /** Per-language: node types that are standalone definitions, and declaration-only container types. */
    private record LanguageSpec(Supplier<TSLanguage> language, Set<String> definitionTypes, Set<String> containerTypes) {
    }

    private final Map<String, LanguageSpec> specs;
    private final boolean available;

    public TreeSitterChunker() {
        Map<String, LanguageSpec> built = Map.of();
        boolean ok = false;
        try {
            built = Map.of(
                    "java", new LanguageSpec(TreeSitterJava::new,
                            Set.of("method_declaration", "constructor_declaration", "compact_constructor_declaration"),
                            Set.of("class_declaration", "interface_declaration", "enum_declaration",
                                    "record_declaration", "annotation_type_declaration")),
                    "python", new LanguageSpec(TreeSitterPython::new,
                            Set.of("function_definition"),
                            Set.of("class_definition"))
            );
            // Force native library loading now so we can detect failures up front.
            built.values().forEach(spec -> spec.language().get());
            ok = true;
            log.info("tree-sitter AST chunking enabled for: {}", built.keySet());
        } catch (Throwable t) {
            log.warn("tree-sitter unavailable, falling back to heuristic chunking: {}", t.getMessage());
        }
        this.specs = built;
        this.available = ok;
    }

    public boolean supports(String language) {
        return available && specs.containsKey(language);
    }

    public Optional<List<CodeChunk>> chunk(String relativePath, String language, String content) {
        if (!supports(language)) {
            return Optional.empty();
        }
        try {
            return Optional.of(parseAndChunk(relativePath, language, content));
        } catch (Throwable t) {
            log.debug("tree-sitter parse failed for {}, will fall back: {}", relativePath, t.getMessage());
            return Optional.empty();
        }
    }

    private List<CodeChunk> parseAndChunk(String relativePath, String language, String content) {
        LanguageSpec spec = specs.get(language);
        String[] lines = content.split("\n", -1);

        TSParser parser = new TSParser();
        parser.setLanguage(spec.language().get());
        TSTree tree = parser.parseString(null, content);
        TSNode root = tree.getRootNode();

        List<TSNode> definitions = new ArrayList<>();
        List<TSNode> containers = new ArrayList<>();
        collect(root, spec, definitions, containers);

        // Build non-overlapping chunk specs: each method/function is its own chunk, and
        // each container contributes either its whole body (no members) or just a header
        // chunk covering its signature and fields down to the first member.
        List<ChunkSpec> chunkSpecs = new ArrayList<>();
        for (TSNode def : definitions) {
            chunkSpecs.add(new ChunkSpec(def.getStartPoint().getRow(), def.getEndPoint().getRow(), symbolName(def, lines)));
        }
        for (TSNode container : containers) {
            int firstMemberRow = firstMemberStartRow(container, definitions, containers);
            if (firstMemberRow < 0) {
                chunkSpecs.add(new ChunkSpec(container.getStartPoint().getRow(),
                        container.getEndPoint().getRow(), symbolName(container, lines)));
            } else {
                chunkSpecs.add(new ChunkSpec(container.getStartPoint().getRow(),
                        Math.max(container.getStartPoint().getRow(), firstMemberRow - 1),
                        symbolName(container, lines)));
            }
        }

        if (chunkSpecs.isEmpty()) {
            return List.of();
        }
        chunkSpecs.sort((a, b) -> Integer.compare(a.startRow(), b.startRow()));

        List<CodeChunk> chunks = new ArrayList<>();
        // Capture a file header (package/imports) preceding the first declaration.
        int firstRow = chunkSpecs.get(0).startRow();
        String header = joinLines(lines, 0, firstRow - 1);
        if (!header.isBlank()) {
            chunks.add(new CodeChunk(relativePath, language, null, 1, firstRow, header));
        }
        for (ChunkSpec cs : chunkSpecs) {
            String text = joinLines(lines, cs.startRow(), cs.endRow());
            if (text.isBlank()) {
                continue;
            }
            chunks.add(new CodeChunk(relativePath, language, cs.symbol(),
                    cs.startRow() + 1, cs.endRow() + 1, text));
        }
        return chunks;
    }

    /** Smallest start row among definitions/containers nested strictly inside the given container, or -1. */
    private int firstMemberStartRow(TSNode container, List<TSNode> definitions, List<TSNode> containers) {
        int best = Integer.MAX_VALUE;
        for (TSNode candidate : definitions) {
            if (isStrictlyInside(candidate, container)) {
                best = Math.min(best, candidate.getStartPoint().getRow());
            }
        }
        for (TSNode candidate : containers) {
            if (isStrictlyInside(candidate, container)) {
                best = Math.min(best, candidate.getStartPoint().getRow());
            }
        }
        return best == Integer.MAX_VALUE ? -1 : best;
    }

    private boolean isStrictlyInside(TSNode inner, TSNode outer) {
        return inner.getStartByte() > outer.getStartByte() && inner.getEndByte() <= outer.getEndByte();
    }

    private record ChunkSpec(int startRow, int endRow, String symbol) {
    }

    private void collect(TSNode node, LanguageSpec spec, List<TSNode> definitions, List<TSNode> containers) {
        if (node == null || node.isNull()) {
            return;
        }
        String type = node.getType();
        if (spec.definitionTypes().contains(type)) {
            definitions.add(node);
        }
        if (spec.containerTypes().contains(type)) {
            containers.add(node);
        }
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            collect(node.getChild(i), spec, definitions, containers);
        }
    }

    private String symbolName(TSNode node, String[] lines) {
        try {
            TSNode nameNode = node.getChildByFieldName("name");
            if (nameNode == null || nameNode.isNull()) {
                return null;
            }
            int row = nameNode.getStartPoint().getRow();
            if (row != nameNode.getEndPoint().getRow() || row >= lines.length) {
                return null;
            }
            String line = lines[row];
            int start = Math.min(nameNode.getStartPoint().getColumn(), line.length());
            int end = Math.min(nameNode.getEndPoint().getColumn(), line.length());
            if (start >= end) {
                return null;
            }
            String candidate = line.substring(start, end).trim();
            return candidate.matches("[A-Za-z_][A-Za-z0-9_]*") ? candidate : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String joinLines(String[] lines, int startRow, int endRow) {
        int from = Math.max(0, startRow);
        int to = Math.min(lines.length - 1, endRow);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            sb.append(lines[i]);
            if (i < to) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }
}
