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
package ai.lingshu.examples.demoproduct;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #054 — User Story 1 blackbox AC verifying the demo-product yml's
 * stdio MCP server entry ({@code mcp.servers[0] name=echo-stdio}) points at the
 * in-repo script via a **module-relative** path.
 *
 * <p>Background: the working tree previously carried a machine-specific absolute
 * path in {@code args:} — and, worse, it pointed at {@code application.yml}
 * itself instead of {@code echo-stdio.py}, so {@code python3 <application.yml>}
 * died on a Python syntax error and the stdio server never came up. The yml now
 * reads {@code src/main/resources/mcp-servers/echo-stdio.py}, resolved against
 * the module basedir (the JVM CWD that {@code mvn -pl lingshu-examples/demo-product
 * spring-boot:run} forks into — {@code StdioMcpServerConnection.spawn()} uses a
 * bare {@code new ProcessBuilder(...)} with no {@code .directory(...)} call, so the
 * child inherits the JVM CWD).
 *
 * <p>Three cases, all blackbox over the shipped {@code application.yml}:
 * <ul>
 *   <li>AC-054-US1-1 — the script argument is NOT a machine-specific absolute
 *       path (no Windows drive letter, no POSIX leading {@code /}, no {@code ~})</li>
 *   <li>AC-054-US1-2 — the script argument ends with
 *       {@code src/main/resources/mcp-servers/echo-stdio.py}</li>
 *   <li>AC-054-US1-3 — the script argument, resolved against the module basedir,
 *       is an existing regular file</li>
 * </ul>
 *
 * <p>constitution §11.2 forbids skipping AC blackbox verification, so this
 * exists to lock the "must not be a machine-specific absolute path" constraint
 * in an automatically runnable form rather than relying on a manual e2e check.
 */
class DemoProductStdioArgsPathTest {

    /** The server name whose {@code args:} entry this test inspects. */
    private static final String SERVER_NAME = "echo-stdio";

    /** The expected module-relative script path suffix (Story #025 precedent). */
    private static final String EXPECTED_SUFFIX = "src/main/resources/mcp-servers/echo-stdio.py";

    @Test
    @DisplayName("AC-054-US1-1: stdio args[0] is not a machine-specific absolute path")
    void argsFirstElement_isNotAbsolutePath() {
        List<String> args = stdioArgs();

        assertThat(args).as("echo-stdio server declares a script argument").isNotEmpty();
        String script = args.get(0);

        assertThat(script)
                .as("args[0] must not carry a Windows drive letter (e.g. d:\\...)")
                .doesNotMatch("(?i)^[a-z]:[\\\\/].*");
        assertThat(script)
                .as("args[0] must not be a POSIX absolute path (leading '/')")
                .doesNotStartWith("/");
        assertThat(script)
                .as("args[0] must not be home-relative (leading '~')")
                .doesNotStartWith("~");
    }

    @Test
    @DisplayName("AC-054-US1-2: stdio args[0] points at the in-repo mcp-servers/echo-stdio.py")
    void argsFirstElement_pointsAtRepoScript() {
        List<String> args = stdioArgs();

        assertThat(args).as("echo-stdio server declares a script argument").isNotEmpty();
        assertThat(args.get(0))
                .as("args[0] must point at the in-repo echo-stdio.py shipped under src/main/resources")
                .endsWith(EXPECTED_SUFFIX);
    }

    @Test
    @DisplayName("AC-054-US1-3: stdio args[0] resolves to an existing file under the module basedir")
    void argsFirstElement_resolvesToExistingFile() {
        List<String> args = stdioArgs();

        assertThat(args).as("echo-stdio server declares a script argument").isNotEmpty();

        Path basedir = moduleBasedir();
        Path resolved = basedir.resolve(args.get(0)).normalize();

        assertThat(Files.isRegularFile(resolved))
                .as("resolved script %s must exist (module basedir = %s)", resolved, basedir)
                .isTrue();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Reads the shipped {@code application.yml} from the test classpath and
     * returns the {@code args:} entries of the {@code echo-stdio} server entry.
     *
     * <p>Parsed line-wise (rather than through a YAML library) so the test keeps
     * zero new Maven dependencies — FR-026 forbids adding any.
     */
    private static List<String> stdioArgs() {
        List<String> lines = readApplicationYmlLines();

        int serverIdx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (("- name: " + SERVER_NAME).equals(lines.get(i).trim())) {
                serverIdx = i;
                break;
            }
        }
        assertThat(serverIdx)
                .as("application.yml declares an MCP server named '%s'", SERVER_NAME)
                .isGreaterThanOrEqualTo(0);

        int argsIdx = -1;
        for (int i = serverIdx + 1; i < lines.size(); i++) {
            if ("args:".equals(lines.get(i).trim())) {
                argsIdx = i;
                break;
            }
        }
        assertThat(argsIdx)
                .as("the '%s' server entry declares an 'args:' key", SERVER_NAME)
                .isGreaterThanOrEqualTo(0);

        List<String> args = new ArrayList<>();
        for (int i = argsIdx + 1; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (!trimmed.startsWith("- ")) {
                break;
            }
            args.add(trimmed.substring(2).trim());
        }
        return args;
    }

    private static List<String> readApplicationYmlLines() {
        InputStream in = DemoProductStdioArgsPathTest.class.getClassLoader()
                .getResourceAsStream("application.yml");
        assertThat(in).as("application.yml is on the demo-product test classpath").isNotNull();

        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to read application.yml from the classpath", e);
        }
        return lines;
    }

    /**
     * The module basedir — the base against which the relative {@code args}
     * path is resolved at runtime.
     *
     * <p>Surefire sets {@code basedir} to the module's project directory; the
     * fallback to {@code user.dir} covers runners that do not define it. This
     * mirrors the JVM CWD that the production stdio transport inherits (no
     * {@code .directory(...)} call in {@code StdioMcpServerConnection.spawn()}).
     */
    private static Path moduleBasedir() {
        String basedir = System.getProperty("basedir");
        if (basedir == null || basedir.trim().isEmpty()) {
            basedir = System.getProperty("user.dir");
        }
        assertThat(basedir)
                .as("module basedir is resolvable (basedir or user.dir system property)")
                .isNotNull();
        return Paths.get(basedir).toAbsolutePath().normalize();
    }
}
