package com.yashsharma.repointel.ingest;

import com.yashsharma.repointel.config.RepoIntelProperties;
import org.eclipse.jgit.api.Git;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Clones public Git repositories using JGit so the application does not depend
 * on a native {@code git} binary being installed.
 */
@Service
public class GitRepoService {

    private static final Logger log = LoggerFactory.getLogger(GitRepoService.class);

    private final Path reposRoot;

    public GitRepoService(RepoIntelProperties properties) {
        this.reposRoot = Path.of(properties.workDir(), "repos");
    }

    /**
     * Shallow-clones the given repository URL into a fresh working directory and
     * returns the local path. Any previous clone of the same repo is removed first.
     */
    public Path clone(String repoUrl, String branch) {
        String repoId = slugify(repoUrl);
        Path target = reposRoot.resolve(repoId);
        try {
            deleteRecursively(target);
            Files.createDirectories(target.getParent());
            log.info("Cloning {} (branch={}) into {}", repoUrl, branch, target);

            var cloneCommand = Git.cloneRepository()
                    .setURI(repoUrl)
                    .setDirectory(target.toFile())
                    .setDepth(1);
            if (branch != null && !branch.isBlank()) {
                cloneCommand.setBranch(branch);
            }
            try (Git git = cloneCommand.call()) {
                log.info("Cloned {} at {}", repoUrl, git.getRepository().getDirectory());
            }
            return target;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to clone repository: " + repoUrl, e);
        }
    }

    private static String slugify(String repoUrl) {
        String cleaned = repoUrl.replaceAll("\\.git$", "");
        int slash = cleaned.lastIndexOf('/');
        int prevSlash = cleaned.lastIndexOf('/', slash - 1);
        String tail = (prevSlash >= 0) ? cleaned.substring(prevSlash + 1) : cleaned.substring(slash + 1);
        return tail.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(GitRepoService::forceDelete);
        }
        if (Files.exists(path)) {
            throw new IOException("Could not fully delete existing clone at " + path);
        }
    }

    /**
     * Deletes a file/dir, first clearing the read-only attribute that Windows sets on
     * Git object/pack files (which otherwise makes deletion silently fail).
     */
    private static void forceDelete(Path p) {
        File file = p.toFile();
        if (!file.canWrite()) {
            file.setWritable(true, false);
        }
        if (!file.delete() && Files.exists(p)) {
            file.setWritable(true, false);
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                log.warn("Could not delete {}", p, e);
            }
        }
    }
}
