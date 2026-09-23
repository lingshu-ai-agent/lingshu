package ai.lingshu.core.mcp;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Helper to launch the {@code TestMcpHttpServer} / {@code TestMcpSseServer}
 * fixtures in-process as subprocesses (Story #021c).
 *
 * <p>The fixture prints {@code PORT=<n>} on stdout as its first line; we
 * read that line and return the {@code http://127.0.0.1:<n>} base URL.
 *
 * <p>JDK 8 compatibility — uses {@code ProcessBuilder} and
 * {@code BufferedReader.readLine()}; no {@code List.of} / {@code var}.
 */
final class McpHttpTestSupport {

    private McpHttpTestSupport() {
        // utility
    }

    /**
     * Start {@code TestMcpHttpServer} with the given system-property
     * forwarding. Returns the bound base URL and the {@link Process}.
     */
    static ProcessHandle startHttpServer(Map<String, String> sysProps) throws Exception {
        Process p = new ProcessBuilder(buildCommand("ai.lingshu.core.mcp.fixture.TestMcpHttpServer", sysProps))
            .redirectErrorStream(true)
            .start();
        String port = readFirstLine(p);
        return new ProcessHandle(p, "http://127.0.0.1:" + port);
    }

    /** Start {@code TestMcpSseServer} (extended fixture with /sse endpoint). */
    static ProcessHandle startSseServer(Map<String, String> sysProps) throws Exception {
        Process p = new ProcessBuilder(buildCommand("ai.lingshu.core.mcp.fixture.TestMcpSseServer", sysProps))
            .redirectErrorStream(true)
            .start();
        String port = readFirstLine(p);
        return new ProcessHandle(p, "http://127.0.0.1:" + port);
    }

    private static List<String> buildCommand(String mainClass, Map<String, String> sysProps) {
        String javaHome = System.getProperty("java.home");
        String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
        String cp = testClasspath();
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.add("-cp");
        cmd.add(cp);
        if (sysProps != null) {
            for (Map.Entry<String, String> e : sysProps.entrySet()) {
                cmd.add("-D" + e.getKey() + "=" + e.getValue());
            }
        }
        cmd.add(mainClass);
        return cmd;
    }

    private static String testClasspath() {
        String cp = System.getProperty("java.class.path");
        if (cp != null && !cp.isEmpty() && cp.contains("lingshu-core")) {
            return cp;
        }
        try {
            URL url = McpHttpTestSupport.class.getProtectionDomain().getCodeSource().getLocation();
            File file = new File(url.toURI());
            File testClasses = file.isDirectory() ? file : file.getParentFile();
            File target = testClasses.getParentFile();
            File coreRoot = target.getParentFile();
            File classesDir = new File(coreRoot, "target/classes");
            File testClassesDir = new File(coreRoot, "target/test-classes");
            return classesDir.getAbsolutePath() + File.pathSeparator + testClassesDir.getAbsolutePath();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot derive test classpath", e);
        }
    }

    private static String readFirstLine(Process p) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        String line = r.readLine();
        if (line == null || !line.startsWith("PORT=")) {
            throw new IllegalStateException("TestMcpHttpServer did not announce PORT=, got: " + line);
        }
        return line.substring("PORT=".length());
    }

    /** Convenience for empty sysProps. */
    static ProcessHandle startHttpServer() throws Exception {
        return startHttpServer(new HashMap<String, String>());
    }

    /** Convenience for empty sysProps. */
    static ProcessHandle startSseServer() throws Exception {
        return startSseServer(new HashMap<String, String>());
    }

    /**
     * Bundle of {@link Process} + base URL so tests can {@code close()}
     * the subprocess in {@code @AfterEach}.
     */
    static final class ProcessHandle {
        private final Process process;
        private final String baseUrl;

        ProcessHandle(Process process, String baseUrl) {
            this.process = process;
            this.baseUrl = baseUrl;
        }

        public Process process() {
            return process;
        }

        public String baseUrl() {
            return baseUrl;
        }

        /** Best-effort destroy. */
        public void close() {
            if (process != null && process.isAlive()) {
                process.destroy();
                try {
                    process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        }
    }
}