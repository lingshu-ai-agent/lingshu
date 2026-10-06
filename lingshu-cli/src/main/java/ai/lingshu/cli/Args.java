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

import lombok.Value;

import java.nio.file.Path;

/**
 * Story #017 — Parsed CLI arguments container. Immutable Lombok {@code @Value} so
 * {@link ArgsParser} builds it once and {@link CliRunner} reads only.
 *
 * <p>Per-subcommand relevant fields (others may be {@code null}/{@code false}):
 * <ul>
 *   <li>{@code RUN}:      {@link #prompt} (required)</li>
 *   <li>{@code RESUME}:   {@link #sessionId} + {@link #prompt} (required)</li>
 *   <li>{@code SERVE}:    {@link #port} (optional, default 8080)</li>
 *   <li>{@code DOCTOR}:   {@link #printSchema} / {@link #printSkills} (optional)</li>
 *   <li>{@code CONFIG}:   {@link #printEffective} / {@link #printSchema} (optional)</li>
 * </ul>
 * All subcommands accept {@link #configPath} (default {@code application.yml}).
 *
 * <p>🆕 Story #020c — {@code lingshu {run|resume|doctor} --list-skills} prints the
 * available Skill commands banner and exits without invoking the Agent.
 */
@Value
public class Args {
    Subcommand subcommand;
    Path configPath;
    String prompt;
    String sessionId;
    Integer port;
    boolean printEffective;
    boolean printSchema;
    /** 🆕 Story #020c — {@code lingshu {run|resume} --list-skills} only prints skills, does not invoke Agent. */
    boolean printSkills;
}