package ai.lingshu.core.impl.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Story #004 — Shared {@link ExecutorService} for parallel tool dispatch (FR-006).
 *
 * <p>Bean name {@code agentToolPool} is the well-known name injected into
 * {@link ai.lingshu.core.impl.flow.LinearTurnEngineProvider} via {@code @Qualifier}.
 * The {@code LinearTurnEngine.dispatchParallel} method submits {@link java.util.concurrent.CompletableFuture}
 * tasks to this pool — bounded by a {@link java.util.concurrent.Semaphore} on the engine side.
 *
 * <p>Sizing (D-01):
 * <ul>
 *   <li>{@code corePoolSize = availableProcessors() * 2} — I/O-bound tool calls
 *       (dsh §14.7 "rule of thumb: 2 * cores for I/O-bound work")</li>
 *   <li>{@code maxPoolSize = corePoolSize * 2} — burst tolerance</li>
 *   <li>{@code keepAliveTime = 60s} — excess threads reaped after 1 minute idle</li>
 *   <li>{@code workQueue = LinkedBlockingQueue(256)} — bounded memory footprint</li>
 *   <li>{@code rejectedExecutionHandler = CallerRunsPolicy} — back-pressure:
 *       if queue is full, the caller thread (LinearTurnEngine) runs the task inline,
 *       slowing the turn rather than rejecting and dropping work</li>
 * </ul>
 *
 * <p>Thread naming (NFR-003):
 * <ul>
 *   <li>Prefix {@code lingshu-tool-N} — visible in jstack / thread dumps</li>
 *   <li>{@code daemon = true} — JVM exit is not blocked by pool threads</li>
 * </ul>
 */
@Configuration
public class ToolExecutorConfig {

    private ExecutorService agentToolPool;

    @Bean(name = "agentToolPool")
    public ExecutorService agentToolPool() {
        final int corePoolSize = Runtime.getRuntime().availableProcessors() * 2;
        final int maxPoolSize = corePoolSize * 2;
        final long keepAliveSeconds = 60L;
        final int queueCapacity = 256;

        final AtomicInteger threadSeq = new AtomicInteger(0);
        ThreadFactory toolThreadFactory = new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "lingshu-tool-" + threadSeq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };

        this.agentToolPool = new ThreadPoolExecutor(
            corePoolSize,
            maxPoolSize,
            keepAliveSeconds,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(queueCapacity),
            toolThreadFactory,
            new ThreadPoolExecutor.CallerRunsPolicy());

        return this.agentToolPool;
    }

    @PreDestroy
    public void shutdown() {
        if (agentToolPool != null) {
            agentToolPool.shutdown();
        }
    }
}
