package com.yashsharma.repointel.ingest;

import com.yashsharma.repointel.config.RepoIntelProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodeChunkerTest {

    private CodeChunker chunker() {
        var ingest = new RepoIntelProperties.Ingest(
                List.of(".java", ".py"), 500_000, 1600, 200);
        var props = new RepoIntelProperties("/tmp", ingest, new RepoIntelProperties.Retrieval(8, 0.3));
        return new CodeChunker(props, new TreeSitterChunker());
    }

    @Test
    void splitsJavaByDeclarations() {
        String java = """
                package demo;

                public class Foo {
                    public int add(int a, int b) {
                        return a + b;
                    }
                }

                class Bar {
                    void hello() {
                        System.out.println("hi");
                    }
                }
                """;

        List<CodeChunk> chunks = chunker().chunk("Foo.java", java);

        assertThat(chunks).isNotEmpty();
        // Methods become individual chunks with their own symbols.
        assertThat(chunks).anySatisfy(c -> assertThat(c.symbol()).isEqualTo("add"));
        assertThat(chunks).anySatisfy(c -> assertThat(c.symbol()).isEqualTo("hello"));
        // Class signatures are preserved in header chunks.
        assertThat(chunks).anySatisfy(c -> assertThat(c.content()).contains("class Foo"));
        assertThat(chunks).anySatisfy(c -> assertThat(c.content()).contains("class Bar"));
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.language()).isEqualTo("java");
            assertThat(c.startLine()).isPositive();
            assertThat(c.endLine()).isGreaterThanOrEqualTo(c.startLine());
        });
    }

    @Test
    void detectsSymbolNames() {
        String java = """
                public class Calculator {
                    public int multiply(int a, int b) {
                        return a * b;
                    }
                }
                """;

        List<CodeChunk> chunks = chunker().chunk("Calculator.java", java);

        assertThat(chunks).anySatisfy(c -> assertThat(c.symbol()).isEqualTo("Calculator"));
        assertThat(chunks).anySatisfy(c -> assertThat(c.symbol()).isEqualTo("multiply"));
    }

    @Test
    void splitsPythonByTopLevelBlocks() {
        String python = """
                import os

                def first():
                    return 1

                def second():
                    return 2

                class Thing:
                    def method(self):
                        return 3
                """;

        List<CodeChunk> chunks = chunker().chunk("mod.py", python);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(c -> assertThat(c.language()).isEqualTo("python"));
        assertThat(chunks).anySatisfy(c -> assertThat(c.symbol()).isEqualTo("first"));
        assertThat(chunks).anySatisfy(c -> assertThat(c.content()).contains("class Thing"));
    }

    @Test
    void fallsBackToSlidingWindowForUnstructuredText() {
        String text = "x".repeat(5000);

        List<CodeChunk> chunks = chunker().chunk("data.unknown", text);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(c -> assertThat(c.content()).isNotBlank());
    }
}
