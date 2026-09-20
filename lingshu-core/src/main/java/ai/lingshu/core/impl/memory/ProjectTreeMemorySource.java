package ai.lingshu.core.impl.memory;

import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.slot.MemorySource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Depth-1 walk of {@code *.md} files in {@code cfg.sandbox.workingDirectory} (or
 * {@code ./} fallback), sorted alphabetically, concatenated with
 * {@code ── separator ──} between blocks. See contracts/memory-source.md §3.4.
 *
 * <p>v1 scope is intentionally depth-1 only; recursive walk with .gitignore parsing
 * is out of scope per spec OOS-6 and deferred to a future Story.
 */
public class ProjectTreeMemorySource implements MemorySource {

    private static final Logger LOG = LoggerFactory.getLogger(ProjectTreeMemorySource.class);

    /** Length cap per source — protect against pathologically large directories. */
    private static final int MAX_OUTPUT_CHARS = 1_000_000;

    private final AgentConfig config;

    public ProjectTreeMemorySource(AgentConfig config) {
        this.config = config;
    }

    @Override public String name() { return "project-tree"; }
    @Override public int priority() { return 40; }

    @Override
    public String load(TurnContext ctx) {
        Path root = config.getSandbox() != null ? config.getSandbox().getWorkingDirectory() : null;
        if (root == null) {
            root = Paths.get(".");
        }
        if (!Files.isDirectory(root)) {
            LOG.debug("ProjectTreeMemorySource: root not a directory, skipping: {}", root);
            return null;
        }

        List<Path> mdFiles = new ArrayList<>();
        try (Stream<Path> stream = Files.list(root)) {
            stream
                .filter(Files::isRegularFile)
                .filter(p -> {
                    String name = p.getFileName().toString().toLowerCase();
                    return name.endsWith(".md");
                })
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .forEach(mdFiles::add);
        } catch (IOException ex) {
            LOG.debug("ProjectTreeMemorySource: list failed for {}, skipping", root, ex);
            return null;
        }

        if (mdFiles.isEmpty()) {
            LOG.debug("ProjectTreeMemorySource: no .md files in {}, skipping", root);
            return null;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mdFiles.size(); i++) {
            if (i > 0) {
                sb.append("\n\n── separator ──\n\n");
            }
            String content = readQuietly(mdFiles.get(i));
            if (content != null) {
                sb.append(content);
            }
        }

        String result = sb.toString();
        if (result.length() > MAX_OUTPUT_CHARS) {
            LOG.debug("ProjectTreeMemorySource: output {} chars exceeds cap, truncating",
                result.length());
            return result.substring(0, MAX_OUTPUT_CHARS) + "\n\n[truncated]";
        }
        return result;
    }

    private static String readQuietly(Path p) {
        try {
            byte[] bytes = Files.readAllBytes(p);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            LOG.debug("ProjectTreeMemorySource: read failed for {}, skipping", p, ex);
            return null;
        }
    }

    /** Visible for testing — package-private comparator snapshot. */
    static List<Path> sortForCache(List<Path> files) {
        List<Path> copy = new ArrayList<>(files);
        Collections.sort(copy, Comparator.comparing(p -> p.getFileName().toString()));
        return copy;
    }
}
