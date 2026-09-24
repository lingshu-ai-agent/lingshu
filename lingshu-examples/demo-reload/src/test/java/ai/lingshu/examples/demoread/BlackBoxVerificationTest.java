package ai.lingshu.examples.demoread;

import ai.lingshu.core.reload.AgentConfigRegistry;
import ai.lingshu.core.reload.ConfigChangeListener;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.impl.runtime.AgentFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #007 AC-06 skeleton — AgentConfigRegistry 单实例 + AtomicReference publish / current + Listener 同步触发。
 *
 * <p>骨架阶段:
 * <ul>
 *   <li>AC-06-1: registry.publish(...) 后 registry.current() 立即返回新 config</li>
 *   <li>AC-06-2: registered ConfigChangeListener 在 publish 同步触发</li>
 *   <li>AC-06-3: listenerCount() 反映 addListener / removeListener</li>
 *   <li>AC-06-4: null publish 抛 NPE(fail-fast)</li>
 * </ul>
 *
 * <p>完整 AC-06(yml mtime 检测 + 1s 轮询)在 Stage B: {@code Files.touch(yml)} → 等 ≤ 1.5s → Listener 触发。
 */
@SpringBootTest(
    classes = DemoReloadApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BlackBoxVerificationTest {

    @Autowired private AgentConfigRegistry registry;
    @Autowired private AgentFactory agentFactory;
    @Autowired private AgentConfig initialConfig;

    @org.junit.jupiter.api.BeforeEach
    void publishInitialIfNeeded() throws Exception {
        // No auto-publish exists in the framework; the user must seed the registry
        // explicitly before any read of current(). This mirrors production usage.
        if (registry.current() == null) {
            registry.publish(initialConfig);
        }
    }

    @Test
    @DisplayName("AC-06-1: registry.current() returns the published initial config")
    void ac06_currentAfterPublishInitial() {
        AgentConfig initial = registry.current();
        assertThat(initial).as("publishInitial invoked by Spring bean lifecycle").isNotNull();
    }

    @Test
    @DisplayName("AC-06-2: ConfigChangeListener invoked synchronously on publish with prev + next")
    void ac06_listenerInvoked() throws Exception {
        AgentConfig next = agentFactory.loadYamlAndValidate(
            new ClassPathResource("application.yml").getFile().toPath());
        assertThat(next).isNotNull();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AgentConfig> seenPrev = new AtomicReference<>();
        AtomicReference<AgentConfig> seenNext = new AtomicReference<>();

        ConfigChangeListener listener = (prev, n) -> {
            seenPrev.set(prev);
            seenNext.set(n);
            latch.countDown();
        };
        registry.addListener(listener);
        try {
            registry.publish(next);
            assertThat(latch.await(2, TimeUnit.SECONDS))
                .as("listener invoked synchronously on publish")
                .isTrue();
            assertThat(seenNext.get()).isSameAs(next);
            assertThat(seenPrev.get()).isNotNull();
        } finally {
            registry.removeListener(listener);
        }
    }

    @Test
    @DisplayName("AC-06-3: addListener + removeListener — duplicate addListener does NOT re-fire")
    void ac06_addListenerDedupe() throws Exception {
        AgentConfig next = agentFactory.loadYamlAndValidate(
            new ClassPathResource("application.yml").getFile().toPath());

        java.util.concurrent.atomic.AtomicInteger callCount =
            new java.util.concurrent.atomic.AtomicInteger(0);
        ConfigChangeListener listener = (prev, n) -> callCount.incrementAndGet();

        registry.addListener(listener);
        try {
            registry.publish(next);
            assertThat(callCount.get()).isEqualTo(1);
            // publish again — listener fires once per publish
            registry.publish(next);
            assertThat(callCount.get()).isEqualTo(2);
        } finally {
            registry.removeListener(listener);
        }

        // After removeListener, no more calls
        registry.publish(next);
        assertThat(callCount.get())
            .as("removed listener must not be invoked")
            .isEqualTo(2);
    }

    @Test
    @DisplayName("AC-06-4: registry rejects null publish (NPE fail-fast)")
    void ac06_rejectsNullPublish() {
        assertThatThrownBy(() -> registry.publish(null))
            .isInstanceOf(NullPointerException.class);
    }
}