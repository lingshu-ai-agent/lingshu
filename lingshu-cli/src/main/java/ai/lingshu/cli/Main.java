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

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Story #017 — CLI entry point.
 *
 * <p>Bootstraps a Spring application context so that the framework can
 * {@code @Autowired}-wire {@code AgentFactory} and its 6 Routers (per dsh §7.1,
 * {@code AgentFactory} is a stateless Spring {@code @Component} singleton).
 *
 * <p>{@link WebApplicationType#NONE} suppresses Tomcat — the {@code serve} subcommand
 * uses {@link ai.lingshu.a2a.server.A2aServer} (Story #009) directly, not Spring MVC.
 *
 * <p>Invocation examples:
 * <pre>
 *   mvn -pl lingshu-cli spring-boot:run \
 *       -Dspring-boot.run.arguments="run --config app.yml --prompt 'say hi'"
 *
 *   mvn -pl lingshu-cli spring-boot:run \
 *       -Dspring-boot.run.arguments="serve --port 8080"
 * </pre>
 *
 * <p>The actual dispatch logic lives in {@link CliRunner}.
 */
@SpringBootApplication
public class Main {

    public static void main(String[] args) {
        new SpringApplicationBuilder(Main.class)
            .web(WebApplicationType.NONE)
            .logStartupInfo(false)
            .run(args);
    }
}