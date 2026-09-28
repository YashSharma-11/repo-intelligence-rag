package com.yashsharma.repointel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Repo Intelligence - a codebase-aware Retrieval-Augmented Generation (RAG)
 * assistant that answers natural-language questions about any GitHub repository.
 */
@SpringBootApplication
public class RepoIntelApplication {

    public static void main(String[] args) {
        SpringApplication.run(RepoIntelApplication.class, args);
    }
}
