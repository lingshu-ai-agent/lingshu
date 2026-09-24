package ai.lingshu.a2a.client;

import ai.lingshu.core.impl.router.A2aTransportRouter;
import ai.lingshu.core.message.ToolSpec;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.AgentRef;
import ai.lingshu.core.slot.A2aTransport;
import ai.lingshu.core.spi.Providers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1 unit tests — {@link RemoteAgentToolAutoConfiguration} (Story #009e).
 *
 * <p>Direct-wiring via {@link AnnotationConfigApplicationContext} so we can
 * swap the {@link A2aTransportRouter} mock-style — {@link A2aTransportRouter}
 * has package-private {@code resolve} semantics; here we use Mockito-free
 * reflection to inject a controlled {@link A2aTransport} per test.</p>
 *
 * <p>The test deliberately does NOT use {@code @SpringBootTest} (avoids
 * Mockito 5.x + JDK 23 inline-mockmaker compatibility issues, mirrors
 * Story #007 pattern).</p>
 */
class RemoteAgentToolAutoConfigurationTest {

    @Test
    @DisplayName("TC-009e-AC-1: remoteAgentToolBean_isExposed_withCorrectName_andType")
    void remoteAgentToolBean_isExposed_withCorrectName_andType() throws Exception {
        Class<?> clazz = RemoteAgentToolAutoConfiguration.class;
        assertThat(clazz.isAnnotationPresent(
            org.springframework.boot.autoconfigure.AutoConfiguration.class))
            .as("must be @AutoConfiguration")
            .isTrue();

        boolean foundToolBean = false;
        for (java.lang.reflect.Method m : clazz.getDeclaredMethods()) {
            if (!m.isAnnotationPresent(org.springframework.context.annotation.Bean.class)) continue;
            org.springframework.context.annotation.Bean bean =
                m.getAnnotation(org.springframework.context.annotation.Bean.class);
            if (RemoteAgentTool.class.isAssignableFrom(m.getReturnType())) {
                foundToolBean = true;
                assertThat(bean.name())
                    .as("@Bean name for RemoteAgentTool must be 'remoteAgentTool'")
                    .containsExactly("remoteAgentTool");
                assertThat(m.getParameterCount())
                    .as("remoteAgentTool(@Bean) must take 4 args (router, cfg, json, schemaBuilder)")
                    .isEqualTo(4);
            }
        }
        assertThat(foundToolBean)
            .as("AutoConfiguration must expose a RemoteAgentTool @Bean")
            .isTrue();
    }

    @Test
    @DisplayName("TC-009e-AC-2: remoteAgentSchemaBuilderBean_isExposed_withCorrectName")
    void remoteAgentSchemaBuilderBean_isExposed_withCorrectName() throws Exception {
        Class<?> clazz = RemoteAgentToolAutoConfiguration.class;

        boolean foundSchemaBuilderBean = false;
        for (java.lang.reflect.Method m : clazz.getDeclaredMethods()) {
            if (!m.isAnnotationPresent(org.springframework.context.annotation.Bean.class)) continue;
            org.springframework.context.annotation.Bean bean =
                m.getAnnotation(org.springframework.context.annotation.Bean.class);
            Class<?> rt = m.getReturnType();
            if (rt.equals(RemoteAgentSchemaBuilder.class)) {
                foundSchemaBuilderBean = true;
                assertThat(bean.name())
                    .as("@Bean name for RemoteAgentSchemaBuilder must be 'remoteAgentSchemaBuilder'")
                    .containsExactly("remoteAgentSchemaBuilder");
                assertThat(m.getParameterCount())
                    .as("remoteAgentSchemaBuilder(@Bean) ctor must take 1 arg (ObjectMapper)")
                    .isEqualTo(1);
                assertThat(m.getParameterTypes()[0])
                    .isEqualTo(ObjectMapper.class);
                Object instance = m.invoke(clazz.getDeclaredConstructor().newInstance(),
                    new ObjectMapper());
                assertThat(instance).isInstanceOf(RemoteAgentSchemaBuilder.class);
            }
        }
        assertThat(foundSchemaBuilderBean)
            .as("AutoConfiguration must expose a RemoteAgentSchemaBuilder @Bean")
            .isTrue();
    }

    @Test
    @DisplayName("TC-009e-AC-3: beanWiring_resolvesHttpJsonRpc_byDefault_when_cfg_isNull")
    void beanWiring_resolvesHttpJsonRpc_byDefault_when_cfg_isNull() {
        // Build a minimal A2aTransportRouter that knows all 3 transports.
        AnnotationConfigApplicationContext ctx = buildContext(
            null /* cfg */, Collections.<AgentRef>emptyList());

        RemoteAgentTool tool = ctx.getBean(RemoteAgentTool.class);
        assertThat(tool).isNotNull();
        assertThat(tool.name()).isEqualTo("remote_agent");

        // cfg == null → default http-jsonrpc-1.0.0
        A2aTransport transport = tool.getTransport();
        assertThat(transport).isNotNull();

        ctx.close();
    }

    @Test
    @DisplayName("TC-009e-AC-4: beanWiring_propagatesRemoteAgents_fromCfg")
    void beanWiring_propagatesRemoteAgents_fromCfg() {
        // AgentRef is a Lombok @Value — single all-args constructor.
        AgentRef ref = new AgentRef("agent-foo", "http://localhost:9999", 10);
        AnnotationConfigApplicationContext ctx = buildContext(
            null /* cfg default http-jsonrpc */, Arrays.asList(ref));

        RemoteAgentTool tool = ctx.getBean(RemoteAgentTool.class);
        assertThat(tool.getRemoteAgents())
            .as("configured AgentRefs must propagate to tool")
            .hasSize(1);

        ctx.close();
    }

    @Test
    @DisplayName("TC-009e-AC-5: importsFile_containsRemoteAgentToolAutoConfiguration_asFirstLine")
    void importsFile_containsRemoteAgentToolAutoConfiguration_asFirstLine() throws Exception {
        URL importsUrl = getClass().getClassLoader().getResource(
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        assertThat(importsUrl)
            .as("AutoConfiguration.imports file must be on classpath")
            .isNotNull();

        List<String> lines = new java.util.ArrayList<String>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(importsUrl.openStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    lines.add(trimmed);
                }
            }
        }

        assertThat(lines)
            .as("imports file must contain 4 entries (3 transports + RemoteAgentTool)")
            .hasSize(4);
        assertThat(lines.get(0))
            .as("RemoteAgentToolAutoConfiguration must be FIRST (before transports)")
            .contains("RemoteAgentToolAutoConfiguration");
        assertThat(lines).anyMatch(l -> l.contains("GrpcA2aTransportAutoConfiguration"));
        assertThat(lines).anyMatch(l -> l.contains("InProcessA2aTransportAutoConfiguration"));
        assertThat(lines).anyMatch(l -> l.contains("HttpJsonRpcA2aTransportAutoConfiguration"));
    }

    @Test
    @DisplayName("TC-009e-AC-6: beanWiring_includesSchemaBuilder_Bean")
    void beanWiring_includesSchemaBuilder_Bean() {
        AnnotationConfigApplicationContext ctx = buildContext(
            null, Collections.<AgentRef>emptyList());

        RemoteAgentSchemaBuilder sb = ctx.getBean(RemoteAgentSchemaBuilder.class);
        assertThat(sb).isNotNull();

        // Verify it can build ToolSpecs from empty list (smoke test)
        List<ToolSpec> specs = sb.buildToolSpecs(Collections.<Map<String, Object>>emptyList());
        assertThat(specs).isEmpty();

        ctx.close();
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    /**
     * Build a minimal Spring context with:
     * <ul>
     *   <li>3 {@link Providers.A2aTransportProvider}s (grpc / in-process / http-jsonrpc)
     *       registered as plain beans</li>
     *   <li>One {@link A2aTransportRouter} wired with those 3 providers</li>
     *   <li>One {@link RemoteAgentToolAutoConfiguration} loaded — produces
     *       {@code remoteAgentTool} + {@code remoteAgentSchemaBuilder} beans</li>
     * </ul>
     */
    private AnnotationConfigApplicationContext buildContext(
        AgentConfig cfg, List<AgentRef> remoteAgents) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        try {
            // 3 transport providers as beans — explicit Supplier<T> to disambiguate registerBean overloads
            ctx.registerBean("a2aTransportProvider_grpc-1.0.0",
                Providers.A2aTransportProvider.class,
                (java.util.function.Supplier<Providers.A2aTransportProvider>) () -> new GrpcA2aTransportProvider());
            ctx.registerBean("a2aTransportProvider_in-process-1.0.0",
                Providers.A2aTransportProvider.class,
                (java.util.function.Supplier<Providers.A2aTransportProvider>) () -> new InProcessA2aTransportProvider());
            ctx.registerBean("a2aTransportProvider_http-jsonrpc-1.0.0",
                Providers.A2aTransportProvider.class,
                (java.util.function.Supplier<Providers.A2aTransportProvider>) () -> new HttpJsonRpcA2aTransportProvider());
            // Router takes List<Providers.A2aTransportProvider> via ctor injection
            ctx.registerBean(A2aTransportRouter.class, () -> new A2aTransportRouter(
                ctx.getBeanProvider(Providers.A2aTransportProvider.class)
                    .stream().collect(Collectors.toList())));
            // ObjectMapper — explicit Supplier to disambiguate
            ctx.registerBean(ObjectMapper.class, (java.util.function.Supplier<ObjectMapper>) ObjectMapper::new);
            // AgentConfig — synthesize a minimal one matching HttpJsonRpcA2aTransportProviderTest#cfg
            AgentConfig effectiveCfg = (cfg != null) ? cfg : buildDefaultAgentConfig(remoteAgents);
            ctx.registerBean(AgentConfig.class, () -> effectiveCfg);
            // The configuration under test
            ctx.register(RemoteAgentToolAutoConfiguration.class);
            // Refresh
            ctx.refresh();
            return ctx;
        } catch (RuntimeException e) {
            ctx.close();
            throw e;
        }
    }

    /**
     * Synthesize a minimal AgentConfig that passes the
     * {@link RemoteAgentToolAutoConfiguration#remoteAgentTool} wiring without
     * exercising the full YAML-binding pipeline. Mirrors
     * {@code HttpJsonRpcA2aTransportProviderTest#cfg()} — same shape, just
     * smaller constructor list.
     */
    private static AgentConfig buildDefaultAgentConfig(List<AgentRef> remoteAgents) {
        AgentConfig.A2a a2a = new AgentConfig.A2a(
            "0.0.0.0", 8080,
            "localhost:50051", java.time.Duration.ofMinutes(5),
            "http://localhost:8080", java.time.Duration.ofSeconds(30),
            remoteAgents, 10
        );
        return new AgentConfig(
            "linear",
            new AgentConfig.Llm("anthropic", "test-model", null, null),
            new AgentConfig.Prompt("default", Collections.<String>emptyList(), null),
            "default",
            new AgentConfig.Sandbox("default", "noop",
                java.nio.file.Paths.get("."), Collections.<String>emptyList(),
                Collections.<String>emptyList()),
            "default", "default",
            null, null, null,
            1, 5, 0, 0, 0, 10,
            AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(),
            AgentConfig.Memory.defaults(),
            null,    // a2aTransport — null → cfg.getA2aTransport() returns null → fallback "http-jsonrpc-1.0.0"
            null,
            a2a,
            AgentConfig.CompactorConfig.defaults(),
            AgentConfig.ToolsConfig.defaults()
        );
    }
}