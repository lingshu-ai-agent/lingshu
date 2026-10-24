/*
 * Copyright 2026 The LingShu Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.lingshu.core.mcp;

import ai.lingshu.core.mcp.fixture.TestMcpHttpServer;

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
 * Helper to launch the {@code TestMcpHttpServer} fixture in a subprocess
 * (Story #021c; single spec-shaped fixture since Story #054 T016).
 *
 * <p>The fixture prints {@code PORT=<n>} on stdout as its first line; we
 * read that line and return the {@code http://127.0.0.1:<n>} base URL.
 *
 * <p><b>Endpoint choice per transport</b> — the one fixture serves both:
 * <ul>
 *   <li>SSE → {@code cfg.getUrl()} = {@code baseUrl}; the transport appends
 *       {@code /sse} itself ({@code SseMcpServerConnection.resolveSseUrl}).</li>
 *   <li>Streamable HTTP → {@code cfg.getUrl()} = {@code baseUrl + "/mcp"} —
 *       the single MCP endpoint, never a per-method path.</li>
 * </ul>
 *
 * <p>Every switch in the fixture's system-property set is forwarded verbatim as
 * {@code -D<key>=<value>} (see {@link #buildCommand}), so L3 real-socket runs and
 * L1 in-process runs see identical behavior — no extra wiring needed.
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

    /**
     * A base URL whose port is guaranteed to be closed.
     *
     * <p>Starts the fixture, reads the port it actually bound, then kills the
     * subprocess — the port is now deterministically closed. This replaces the
     * old {@code http://127.0.0.1:1} magic literal, which only proved that
     * <em>privileged</em> ports are refused and never exercised a port that
     * had really been in use (Story #054 T019 / T034 / T035).
     *
     * @return {@code http://127.0.0.1:<closed port>}
     */
    static String pickClosedBaseUrl() throws Exception {
        ProcessHandle handle = startHttpServer();
        String url = handle.baseUrl();
        handle.close();
        return url;
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

        /**
         * The Streamable HTTP single endpoint ({@code baseUrl + "/mcp"}).
         * Streamable HTTP POSTs every JSON-RPC message — including
         * {@code initialize} — here; the URL is never a path prefix (FR-017).
         */
        String mcpEndpoint() {
            return baseUrl + "/mcp";
        }

        /**
         * Snapshot of what the fixture actually received, in arrival order.
         * Read from the fixture's {@code GET /__recorded} probe.
         */
        List<TestMcpHttpServer.RecordedRequest> recorded() throws Exception {
            return TestMcpHttpServer.RequestRecorder.fetch(baseUrl);
        }

        /** Drop the fixture's recording. */
        void clearRecorded() throws Exception {
            TestMcpHttpServer.RequestRecorder.clear(baseUrl);
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