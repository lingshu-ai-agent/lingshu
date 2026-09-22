package ai.lingshu.cli;

/**
 * Story #017 — CLI subcommand enum. Mirrors dsh §10.3 (run / resume / serve / doctor / config).
 *
 * <p>The {@link #fromString(String)} factory throws {@link LingsCliException} with
 * {@code LINGS-Z01} on unknown subcommand, used by {@link ArgsParser} to fail fast
 * with a friendly hint.
 */
public enum Subcommand {
    /** Single-turn: load yaml + create Agent + runBlocking(prompt) → stdout finalText. */
    RUN("run", "Run a single turn"),
    /** Multi-turn: load yaml + create Agent(sessionId) + continueWithUserMessage(prompt) → stdout finalText. */
    RESUME("resume", "Resume an existing session"),
    /** A2A HTTP server: new A2aServer(cfg).start() + block until SIGTERM. */
    SERVE("serve", "Start A2A HTTP server"),
    /** Introspection: factory.description() + 6 Router counts → stdout. */
    DOCTOR("doctor", "Diagnose configuration"),
    /** Effective config dump: Jackson JSON serialization of resolved AgentConfig → stdout. */
    CONFIG("config", "Print effective AgentConfig");

    private final String cmd;
    private final String description;

    Subcommand(String cmd, String description) {
        this.cmd = cmd;
        this.description = description;
    }

    public String getCmd() {
        return cmd;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Resolve a CLI string (case-insensitive) into the corresponding {@link Subcommand}.
     *
     * @throws LingsCliException {@code LINGS-Z01} if the input matches none of {@link #RUN} /
     *         {@link #RESUME} / {@link #SERVE} / {@link #DOCTOR} / {@link #CONFIG}.
     */
    public static Subcommand fromString(String s) {
        if (s == null) {
            throw new LingsCliException("LINGS-Z01",
                "subcommand must not be null",
                "valid: run / resume / serve / doctor / config");
        }
        for (Subcommand sc : values()) {
            if (sc.cmd.equalsIgnoreCase(s)) {
                return sc;
            }
        }
        throw new LingsCliException("LINGS-Z01",
            "unknown subcommand: " + s,
            "valid: run / resume / serve / doctor / config");
    }
}