package ai.lingshu.a2a.server;

import lombok.Getter;

/**
 * 🆕 Story #009 — A2A server runtime exception.
 *
 * <p>Wraps any failure during {@code A2aServer} startup / request handling with a
 * stable error code from dsh §15 (the {@code LINGS-<domain><nn>} convention).
 *
 * <p>Two error codes are emitted by this Story:
 * <ul>
 *   <li>{@code LINGS-S06} {@code A2A_SERVER_START_FAILED} — {@code A2aServer.start()}
 *       bind/listen failure (port collision / unknown host / invalid port / JDK
 *       {@code HttpServer.start()} idempotency violation).</li>
 *   <li>{@code LINGS-T02} {@code A2A_CARD_INVALID_CONFIG} — {@code LocalAgentCardGenerator.generate()}
 *       validation failure (empty / blank {@code Identity.name}).</li>
 * </ul>
 *
 * <p>The {@link #getHint()} field is rendered to the user alongside the error code
 * so a typo in {@code application.yml} can be corrected without reading source.
 */
public class LingsA2aServerException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Error code per dsh §15; one of {@code "LINGS-S06"}, {@code "LINGS-T02"}. */
    @Getter
    private final String errorCode;

    /** User-facing remediation hint (e.g. {@code "change 'a2a.server.port' in application.yml"}). */
    @Getter
    private final String hint;

    public LingsA2aServerException(String errorCode, String message, String hint) {
        super(message);
        this.errorCode = errorCode;
        this.hint = hint;
    }

    public LingsA2aServerException(String errorCode, String message, String hint, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.hint = hint;
    }

    @Override
    public String getMessage() {
        return "[" + errorCode + "] " + super.getMessage()
            + (hint == null || hint.isEmpty() ? "" : "\nhint: " + hint);
    }
}