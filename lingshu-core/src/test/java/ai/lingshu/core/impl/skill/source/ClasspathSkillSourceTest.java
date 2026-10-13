/*
 * Copyright 2026 The LingShu Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.lingshu.core.impl.skill.source;

import ai.lingshu.core.slot.Skill;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Story #020b — L2 functional test for {@link ClasspathSkillSource}.
 *
 * <p>AC-020b-1: discovers {@code SKILL.md} files under {@code classpath:skills/agent-builtin/}
 * and produces 3 {@link Skill} instances (commit / review / docs).
 *
 * <p>Fixtures live at {@code src/test/resources/skills/agent-builtin/<name>/SKILL.md}
 * (committed alongside the test so they ride the test classpath).
 */
class ClasspathSkillSourceTest {

    @Test
    @DisplayName("AC-020b-1: discovers 3 SKILL.md files under classpath:skills/agent-builtin/")
    void discoversThreeSkills() throws Exception {
        ClasspathSkillSource source = new ClasspathSkillSource("classpath:skills/agent-builtin/");

        List<Skill> skills = source.discover();

        assertThat(skills).hasSize(3);
        Set<String> names = new HashSet<String>();
        for (Skill s : skills) names.add(s.name());
        assertThat(names).containsExactlyInAnyOrder("commit", "review", "docs");
    }

    @Test
    @DisplayName("AC-020b-1: each Skill description equals the first H1 title from SKILL.md")
    void descriptionMatchesFirstH1() throws Exception {
        ClasspathSkillSource source = new ClasspathSkillSource("classpath:skills/agent-builtin/");

        List<Skill> skills = source.discover();

        Skill commit = findByName(skills, "commit");
        assertThat(commit.description()).isEqualTo("commit");

        Skill review = findByName(skills, "review");
        assertThat(review.description()).isEqualTo("review");
    }

    @Test
    @DisplayName("AC-020b-1: type() and location() echo the routing key and configured prefix")
    void typeAndLocationEcho() {
        ClasspathSkillSource source = new ClasspathSkillSource("classpath:skills/agent-builtin/");

        assertThat(source.type()).isEqualTo("classpath");
        assertThat(source.location()).isEqualTo("classpath:skills/agent-builtin/");
        assertThat(source.watchable()).isFalse();
    }

    private static Skill findByName(List<Skill> skills, String name) {
        for (Skill s : skills) {
            if (s.name().equals(name)) return s;
        }
        throw new AssertionError("Skill not found: " + name);
    }

    /**
     * Story #020b follow-up (Windows-path regression): on Windows the resolved
     * {@link Resource} is a {@code FileSystemResource} whose
     * {@link Resource#getDescription()} uses backslashes. Without the
     * {@code '\\' → '/'} normalization in {@link ClasspathSkillSource#deriveName(Resource)},
     * {@code lastIndexOf('/')} misses and the skill name degenerates to
     * {@code "SKILL.md"} — which then violates the Anthropic tool-name wire rule
     * {@code ^[a-zA-Z0-9_-]+$} and breaks the demo-product boot on Windows.
     *
     * <p>This test runs on Linux/macOS CI runners (no Windows fixture needed)
     * by mocking the {@link Resource} to return a backslash-formatted description.
     */
    @Test
    @DisplayName("Windows backslash: deriveName normalizes '\\\\' → '/' and extracts parent dir")
    void deriveName_handlesWindowsBackslashPath() {
        Resource resource = mock(Resource.class);
        when(resource.getDescription()).thenReturn(
            "file [D:\\workspace\\lingshu\\skills\\agent\\SKILL.md]");
        assertThat(ClasspathSkillSource.deriveName(resource)).isEqualTo("agent");
    }

    /**
     * Sanity-check the same code path on a Linux-style classpath description
     * (forward slashes only) so a regression on either branch is caught.
     */
    @Test
    @DisplayName("Linux classpath: deriveName extracts parent dir from forward-slash description")
    void deriveName_handlesLinuxClasspathPath() {
        Resource resource = mock(Resource.class);
        when(resource.getDescription()).thenReturn(
            "class path resource [skills/agent-builtin/commit/SKILL.md]");
        assertThat(ClasspathSkillSource.deriveName(resource)).isEqualTo("commit");
    }

    /**
     * Sanity-check the URL/jar description path (forward slashes; the
     * replace is a no-op there) — keeps the three Resource subtypes
     * (FileSystem / ClassPath / Url) covered.
     */
    @Test
    @DisplayName("Jar URL: deriveName extracts parent dir from URL-style description")
    void deriveName_handlesJarUrlPath() {
        Resource resource = mock(Resource.class);
        when(resource.getDescription()).thenReturn(
            "URL [jar:file:/workspace/lingshu-skills.jar!/skills/agent-builtin/review/SKILL.md]");
        assertThat(ClasspathSkillSource.deriveName(resource)).isEqualTo("review");
    }
}
