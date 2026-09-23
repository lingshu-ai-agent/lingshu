package ai.lingshu.core.impl.skill;

import ai.lingshu.core.slot.Tool;
import ai.lingshu.core.slot.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #020a — L2 contract test for {@link ToolRegistry}.
 *
 * <p>AC-020a-6: the SPI must expose the 4 new Skill-aware extension points:
 * {@code modelVisibleSpecs()}, {@code findSkill(String)}, {@code skillNames()},
 * {@code findByName(String)}. Existing 3 methods ({@code register / lookup / names})
 * must remain (backward compat for #001 / #019).
 *
 * <p>Why reflection: SPI contract is a compile-time surface but contract tests should
 * also catch accidental signature drift (e.g. someone removing a method during a refactor).
 * Direct source inspection is easier to fake than reflective method lookup.
 */
class ToolRegistryContractTest {

    @Test
    @DisplayName("AC-020a-6: ToolRegistry exposes the 4 new Skill-aware methods")
    void interfaceHasFourNewMethods() {
        Set<String> methodNames = Arrays.stream(ToolRegistry.class.getMethods())
            .map(Method::getName)
            .collect(Collectors.toSet());

        assertThat(methodNames).contains("modelVisibleSpecs");
        assertThat(methodNames).contains("findSkill");
        assertThat(methodNames).contains("skillNames");
        assertThat(methodNames).contains("findByName");
    }

    @Test
    @DisplayName("AC-020a-6: ToolRegistry retains original 3 methods (backward compat)")
    void interfaceKeepsOriginalThreeMethods() {
        Set<String> methodNames = Arrays.stream(ToolRegistry.class.getMethods())
            .map(Method::getName)
            .collect(Collectors.toSet());

        assertThat(methodNames).contains("register");
        assertThat(methodNames).contains("lookup");
        assertThat(methodNames).contains("names");
    }

    @Test
    @DisplayName("AC-020a-6: modelVisibleSpecs return type is List<ToolSpec>")
    void modelVisibleSpecs_returnsListOfToolSpec() throws NoSuchMethodException {
        Method m = ToolRegistry.class.getMethod("modelVisibleSpecs");
        assertThat(m.getReturnType().getName()).isEqualTo(List.class.getName());
    }

    @Test
    @DisplayName("AC-020a-6: findSkill return type is Skill")
    void findSkill_returnsSkill() throws NoSuchMethodException {
        Method m = ToolRegistry.class.getMethod("findSkill", String.class);
        assertThat(m.getReturnType().getName()).isEqualTo("ai.lingshu.core.slot.Skill");
    }

    @Test
    @DisplayName("AC-020a-6: skillNames return type is Set<String>")
    void skillNames_returnsSetOfString() throws NoSuchMethodException {
        Method m = ToolRegistry.class.getMethod("skillNames");
        assertThat(m.getReturnType().getName()).isEqualTo(Set.class.getName());
    }

    @Test
    @DisplayName("AC-020a-6: findByName return type is Tool")
    void findByName_returnsTool() throws NoSuchMethodException {
        Method m = ToolRegistry.class.getMethod("findByName", String.class);
        assertThat(m.getReturnType().getName()).isEqualTo(Tool.class.getName());
    }
}
