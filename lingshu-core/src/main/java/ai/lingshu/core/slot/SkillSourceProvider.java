package ai.lingshu.core.slot;

import ai.lingshu.core.spi.ContractVersionRef;

/**
 * Slot 4 sub-SPI — factory for {@link SkillSource} (dsh §6.4 L4088-4097).
 *
 * <p>Each Provider declares a unique {@link #type()} key (e.g. {@code "classpath"}).
 * {@code SkillSourceRouter} collects all Providers at startup via Spring DI
 * ({@code List<SkillSourceProvider>}), indexed by {@code type()}.
 *
 * <p>At config time, {@code CompositeSkillLoader} calls
 * {@code SkillSourceRouter.resolve(type, location)} →
 * {@link #create(String) Provider.create(location)} → {@link SkillSource} instance.
 *
 * <p><b>🆕 Story #020b:</b> v1 ships two Providers —
 * {@code ClasspathSkillSourceProvider} and {@code DirectorySkillSourceProvider}.
 * Users add {@code git} / {@code s3} / {@code http} types by writing a new
 * {@code @Component implements SkillSourceProvider} — no core code changes.
 *
 * <p><b>Bean registration:</b> use {@code @Component} on a {@code public class}.
 * Spring auto-discovers Providers; {@code SkillSourceRouter} constructor injects
 * {@code List<SkillSourceProvider>} and indexes by {@code type()}.
 *
 * <p><b>Multi-Provider philosophy</b> (dsh §5.3.1.0 SlotRouter template +
 * v1.5.28 §5.5 unique Bean name convention): v1 ships 2 Providers
 * ({@code classpath}, {@code directory}). Plugin authors add new Providers
 * ({@code git}, {@code s3}) without modifying core. The router's
 * {@code Map<type, Provider>} supports size=N — same pattern as other Slots
 * after v1.5.28 dropped the single-Provider constraint.
 */
public interface SkillSourceProvider {

    /** Contract version (semver MAJOR.MINOR.PATCH). */
    @ContractVersionRef
    String CONTRACT_VERSION = "1.0.0";

    /**
     * Source type key (e.g. {@code "classpath"}, {@code "directory"}).
     * Must be unique across all {@code SkillSourceProvider} beans.
     * The Router keys Providers by this value.
     */
    String type();

    /**
     * Build a {@link SkillSource} for the given location string.
     *
     * <p>Implementation parses {@code location} according to its own convention:
     * <ul>
     *   <li>{@code ClasspathSkillSourceProvider} strips optional {@code "classpath:"}
     *       prefix.</li>
     *   <li>{@code DirectorySkillSourceProvider} treats location as filesystem path.</li>
     * </ul>
     *
     * <p>This method does NOT throw {@code IOException} — building the source is
     * a pure in-memory operation. {@code IOException} is deferred to
     * {@link SkillSource#discover()}.
     *
     * @param location the location string from {@code agent.skills.sources[].location}
     * @return a new {@link SkillSource} instance (not yet discovered)
     */
    SkillSource create(String location);
}
