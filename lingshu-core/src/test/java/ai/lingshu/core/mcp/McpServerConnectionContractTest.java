package ai.lingshu.core.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #021a — {@link McpServerConnection} L1 contract test (AC-021a-3).
 *
 * <p>Single case asserting the interface declares exactly the eight expected
 * abstract methods. Catches accidental signature drift early.
 */
@DisplayName("Story #021a — McpServerConnection contract")
class McpServerConnectionContractTest {

    @Test
    @DisplayName("interface declares 8 expected abstract methods")
    void interfaceHasEightMethods() {
        Method[] methods = McpServerConnection.class.getDeclaredMethods();
        Set<String> names = new HashSet<>();
        for (Method m : methods) {
            names.add(m.getName());
        }
        assertThat(names).contains(
            "name", "state", "lastHeartbeatAt", "listTools",
            "callTool", "onStateChange", "start", "close");
        // exactly 8 declared methods on this interface
        assertThat(methods.length).isEqualTo(8);
    }
}