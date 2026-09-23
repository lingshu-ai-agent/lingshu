package ai.lingshu.core.impl.skill;

import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;

/**
 * 🆕 Story #020b — plain POJO for {@code agent.skills.*} configuration.
 *
 * <p>Binds the following YAML structure:
 * <pre>
 * agent:
 *   skills:
 *     enabled: true              # default true (mirrors #020a agent.skills.enabled)
 *     hotReload: false           # OQ-Future — field reserved, no WatchService in v1
 *     sources:
 *       - type: classpath
 *         location: classpath:skills/agent-builtin/
 *       - type: directory
 *         location: ./skills/
 * </pre>
 *
 * <p><b>Why a plain POJO (no {@code @ConfigurationProperties} annotation):</b>
 * {@code @ConfigurationProperties} lives in {@code spring-boot}, which is NOT
 * on lingshu-core's compile classpath (R-13 dependency lock — adding it would
 * violate the 13-dependency cap). Instead, {@link SkillAutoConfiguration}
 * constructs instances via {@code Binder.get(environment).bind("agent.skills",
 * SkillSourceProperties.class)} at startup. The annotation is NOT required —
 * the {@link Binder} API binds any POJO with matching setters regardless of
 * annotations.
 *
 * <p><b>Why a separate class (not reusing {@code AgentConfig.Skills}):</b>
 * {@code AgentConfig.Skills} is the runtime immutable config (Lombok @Value)
 * consumed by the engine. Property binding needs a separate, mutable POJO with
 * setters — coupling them would pollute {@code AgentConfig} with Spring concerns.
 *
 * <p><b>Setter/getter rationale:</b> hand-written (not Lombok {@code @Data}) because
 * the nested {@link SourceEntry} has only 2 fields and {@code @Data} generates
 * noisy {@code equals/hashCode/toString} that add nothing to a binding POJO.
 * Avoiding Lombok here keeps the file readable for first-time contributors.
 */
public class SkillSourceProperties {

    /** Global toggle. Default {@code true}. Mirrors {@code agent.skills.enabled} (Story #020a). */
    private boolean enabled = true;

    /** Hot-reload toggle (OQ-Future). Default {@code false}. No WatchService wired in v1. */
    private boolean hotReload = false;

    /** Ordered list of Skill sources. Empty list = no Skills from sources. */
    private List<SourceEntry> sources = new ArrayList<>();

    /**
     * One Skill source entry — bound from {@code agent.skills.sources[].type} and
     * {@code .location}. {@link #type} is the router key (e.g. {@code "classpath"});
     * {@link #location} is the implementation-specific path (e.g. {@code "classpath:skills/builtin/"}).
     */
    public static class SourceEntry {
        private String type;
        private String location;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isHotReload() { return hotReload; }
    public void setHotReload(boolean hotReload) { this.hotReload = hotReload; }
    public List<SourceEntry> getSources() { return sources; }
    public void setSources(List<SourceEntry> sources) { this.sources = sources; }

    /**
     * Bind {@code agent.skills.*} properties from a Spring {@link Environment} into
     * a fresh {@link SkillSourceProperties} instance.
     *
     * <p>Reads:
     * <ul>
     *   <li>{@code agent.skills.enabled} — boolean, default {@code true}</li>
     *   <li>{@code agent.skills.hot-reload} — boolean, default {@code false}</li>
     *   <li>{@code agent.skills.sources[N].type} + {@code .location} — iterated
     *       from index 0 upward until both keys are absent (terminating sentinel)</li>
     * </ul>
     *
     * <p><b>Why not use {@code @ConfigurationProperties} + {@code Binder}:</b>
     * both APIs live in the {@code spring-boot} jar, which is NOT on
     * lingshu-core's compile classpath (R-13 dependency lock). {@link Environment}
     * is in {@code spring-context} and is available.
     *
     * <p><b>Iteration termination:</b> we walk {@code sources[N]} for
     * {@code N = 0, 1, 2, ...} until both {@code .type} and {@code .location} at
     * index N are missing. This mirrors Spring Boot's index-based list binding
     * semantics — sparse YAML like {@code sources: [a, , c]} still binds a list
     * of length 3 because index 1 has at least one key present.
     */
    public static SkillSourceProperties bindFromEnvironment(Environment env) {
        SkillSourceProperties p = new SkillSourceProperties();
        p.setEnabled(env.getProperty("agent.skills.enabled", Boolean.class, Boolean.TRUE));
        p.setHotReload(env.getProperty("agent.skills.hot-reload", Boolean.class, Boolean.FALSE));

        List<SourceEntry> entries = new ArrayList<>();
        for (int i = 0; ; i++) {
            String type = env.getProperty("agent.skills.sources[" + i + "].type");
            String location = env.getProperty("agent.skills.sources[" + i + "].location");
            if (type == null && location == null) {
                break;
            }
            SourceEntry e = new SourceEntry();
            e.setType(type);
            e.setLocation(location);
            entries.add(e);
        }
        p.setSources(entries);
        return p;
    }
}
