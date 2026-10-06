package ai.lingshu.core.impl.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #043 — L1 unit tests for {@link AnthropicLlmProvider#ioExecutor} bounded
 * thread pool shape (AC-NN-pool-1 / AC-NN-name-1 / AC-NN-1).
 *
 * <p>The {@code ioExecutor} field is {@code private final} (per {@code AgentFactory.create()}
 * prototype-of-Agent pattern, dsh §7.1); these tests use {@link Field#setAccessible(boolean)}
 * to read the field directly (consistent with the {@code AnthropicLlmProviderTest}
 * {@code getDeclaredMethod + setAccessible} precedent).
 *
 * <p>Mirrors {@code ToolExecutorConfig.agentToolPool} shape — the project's only
 * other bounded-pool template. Both are documented at dsh §10 NFR "并发 turn 数"
 * as the project's authoritative pool-shape contract.
 *
 * <p><b>Why no L2 IT</b> — Story #043 is hygiene-only (replaces an unbounded
 * {@code newCachedThreadPool} with a bounded {@code ThreadPoolExecutor}). With the
 * current single-task-per-turn reality, both implementations are behaviorally
 * identical (queue never fills, no CallerRunsPolicy triggered). Verifying pool
 * <i>shape</i> via reflection is sufficient; behavior is locked down by the
 * existing 38-case {@code Anthropic*} regression suite (zero changes here).
 */
@DisplayName("Story #043 — AnthropicLlmProvider bounded I/O pool")
public class AnthropicLlmProviderBoundedPoolTest {

    private static AnthropicLlmProvider newProvider() {
        return new AnthropicLlmProvider(
            "https://api.example.com/anthropic",
            "test-key",
            "2023-06-01",
            "test-model",
            1024,
            0.7);
    }

    private static ThreadPoolExecutor readIoExecutor(AnthropicLlmProvider provider)
            throws ReflectiveOperationException {
        Field f = AnthropicLlmProvider.class.getDeclaredField("ioExecutor");
        f.setAccessible(true);
        ExecutorService es = (ExecutorService) f.get(provider);
        assertThat(es)
            .as("ioExecutor must be a ThreadPoolExecutor (Story #043)")
            .isInstanceOf(ThreadPoolExecutor.class);
        return (ThreadPoolExecutor) es;
    }

    @Test
    @DisplayName("AC-NN-pool-1.1 — ioExecutor field is a ThreadPoolExecutor")
    void poolType_isThreadPoolExecutor() throws ReflectiveOperationException {
        ThreadPoolExecutor pool = readIoExecutor(newProvider());
        // Sanity: the runtime class is exactly ThreadPoolExecutor (not a subclass).
        assertThat(pool.getClass()).isEqualTo(ThreadPoolExecutor.class);
    }

    @Test
    @DisplayName("AC-NN-pool-1.2 — corePoolSize == availableProcessors() * 2")
    void corePoolSize_isAvailableProcessorsTimes2() throws ReflectiveOperationException {
        int expected = Runtime.getRuntime().availableProcessors() * 2;
        assertThat(readIoExecutor(newProvider()).getCorePoolSize()).isEqualTo(expected);
    }

    @Test
    @DisplayName("AC-NN-pool-1.3 — maxPoolSize == corePoolSize * 2")
    void maxPoolSize_isCoreTimes2() throws ReflectiveOperationException {
        ThreadPoolExecutor pool = readIoExecutor(newProvider());
        assertThat(pool.getMaximumPoolSize()).isEqualTo(pool.getCorePoolSize() * 2);
    }

    @Test
    @DisplayName("AC-NN-pool-1.4 — keepAliveTime == 60s (excess threads reaped after 1 min idle)")
    void keepAliveTime_is60Seconds() throws ReflectiveOperationException {
        assertThat(readIoExecutor(newProvider()).getKeepAliveTime(TimeUnit.SECONDS))
            .isEqualTo(60L);
    }

    @Test
    @DisplayName("AC-NN-pool-1.5 — work queue is LinkedBlockingQueue(256)")
    void queue_isLinkedBlockingQueueOf256() throws ReflectiveOperationException {
        ThreadPoolExecutor pool = readIoExecutor(newProvider());
        assertThat(pool.getQueue()).isInstanceOf(LinkedBlockingQueue.class);
        LinkedBlockingQueue<?> q = (LinkedBlockingQueue<?>) pool.getQueue();
        // Bounded: remainingCapacity() + size() must equal capacity (256) at construction.
        assertThat(q.remainingCapacity() + q.size()).isEqualTo(256);
    }

    @Test
    @DisplayName("AC-NN-pool-1.6 — rejectedExecutionHandler is CallerRunsPolicy")
    void rejectedHandler_isCallerRunsPolicy() throws ReflectiveOperationException {
        ThreadPoolExecutor pool = readIoExecutor(newProvider());
        assertThat(pool.getRejectedExecutionHandler())
            .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
    }

    @Test
    @DisplayName("AC-NN-name-1 — thread name prefix is 'anthropic-llm-io-N', daemon=true")
    void threadName_prefix_isAnthropicLlmIo_N_daemon() throws Exception {
        AnthropicLlmProvider provider = newProvider();
        ThreadPoolExecutor pool = readIoExecutor(provider);

        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        pool.submit(new Runnable() {
            @Override public void run() {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS))
                .as("worker thread must start within 5s")
                .isTrue();

            // Find the pool's worker thread and verify name + daemon flag.
            boolean found = false;
            Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
            for (Thread t : all.keySet()) {
                String name = t.getName();
                if (name != null && name.startsWith("anthropic-llm-io-")) {
                    assertThat(t.isDaemon())
                        .as("anthropic-llm-io-* threads must be daemon (NFR)")
                        .isTrue();
                    // Counter starts at 1 per spec (threadSeq.incrementAndGet()).
                    assertThat(name)
                        .as("thread name should match 'anthropic-llm-io-<N>' where N>=1")
                        .matches("anthropic-llm-io-\\d+");
                    found = true;
                    break;
                }
            }
            assertThat(found)
                .as("must observe at least one thread named 'anthropic-llm-io-*' in jstack")
                .isTrue();
        } finally {
            release.countDown();
        }
    }
}
