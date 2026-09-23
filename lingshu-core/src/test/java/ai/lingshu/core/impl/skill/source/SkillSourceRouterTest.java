package ai.lingshu.core.impl.skill.source;

import ai.lingshu.core.slot.SkillSource;
import ai.lingshu.core.slot.SkillSourceProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #020b — L2 functional test for {@link SkillSourceRouter}.
 *
 * <p>AC-020b-3: router indexes Providers by {@code type()}, resolves to a
 * {@link SkillSource} via {@code create(location)}, throws on unknown type.
 *
 * <p>Tests construct the router directly with hand-built Provider lists (no Spring)
 * to keep the unit pure.
 */
class SkillSourceRouterTest {

    @Test
    @DisplayName("AC-020b-3: router indexes both v1 Providers and resolves them by type")
    void resolvesBothV1Providers() {
        SkillSourceRouter router = new SkillSourceRouter(Arrays.asList(
            new ClasspathSkillSourceProvider(),
            new DirectorySkillSourceProvider()
        ));

        SkillSource classpath = router.resolve("classpath", "classpath:foo/");
        assertThat(classpath).isInstanceOf(ClasspathSkillSource.class);
        assertThat(classpath.type()).isEqualTo("classpath");

        SkillSource directory = router.resolve("directory", "/tmp/bar");
        assertThat(directory).isInstanceOf(DirectorySkillSource.class);
        assertThat(directory.type()).isEqualTo("directory");
    }

    @Test
    @DisplayName("AC-020b-3: available() returns the registered type keys")
    void availableListsAllTypes() {
        SkillSourceRouter router = new SkillSourceRouter(Arrays.asList(
            new ClasspathSkillSourceProvider(),
            new DirectorySkillSourceProvider()
        ));

        assertThat(router.available()).containsExactlyInAnyOrder("classpath", "directory");
    }

    @Test
    @DisplayName("AC-020b-3: unknown type throws IllegalArgumentException listing available keys")
    void unknownTypeThrows() {
        SkillSourceRouter router = new SkillSourceRouter(Collections.singletonList(
            new ClasspathSkillSourceProvider()
        ));

        assertThatThrownBy(() -> router.resolve("git", "https://example.com/repo.git"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown SkillSource type 'git'")
            .hasMessageContaining("classpath");
    }

    @Test
    @DisplayName("EC-020b-3: duplicate type keeps first-registered and warns the second away")
    void duplicateTypeKeepsFirst() {
        SkillSourceProvider first = new ClasspathSkillSourceProvider();
        SkillSourceProvider duplicate = new SkillSourceProvider() {
            @Override public String type() { return "classpath"; }
            @Override public SkillSource create(String location) {
                return new DirectorySkillSource(location);
            }
        };

        SkillSourceRouter router = new SkillSourceRouter(Arrays.asList(first, duplicate));

        // First-wins: resolving "classpath" should yield a ClasspathSkillSource, not DirectorySkillSource
        SkillSource resolved = router.resolve("classpath", "x");
        assertThat(resolved).isInstanceOf(ClasspathSkillSource.class);

        // Map size is still 1 (no second entry)
        assertThat(router.available()).hasSize(1);
    }

    @Test
    @DisplayName("AC-020b-3: empty provider list resolves nothing and is non-null")
    void emptyProviderListYieldsEmptyIndex() {
        SkillSourceRouter router = new SkillSourceRouter(Collections.<SkillSourceProvider>emptyList());
        assertThat(router.available()).isEmpty();
        Set<String> keys = new HashSet<String>();
        keys.addAll(router.available());
        assertThat(keys).isEmpty();
    }
}
