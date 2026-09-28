package ai.lingshu.core.tool;

import ai.lingshu.core.slot.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Spring AI {@code @AgentTool} 注解扫描器 (Story #022, dsh v1.5.40 §6.5 (3) L4961-4979)。
 *
 * <p><b>职责</b> —— 在 Spring ApplicationContext refresh 完成阶段被
 * {@link #onContextRefreshed} 回调(@EventListener),遍历所有 {@code @Component}
 * Bean,反射找带 {@link AgentTool} 注解的 method,包装成
 * {@link SpringAiToolAdapter} 注册到共享 {@link ToolRegistry}。
 *
 * <p><b>为何用 {@link EventListener @EventListener(ContextRefreshedEvent.class)}
 * 而非 {@code ApplicationContextAware} / {@code @PostConstruct} / {@code SmartLifecycle}</b>:
 * <ul>
 *   <li>{@code @PostConstruct} 来自 {@code javax.annotation-api},不在 lingshu-core
 *       13 项依赖列表内</li>
 *   <li>{@link EventListener} 在 {@code spring-context} transitive 内,0 新依赖</li>
 *   <li><b>避开 circular ref bug</b> —— {@link ContextRefreshedEvent} 在所有
 *       {@code @Component} bean 全部 init 完毕后触发,此时
 *       {@code ctx.getBeansWithAnnotation(Component.class)} 不会再强制 eagerly
 *       init 任何 bean,无 {@code BeanCurrentlyInCreationException} 风险;
 *       原 {@code ApplicationContextAware} 方案会在 scanner 自己 init 阶段被回调,
 *       容器内还有 bean 未完成创建 → circular ref。lingshu-examples 全部 14 个
 *       demo 之前因此排除本类</li>
 *   <li>比 {@code SmartLifecycle} 简单一档(scanner 与 JVM 同生命周期,无需
 *       stop / 重连 —— MCP 长连接才需要 lifecycle,见 {@code McpTransportLifecycle})</li>
 *   <li>比 {@code InitializingBean} 安全 —— {@code InitializingBean.afterPropertiesSet}
 *       跑在 scanner 自己 init 阶段,getBeansWithAnnotation 仍可能撞未初始化 bean</li>
 * </ul>
 *
 * <p><b>幂等保护</b> —— nested context / 多次 refresh 场景下
 * {@link ContextRefreshedEvent} 可能触发多次,本类用 {@link AtomicBoolean#compareAndSet}
 * 保证 {@link #scanContext} 只跑一次。
 *
 * <p><b>扫描约束</b>(对齐 dsh §6.5 (3) L4970-4978 字面):
 * <ul>
 *   <li>只扫 {@code ctx.getBeansWithAnnotation(Component.class).values()} —— <b>不</b>扫
 *       static method,<b>不</b>扫 {@code @Bean} 工厂方法,<b>不</b>扫非 {@code @Component}
 *       Bean(e.g. {@code @Service} <b>也</b>被覆盖,因为 {@code @Service} 是 stereotype
 *       annotation 被 {@code @Component} meta-annotated,Spring component-scan 包括它)</li>
 *   <li>同名 method 走 {@code getMethods()} 自然顺序取第一个(用户应保持 method 名唯一)</li>
 *   <li>{@link ToolRegistry#register} 已有 first-wins + log warn 的 dup 语义
 *       (align with {@code DefaultToolRegistry.register} 对齐 §6.5 一致契约)</li>
 * </ul>
 *
 * <p><b>关键不变项</b>(Story #022 严格冻结):
 * <ul>
 *   <li>{@link ToolRegistry} interface 0 改动</li>
 *   <li>{@link ai.lingshu.core.slot.ToolExecutor#dispatch} 5 步流水线 0 改动</li>
 *   <li>0 新 Maven 依赖</li>
 *   <li>0 修改其他已有类(<b>纯本类内部</b> —— {@code ApplicationContextAware} →
 *       {@code @EventListener} 是同 SPI 内部实现切换)</li>
 * </ul>
 *
 * <p><b>JDK 8 兼容</b> — {@code @Component} + {@link EventListener},无
 * {@code var} / {@code List.of} / sealed。
 *
 * @since 1.0.0
 */
@Component
public class AgentToolScanner {

    private static final Logger LOG = LoggerFactory.getLogger(AgentToolScanner.class);

    private final ToolRegistry toolRegistry;

    /**
     * 幂等守卫 —— nested context / 多次 refresh 场景下 {@link ContextRefreshedEvent}
     * 可能触发多次,确保 {@link #scanContext} 只跑一次。
     */
    private final AtomicBoolean scanned = new AtomicBoolean(false);

    /**
     * 构造器 —— 由 Spring {@code @Autowired} 注入共享 {@link ToolRegistry}。
     *
     * <p>为支持纯 {@code AnnotationConfigApplicationContext} 直接构造(测试场景,
     * 不走 Spring component-scan),{@link ToolRegistry} 是显式 nullable 边界;
     * 但生产路径 Spring 保证非 null。
     */
    public AgentToolScanner(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * Spring ApplicationContext refresh 完毕回调 —— {@link ContextRefreshedEvent}
     * 由 Spring 在所有 {@code @Component} bean 全部 init 完成后发布,此时
     * {@code ctx.getBeansWithAnnotation(Component.class)} 不会再触发任何 init,
     * 安全。
     *
     * <p><b>异常策略</b> — {@link #scanContext} 内部每个 bean 单独 try/catch,
     * 单个 bean 扫描失败<b>不</b>阻塞其他 bean 注册(对齐 #021b
     * {@code McpTransport#connect} 同款 fail-tolerant 启动策略)。
     */
    @EventListener(ContextRefreshedEvent.class)
    public void onContextRefreshed(ContextRefreshedEvent event) {
        scanContext(event.getApplicationContext());
    }

    /**
     * 实际扫描逻辑 —— {@link EventListener} 入口 + 同 package 测试直接调用入口。
     *
     * <p>幂等:首次调用 {@code compareAndSet(false, true)} 成功才扫,后续直接 no-op。
     * null ctx 走 debug log + return,不抛异常。
     */
    void scanContext(ApplicationContext ctx) {
        if (!scanned.compareAndSet(false, true)) {
            LOG.debug("AgentToolScanner: already scanned — skipping");
            return;
        }
        if (ctx == null) {
            LOG.debug("AgentToolScanner: null ApplicationContext — skipping scan");
            return;
        }
        Map<String, Object> beans = ctx.getBeansWithAnnotation(Component.class);
        int totalRegistered = 0;
        for (Map.Entry<String, Object> e : beans.entrySet()) {
            String beanName = e.getKey();
            Object bean = e.getValue();
            try {
                totalRegistered += scanBean(beanName, bean);
            } catch (Exception ex) {
                LOG.warn("AgentToolScanner: failed to scan bean '{}' — {}",
                    beanName, ex.toString());
            }
        }
        LOG.info("AgentToolScanner: registered {} @AgentTool method(s) across {} bean(s)",
            totalRegistered, beans.size());
    }

    /** 单 bean 扫描 —— 反射 {@code bean.getClass().getMethods()} 找 {@link AgentTool} 注解。 */
    private int scanBean(String beanName, Object bean) {
        int registered = 0;
        for (Method m : bean.getClass().getMethods()) {
            AgentTool at = m.getAnnotation(AgentTool.class);
            if (at == null) continue;
            try {
                SpringAiToolAdapter adapter = new SpringAiToolAdapter(bean, m, at);
                toolRegistry.register(adapter);
                registered++;
                LOG.debug("AgentToolScanner: registered tool '{}' from bean '{}' method '{}'",
                    at.name(), beanName, m.getName());
            } catch (Exception ex) {
                // 单 method 失败不影响其他 method 注册(整体 try/catch 在 caller)
                throw new IllegalStateException(
                    "Failed to register @AgentTool '" + at.name()
                        + "' (bean=" + beanName + ", method=" + m.getName() + "): "
                        + ex.getMessage(), ex);
            }
        }
        return registered;
    }
}
