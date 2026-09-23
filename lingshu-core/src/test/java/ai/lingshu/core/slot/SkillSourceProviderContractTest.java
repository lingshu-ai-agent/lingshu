package ai.lingshu.core.slot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #020b — L1 contract test for {@link SkillSourceProvider}.
 *
 * <p>AC-020b-2: the SPI must expose 2 methods — {@code type()} and
 * {@code create(String location)}. Reflection-based verification mirrors the
 * pattern used by {@code SkillSourceContractTest} and {@code ToolRegistryContractTest}.
 */
class SkillSourceProviderContractTest {

    @Test
    @DisplayName("AC-020b-2: SkillSourceProvider exposes the 2 declared methods")
    void interfaceHasTwoMethods() {
        Set<String> methodNames = Arrays.stream(SkillSourceProvider.class.getMethods())
            .map(Method::getName)
            .collect(Collectors.toSet());

        assertThat(methodNames).contains("type");
        assertThat(methodNames).contains("create");
    }

    @Test
    @DisplayName("AC-020b-2: type() return type is String")
    void type_returnsString() throws NoSuchMethodException {
        Method m = SkillSourceProvider.class.getMethod("type");
        assertThat(m.getReturnType().getName()).isEqualTo(String.class.getName());
    }

    @Test
    @DisplayName("AC-020b-2: create(String) return type is SkillSource")
    void create_returnsSkillSource() throws NoSuchMethodException {
        Method m = SkillSourceProvider.class.getMethod("create", String.class);
        assertThat(m.getReturnType().getName()).isEqualTo(SkillSource.class.getName());
    }
}
