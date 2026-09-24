package ai.lingshu.core.impl.prompt;

import ai.lingshu.core.impl.tool.DefaultToolRegistry;
import ai.lingshu.core.message.Message;
import ai.lingshu.core.message.Prompt;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.message.ToolSpec;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.Session;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.slot.MemorySource;
import ai.lingshu.core.slot.Tool;
import ai.lingshu.core.slot.ToolExecutionContext;
import ai.lingshu.core.slot.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Story #002 AC-09 — DefaultPromptBuilder 5-segment assembly contract tests.
 *
 * <p>Covers spec US1 + US3 + US4 (FR-005 mustache) + reverse AC (no IOException).
 * See contracts/prompt-builder.md §8 for the full scenario table.
 */
class DefaultPromptBuilderTest {

    private TurnContext ctx;
    private Session session;
    private AgentConfig config;

    @BeforeEach
    void setUp() {
        ctx = mock(TurnContext.class);
        session = mock(Session.class);
        when(ctx.session()).thenReturn(session);
        when(session.history()).thenReturn(Collections.<Message>emptyList());
    }

    private AgentConfig buildConfig(AgentConfig.Identity id, AgentConfig.Instructions ins,
                                    AgentConfig.Memory mem) {
        return new AgentConfig(
            "linear",
            new AgentConfig.Llm("anthropic", "claude-3-5-sonnet-latest", 8192, 1.0),
            new AgentConfig.Prompt("default", Collections.<String>emptyList(), 0),
            "default",
            new AgentConfig.Sandbox("default", "chroot",
                Paths.get(System.getProperty("user.dir")),
                Collections.<String>emptyList(), Collections.<String>emptyList()),
            "default",
            "default",
            null, null, null,
            8, 60, 300, 0, 60, 50,
            id, ins, mem,
            "default",         // a2aTransport
            null,                  // tenants (Story #006 — single-tenant mode)
            AgentConfig.A2a.defaults(),    // a2a (Story #009)
            AgentConfig.CompactorConfig.defaults(),  // compactorConfig (Story #018)
            AgentConfig.ToolsConfig.defaults());     // tools (Story #019)
    }

    // ── [ROLE] segment ──────────────────────────────────────────────

    @Test
    @DisplayName("build_emptyConfig_onlyRoleSegment")
    void build_emptyConfig_onlyRoleSegment() {
        config = buildConfig(
            AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(),
            AgentConfig.Memory.defaults());
        when(ctx.config()).thenReturn(config);
        when(ctx.userInput()).thenReturn("1+1=几");

        DefaultPromptBuilder b = new DefaultPromptBuilder(Collections.<MemorySource>emptyList());
        Prompt prompt = b.build(ctx);

        assertThat(prompt.getMessages()).hasSize(2);
        Message sys = prompt.getMessages().get(0);
        assertThat(sys).isInstanceOf(Message.System.class);
        assertThat(((Message.System) sys).getContent()).contains("你是 lingShu-agent");
        assertThat(((Message.System) sys).getContent()).doesNotContain("[ROLE]");
        assertThat(((Message.System) sys).getContent()).doesNotContain("[INSTRUCTIONS]");
        assertThat(((Message.System) sys).getContent()).doesNotContain("[PROJECT MEMORY]");

        Message user = prompt.getMessages().get(1);
        assertThat(user).isInstanceOf(Message.User.class);
        assertThat(((Message.User) user).getContent()).isEqualTo("1+1=几");
    }

    @Test
    @DisplayName("build_identityFull_allFourRoleLines")
    void build_identityFull_allFourRoleLines() {
        AgentConfig.Identity id = new AgentConfig.Identity(
            "bob", "测试工程师", "zh",
            Arrays.asList("细致", "耐心"), "温和", null);
        config = buildConfig(id, AgentConfig.Instructions.empty(), AgentConfig.Memory.defaults());
        when(ctx.config()).thenReturn(config);
        when(ctx.userInput()).thenReturn("hi");

        DefaultPromptBuilder b = new DefaultPromptBuilder(Collections.<MemorySource>emptyList());
        String content = ((Message.System) b.build(ctx).getMessages().get(0)).getContent();

        assertThat(content).contains("你是 bob,测试工程师。");
        assertThat(content).contains("输出语言:zh");
        assertThat(content).contains("人格特质:细致、耐心");
        assertThat(content).contains("语气:温和");
    }

    @Test
    @DisplayName("build_identityPartial_skipsBlankLines")
    void build_identityPartial_skipsBlankLines() {
        AgentConfig.Identity id = new AgentConfig.Identity(
            "alice", null, null, Collections.<String>emptyList(), null, null);
        config = buildConfig(id, AgentConfig.Instructions.empty(), AgentConfig.Memory.defaults());
        when(ctx.config()).thenReturn(config);
        when(ctx.userInput()).thenReturn("hi");

        DefaultPromptBuilder b = new DefaultPromptBuilder(Collections.<MemorySource>emptyList());
        String content = ((Message.System) b.build(ctx).getMessages().get(0)).getContent();

        assertThat(content).contains("你是 alice。");
        assertThat(content).doesNotContain("输出语言");
        assertThat(content).doesNotContain("人格特质");
        assertThat(content).doesNotContain("语气");
        assertThat(content).doesNotContain("\n\n\n");
    }

    // ── [INSTRUCTIONS] segment + mustache ───────────────────────────

    @Test
    @DisplayName("build_instructionsMustache_rendersVars")
    void build_instructionsMustache_rendersVars() {
        Map<String, String> vars = new HashMap<>();
        vars.put("name", "Alice");
        vars.put("lang", "zh");
        AgentConfig.Instructions ins = new AgentConfig.Instructions(
            null, "Hi {{name}}, speak {{lang}}.", "mustache", vars);
        config = buildConfig(AgentConfig.Identity.defaults(), ins, AgentConfig.Memory.defaults());
        when(ctx.config()).thenReturn(config);
        when(ctx.userInput()).thenReturn("hi");

        DefaultPromptBuilder b = new DefaultPromptBuilder(Collections.<MemorySource>emptyList());
        String content = ((Message.System) b.build(ctx).getMessages().get(0)).getContent();

        assertThat(content).contains("Hi Alice, speak zh.");
        assertThat(content).doesNotContain("{{name}}");
        assertThat(content).doesNotContain("{{lang}}");
    }

    @Test
    @DisplayName("build_instructionsUnknownPlaceholder_passthrough")
    void build_instructionsUnknownPlaceholder_passthrough() {
        Map<String, String> vars = new HashMap<>();
        vars.put("name", "Alice");
        AgentConfig.Instructions ins = new AgentConfig.Instructions(
            null, "Hi {{name}}, unknown={{unknown}}.", "mustache", vars);
        config = buildConfig(AgentConfig.Identity.defaults(), ins, AgentConfig.Memory.defaults());
        when(ctx.config()).thenReturn(config);
        when(ctx.userInput()).thenReturn("hi");

        DefaultPromptBuilder b = new DefaultPromptBuilder(Collections.<MemorySource>emptyList());
        String content = ((Message.System) b.build(ctx).getMessages().get(0)).getContent();

        assertThat(content).contains("Hi Alice");
        assertThat(content).contains("{{unknown}}");
    }

    @Test
    @DisplayName("build_instructionsNone_passthrough")
    void build_instructionsNone_passthrough() {
        Map<String, String> vars = new HashMap<>();
        vars.put("name", "Alice");
        AgentConfig.Instructions ins = new AgentConfig.Instructions(
            null, "Hi {{name}}.", "none", vars);
        config = buildConfig(AgentConfig.Identity.defaults(), ins, AgentConfig.Memory.defaults());
        when(ctx.config()).thenReturn(config);
        when(ctx.userInput()).thenReturn("hi");

        DefaultPromptBuilder b = new DefaultPromptBuilder(Collections.<MemorySource>emptyList());
        String content = ((Message.System) b.build(ctx).getMessages().get(0)).getContent();

        assertThat(content).contains("Hi {{name}}.");
    }

    // ── [PROJECT MEMORY] segment ─────────────────────────────────────

    private static MemorySource fixedSource(final String text) {
        return new MemorySource() {
            @Override public String name() { return "fixed"; }
            @Override public int priority() { return 0; }
            @Override public String load(TurnContext c) { return text; }
        };
    }

    private static MemorySource nullSource() {
        return new MemorySource() {
            @Override public String name() { return "null-src"; }
            @Override public int priority() { return 0; }
            @Override public String load(TurnContext c) { return null; }
        };
    }

    @Test
    @DisplayName("build_noMemorySources_omitsSegment")
    void build_noMemorySources_omitsSegment() {
        config = buildConfig(AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(), AgentConfig.Memory.defaults());
        when(ctx.config()).thenReturn(config);
        when(ctx.userInput()).thenReturn("hi");

        DefaultPromptBuilder b = new DefaultPromptBuilder(Collections.<MemorySource>emptyList());
        String content = ((Message.System) b.build(ctx).getMessages().get(0)).getContent();

        assertThat(content).doesNotContain("PROJECT MEMORY");
        assertThat(content).doesNotContain("── separator ──");
    }

    @Test
    @DisplayName("build_fourSourcesJoinedWithSeparators")
    void build_fourSourcesJoinedWithSeparators() {
        config = buildConfig(AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(), AgentConfig.Memory.defaults());
        when(ctx.config()).thenReturn(config);
        when(ctx.userInput()).thenReturn("hi");

        List<MemorySource> sources = Arrays.asList(
            fixedSource("block-one"),
            fixedSource("block-two"),
            fixedSource("block-three"),
            fixedSource("block-four"));
        DefaultPromptBuilder b = new DefaultPromptBuilder(sources);
        String content = ((Message.System) b.build(ctx).getMessages().get(0)).getContent();

        assertThat(content).contains("block-one");
        assertThat(content).contains("block-two");
        assertThat(content).contains("block-three");
        assertThat(content).contains("block-four");
        // 3 separators between 4 blocks
        assertThat(countOccurrences(content, "── separator ──")).isEqualTo(3);
    }

    @Test
    @DisplayName("build_twoNullSources_remainingJoined")
    void build_twoNullSources_remainingJoined() {
        config = buildConfig(AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(), AgentConfig.Memory.defaults());
        when(ctx.config()).thenReturn(config);
        when(ctx.userInput()).thenReturn("hi");

        List<MemorySource> sources = Arrays.asList(
            nullSource(), fixedSource("alpha"), nullSource(), fixedSource("beta"));
        DefaultPromptBuilder b = new DefaultPromptBuilder(sources);
        String content = ((Message.System) b.build(ctx).getMessages().get(0)).getContent();

        assertThat(content).contains("alpha");
        assertThat(content).contains("beta");
        // 1 separator between 2 non-null blocks
        assertThat(countOccurrences(content, "── separator ──")).isEqualTo(1);
    }

    // ── [USER MESSAGE] + history ─────────────────────────────────────

    @Test
    @DisplayName("build_userMessageAlwaysPresent_evenIfNullInput")
    void build_userMessageAlwaysPresent_evenIfNullInput() {
        config = buildConfig(AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(), AgentConfig.Memory.defaults());
        when(ctx.config()).thenReturn(config);
        when(ctx.userInput()).thenReturn(null);

        DefaultPromptBuilder b = new DefaultPromptBuilder(Collections.<MemorySource>emptyList());
        Prompt prompt = b.build(ctx);

        Message last = prompt.getMessages().get(prompt.getMessages().size() - 1);
        assertThat(last).isInstanceOf(Message.User.class);
        assertThat(((Message.User) last).getContent()).isEqualTo("");
    }

    @Test
    @DisplayName("build_sessionHistoryInOrder_betweenSystemAndUser")
    void build_sessionHistoryInOrder_betweenSystemAndUser() {
        config = buildConfig(AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(), AgentConfig.Memory.defaults());
        when(ctx.config()).thenReturn(config);
        when(ctx.userInput()).thenReturn("new input");

        Message.User priorUser = new Message.User("previous user message");
        Message.Assistant priorAssistant = new Message.Assistant(
            "previous assistant", Collections.<ai.lingshu.core.message.ToolCall>emptyList(),
            ai.lingshu.core.message.StopReason.END_TURN,
            new ai.lingshu.core.message.Usage(10, 5));
        when(session.history()).thenReturn(Arrays.asList(priorUser, priorAssistant));

        DefaultPromptBuilder b = new DefaultPromptBuilder(Collections.<MemorySource>emptyList());
        List<Message> messages = b.build(ctx).getMessages();

        // messages[0] = system, messages[1..2] = history (User, Assistant), messages[3] = current User
        assertThat(messages).hasSize(4);
        assertThat(messages.get(0)).isInstanceOf(Message.System.class);
        assertThat(messages.get(1)).isSameAs(priorUser);
        assertThat(messages.get(2)).isSameAs(priorAssistant);
        assertThat(messages.get(3)).isInstanceOf(Message.User.class);
        assertThat(((Message.User) messages.get(3)).getContent()).isEqualTo("new input");
    }

    // ── reverse AC: missing-file sources don't throw ────────────────

    @Test
    @DisplayName("build_sourceThrowsIOException_returnsNullContent_continuesAssembly")
    void build_sourceThrowsIOException_returnsNullContent_continuesAssembly() {
        config = buildConfig(AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(), AgentConfig.Memory.defaults());
        when(ctx.config()).thenReturn(config);
        when(ctx.userInput()).thenReturn("hi");

        MemorySource throwing = new MemorySource() {
            @Override public String name() { return "throwing"; }
            @Override public int priority() { return 0; }
            @Override public String load(TurnContext c) {
                // simulates the catch-and-return-null contract
                return null;
            }
        };
        DefaultPromptBuilder b = new DefaultPromptBuilder(Arrays.asList(
            throwing, fixedSource("after-throw")));
        String content = ((Message.System) b.build(ctx).getMessages().get(0)).getContent();

        assertThat(content).contains("after-throw");
        assertThat(content).doesNotContain("throwing");
    }

    // ── tools + hints ────────────────────────────────────────────────

    @Test
    @DisplayName("build_toolsAndHints_populatedFromConfig")
    void build_toolsAndHints_populatedFromConfig() {
        config = buildConfig(AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(), AgentConfig.Memory.defaults());
        when(ctx.config()).thenReturn(config);
        when(ctx.userInput()).thenReturn("hi");

        DefaultPromptBuilder b = new DefaultPromptBuilder(Collections.<MemorySource>emptyList());
        Prompt prompt = b.build(ctx);

        assertThat(prompt.getTools()).isEmpty();
        assertThat(prompt.getHints()).isNotNull();
        assertThat(prompt.getHints().getModel()).isEqualTo("claude-3-5-sonnet-latest");
        assertThat(prompt.getHints().getTemperature()).isEqualTo(1.0);
        assertThat(prompt.getHints().getMaxTokens()).isEqualTo(8192);
    }

    // ── Story #024 — [TOOL SCHEMAS] wiring from shared ToolRegistry ──

    /** Stub {@link Tool} that ignores {@code execute()} — only the schema surface matters here. */
    private static Tool toolOf(final String name, final String description, final JsonNode schema) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return description; }
            @Override public JsonNode inputSchema() { return schema; }
            @Override public ToolResult execute(ToolCall call, ToolExecutionContext c) {
                throw new UnsupportedOperationException(
                    "DefaultPromptBuilderTest.toolOf — execute() not exercised in unit tests");
            }
        };
    }

    private static JsonNode emptyObjectSchema() {
        ObjectMapper m = new ObjectMapper();
        ObjectNode node = m.createObjectNode();
        node.put("type", "object");
        node.put("additionalProperties", true);
        return node;
    }

    @Test
    @DisplayName("build_nullRegistryBackwardCompat_returnsEmptyTools")
    void build_nullRegistryBackwardCompat_returnsEmptyTools() {
        config = buildConfig(AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(), AgentConfig.Memory.defaults());
        when(ctx.config()).thenReturn(config);
        when(ctx.userInput()).thenReturn("hi");

        // Legacy 1-arg ctor — no ToolRegistry injected. [TOOL SCHEMAS] must be empty.
        DefaultPromptBuilder b = new DefaultPromptBuilder(Collections.<MemorySource>emptyList());
        Prompt prompt = b.build(ctx);

        assertThat(prompt.getTools()).isEmpty();
    }

    @Test
    @DisplayName("build_emptyRegistry_returnsEmptyToolsList")
    void build_emptyRegistry_returnsEmptyToolsList() {
        config = buildConfig(AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(), AgentConfig.Memory.defaults());
        when(ctx.config()).thenReturn(config);
        when(ctx.userInput()).thenReturn("hi");

        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.modelVisibleSpecs()).thenReturn(Collections.<ToolSpec>emptyList());

        DefaultPromptBuilder b = new DefaultPromptBuilder(
            Collections.<MemorySource>emptyList(), registry);
        Prompt prompt = b.build(ctx);

        assertThat(prompt.getTools()).isEmpty();
    }

    @Test
    @DisplayName("build_withRegisteredTools_populatesToolsFromRegistry")
    void build_withRegisteredTools_populatesToolsFromRegistry() {
        config = buildConfig(AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(), AgentConfig.Memory.defaults());
        when(ctx.config()).thenReturn(config);
        when(ctx.userInput()).thenReturn("hi");

        // Pre-sorted by name (registry's contract; DefaultPromptBuilder does not re-sort).
        ToolSpec edit = new ToolSpec("Edit", "Edit a file", emptyObjectSchema());
        ToolSpec read = new ToolSpec("Read", "Read a file", emptyObjectSchema());
        ToolSpec write = new ToolSpec("Write", "Write a file", emptyObjectSchema());
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.modelVisibleSpecs()).thenReturn(
            java.util.Arrays.asList(edit, read, write));

        DefaultPromptBuilder b = new DefaultPromptBuilder(
            Collections.<MemorySource>emptyList(), registry);
        Prompt prompt = b.build(ctx);

        assertThat(prompt.getTools())
            .hasSize(3)
            .containsExactly(edit, read, write)
            .extracting(ToolSpec::getName)
            .containsExactly("Edit", "Read", "Write");
    }

    @Test
    @DisplayName("build_skillsAreVisibleAsTools")
    void build_skillsAreVisibleAsTools() {
        config = buildConfig(AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(), AgentConfig.Memory.defaults());
        when(ctx.config()).thenReturn(config);
        when(ctx.userInput()).thenReturn("hi");

        // Skill schemas surface the same way as a plain Tool — both indices in
        // DefaultToolRegistry (#020a dual-index) feed modelVisibleSpecs() identically.
        ToolSpec commit = new ToolSpec("commit", "Generate a commit message",
            emptyObjectSchema());
        ToolSpec review = new ToolSpec("code_review", "Review changed code",
            emptyObjectSchema());
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.modelVisibleSpecs()).thenReturn(
            java.util.Arrays.asList(commit, review));

        DefaultPromptBuilder b = new DefaultPromptBuilder(
            Collections.<MemorySource>emptyList(), registry);
        Prompt prompt = b.build(ctx);

        assertThat(prompt.getTools())
            .hasSize(2)
            .extracting(ToolSpec::getName)
            .containsExactly("commit", "code_review");
    }

    @Test
    @DisplayName("build_withRegistry_reReadsEachBuild (matches dynamic MCP toolchain)")
    void build_withRegistry_reReadsEachBuild() {
        config = buildConfig(AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(), AgentConfig.Memory.defaults());
        when(ctx.config()).thenReturn(config);
        when(ctx.userInput()).thenReturn("hi");

        ToolRegistry registry = mock(ToolRegistry.class);
        // First call returns 1 tool, second returns 2 — simulates MCP server
        // adding a tool between turns (Story #021b onConnectionStateChange contract).
        when(registry.modelVisibleSpecs())
            .thenReturn(java.util.Collections.singletonList(
                new ToolSpec("Read", "Read a file", emptyObjectSchema())))
            .thenReturn(java.util.Arrays.asList(
                new ToolSpec("Read", "Read a file", emptyObjectSchema()),
                new ToolSpec("Write", "Write a file", emptyObjectSchema())));

        DefaultPromptBuilder b = new DefaultPromptBuilder(
            Collections.<MemorySource>emptyList(), registry);

        Prompt p1 = b.build(ctx);
        assertThat(p1.getTools()).hasSize(1);

        Prompt p2 = b.build(ctx);
        assertThat(p2.getTools()).hasSize(2);
    }

    @Test
    @DisplayName("build_withTools_otherSegmentsUnaffected")
    void build_withTools_otherSegmentsUnaffected() {
        config = buildConfig(AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(), AgentConfig.Memory.defaults());
        when(ctx.config()).thenReturn(config);
        when(ctx.userInput()).thenReturn("hi");

        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.modelVisibleSpecs()).thenReturn(java.util.Arrays.asList(
            new ToolSpec("Read", "Read a file", emptyObjectSchema())));

        DefaultPromptBuilder b = new DefaultPromptBuilder(
            Collections.<MemorySource>emptyList(), registry);
        Prompt prompt = b.build(ctx);

        // Tools must not leak into the system message text — they live in
        // Prompt.tools as a separate field (dsh §4.5.1 v1.5.13 invariant).
        String systemText = ((Message.System) prompt.getMessages().get(0)).getContent();
        assertThat(systemText)
            .as("tools must NOT appear in the [SYSTEM] text — they live in Prompt.tools")
            .doesNotContain("Read")
            .doesNotContain("Read a file")
            .doesNotContain("inputSchema");
        assertThat(prompt.getTools()).hasSize(1);
    }

    @Test
    @DisplayName("build_withRealDefaultToolRegistry_endToEnd (registers Tool → prompt sees it)")
    void build_withRealDefaultToolRegistry_endToEnd() {
        config = buildConfig(AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(), AgentConfig.Memory.defaults());
        when(ctx.config()).thenReturn(config);
        when(ctx.userInput()).thenReturn("hi");

        // Real DefaultToolRegistry — register real Tool instances, verify the
        // prompt builder picks them up via DefaultToolRegistry.modelVisibleSpecs()
        // (sorted snapshot). End-to-end wiring proof for Story #024.
        DefaultToolRegistry real = new DefaultToolRegistry();
        real.register(toolOf("Read", "Read a file", emptyObjectSchema()));
        real.register(toolOf("Write", "Write a file", emptyObjectSchema()));
        real.register(toolOf("Edit", "Edit a file", emptyObjectSchema()));

        DefaultPromptBuilder b = new DefaultPromptBuilder(
            Collections.<MemorySource>emptyList(), real);
        Prompt prompt = b.build(ctx);

        assertThat(prompt.getTools())
            .hasSize(3)
            .extracting(ToolSpec::getName)
            .containsExactly("Edit", "Read", "Write");  // sorted asc by registry
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /** Unused — kept to ensure Subscriber import is recognized (java 8 compiler). */
    @SuppressWarnings("unused")
    private void touchSubscriberType(Subscriber<?> s) { /* no-op */ }

    /** Unused — kept to ensure Path import is recognized. */
    @SuppressWarnings("unused")
    private Path touchPathType() { return Paths.get("/tmp"); }

    /** Unused — kept to ensure ArrayList import is recognized. */
    @SuppressWarnings("unused")
    private List<String> touchArrayListType() { return new ArrayList<>(); }
}
