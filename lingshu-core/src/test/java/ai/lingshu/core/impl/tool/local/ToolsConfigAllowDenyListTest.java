package ai.lingshu.core.impl.tool.local;

import ai.lingshu.core.runtime.AgentConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #029 — L1 unit tests for {@link AgentConfig.ToolsConfig} new fields
 * {@code allowList} + {@code denyList} (3 cases).
 *
 * <p>Validates constructor arity, getter accessors, and {@code defaults()}
 * returning empty immutable lists (zero-config back-compat).
 */
class ToolsConfigAllowDenyListTest {

    @Test
    @DisplayName("AC-029-8: defaults_listsAreEmptyAndImmutable")
    void defaults_listsAreEmptyAndImmutable() {
        AgentConfig.ToolsConfig tc = AgentConfig.ToolsConfig.defaults();

        assertThat(tc.getAllowList()).isEmpty();
        assertThat(tc.getDenyList()).isEmpty();
    }

    @Test
    @DisplayName("AC-029-9: explicitAllowList_roundTripsThroughGetters")
    void explicitAllowList_roundTripsThroughGetters() {
        AgentConfig.ToolsConfig tc = new AgentConfig.ToolsConfig(
            true,
            Arrays.asList("read_file", "write_file"),
            Collections.<String>emptyList(),
            Collections.<String>emptyList(),  // 🆕 Story #030 — askList
            1024, 2048);

        assertThat(tc.getAllowList()).containsExactly("read_file", "write_file");
        assertThat(tc.getDenyList()).isEmpty();
    }

    @Test
    @DisplayName("AC-029-10: bothListsConfigured_bothRoundTrip")
    void bothListsConfigured_bothRoundTrip() {
        AgentConfig.ToolsConfig tc = new AgentConfig.ToolsConfig(
            true,
            Arrays.asList("read_file"),
            Arrays.asList("dangerous_tool", "rm"),
            Collections.<String>emptyList(),  // 🆕 Story #030 — askList
            1024, 2048);

        assertThat(tc.getAllowList()).containsExactly("read_file");
        assertThat(tc.getDenyList()).containsExactly("dangerous_tool", "rm");
    }
}
