package ai.lingshu.core.impl.skill.source;

import ai.lingshu.core.slot.SkillSource;
import ai.lingshu.core.slot.SkillSourceProvider;
import org.springframework.stereotype.Component;

/**
 * Story #020b — v1 {@link SkillSourceProvider} that resolves YAML entries of
 * {@code agent.skills.sources[].type = "directory"} into {@link DirectorySkillSource}
 * instances.
 *
 * <p><b>Routing key:</b> {@code "directory"} (must match the YAML
 * {@code agent.skills.sources[].type} string and {@link DirectorySkillSource#type()}).
 *
 * <p>The configured {@code location} is a filesystem path (relative or absolute) —
 * forwarded verbatim to {@link DirectorySkillSource}.
 */
@Component
public class DirectorySkillSourceProvider implements SkillSourceProvider {

    /** Stable routing key — must match {@link DirectorySkillSource#type()} and YAML. */
    public static final String TYPE = "directory";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public SkillSource create(String location) {
        return new DirectorySkillSource(location);
    }
}
