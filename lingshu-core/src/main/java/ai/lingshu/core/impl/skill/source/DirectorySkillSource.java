package ai.lingshu.core.impl.skill.source;

import ai.lingshu.core.impl.skill.SkillTool;
import ai.lingshu.core.slot.Skill;
import ai.lingshu.core.slot.SkillSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Story #020b — v1 {@link SkillSource} that scans a local filesystem directory for
 * {@code SKILL.md} files (one level deep — each immediate subdirectory's {@code SKILL.md}
 * becomes a Skill).
 *
 * <p><b>Scan rule:</b> the configured directory is iterated as a single-level
 * {@link DirectoryStream}; each subdirectory is checked for a {@code SKILL.md} file.
 * Subdirectories without {@code SKILL.md} are silently skipped. Nested scans (deeper
 * than one level) are not in scope for v1 (dsh §6.4 — directory source = 1-level scan).
 *
 * <p><b>Skill name:</b> the immediate subdirectory name (e.g. {@code "commit"}) —
 * matches the classpath-source convention.
 *
 * <p><b>Errors:</b> if the configured location does not exist or is not a directory,
 * {@link #discover()} throws {@link IOException}. {@code CompositeSkillLoader} catches
 * per source and continues.
 *
 * <p><b>{@code watchable()} returns {@code true}:</b> filesystem files are mutable,
 * and a future §14.8 Story may add a {@code WatchService}-based reloader. v1 itself does
 * not wire the watcher.
 */
public class DirectorySkillSource implements SkillSource {

    private static final Logger LOG = LoggerFactory.getLogger(DirectorySkillSource.class);
    private static final String SKILL_FILE_NAME = "SKILL.md";

    private final String location;

    public DirectorySkillSource(String location) {
        this.location = location;
    }

    @Override
    public String type() {
        return DirectorySkillSourceProvider.TYPE;
    }

    @Override
    public String location() {
        return location;
    }

    /**
     * Discover all {@code SKILL.md} files one level under {@link #location}.
     *
     * @throws IOException if the directory does not exist, is not readable, or contains
     *                     an unreadable file
     */
    @Override
    public List<Skill> discover() throws IOException {
        Path root = Paths.get(location);
        if (!Files.exists(root)) {
            throw new IOException("Skill source directory does not exist: " + location);
        }
        if (!Files.isDirectory(root)) {
            throw new IOException("Skill source location is not a directory: " + location);
        }

        List<Skill> skills = new ArrayList<Skill>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path entry : stream) {
                if (!Files.isDirectory(entry)) {
                    continue;
                }
                Path skillFile = entry.resolve(SKILL_FILE_NAME);
                if (!Files.isRegularFile(skillFile)) {
                    continue;
                }
                String skillName = entry.getFileName().toString();
                String content = new String(Files.readAllBytes(skillFile), StandardCharsets.UTF_8);
                Skill skill = SkillTool.fromMarkdown(skillName, content);
                skills.add(skill);
                LOG.debug("DirectorySkillSource loaded skill '{}' from {}",
                    skillName, skillFile);
            }
        }
        if (skills.isEmpty()) {
            return Collections.emptyList();
        }
        return skills;
    }

    @Override
    public boolean watchable() {
        return true;
    }
}
