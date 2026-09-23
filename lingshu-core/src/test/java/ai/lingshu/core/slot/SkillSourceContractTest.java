package ai.lingshu.core.slot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #020b — L1 contract test for {@link SkillSource}.
 *
 * <p>AC-020b-1: the SPI must expose 4 methods — {@code type()}, {@code location()},
 * {@code discover()}, and the default {@code watchable()}. Reflection-based
 * verification catches accidental signature drift during refactors.
 *
 * <p>Mirrors {@code ToolRegistryContractTest} (Story #020a) pattern: source inspection
 * is easier to fake than reflective method lookup, but reflection catches method-name
 * removal during IDE-driven refactors that miss caller sites.
 */
class SkillSourceContractTest {

    @Test
    @DisplayName("AC-020b-1: SkillSource exposes the 4 declared methods")
    void interfaceHasFourMethods() {
        Set<String> methodNames = Arrays.stream(SkillSource.class.getMethods())
            .map(Method::getName)
            .collect(Collectors.toSet());

        assertThat(methodNames).contains("type");
        assertThat(methodNames).contains("location");
        assertThat(methodNames).contains("discover");
        assertThat(methodNames).contains("watchable");
    }

    @Test
    @DisplayName("AC-020b-1: type() return type is String")
    void type_returnsString() throws NoSuchMethodException {
        Method m = SkillSource.class.getMethod("type");
        assertThat(m.getReturnType().getName()).isEqualTo(String.class.getName());
    }

    @Test
    @DisplayName("AC-020b-1: location() return type is String")
    void location_returnsString() throws NoSuchMethodException {
        Method m = SkillSource.class.getMethod("location");
        assertThat(m.getReturnType().getName()).isEqualTo(String.class.getName());
    }

    @Test
    @DisplayName("AC-020b-1: discover() return type is List<Skill>")
    void discover_returnsListOfSkill() throws NoSuchMethodException {
        Method m = SkillSource.class.getMethod("discover");
        assertThat(m.getReturnType().getName()).isEqualTo(List.class.getName());
    }

    @Test
    @DisplayName("AC-020b-1: watchable() return type is boolean (default method)")
    void watchable_returnsBoolean() throws NoSuchMethodException {
        Method m = SkillSource.class.getMethod("watchable");
        assertThat(m.getReturnType().getName()).isEqualTo(boolean.class.getName());
    }
}
