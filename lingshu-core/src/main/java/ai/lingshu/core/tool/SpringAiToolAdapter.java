package ai.lingshu.core.tool;

import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.slot.Tool;
import ai.lingshu.core.slot.ToolExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.Objects;

/**
 * SpringAI 风格 {@code @AgentTool} 注解 method 的反射适配器 (Story #022, dsh v1.5.40 §6.5 (3) L4896-4956)。
 *
 * <p><b>职责</b> —— 把 {@code @Component} Bean 上挂 {@link AgentTool} 注解的 method
 * 包装成 LingShu {@link Tool} 接口。该 method 仍属于业务方(被反射回调),但 ToolRegistry 视角下
 * 它与 #019 落地的 4 个 hand-written tool 等价。
 *
 * <p><b>三步执行</b>(对齐 dsh §6.5 (3) L4916-4928):
 * <ol>
 *   <li>{@link JsonArgsConverter#convert} — JSON input → {@code Object[] args}</li>
 *   <li>{@code method.invoke(bean, args)} — 反射调业务方法</li>
 *   <li>wrap result / catch exception — 转 {@link ToolResult#success} 或 {@link ToolResult#error}</li>
 * </ol>
 *
 * <p><b>错误语义</b>(对齐 §4.10.1 硬规则 2)——
 * <ul>
 *   <li>{@link InvocationTargetException} 包裹的业务异常 → catch + 转 {@link ToolResult#error}
 *       嵌入 {@code [LINGS-T08]} ErrorCode 编码(对齐 #021b {@code McpToolAdapter.execute()}
 *       同款模式;{@code SpringAiToolAdapter} 内部**不**抛异常到 caller)</li>
 *   <li>catch-all {@code Exception} → 同样转 {@code ToolResult.error} + LINGS-T08 编码
 *       覆盖 {@code IllegalArgumentException}({@link JsonArgsConverter} 内不匹配类型抛)+
 *       {@code IllegalAccessException}(非 public method)+ 其他反射异常</li>
 *   <li>正常返回 — {@code Objects.toString(result, "")} 转 string 给 LLM</li>
 * </ul>
 *
 * <p><b>Schema 生成</b>(对齐 dsh §6.5 (3) L4930-4946)——
 * <pre>{@code
 * {
 *   "type": "object",
 *   "properties": {
 *     "a": {"type": "integer"},
 *     "b": {"type": "boolean"}
 *   },
 *   "required": ["a", "b"]
 * }
 * }</pre>
 *
 * <p>{@code required} 字段由 primitive parameter 推导(primitive 不能 null → 必传);
 * boxed parameter 与复杂类型不进入 required。
 *
 * <p><b>与 §4.10.1 硬规则 2 兼容</b> —— {@code execute()} 永远只返回 {@link ToolResult}
 * (不抛异常),{@link ai.lingshu.core.slot.ToolExecutor#dispatch} 走的 5 步流水线
 * (permission → registry → timeout → sandbox → execute → checkpoint)不绕过任何一步。
 *
 * <p><b>JDK 8 兼容</b> — 经典 POJO,无 {@code record} / sealed / var;{@code method.setAccessible(true)}
 * 在构造期调一次,允许业务 method 是 non-public。
 *
 * <p><b>🆕 Story #022 — 关键不变项</b>:
 * <ul>
 *   <li>{@link Tool} interface 0 改动</li>
 *   <li>{@link ai.lingshu.core.slot.ToolRegistry} 0 改动</li>
 *   <li>{@link ai.lingshu.core.slot.ToolExecutor#dispatch} 5 步流水线 0 改动</li>
 *   <li>{@code ApplicationContextAware} 回调时由 {@link AgentToolScanner} 自动注册
 *       (<b>不</b>引 {@code SmartLifecycle};{@code @Component} Bean 与 JVM 同生命周期)</li>
 * </ul>
 *
 * @since 1.0.0
 */
public class SpringAiToolAdapter implements Tool {

    private static final Logger LOG = LoggerFactory.getLogger(SpringAiToolAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Object bean;
    private final Method method;
    private final AgentTool annotation;
    private final JsonNode inputSchema;
    /** 缓存 {@link Parameter} 数组,避免 invoke 路径重复反射拿。 */
    private final Parameter[] parameters;

    /**
     * 构造反射适配器,在 Spring ApplicationContext 刷新阶段由 {@link AgentToolScanner} 调一次。
     *
     * <p>立即做 3 件事:(1) 让业务 method 可调用 (setAccessible); (2) 生成 JSON Schema 缓存;
     * (3) 缓存 parameter 数组供 invoke 路径复用。
     *
     * @param bean       业务对象({@code @Component} Bean 实例,非 null)
     * @param method     业务 method(必须在 {@code bean.getClass()} 反射可见)
     * @param annotation method 上的 {@link AgentTool} 注解(决定 {@code name}/{@code description})
     */
    public SpringAiToolAdapter(Object bean, Method method, AgentTool annotation) {
        if (bean == null) {
            throw new IllegalArgumentException("bean must not be null");
        }
        if (method == null) {
            throw new IllegalArgumentException("method must not be null");
        }
        if (annotation == null) {
            throw new IllegalArgumentException("annotation must not be null");
        }
        this.bean = bean;
        this.method = method;
        this.annotation = annotation;
        this.parameters = method.getParameters();
        // 让 private / package-private method 也能 invoke —— Story #022 设计意图允许业务方法非 public
        if (!Modifier.isPublic(method.getModifiers())) {
            method.setAccessible(true);
        }
        this.inputSchema = generateSchemaFromMethod(method);
    }

    @Override
    public String name() {
        return annotation.name();
    }

    @Override
    public String description() {
        return annotation.description();
    }

    @Override
    public JsonNode inputSchema() {
        return inputSchema;
    }

    /**
     * 反射调业务 method(JSON → args → {@code method.invoke})。
     *
     * <p><b>任何异常都不外抛</b>(对齐 §4.10.1 硬规则 2)——
     * 业务异常 / 反射异常一律 catch + 转 {@link ToolResult#error} + 嵌入 {@code [LINGS-T08]} 编码。
     * 正常返回走 {@link ToolResult#success} + {@code Objects.toString(result)} 转 LLM 可见 string。
     *
     * <p>{@link ToolExecutionContext} 当前<b>不</b>消费(@{@code capabilities()} 字段也保留未消费),
     * 但保留入参以匹配 {@link Tool#execute} 契约。
     */
    @Override
    public ToolResult execute(ToolCall call, ToolExecutionContext ctx) {
        if (call == null) {
            return ToolResult.builder()
                .status(ToolResult.Status.ERROR)
                .toolUseId(null)
                .content("[" + ToolErrorCodes.LINGS_T08 + "] null ToolCall")
                .isError(true)
                .build();
        }
        String callId = call.getId();
        try {
            Object[] args = JsonArgsConverter.convert(call.getInput(), parameters);
            Object result = method.invoke(bean, args);
            String content = Objects.toString(result, "");
            return ToolResult.builder()
                .status(ToolResult.Status.SUCCESS)
                .toolUseId(callId)
                .content(content)
                .isError(false)
                .build();
        } catch (InvocationTargetException ite) {
            // 业务方法抛的异常被反射包成 InvocationTargetException —— 解包到根因给 LLM
            Throwable cause = ite.getCause();
            String msg = cause != null
                ? cause.getClass().getSimpleName() + ": " + cause.getMessage()
                : ite.getClass().getSimpleName() + ": " + ite.getMessage();
            LOG.warn("[AgentTool:{}] reflection invocation failed: {}", annotation.name(), msg);
            return ToolResult.builder()
                .status(ToolResult.Status.ERROR)
                .toolUseId(callId)
                .content("[" + ToolErrorCodes.LINGS_T08 + "] tool invocation failed: " + msg)
                .isError(true)
                .build();
        } catch (Exception e) {
            // catch-all —— 覆盖 IllegalArgumentException (参数类型不兼容) / IllegalAccessException (非 public) / 其他
            String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
            LOG.warn("[AgentTool:{}] unexpected reflection error: {}", annotation.name(), msg);
            return ToolResult.builder()
                .status(ToolResult.Status.ERROR)
                .toolUseId(callId)
                .content("[" + ToolErrorCodes.LINGS_T08 + "] tool invocation failed: " + msg)
                .isError(true)
                .build();
        }
    }

    /**
     * 生成 method 的 JSON Schema(简化路径,仅覆盖 primitive + String)。
     *
     * <p><b>简化路径</b>(dsh §6.5 (3) L4930-4946)——
     * 不引 {@code jackson-module-jsonSchema};直接走 Jackson {@link ObjectMapper} + reflection
     * 读参数名(Java 8 -parameters 编译选项 / 反射降级到 {@code argN})。
     *
     * <p>{@code required} 数组由 primitive parameter 决定(primitive 不能 null 即必传)。
     * Boxed 与复杂类型不进入 required。
     *
     * @param method 业务 method(已经在构造期 setAccessible)
     * @return 不可变 JSON Schema 节点(Jackson ObjectNode 是可变容器但 adapter 视为只读)
     */
    private static JsonNode generateSchemaFromMethod(Method method) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        ArrayNode required = MAPPER.createArrayNode();
        Parameter[] params = method.getParameters();
        for (Parameter p : params) {
            String name = p.getName();
            if (name == null || name.isEmpty()) {
                // Java 8 编译默认擦除参数名 → 落到 arg0 / arg1 / ... 这种占位
                // 业务方建议加 -parameters 编译选项,这里兜底给个可读名
                name = "arg" + paramOrdinal(params, p);
            }
            ObjectNode s = props.putObject(name);
            s.put("type", jsonTypeOf(p.getType()));
            if (p.getType().isPrimitive()) {
                required.add(name);
            }
        }
        root.set("required", required);
        // 深拷贝一次,防 caller 篡改内部缓存
        return root.deepCopy();
    }

    /**
     * primitive / String 类型映射(JSON Schema atomic type)。
     *
     * <p>复杂类型(Object / Map / List / 自定义 POJO)走 {@link JsonArgsConverter} 路径
     * 时会抛 {@code IllegalArgumentException} → 适配器 catch → LINGS-T08。Schema 生成侧
     * 对复杂类型返回 {@code "object"} placeholder(LLM 端 schema validator 会
     * 给 {@code additionalProperties: true} fallback —— {@code #009d} OQ-Future 优化点)。
     */
    private static String jsonTypeOf(Class<?> c) {
        if (c == String.class) return "string";
        if (c == Integer.class || c == int.class) return "integer";
        if (c == Long.class || c == long.class) return "integer";
        if (c == Boolean.class || c == boolean.class) return "boolean";
        if (c == Double.class || c == double.class
                || c == Float.class || c == float.class) return "number";
        return "object";
    }

    /** 给 param 找不到名字时算 ordinal 1-based(Java 8 默认擦除参数名的兜底)。 */
    private static int paramOrdinal(Parameter[] params, Parameter target) {
        for (int i = 0; i < params.length; i++) {
            if (params[i] == target) return i;
        }
        return 0;
    }
}
