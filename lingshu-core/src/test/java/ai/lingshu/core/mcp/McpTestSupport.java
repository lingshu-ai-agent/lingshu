package ai.lingshu.core.mcp;

import ai.lingshu.core.runtime.McpTransportType;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shared fixtures for Story #021a L3 tests. Builds the subprocess command
 * line that launches {@link ai.lingshu.core.mcp.fixture.TestMcpServer}
 * with the right Maven test classpath, and provides factories for the
 * small-heartbeat {@link McpServerConfig} variants used throughout the suite.
 *
 * <p><b>JDK 8 compatibility</b> — No {@code List.of} / {@code var} /
 * {@code Paths.of} (use {@code Paths.get}).
 */
final class McpTestSupport {

    private McpTestSupport() {
        // utility
    }

    /**
     * @return a {@code List<String>} suitable for
     *         {@link ProcessBuilder#command(List)}. Index 0 is the
     *         {@code java} executable; the rest is classpath + main class.
     *
     * <p><b>Subprocess system-property forwarding (Story #021b, T-13)</b> —
     * Java does not automatically forward the parent JVM's system properties
     * to child processes (only the environment), so the {@code test.mcp.*}
     * switches the fake server reads at startup must be added as {@code -D}
     * flags on the command line. This lets tests control the fake subprocess
     * (e.g. simulate death via {@code test.mcp.exitAfter}) without forking
     * helper classes.
     */
    static List<String> testServerCommand() {
        String javaHome = System.getProperty("java.home");
        String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
        String cp = testClasspath();
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.add("-cp");
        cmd.add(cp);
        // Forward test.mcp.* switches that the fake server reads at startup.
        for (String key : new String[]{"test.mcp.dontReplyPing",
                                       "test.mcp.exitAfter",
                                       "test.mcp.delayMs"}) {
            String v = System.getProperty(key);
            if (v != null) {
                cmd.add("-D" + key + "=" + v);
            }
        }
        cmd.add("ai.lingshu.core.mcp.fixture.TestMcpServer");
        return Collections.unmodifiableList(cmd);
    }

    static String testClasspath() {
        String cp = System.getProperty("java.class.path");
        if (cp != null && !cp.isEmpty() && cp.contains("lingshu-core")) {
            return cp;
        }
        try {
            URL url = McpTestSupport.class.getProtectionDomain().getCodeSource().getLocation();
            File file = new File(url.toURI());
            File testClasses = file.isDirectory() ? file : file.getParentFile();
            File target = testClasses.getParentFile();
            File coreRoot = target.getParentFile();
            File classesDir = new File(coreRoot, "target/classes");
            File testClassesDir = new File(coreRoot, "target/test-classes");
            return classesDir.getAbsolutePath() + File.pathSeparator + testClassesDir.getAbsolutePath();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Cannot derive test classpath", e);
        }
    }

    /**
     * Build a {@link McpServerConfig} with shrunk heartbeat / timeout params
     * so the L3 test suite can run in seconds.
     */
    static McpServerConfig stdioCfg(String name, long hbMs, long hbTimeoutMs, long reconnectMs) {
        List<String> cmd = testServerCommand();
        return McpServerConfig.builder()
            .name(name)
            .transport(McpTransportType.STDIO)
            .command(cmd.get(0))
            .args(cmd.subList(1, cmd.size()))
            .heartbeatIntervalMs(hbMs)
            .heartbeatTimeoutMs(hbTimeoutMs)
            .reconnectCapMs(reconnectMs)
            .build();
    }
}