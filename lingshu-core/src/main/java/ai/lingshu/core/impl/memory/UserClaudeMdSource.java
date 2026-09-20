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
 * Reads {@code cfg.memory.claudeMd.user} (default {@code ~/.lingshu/CLAUDE.md}) into
 * the {@code [PROJECT MEMORY]} segment. Silent skip on missing file or IOException.
 * In CI containers / fresh dev worktrees the user-level CLAUDE.md is typically absent,
 * which is the expected v1 behavior — see contracts/memory-source.md §3.2.
 */
public class UserClaudeMdSource implements MemorySource {

    private static final Logger LOG = LoggerFactory.getLogger(UserClaudeMdSource.class);

    private final AgentConfig config;

    public UserClaudeMdSource(AgentConfig config) {
        this.config = config;
    }

    @Override public String name() { return "user-claude-md"; }
    @Override public int priority() { return 20; }

    @Override
    public String load(TurnContext ctx) {
        AgentConfig.Memory mem = config.getMemory();
        if (mem == null || mem.getClaudeMd() == null || !mem.getClaudeMd().isEnabled()) {
            return null;
        }
        Path p = mem.getClaudeMd().getUser();
        if (p == null) return null;
        try {
            if (!Files.exists(p)) {
                LOG.debug("UserClaudeMdSource: file not found, skipping: {}", p);
                return null;
            }
            byte[] bytes = Files.readAllBytes(p);
            String content = new String(bytes, StandardCharsets.UTF_8);
            if (content.trim().isEmpty()) {
                LOG.debug("UserClaudeMdSource: file is empty, skipping: {}", p);
                return null;
            }
            return content;
        } catch (IOException ex) {
            LOG.debug("UserClaudeMdSource: read failed for {}, skipping", p, ex);
            return null;
        }
    }
}
