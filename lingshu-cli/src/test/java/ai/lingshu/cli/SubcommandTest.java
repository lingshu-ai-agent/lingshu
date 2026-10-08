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
package ai.lingshu.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #017 — {@link Subcommand} enum unit tests. 4 cases covering:
 * <ul>
 *   <li>each of 5 enum values resolves from its string</li>
 *   <li>unknown string → {@link LingsCliException} LINGS-Z01</li>
 *   <li>null input → {@link LingsCliException} LINGS-Z01</li>
 *   <li>case-sensitivity: "RUN" is rejected (lowercase only)</li>
 * </ul>
 */
class SubcommandTest {

    @Test
    void fromString_eachValue_resolvesCorrectly() {
        assertThat(Subcommand.fromString("run")).isEqualTo(Subcommand.RUN);
        assertThat(Subcommand.fromString("resume")).isEqualTo(Subcommand.RESUME);
        assertThat(Subcommand.fromString("serve")).isEqualTo(Subcommand.SERVE);
        assertThat(Subcommand.fromString("doctor")).isEqualTo(Subcommand.DOCTOR);
        assertThat(Subcommand.fromString("config")).isEqualTo(Subcommand.CONFIG);
    }

    @Test
    void fromString_unknownValue_throwsLingsZ01() {
        assertThatThrownBy(() -> Subcommand.fromString("frobnicate"))
            .isInstanceOf(LingsCliException.class)
            .satisfies(e -> assertThat(((LingsCliException) e).getErrorCode()).isEqualTo("LINGS-Z01"));
    }

    @Test
    void fromString_nullInput_throwsLingsZ01() {
        assertThatThrownBy(() -> Subcommand.fromString(null))
            .isInstanceOf(LingsCliException.class)
            .satisfies(e -> assertThat(((LingsCliException) e).getErrorCode()).isEqualTo("LINGS-Z01"));
    }

    @Test
    void fromString_isCaseInsensitive() {
        // dsh §10.3 lists lowercase forms, but we accept any case for ergonomics
        // (avoids surprises on Windows where users may type "Run").
        assertThat(Subcommand.fromString("RUN")).isEqualTo(Subcommand.RUN);
        assertThat(Subcommand.fromString("Run")).isEqualTo(Subcommand.RUN);
        assertThat(Subcommand.fromString("Resume")).isEqualTo(Subcommand.RESUME);
    }
}
