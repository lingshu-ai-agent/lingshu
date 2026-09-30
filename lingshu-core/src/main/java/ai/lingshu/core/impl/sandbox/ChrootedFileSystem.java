package ai.lingshu.core.impl.sandbox;

import ai.lingshu.core.slot.AccessDeniedException;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.Collections;
import java.util.Set;

/**
 * Story #028 — JVM-internal "chroot" FileSystem for Slot 3 Sandbox (dsh §6.3
 * ChrootedFileSystem template).
 *
 * <p>Wraps a delegate {@link FileSystem} (typically {@link java.nio.file.FileSystems#getDefault})
 * and confines every {@link #getPath(String, String...)} call to a configured {@code rootDir}
 * prefix. Resolved paths are normalised via {@code toAbsolutePath().normalize()}; if the
 * resulting path does not start with {@code rootDir}, an {@link AccessDeniedException}
 * carrying the {@code [LINGS-S01]} ErrorCode is thrown.
 *
 * <h2>What this is <em>not</em></h2>
 *
 * <p>This is <b>not</b> an OS-level chroot(2) — there is no {@code chroot} syscall, no
 * namespace isolation, no filesystem mount table reorganisation. It is a JVM-level
 * path-prefix gate: the underlying delegate still exposes the full host filesystem if a
 * tool bypasses this wrapper and obtains the delegate directly. Real OS-level chroot is
 * deferred to a v2 follow-up with a Docker / Landlock / gVisor provider.
 *
 * <h2>Delegate SPI</h2>
 *
 * <p>All other {@link FileSystem} SPI methods ({@link #provider()}, {@link #supportedFileAttributeViews()},
 * etc.) are passed straight through to {@code delegate}. This keeps the surface area
 * minimal — only {@link #getPath} is hardened, since that is the entry point used by all
 * path-derivation APIs ({@link java.nio.file.Files#readAllBytes(Path)},
 * {@link java.nio.file.Files#newInputStream(Path)}, {@link java.nio.file.Files#walk(Path)}, etc.).
 *
 * @see AccessDeniedException for the ErrorCode embedded in deny messages
 */
public final class ChrootedFileSystem extends FileSystem {

    private final FileSystem delegate;
    private final Path rootDir;

    /**
     * @param delegate underlying {@link FileSystem} (typically {@link java.nio.file.FileSystems#getDefault()})
     * @param rootDir  absolute path of the chroot root; resolved paths must
     *                 {@code startsWith(rootDir)} after normalisation
     */
    public ChrootedFileSystem(FileSystem delegate, Path rootDir) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate FileSystem must not be null");
        }
        if (rootDir == null) {
            throw new IllegalArgumentException("rootDir must not be null");
        }
        this.delegate = delegate;
        // Normalise rootDir once at construction so getPath() doesn't repeat the work
        // on every call. Use the delegate FS so equals() semantics line up.
        this.rootDir = delegate.getPath(rootDir.toString()).toAbsolutePath().normalize();
    }

    /**
     * Resolve a path under {@link #delegate} and verify it stays under {@link #rootDir}.
     *
     * <p>Throws {@link AccessDeniedException} with {@code [LINGS-S01]} prefix when the
     * normalised absolute path does not start with {@link #rootDir}.
     *
     * @throws AccessDeniedException if the resolved path escapes the configured root
     */
    @Override
    public Path getPath(String first, String... more) {
        if (first == null) {
            throw new IllegalArgumentException("first path component must not be null");
        }
        Path resolved = delegate.getPath(first, more == null ? new String[0] : more);
        Path absolute = resolved.toAbsolutePath().normalize();
        if (!absolute.startsWith(rootDir)) {
            throw new AccessDeniedException(
                "Path escapes working dir: " + absolute);
        }
        return resolved;
    }

    @Override
    public FileSystemProvider provider() {
        return delegate.provider();
    }

    @Override
    public String getSeparator() {
        return delegate.getSeparator();
    }

    @Override
    public boolean isOpen() {
        return delegate.isOpen();
    }

    @Override
    public boolean isReadOnly() {
        return delegate.isReadOnly();
    }

    @Override
    public Set<String> supportedFileAttributeViews() {
        return delegate.supportedFileAttributeViews();
    }

    @Override
    public Iterable<Path> getRootDirectories() {
        // Only expose the configured rootDir so callers that walk the root set see
        // exactly one entry — the chroot. Anything else would leak the delegate's
        // true root set.
        return Collections.singletonList(rootDir);
    }

    @Override
    public Iterable<FileStore> getFileStores() {
        return delegate.getFileStores();
    }

    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        return delegate.getPathMatcher(syntaxAndPattern);
    }

    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        return delegate.getUserPrincipalLookupService();
    }

    @Override
    public WatchService newWatchService() throws IOException {
        return delegate.newWatchService();
    }

    @Override
    public void close() throws IOException {
        // Intentionally do NOT close the delegate — it's typically the JVM-wide default
        // FileSystem and closing it would break the rest of the agent / JVM. Future
        // Story #014 graceful shutdown will revisit lifecycle ownership.
    }

    /** Return the normalised chroot root path (mainly for diagnostics / tests). */
    public Path getRootDir() {
        return rootDir;
    }
}