package ai.lingshu.cli;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Story #017 — Hand-rolled argv parser. ~80 LOC, 0 Maven deps (picocli would be the 14th
 * coordinate — RFC + enforcer + binary bloat).
 *
 * <p>Recognized grammar:
 * <pre>
 *   lingshu {run|resume|serve|doctor|config} [--config &lt;path>]
 *                                          [--prompt <text>]
 *                                          [--port <port>]
 *                                          [--session <id>]
 *                                          [--print-effective]
 *                                          [--print-schema]
 * </pre>
 *
 * <p>Flags accept both {@code --key value} and {@code --key=value} forms (latter
 * useful for {@code --config=app.yml}). Boolean flags ({@code --print-effective},
 * {@code --print-schema}) take no value.
 *
 * <p>Validation per subcommand:
 * <ul>
 *   <li>{@code run}:    {@code --prompt} non-empty (else {@link LingsCliException} {@code LINGS-Z01})</li>
 *   <li>{@code resume}: {@code --session} non-empty + {@code --prompt} non-empty (else {@code LINGS-Z01})</li>
 *   <li>{@code serve}:  {@code --port} (if present) is 1—65535 (else {@code LINGS-Z01})</li>
 *   <li>{@code doctor}: no required flag</li>
 *   <li>{@code config}: no required flag</li>
 * </ul>
 *
 * <p>Empty argv → {@code LINGS-Z01} (no subcommand given). Unknown subcommand →
 * {@code LINGS-Z01} via {@link Subcommand#fromString(String)}.
 */
public final class ArgsParser {

    private ArgsParser() {}

    /**
     * Default config path used when {@code --config} is not supplied.
     * Mirrors {@code YamlWatcher}'s {@code spring.config.location:application.yml} fallback.
     */
    public static final String DEFAULT_CONFIG = "application.yml";

    /** Default HTTP port for {@code serve} subcommand. Mirrors {@code AgentConfig.A2a.defaults()}. */
    public static final int DEFAULT_PORT = 8080;

    public static Args parse(String[] argv) {
        if (argv == null || argv.length == 0) {
            throw new LingsCliException("LINGS-Z01",
                "no subcommand given",
                "usage: lingshu {run|resume|serve|doctor|config} [--flags]");
        }

        Subcommand sub = Subcommand.fromString(argv[0]);
        Map<String, String> flags = parseFlags(argv, 1);

        Path configPath = Paths.get(flags.getOrDefault("--config", DEFAULT_CONFIG));
        String prompt = flags.get("--prompt");
        String sessionId = flags.get("--session");
        Integer port = parsePort(flags.get("--port"));
        boolean printEffective = flags.containsKey("--print-effective");
        boolean printSchema = flags.containsKey("--print-schema");

        validate(sub, prompt, sessionId, port);

        return new Args(sub, configPath, prompt, sessionId, port, printEffective, printSchema);
    }

    /**
     * Parse {@code --key value} / {@code --key=value} / boolean-flag pairs starting
     * from {@code argv[startIdx]}. Unknown flags are silently ignored (forward-compat).
     */
    private static Map<String, String> parseFlags(String[] argv, int startIdx) {
        Map<String, String> flags = new HashMap<String, String>();
        for (int i = startIdx; i < argv.length; i++) {
            String tok = argv[i];
            if (tok == null || !tok.startsWith("--")) {
                continue;
            }
            int eq = tok.indexOf('=');
            if (eq >= 0) {
                flags.put(tok.substring(0, eq), tok.substring(eq + 1));
            } else {
                flags.put(tok, "");
                // boolean flag if next token is missing / also a flag
                if (i + 1 < argv.length && argv[i + 1] != null && !argv[i + 1].startsWith("--")) {
                    flags.put(tok, argv[i + 1]);
                    i++;
                }
            }
        }
        return flags;
    }

    private static Integer parsePort(String raw) {
        if (raw == null || raw.isEmpty()) {
            return DEFAULT_PORT;
        }
        try {
            int p = Integer.parseInt(raw.trim());
            if (p < 1 || p > 65535) {
                throw new LingsCliException("LINGS-Z01",
                    "--port out of range: " + raw + " (must be 1—65535)",
                    "use --port 8080 for default");
            }
            return p;
        } catch (NumberFormatException e) {
            throw new LingsCliException("LINGS-Z01",
                "--port not an integer: " + raw,
                "use --port 8080 for default");
        }
    }

    private static void validate(Subcommand sub, String prompt, String sessionId, Integer port) {
        switch (sub) {
            case RUN:
                if (prompt == null || prompt.trim().isEmpty()) {
                    throw new LingsCliException("LINGS-Z01",
                        "run: --prompt <text> is required",
                        "example: lingshu run --config app.yml --prompt 'say hi'");
                }
                return;
            case RESUME:
                if (sessionId == null || sessionId.trim().isEmpty()) {
                    throw new LingsCliException("LINGS-Z01",
                        "resume: --session <id> is required",
                        "example: lingshu resume --session abc123 --prompt 'continue'");
                }
                if (prompt == null || prompt.trim().isEmpty()) {
                    throw new LingsCliException("LINGS-Z01",
                        "resume: --prompt <text> is required",
                        "example: lingshu resume --session abc123 --prompt 'continue'");
                }
                return;
            case SERVE:
            case DOCTOR:
            case CONFIG:
                return;
            default:
                throw new LingsCliException("LINGS-Z01",
                    "unhandled subcommand: " + sub,
                    "valid: run / resume / serve / doctor / config");
        }
    }
}