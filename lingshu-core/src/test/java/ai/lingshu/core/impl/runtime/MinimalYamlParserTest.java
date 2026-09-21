package ai.lingshu.core.impl.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #007 — smoke tests for the minimal YAML parser used by
 * {@link AgentFactory#loadYamlAndValidate(java.nio.file.Path)}.
 *
 * <p>These tests live in the {@code impl.runtime} package so they can reach
 * the package-private {@link AgentFactory#parseMinimalYaml(String)} helper.
 */
class MinimalYamlParserTest {

    @Test
    @DisplayName("block-style list under key")
    void blockStyleList() {
        String yml =
            "agent:\n" +
            "  sandbox:\n" +
            "    policy: strict\n" +
            "    command-whitelist:\n" +
            "      - ls\n" +
            "      - cat\n" +
            "      - echo\n";
        Map<String, Object> root = AgentFactory.parseMinimalYaml(yml);
        @SuppressWarnings("unchecked")
        Map<String, Object> agent = (Map<String, Object>) root.get("agent");
        @SuppressWarnings("unchecked")
        Map<String, Object> sandbox = (Map<String, Object>) agent.get("sandbox");
        @SuppressWarnings("unchecked")
        List<Object> wl = (List<Object>) sandbox.get("command-whitelist");
        assertThat(wl).containsExactly("ls", "cat", "echo");
        assertThat(sandbox.get("policy")).isEqualTo("strict");
    }

    @Test
    @DisplayName("flow-style list [a, b, c]")
    void flowStyleList() {
        String yml =
            "agent:\n" +
            "  sandbox:\n" +
            "    command-whitelist: [ls, cat, echo]\n";
        Map<String, Object> root = AgentFactory.parseMinimalYaml(yml);
        @SuppressWarnings("unchecked")
        Map<String, Object> agent = (Map<String, Object>) root.get("agent");
        @SuppressWarnings("unchecked")
        Map<String, Object> sandbox = (Map<String, Object>) agent.get("sandbox");
        @SuppressWarnings("unchecked")
        List<Object> wl = (List<Object>) sandbox.get("command-whitelist");
        assertThat(wl).containsExactly("ls", "cat", "echo");
    }

    @Test
    @DisplayName("comments + blank lines ignored")
    void commentsAndBlanks() {
        String yml =
            "# header comment\n" +
            "\n" +
            "agent: # inline comment\n" +
            "  llm:\n" +
            "    provider: anthropic # another inline\n" +
            "\n";
        Map<String, Object> root = AgentFactory.parseMinimalYaml(yml);
        @SuppressWarnings("unchecked")
        Map<String, Object> agent = (Map<String, Object>) root.get("agent");
        @SuppressWarnings("unchecked")
        Map<String, Object> llm = (Map<String, Object>) agent.get("llm");
        assertThat(llm.get("provider")).isEqualTo("anthropic");
    }

    @Test
    @DisplayName("orphan list item throws IllegalStateException")
    void orphanListItemThrows() {
        String yml = "- foo\n";
        assertThatThrownBy(() -> AgentFactory.parseMinimalYaml(yml))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("yml parse error");
    }
}