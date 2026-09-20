package ai.lingshu.core.spi;

import ai.lingshu.core.runtime.AgentConfig;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared resolver logic for typed {@link SlotProvider}s (dsh §5.2).
 *
 * <p>🆕 Story #003: constructor now validates {@link SlotProvider#version()}
 * against Slot's {@code CONTRACT_VERSION} (read via reflection from
 * {@code T.class.getField("CONTRACT_VERSION")}). Fails fast at Spring startup with
 * {@link ProviderInitException} (LINGS-S05).
 *
 * <p>🆕 Story #003: new {@link #describe()} method for {@code AgentFactory.description()}
 * output.
 *
 * <p>Concrete Routers (one per Slot) extend this abstract class, supplying the {@code P} /
 * {@code T} pair. The constructor takes the Spring-injected {@code List<P>} and runs the
 * same-name / priority / startup-log dance that all 9 Routers share.
 *
 * <p>Hot path is {@link #resolve(String, AgentConfig)} (now with secondary version check);
 * startup path is the constructor (validates versions, then logs).
 */
public abstract class SlotRouter<P extends SlotProvider<T>, T> {

    private final Map<String, P> byName;

    /** 🆕 Story #003 — slot contract version reflected from T.class.getField("CONTRACT_VERSION"). */
    private final String slotContractVersion;

    /**
     * @param providers Spring-injected list of all Providers of this Slot
     * @param typeName  short name for log lines (e.g. {@code "LlmProvider"})
     * @param log       the Logger to receive the startup summary
     * @throws ProviderInitException (LINGS-S05) if any Provider.version() is null,
     *         malformed semver, or incompatible with Slot CONTRACT_VERSION
     */
    protected SlotRouter(List<P> providers, String typeName, Logger log) {
        // 🆕 Step 1: reflect Slot contract version
        this.slotContractVersion = readContractVersion();

        // 🆕 Step 2: validate every Provider.version() (FAIL-FAST on first error)
        validateProviderVersions(providers);

        // Step 3: existing byName map + conflict handling (UNCHANGED)
        Map<String, P> winners = new LinkedHashMap<>();
        Map<String, List<P>> conflicts = new LinkedHashMap<>();
        for (P p : providers) {
            P cur = winners.get(p.name());
            if (cur == null) {
                winners.put(p.name(), p);
            } else if (p.priority() > cur.priority()) {
                conflicts.computeIfAbsent(p.name(), k -> new ArrayList<>()).add(cur);
                winners.put(p.name(), p);
            } else {
                conflicts.computeIfAbsent(p.name(), k -> new ArrayList<>()).add(p);
            }
        }
        this.byName = winners;

        // 🆕 Step 4: log with version info (MODIFIED format)
        log.info("[{}] resolved {} provider(s) [contract v{}]:",
            typeName, winners.size(), slotContractVersion);
        for (Map.Entry<String, P> e : winners.entrySet()) {
            List<P> all = conflicts.getOrDefault(e.getKey(), Collections.<P>emptyList());
            String conflictInfo = all.isEmpty()
                ? ""
                : " (overrode " + all.size() + " lower-priority impl(s): "
                  + joinNames(all) + ")";
            log.info("  \u2713 {} v{} -> {} [priority={}]{}",
                e.getKey(),
                e.getValue().version(),
                e.getValue().getClass().getSimpleName(),
                e.getValue().priority(),
                conflictInfo);
        }
    }

    /**
     * Resolve a Provider by name and create the Slot instance.
     *
     * <p>🆕 Story #003: secondary version check (defends against yml hot-reload
     * pointing to a Provider that wasn't in the original constructor {@code List<P>}).
     *
     * @throws IllegalArgumentException if no Provider with that name is registered (LINGS-S01)
     * @throws ProviderInitException (LINGS-S05) if resolved Provider.version() is incompatible
     */
    public T resolve(String name, AgentConfig config) {
        P p = byName.get(name);
        if (p == null) {
            throw new IllegalArgumentException(
                "Unknown " + getClass().getSimpleName() + " '" + name + "'. Available: " + byName.keySet());
        }
        // 🆕 secondary version check (cheap — integer compare)
        Version.isCompatible(p.version(), slotContractVersion);
        return p.create(config);
    }

    /** All registered names — useful for diagnostics and {@code /slots} CLI commands. */
    public Set<String> available() {
        return byName.keySet();
    }

    /**
     * 🆕 Story #003 — Return N lines describing all registered Providers (sync, read-only).
     * Used by {@code AgentFactory.description()} for self-describe output (US3 Scenario 1).
     *
     * <p>Format: {@code "  <name> v<version> (priority=<n>)"} per line.
     *
     * @return immutable list of description lines, never null
     */
    public List<String> describe() {
        List<String> lines = new ArrayList<>(byName.size());
        for (Map.Entry<String, P> e : byName.entrySet()) {
            lines.add(String.format("  %s v%s (priority=%d)",
                e.getKey(),
                e.getValue().version(),
                e.getValue().priority()));
        }
        return Collections.unmodifiableList(lines);
    }

    /**
     * Read {@code T.class.getField("CONTRACT_VERSION")} via reflection. Throws
     * {@link ProviderInitException} (LINGS-S05) on failure.
     */
    private String readContractVersion() {
        try {
            Field f = getSlotInterface().getField("CONTRACT_VERSION");
            Object value = f.get(null);
            if (value == null) {
                throw new ProviderInitException(
                    "Slot interface " + getSlotInterface().getSimpleName()
                        + " has null CONTRACT_VERSION field",
                    null,
                    "add 'String CONTRACT_VERSION = \"1.0.0\";' to your Slot interface");
            }
            String s = value.toString();
            // Validate it parses as semver (fail-fast at startup if not)
            Version.parse(s);
            return s;
        } catch (NoSuchFieldException e) {
            throw new ProviderInitException(
                "Slot interface " + getSlotInterface().getSimpleName()
                    + " is missing CONTRACT_VERSION field",
                e,
                "add '@ContractVersionRef String CONTRACT_VERSION = \"1.0.0\";' to your Slot interface");
        } catch (SecurityException e) {
            throw new ProviderInitException(
                "Slot interface " + getSlotInterface().getSimpleName()
                    + " CONTRACT_VERSION field is not accessible",
                e,
                "ensure CONTRACT_VERSION is public static final");
        } catch (IllegalAccessException e) {
            throw new ProviderInitException(
                "Slot interface " + getSlotInterface().getSimpleName()
                    + " CONTRACT_VERSION field is not accessible at runtime",
                e,
                "ensure CONTRACT_VERSION is public static final (no module-private access)");
        } catch (IllegalArgumentException e) {
            throw new ProviderInitException(
                "Slot interface " + getSlotInterface().getSimpleName()
                    + " CONTRACT_VERSION is not a valid semver",
                e,
                "set CONTRACT_VERSION to 'MAJOR.MINOR.PATCH' (e.g. '1.0.0')");
        }
    }

    /**
     * Validate every Provider's {@link SlotProvider#version()} against the Slot's
     * {@code CONTRACT_VERSION}. Throws {@link ProviderInitException} (LINGS-S05) on the first
     * failure.
     */
    private void validateProviderVersions(List<P> providers) {
        for (P p : providers) {
            String v = p.version();
            if (v == null) {
                throw new ProviderInitException(
                    getClass().getSimpleName() + " provider '" + p.name() + "' version() returned null",
                    new IllegalArgumentException("Provider '" + p.name() + "' version must not be null"),
                    "implement version() returning '1.0.0' on your @Component class");
            }
            try {
                Version.isCompatible(v, slotContractVersion);
            } catch (IllegalArgumentException e) {
                throw new ProviderInitException(
                    getClass().getSimpleName() + " provider '" + p.name()
                        + "' v" + v + " incompatible with slot contract v" + slotContractVersion
                        + " (" + e.getMessage() + ")",
                    e,
                    "bump " + getSlotInterface().getSimpleName()
                        + " CONTRACT_VERSION to '" + v + "' or downgrade "
                        + p.getClass().getSimpleName() + ".version() to a compatible semver");
            }
        }
    }

    /**
     * Subclasses MUST return the Slot interface (e.g. {@code LlmProvider.class}).
     * Used by the constructor to reflect {@code CONTRACT_VERSION}.
     */
    protected abstract Class<T> getSlotInterface();

    private static String joinNames(List<?> ps) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Object p : ps) {
            if (!first) sb.append(", ");
            sb.append(p.getClass().getSimpleName());
            first = false;
        }
        return sb.toString();
    }
}