package ai.lingshu.cli;

import lombok.Getter;

/**
 * Story #017 — CLI error exception with {@code errorCode} + {@code hint} + {@code exitCode}.
 *
 * <p>Mirrors {@code LingsA2aServerException} (Story #009) — both expose
 * {@link #getErrorCode()} and {@link #getHint()}, override {@link #getMessage()} to
 * render {@code "[CODE] message\nhint: ..."} so {@code Main.main} → {@code System.exit}
 * produces human-friendly stderr.
 *
 * <p>Maps each known {@code LINGS-*} code to a process exit code:
 * <ul>
 *   <li>{@code LINGS-Z01} (CLI arg) → 2</li>
 *   <li>{@code LINGS-Z02} (YAML)    → 3</li>
 *   <li>{@code LINGS-C02} (config)  → 4</li>
 *   <li>{@code LINGS-S06} (A2A)     → 6</li>
 *   <li>other → 1</li>
 * </ul>
 */
public class LingsCliException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    @Getter private final String errorCode;
    @Getter private final String hint;
    @Getter private final int exitCode;

    public LingsCliException(String errorCode, String message, String hint) {
        super(message);
        this.errorCode = errorCode;
        this.hint = hint;
        this.exitCode = exitCodeFor(errorCode);
    }

    public LingsCliException(String errorCode, String message, String hint, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.hint = hint;
        this.exitCode = exitCodeFor(errorCode);
    }

    @Override
    public String getMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(errorCode).append("] ").append(super.getMessage());
        if (hint != null && !hint.isEmpty()) {
            sb.append("\nhint: ").append(hint);
        }
        return sb.toString();
    }

    private static int exitCodeFor(String code) {
        if (code == null) {
            return 1;
        }
        switch (code) {
            case "LINGS-Z01":
                return 2;
            case "LINGS-Z02":
                return 3;
            case "LINGS-C02":
                return 4;
            case "LINGS-S06":
                return 6;
            default:
                return 1;
        }
    }
}