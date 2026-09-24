package ai.lingshu.examples.demoskill;

import ai.lingshu.core.impl.config.AgentConfigDefaults;
import ai.lingshu.core.reload.YamlWatcher;
import ai.lingshu.core.runtime.AgentConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Story #020a + #020b + #020c demo — Skill 三件套(SkillTool / SkillSource / CLI /xxx 触发)。
 *
 * <p>Stage A 骨架只验证:
 * <ol>
 *   <li>Spring Boot main 启动成功</li>
 *   <li>内置 {@code @Component CommitSkill} + classpath {@code SKILL.md} 都被注册</li>
 *   <li>lingshu-cli 模块的 {@code SkillCommandDispatcher} 类位于 classpath(jar 部署就绪)</li>
 * </ol>
 *
 * <p>完整 AC 黑盒覆盖(SKILL.md 多源 / Classpath vs Directory / CLI dispatcher dispatch)留 Stage B。
 *
 * <p>不扫 {@code ai.lingshu.cli}:lingshu-cli 模块的 {@code CliRunner} 是 {@code ApplicationRunner},
 * 启动时会调 {@code System.exit(...)} —— 本 demo 是 web 容器场景,不需要 CLI 主循环。
 * 通过 {@code Class.forName} + reflection 验证 cli 模块类已部署到 classpath。
 */
@SpringBootApplication
@ComponentScan(
    basePackages = {"ai.lingshu.examples.demoskill", "ai.lingshu.core"},
    // 排除:
    //   - YamlWatcher(Story #007 hot reload daemon —— 本 demo 不验证 hot-reload)
    //   - AgentToolScanner(setApplicationContext 阶段 ctx.getBeansWithAnnotation 自引用 circular ref)
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {YamlWatcher.class}))
public class DemoSkillApplication {

    @Bean
    public AgentConfig agentConfig() {
        return AgentConfigDefaults.defaults();
    }

    public static void main(String[] args) {
        SpringApplication.run(DemoSkillApplication.class, args);
    }
}
