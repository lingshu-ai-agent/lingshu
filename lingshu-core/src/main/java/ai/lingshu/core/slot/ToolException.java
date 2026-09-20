package ai.lingshu.core.slot;

/**
 * Common base class for all tool-execution exceptions surfaced from {@link ToolExecutor}.
 *
 * <p>Each subclass carries an error code aligned with dsh §15 catalog
 * ({@code LINGS-Txx} = Tool domain). Concrete error codes are assigned in Story #001 follow-ups
 * once the corresponding diagnostic paths are exercised in practice.
 */
public class ToolException extends RuntimeException {
    public ToolException(String message) { super(message); }
    public ToolException(String message, Throwable cause) { super(message, cause); }

    /** Tool name not found in {@code ToolRegistry}. */
    public static final class ToolNotFoundException extends ToolException {
        public ToolNotFoundException(String toolName) {
            super("Tool not registered: " + toolName);
        }
    }

    /** {@link PermissionPolicy} returned {@link ai.lingshu.core.decision.Decision.Deny}. */
    public static final class PermissionDeniedException extends ToolException {
        public PermissionDeniedException(String reason) {
            super("Permission denied: " + reason);
        }
    }

    /** {@code callConfig.timeoutSeconds} elapsed before the tool returned. */
    public static final class ToolTimeoutException extends ToolException {
        public ToolTimeoutException(int seconds) {
            super("Tool call exceeded timeoutSeconds=" + seconds);
        }
    }

    /** Cancellation token fired (Ctrl+C / engine.markDone / timeout cascade). */
    public static final class ToolCancelledException extends ToolException {
        public ToolCancelledException() {
            super("Tool call cancelled");
        }
    }
}