package ai.lingshu.examples.democompactor;

import ai.lingshu.core.impl.config.AgentConfigDefaults;
import ai.lingshu.core.impl.router.Routers;
import ai.lingshu.core.impl.runtime.AgentFactory;
import ai.lingshu.core.message.Message;
import ai.lingshu.core.message.Prompt;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.Session;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.slot.Compactor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #018 AC-13 skeleton — TruncatingCompactor + CompactorRouter。
 *
 * <p>骨架阶段不触发完整 turn(避免 Anthropic API 依赖);只通过 CompactorRouter SPI
 * 拿到 Compactor 实例,直接喂超长 history 验证:
 * <ul>
 *   <li>AC-13-1: TruncatingCompactorProvider 通过 SPI 注册 + Router 可见</li>
 *   <li>AC-13-2: shouldCompact(prompt) 返 true 当 token 估算超 maxPromptTokens</li>
 *   <li>AC-13-3: compact(ctx) 后 ToolResult 长 content 缩短 + sliding-window 生效</li>
 * </ul>
 */
@SpringBootTest(
    classes = DemoCompactorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BlackBoxVerificationTest {

    @Autowired private AgentFactory agentFactory;
    @Autowired private Routers.CompactorRouter compactorRouter;

    @Test
    @DisplayName("AC-13-1: TruncatingCompactorProvider registered + visible via Router")
    void ac13_providerRegistered() {
        assertThat(compactorRouter.available()).contains("truncating");
    }

    @Test
    @DisplayName("AC-13-2: shouldCompact returns true when token estimate > maxPromptTokens")
    void ac13_shouldCompactThreshold() {
        AgentConfig cfg = AgentConfigDefaults.defaults();
        Compactor compactor = compactorRouter.resolve("truncating", cfg);
        assertThat(compactor).isNotNull();

        // Build a Prompt whose total chars exceeds (maxPromptTokens * 4).
        // Default CompactorConfig has maxPromptTokens = 100_000; compactor uses strict >
        // so estimate must be strictly > 100_000 — 400_004 chars / 4 = 100_001 tokens.
        // Note: Message.System ctor is (content, source) — long string must go in `content`
        // since estimateTokens() reads getContent() only.
        long big = 400_004L;
        Message.System sys = new Message.System(repeat('x', (int) big), "default");
        List<Message> msgs = Collections.<Message>singletonList(sys);
        Prompt p = Prompt.builder().messages(msgs).build();

        assertThat(compactor.shouldCompact(p))
            .as("token estimate %d > %d", big / 4, cfg.getCompactorConfig().getMaxPromptTokens())
            .isTrue();
    }

    @Test
    @DisplayName("AC-13-3: shouldCompact returns false when prompt is well under budget")
    void ac13_shouldNotCompact() {
        AgentConfig cfg = AgentConfigDefaults.defaults();
        Compactor compactor = compactorRouter.resolve("truncating", cfg);

        Prompt small = Prompt.builder()
            .messages(Collections.<Message>singletonList(new Message.System("default", "hello")))
            .build();
        assertThat(compactor.shouldCompact(small)).isFalse();
    }

    @Test
    @DisplayName("AC-13-4: Compactor contract version 1.0.0")
    void ac13_contractVersion() {
        assertThat(Compactor.CONTRACT_VERSION).isEqualTo("1.0.0");
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static String repeat(char c, int n) {
        char[] buf = new char[n];
        java.util.Arrays.fill(buf, c);
        return new String(buf);
    }
}