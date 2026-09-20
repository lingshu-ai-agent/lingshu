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

/**
 * Reads {@code cfg.memory.claudeMd.project} (default {@code ./CLAUDE.md}) into the
 * {@code [PROJECT MEMORY]} segment. Silent skip on missing file, disabled flag, or
 * IOException — see contracts/memory-source.md §3.1.
 */
public class ProjectClaudeMdSource implements MemorySource {

    private static final Logger LOG = LoggerFactory.getLogger(ProjectClaudeMdSource.class);

    private final AgentConfig config;

    public ProjectClaudeMdSource(AgentConfig config) {
        this.config = config;
    }

    @Override public String name() { return "project-claude-md"; }
    @Override public int priority() { return 10; }

    @Override
    public String load(TurnContext ctx) {
        AgentConfig.Memory mem = config.getMemory();
        if (mem == null || mem.getClaudeMd() == null || !mem.getClaudeMd().isEnabled()) {
            return null;
        }
        Path p = mem.getClaudeMd().getProject();
        if (p == null) return null;
        try {
            if (!Files.exists(p)) {
                LOG.debug("ProjectClaudeMdSource: file not found, skipping: {}", p);
                return null;
            }
            byte[] bytes = Files.readAllBytes(p);
            String content = new String(bytes, StandardCharsets.UTF_8);
            if (content.trim().isEmpty()) {
                LOG.debug("ProjectClaudeMdSource: file is empty, skipping: {}", p);
                return null;
            }
            return content;
        } catch (IOException ex) {
            LOG.debug("ProjectClaudeMdSource: read failed for {}, skipping", p, ex);
            return null;
        }
    }
}
