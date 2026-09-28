package ai.lingshu.a2a.server;

import ai.lingshu.core.decision.Decision;
import ai.lingshu.core.runtime.Session;
import ai.lingshu.core.slot.ToolCallConfig;
import ai.lingshu.core.slot.ToolExecutionContext;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Story a2a-server-tool-registry-dispatch — minimal no-op {@link ToolExecutionContext}
 * for tool invocations made <i>outside</i> the normal turn flow (specifically, when
 * {@link A2aServer.RpcDispatcherHandler#handleMessageSend} dispatches a JSON-RPC
 * {@code message/send} to a local {@link ai.lingshu.core.slot.Tool}).
 *
 * <p>The engine's normal per-turn {@code TurnContext} does not flow through the
 * JSON-RPC boundary — an inbound HTTP request has no associated engine session,
 * no chroot-bound working directory, no approval gate. We hand the tool a stub
 * that returns safe defaults for all 8 SPI methods.
 *
 * <h2>What each stub returns</h2>
 * <ul>
 *   <li>{@link #session()} — throws {@link UnsupportedOperationException} (the dispatcher
 *       never reads history from inside an off-engine tool call)</li>
 *   <li>{@link #sink()} — no-op {@link ToolSink} (no streaming output for this demo path)</li>
 *   <li>{@link #workingDirectory()} — {@code System.getProperty("user.dir")} (un-chrooted;
 *       file tools that need sandboxing should refuse the call)</li>
 *   <li>{@link #fs()} — {@link FileSystems#getDefault() default filesystem} (no chroot)</li>
 *   <li>{@link #http()} — {@link NetworkClient} that throws on every call (HTTP tools
 *       cannot reach the network from this demo path)</li>
 *   <li>{@link #approval()} — {@link ApprovalGate} that always denies {@link Decision.AskUser}
 *       (no human in the loop on the server side)</li>
 *   <li>{@link #cancellation()} — {@link CancellationToken} that reports never cancelled
 *       and fires no callbacks</li>
 *   <li>{@link #callConfig()} — {@link ToolCallConfig} with 30s timeout, 0 token / cost caps</li>
 * </ul>
 *
 * <h2>Tool author guidance</h2>
 *
 * <p>If a tool you register here touches any context field (file system, HTTP, approval,
 * cancellation), <i>be aware this stub returns safe defaults</i> — those features are
 * out of scope for off-engine dispatch. For full sandboxed execution, drive the tool
 * through the engine's normal {@code ToolExecutor.dispatch} flow (5-step pipeline, see
 * dsh §4.10.1 硬规则 2), not through {@link A2aServer}'s JSON-RPC boundary.
 *
 * <p>Mirrors {@code DemoA2aServer.StubToolExecutionContext} from the demo-product-a2a-server
 * example — both serve the same purpose (off-engine tool dispatch via JSON-RPC) and have
 * intentionally identical safe-default semantics.
 */
final class A2aServerToolExecutionContext implements ToolExecutionContext {

    @Override
    public Session session() {
        throw new UnsupportedOperationException(
            "A2aServerToolExecutionContext.session() — off-engine tool dispatch has no session");
    }

    @Override
    public ToolSink sink() {
        return new ToolSink() {
            @Override public void emitPartial(String partial) { /* no-op */ }
            @Override public void emitProgress(String progress) { /* no-op */ }
        };
    }

    @Override
    public Path workingDirectory() {
        return Paths.get(System.getProperty("user.dir"));
    }

    @Override
    public FileSystem fs() {
        return FileSystems.getDefault();
    }

    @Override
    public NetworkClient http() {
        return new NetworkClient() {
            @Override public InputStream getStream(String url) throws IOException {
                throw new UnsupportedOperationException(
                    "A2aServerToolExecutionContext.http().getStream — HTTP sandbox not wired");
            }
            @Override public String get(String url) throws IOException {
                throw new UnsupportedOperationException(
                    "A2aServerToolExecutionContext.http().get — HTTP sandbox not wired");
            }
            @Override public String post(String url, String body) throws IOException {
                throw new UnsupportedOperationException(
                    "A2aServerToolExecutionContext.http().post — HTTP sandbox not wired");
            }
        };
    }

    @Override
    public ApprovalGate approval() {
        // Always deny AskUser — off-engine dispatch has no human in the loop.
        // If a tool here ever needs approval, replace this stub with one wired to a real channel.
        return new ApprovalGate() {
            @Override public Decision ask(Decision.AskUser ask) {
                return new Decision.Deny(
                    "A2aServerToolExecutionContext.approval() — AskUser denied (no human channel)");
            }
        };
    }

    @Override
    public CancellationToken cancellation() {
        return new CancellationToken() {
            @Override public boolean isCancelled() { return false; }
            @Override public Runnable onCancel(Runnable callback) {
                // No-op unregister — the token never fires, so registered callbacks
                // never run. The returned Runnable is itself a no-op so the caller
                // can call it without surprise.
                return new Runnable() {
                    @Override public void run() { /* no-op unregister */ }
                };
            }
        };
    }

    @Override
    public ToolCallConfig callConfig() {
        // 30s timeout (matches demo-product Tools), 0 token / cost caps (unlimited).
        return new ToolCallConfig(30, 0, 0);
    }
}
