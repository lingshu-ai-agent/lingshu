package ai.lingshu.core.impl.skill.source;

import ai.lingshu.core.impl.skill.SkillTool;
import ai.lingshu.core.slot.Skill;
import ai.lingshu.core.slot.SkillSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Story #020b — v1 {@link SkillSource} that scans a classpath prefix for {@code SKILL.md}
 * files and turns each into a {@link Skill} via {@link SkillTool#fromMarkdown(String, String)}.
 *
 * <p><b>Location format:</b> {@code "classpath:skills/agent-builtin/"} — the
 * {@code classpath:} prefix is forwarded to Spring's {@link PathMatchingResourcePatternResolver},
 * which understands it natively (it routes to the thread context class loader).
 *
 * <p><b>Scan rule:</b> the configured prefix is appended with {@code **&#47;SKILL.md}
 * (one or more subdirectories under the prefix may contain the file). Spring's
 * {@code PathMatchingResourcePatternResolver.getResources(pattern)} returns the resolved
 * {@link Resource} list — each is a single {@code SKILL.md} file.
 *
 * <p><b>Skill name derivation:</b> the parent directory name of the {@code SKILL.md} file
 * is used as the Skill name — this matches {@code /commit} → directory {@code skills/commit/SKILL.md}
 * from the user perspective (Story #020c) and matches the {@code @Component}-path pattern
 * where {@code CommitSkill.name() == "commit"}.
 *
 * <p><b>Why not walk the classpath manually:</b> the {@code Resource} abstraction handles
 * jar entries, IDE exploded output, and test classpath shadowing uniformly. Manual
 * {@code ClassLoader.getResources(...)} would miss files inside nested jars.
 *
 * <p><b>{@code watchable()} returns {@code false}:</b> jar contents are immutable at
 * runtime; hot-reload would require a {@code JarFile}-level watcher which is out of
 * scope for v1 (Story #020b / dsh §14.8 OQ-Future).
 */
public class ClasspathSkillSource implements SkillSource {

    private static final Logger LOG = LoggerFactory.getLogger(ClasspathSkillSource.class);
    private static final String SKILL_FILE_NAME = "SKILL.md";

    private final String location;

    public ClasspathSkillSource(String location) {
        this.location = location;
    }

    @Override
    public String type() {
        return ClasspathSkillSourceProvider.TYPE;
    }

    @Override
    public String location() {
        return location;
    }

    /**
     * Discover all {@code SKILL.md} files under the configured classpath prefix.
     *
     * <p><b>Pattern:</b> {@code <prefix>**&#47;SKILL.md}. The {@code **&#47;} segment
     * matches zero-or-more subdirectories; if the user points directly at a leaf
     * directory the pattern still resolves.
     *
     * <p><b>Errors:</b> if the underlying {@code getResources} throws
     * {@link IOException} (classpath corruption, missing prefix), we propagate it
     * — the caller ({@code CompositeSkillLoader}) catches per source and continues.
     */
    @Override
    public List<Skill> discover() throws IOException {
        String prefix = location;
        if (prefix == null || prefix.isEmpty()) {
            LOG.debug("ClasspathSkillSource has empty location — returning 0 skills");
            return Collections.emptyList();
        }
        if (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        String pattern = prefix + "/**/" + SKILL_FILE_NAME;

        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources(pattern);

        if (resources.length == 0) {
            LOG.debug("ClasspathSkillSource found 0 SKILL.md under '{}'", location);
            return Collections.emptyList();
        }

        List<Skill> skills = new ArrayList<Skill>(resources.length);
        for (Resource r : resources) {
            String skillName = deriveName(r);
            try {
                String content = new String(readAllBytes(r.getInputStream()), StandardCharsets.UTF_8);
                Skill skill = SkillTool.fromMarkdown(skillName, content);
                skills.add(skill);
                LOG.debug("ClasspathSkillSource loaded skill '{}' from {}",
                    skillName, r.getDescription());
            } catch (IOException e) {
                // Single file failure must not stop the whole source — log and continue.
                LOG.warn("ClasspathSkillSource failed to read {}: {}", r.getDescription(), e.getMessage());
            }
        }
        return skills;
    }

    /**
     * Extract the parent directory name as the Skill name. For
     * {@code classpath:skills/agent-builtin/commit/SKILL.md} the name is {@code "commit"}.
     */
    private static String deriveName(Resource r) {
        String desc = r.getDescription();
        // Resource description for classpath:scan entries looks like
        // "URL [jar:file:.../skills/agent-builtin/commit/SKILL.md]" or
        // "class path resource [skills/agent-builtin/commit/SKILL.md]".
        // Extract everything between the last "/" and the filename.
        int lastSlash = desc.lastIndexOf('/');
        if (lastSlash < 0) {
            return SKILL_FILE_NAME;
        }
        int prevSlash = desc.lastIndexOf('/', lastSlash - 1);
        if (prevSlash < 0) {
            // Top-level: take the segment after the leading "[" marker.
            return desc.substring(lastSlash + 1).replaceAll("\\].*", "");
        }
        return desc.substring(prevSlash + 1, lastSlash);
    }

    @Override
    public boolean watchable() {
        return false;
    }

    /**
     * JDK 8 replacement for {@link InputStream#readAllBytes()} (added in JDK 9).
     * Reads the entire stream into a byte array — SKILL.md files are small (KB range),
     * so memory is not a concern.
     */
    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }
}
