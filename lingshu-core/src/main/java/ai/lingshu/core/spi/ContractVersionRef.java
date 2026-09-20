package ai.lingshu.core.spi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 🆕 Story #003 — marker annotation for Slot interface contract version field.
 *
 * <p>Each Slot interface ({@link ai.lingshu.core.slot.LlmProvider},
 * {@link ai.lingshu.core.slot.ToolExecutor}, etc.) declares a public static final
 * {@code String CONTRACT_VERSION = "1.0.0"} field annotated with this marker.
 *
 * <p>{@link SlotRouter} reflects on {@code T.class} (the Slot interface) to read this
 * field at construction time. The annotation is preferred over name-based reflection
 * ({@code getField("CONTRACT_VERSION")}) so IDE static analysis can verify the contract
 * field is properly marked.
 *
 * <p>The annotation is retained at RUNTIME because {@link SlotRouter} reflects it at
 * startup. If Slot interfaces ever switch to a {@code contractVersion()} method
 * (v2 future), this annotation can be renamed to {@code ContractVersionMethod} with
 * {@code @Target(METHOD)} — no API break for users.
 *
 * <p>Usage example:
 * <pre>{@code
 * public interface LlmProvider {
 *     @ContractVersionRef
 *     String CONTRACT_VERSION = "1.0.0";
 *     // ... other methods
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ContractVersionRef {
}