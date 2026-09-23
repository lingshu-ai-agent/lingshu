package ai.lingshu.core.impl.tool.local;

import ai.lingshu.core.decision.Decision;
import ai.lingshu.core.impl.tool.DefaultToolExecutor;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.slot.PermissionPolicy;
import ai.lingshu.core.slot.RuntimeSandbox;
import ai.lingshu.core.slot.ToolCallConfig;
import ai.lingshu.core.slot.ToolException;
import ai.lingshu.core.slot.ToolExecutionContext;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Story #019 — L1/L2 tests for {@link BashTool}.
 *
 * <p>{@link Process} cannot be mocked directly (JDK final class), so each test
 * constructs a concrete {@link TestProcess} with the desired exit code, stdout, and
 * timeout behavior. The PermissionDenied case wires {@link BashTool} through
 * {@link DefaultToolExecutor#dispatch} so the LINGS-T02 translation path is exercised
 * end-to-end (see FR-007).
 */
class BashToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path CWD = Paths.get(System.getProperty("user.dir"));

    @Test
    @DisplayName("AC-019-9: runWhitelistedCommand_returnsSuccess")
    void runWhitelistedCommand_returnsSuccess() {
        BashTool t = new BashTool();
        TestProcess p = TestProcess.finished(0, "hello\n", "");
        t.setProcessRunner(simpleRunner(p));

        ToolResult r = t.execute(bashCall("Bash", "c1", "ls"), ctx(30));

        assertThat(r.getStatus()).isEqualTo(ToolResult.Status.SUCCESS);
        assertThat(r.getContent()).isEqualTo("hello\n");
        assertThat(r.isError()).isFalse();
    }

    @Test
    @DisplayName("AC-019-9: runNonZeroExit_returnsError")
    void runNonZeroExit_returnsError() {
        BashTool t = new BashTool();
        TestProcess p = TestProcess.finished(1, "", "oops\n");
        t.setProcessRunner(simpleRunner(p));

        ToolResult r = t.execute(bashCall("Bash", "c1", "false"), ctx(30));

        assertThat(r.getStatus()).isEqualTo(ToolResult.Status.ERROR);
        assertThat(r.getContent()).startsWith("exit 1\n[stderr]\noops\n");
        assertThat(r.isError()).isTrue();
    }

    @Test
    @DisplayName("AC-019-10: runNotWhitelistedCommand_returnsPermissionDenied (L2 Slice)")
    void runNotWhitelistedCommand_returnsPermissionDenied() {
        // Wire BashTool through DefaultToolExecutor — DefaultToolExecutor catches
        // ToolException.PermissionDeniedException thrown from inside Tool.execute() and
        // translates to ToolResult.error("Permission denied: ...").
        BashTool t = new BashTool();
        t.setProcessRunner(throwingRunner(new ToolException.PermissionDeniedException(
            "command 'rm' not in tenant whitelist")));

        DefaultToolExecutor exec = new DefaultToolExecutor(allowAllPolicy());
        exec.register(t);

        ToolResult r = exec.dispatch(bashCall("Bash", "c1", "rm"), ctx(30));

        assertThat(r.getStatus()).isEqualTo(ToolResult.Status.ERROR);
        assertThat(r.getContent()).startsWith("Permission denied:");
        assertThat(r.getContent()).contains("rm");
        assertThat(r.isError()).isTrue();
    }

    @Test
    @DisplayName("EC-019-4: runEmptyCommand_returnsError")
    void runEmptyCommand_returnsError() {
        BashTool t = new BashTool();
        // processRunner is wired but never reached — empty-command guard short-circuits.
        t.setProcessRunner(simpleRunner(TestProcess.finished(0, "", "")));

        ToolResult r = t.execute(bashCall("Bash", "c1", ""), ctx(30));

        assertThat(r.getStatus()).isEqualTo(ToolResult.Status.ERROR);
        assertThat(r.getContent()).isEqualTo("command must not be empty");
        assertThat(r.isError()).isTrue();
    }

    @Test
    @DisplayName("regression: runProcessRunnerNotWired_returnsError")
    void runProcessRunnerNotWired_returnsError() {
        // If a Test does not call setProcessRunner, BashTool must NOT NPE —
        // it returns a defensive ToolResult.error instead.
        BashTool t = new BashTool();
        // No setProcessRunner call.

        ToolResult r = t.execute(bashCall("Bash", "c1", "ls"), ctx(30));

        assertThat(r.getStatus()).isEqualTo(ToolResult.Status.ERROR);
        assertThat(r.getContent()).contains("processRunner not wired");
        assertThat(r.isError()).isTrue();
    }

    @Test
    @DisplayName("regression: runTimeout_returnsErrorAndDestroysProcess")
    void runTimeout_returnsErrorAndDestroysProcess() {
        BashTool t = new BashTool();
        TestProcess p = TestProcess.timedOut();
        t.setProcessRunner(simpleRunner(p));

        ToolResult r = t.execute(bashCall("Bash", "c1", "sleep 99"), ctx(30));

        assertThat(r.getStatus()).isEqualTo(ToolResult.Status.ERROR);
        assertThat(r.getContent()).startsWith("Command timed out after 30s");
        assertThat(r.isError()).isTrue();
        // CRITICAL: process destroyed forcibly so the sleep doesn't linger.
        assertThat(p.destroyedForcibly.get()).isTrue();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static ToolCall bashCall(String name, String id, String command) {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("command", command);
        return new ToolCall(id, name, input);
    }

    private static ToolExecutionContext ctx(int timeoutSec) {
        ToolExecutionContext c = mock(ToolExecutionContext.class);
        when(c.workingDirectory()).thenReturn(CWD);
        when(c.callConfig()).thenReturn(new ToolCallConfig(timeoutSec, 0, 0));
        return c;
    }

    /** {@link RuntimeSandbox.ProcessRunner} that always returns the given Process. */
    private static RuntimeSandbox.ProcessRunner simpleRunner(Process p) {
        return new RuntimeSandbox.ProcessRunner() {
            @Override
            public Process run(String command, List<String> args, Path cwd) throws IOException {
                return p;
            }
        };
    }

    /** {@link RuntimeSandbox.ProcessRunner} that always throws the given exception. */
    private static RuntimeSandbox.ProcessRunner throwingRunner(final RuntimeException ex) {
        return new RuntimeSandbox.ProcessRunner() {
            @Override
            public Process run(String command, List<String> args, Path cwd) throws IOException {
                throw ex;
            }
        };
    }

    /** Allow-all policy so DefaultToolExecutor reaches the registry lookup step. */
    private static PermissionPolicy allowAllPolicy() {
        PermissionPolicy p = mock(PermissionPolicy.class);
        when(p.check(any(), any())).thenReturn(new Decision.Allow("test"));
        return p;
    }

    // ── TestProcess — JDK {@link Process} can't be mocked (final class), so we
    //    provide a minimal concrete subclass with the methods BashTool actually uses.

    /** Concrete {@link Process} for tests — pre-loaded with exit code, streams, and timeout behavior. */
    static final class TestProcess extends Process {
        private final int exitCode;
        private final InputStream stdout;
        private final InputStream stderr;
        private final boolean waitForResult;
        final AtomicBoolean destroyedForcibly = new AtomicBoolean(false);

        private TestProcess(boolean waitForResult, int exitCode, String stdout, String stderr) {
            this.waitForResult = waitForResult;
            this.exitCode = exitCode;
            this.stdout = new ByteArrayInputStream(stdout.getBytes());
            this.stderr = new ByteArrayInputStream(stderr.getBytes());
        }

        static TestProcess finished(int exitCode, String stdout, String stderr) {
            return new TestProcess(true, exitCode, stdout, stderr);
        }

        static TestProcess timedOut() {
            return new TestProcess(false, 0, "", "");
        }

        @Override public OutputStream getOutputStream() { return new java.io.ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return stdout; }
        @Override public InputStream getErrorStream() { return stderr; }
        @Override public int waitFor() { return exitCode; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return waitForResult; }
        @Override public int exitValue() { return exitCode; }
        @Override public void destroy() { /* no-op */ }
        @Override public Process destroyForcibly() {
            destroyedForcibly.set(true);
            return this;
        }
        @Override public boolean isAlive() { return !waitForResult; }
    }
}