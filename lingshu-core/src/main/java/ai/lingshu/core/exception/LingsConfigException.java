package ai.lingshu.core.exception;

/**
 * Configuration-domain exception (dsh §15 error code {@code LINGS-C02}).
 *
 * <p>Thrown at startup (or on first invalid use) when an {@link ai.lingshu.core.runtime.AgentConfig}
 * sub-section fails validation. The {@code code} field carries the canonical
 * machine-readable identifier; the {@code message} carries the human-readable
 * explanation with the exact field paths that failed.
 *
 * <p>Story #006 — also raised by {@link ai.lingshu.core.runtime.AgentConfig.TenantsConfig#validate()}
 * when tenant configurations are missing required fields, exceed the {@code 1000}-entry
 * limit, or contain mismatched tenantId keys. Reusing {@code C02} (rather than
 * inventing a new Tenant-domain code) keeps the error catalog small — tenant config
 * validation is still fundamentally "config validation", just with a richer schema.
 */
public class LingsConfigException extends RuntimeException {

    /** Canonical error code; always set so callers can branch on it without parsing the message. */
    private final String code;

    /**
     * @param code    machine-readable code (e.g. {@code "C02"}, {@code "C03"}); should
     *                align with the dsh §15 catalog
     * @param message human-readable explanation, ideally with the failing field path
     */
    public LingsConfigException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** @return the canonical error code (e.g. {@code "C02"}) */
    public String getCode() {
        return code;
    }
}