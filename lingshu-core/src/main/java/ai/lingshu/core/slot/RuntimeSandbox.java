package ai.lingshu.core.slot;

import ai.lingshu.core.spi.ContractVersionRef;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.List;

/**
 * Slot 3 system layer — issues bounded fs / http / process capabilities.
 *
 * <p>Pairs with {@link PermissionPolicy}: policy decides intent ("should this run?"); sandbox
 * decides scope ("what can it touch?"). Both must pass for a tool call to execute.
 *
 * <p>Default v1 implementation is {@code ChrootRuntimeSandbox} (Story #001+ stub; full impl in
 * follow-up) which uses {@code java.nio.file.FileSystems.newFileSystem} with a custom
 * {@code Path} filter to confine reads/writes to the configured working directory.
 */
public interface RuntimeSandbox {

    /** 🆕 Story #003 — Contract version (semver MAJOR.MINOR.PATCH). */
    @ContractVersionRef
    String CONTRACT_VERSION = "1.0.0";

    /** Bounded filesystem handle; out-of-bounds access throws {@code AccessDeniedException}. */
    FileSystem fs();

    /** Bounded HTTP client; off-whitelist domains throw {@code AccessDeniedException}. */
    ToolExecutionContext.NetworkClient http();

    /** Bounded process runner; non-whitelisted binaries throw {@code AccessDeniedException}. */
    ProcessRunner process();

    /**
     * Runs a single command with explicit args inside the sandbox.
     *
     * @param command binary name (e.g. {@code "ls"}, {@code "git"})
     * @param args    arguments; must not contain shell metacharacters (caller responsible for escaping)
     * @param cwd     working directory inside the sandbox fs
     */
    interface ProcessRunner {
        Process run(String command, List<String> args, Path cwd) throws IOException;
    }
}