package ai.lingshu.core.impl.sandbox;

import ai.lingshu.core.slot.AccessDeniedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #028 — L1 unit tests for {@link ChrootedFileSystem} (dsh §6.3 chrooted FS template).
 *
 * <p>AC-NN-2 contract:
 * <ol>
 *   <li>Path that escapes the configured {@code rootDir} → throws
 *       {@link AccessDeniedException} with {@code [LINGS-S01]} prefix and a message
 *       including {@code "Path escapes working dir"}</li>
 *   <li>Path that stays under {@code rootDir} → returns a normal {@link Path}</li>
 *   <li>Path equal to {@code rootDir} itself → returns a normal {@link Path} (boundary)</li>
 * </ol>
 */
class ChrootedFileSystemTest {

    @Test
    @DisplayName("AC-NN-2: getPath(\"/etc/passwd\") throws AccessDeniedException with [LINGS-S01] prefix")
    void escapePathThrowsAccessDenied(@TempDir Path workDir) {
        FileSystem delegate = FileSystems.getDefault();
        ChrootedFileSystem fs = new ChrootedFileSystem(delegate, workDir);

        // /etc/passwd is clearly outside any temp dir on Linux/macOS — should be denied
        assertThatThrownBy(() -> fs.getPath("/etc/passwd"))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageStartingWith("[LINGS-S01]")
            .hasMessageContaining("Path escapes working dir");
    }

    @Test
    @DisplayName("AC-NN-2: getPath(\"<workDir>/file.txt\") returns a valid Path")
    void validPathReturnsResolvedPath(@TempDir Path workDir) {
        FileSystem delegate = FileSystems.getDefault();
        ChrootedFileSystem fs = new ChrootedFileSystem(delegate, workDir);

        Path resolved = fs.getPath(workDir.toString(), "file.txt");

        assertThat(resolved).isNotNull();
        assertThat(resolved.toAbsolutePath().normalize())
            .as("resolved path must be normalised and absolute")
            .isEqualTo(workDir.resolve("file.txt").toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("AC-NN-2: getPath(rootDir itself) passes the boundary check")
    void rootDirItselfPassesBoundaryCheck(@TempDir Path workDir) {
        FileSystem delegate = FileSystems.getDefault();
        ChrootedFileSystem fs = new ChrootedFileSystem(delegate, workDir);

        // The exact rootDir path is the boundary — `startsWith` should match itself
        Path resolved = fs.getPath(workDir.toAbsolutePath().normalize().toString());

        assertThat(resolved).isNotNull();
        assertThat(resolved.toAbsolutePath().normalize())
            .isEqualTo(workDir.toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("AC-NN-2: traversal via \"../\" segments is caught by normalisation")
    void traversalEscapeIsBlockedByNormalisation(@TempDir Path workDir) {
        FileSystem delegate = FileSystems.getDefault();
        ChrootedFileSystem fs = new ChrootedFileSystem(delegate, workDir);

        // Use a sub-path then ".." back out — normalise() resolves the escape
        String subdir = workDir.resolve("sub").toString();
        assertThatThrownBy(() -> fs.getPath(subdir, "..", "..", "etc", "passwd"))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageStartingWith("[LINGS-S01]")
            .hasMessageContaining("Path escapes working dir");
    }

    @Test
    @DisplayName("AC-NN-2: getRootDir() returns the normalised root")
    void getRootDirReturnsNormalisedRoot(@TempDir Path workDir) {
        FileSystem delegate = FileSystems.getDefault();
        ChrootedFileSystem fs = new ChrootedFileSystem(delegate, workDir);

        assertThat(fs.getRootDir())
            .isEqualTo(workDir.toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("AC-NN-2: null first component throws IllegalArgumentException")
    void nullFirstComponentThrows(@TempDir Path workDir) {
        FileSystem delegate = FileSystems.getDefault();
        ChrootedFileSystem fs = new ChrootedFileSystem(delegate, workDir);

        assertThatThrownBy(() -> fs.getPath(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not be null");
    }

    @Test
    @DisplayName("AC-NN-2: constructor null guards")
    void ctorRejectsNullArgs() {
        FileSystem delegate = FileSystems.getDefault();
        Path root = Paths.get("/tmp");

        assertThatThrownBy(() -> new ChrootedFileSystem(null, root))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("delegate");
        assertThatThrownBy(() -> new ChrootedFileSystem(delegate, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("rootDir");
    }
}