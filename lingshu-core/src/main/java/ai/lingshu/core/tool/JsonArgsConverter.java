package ai.lingshu.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.lang.reflect.Parameter;
import java.util.Iterator;
import java.util.Map;

/**
 * JSON → Method args 转换器 (Story #022, dsh v1.5.40 §6.5 (3) L4918)。
 *
 * <p><b>职责</b> —— 把 LLM 在 ReAct Action 阶段发来的 JSON input({@link com.fasterxml.jackson.databind.JsonNode})
 * 按 method signature 转成 {@code Object[]} 给 {@link java.lang.reflect.Method#invoke} 用。
 *
 * <p><b>支持的类型映射</b>(对齐 dsh §6.5 (3) L4949-4955 + Story #022 spec §4 AC-NN-5):
 * <ul>
 *   <li>{@code String} ↔ JSON string</li>
 *   <li>{@code int / Integer} ↔ JSON integer</li>
 *   <li>{@code long / Long} ↔ JSON integer(> Integer.MAX_VALUE 自动升级 Long)</li>
 *   <li>{@code boolean / Boolean} ↔ JSON boolean</li>
 *   <li>{@code double / Double / float / Float} ↔ JSON number</li>
 *   <li>其他复杂类型 ({@code Object / Map / List / 自定义 POJO}) <b>未支持</b> →
 *       转 {@link IllegalArgumentException}(由 {@link SpringAiToolAdapter#execute}
 *       catch-all 收 → 转 {@code LINGS-T08} ErrorCode)</li>
 * </ul>
 *
 * <p><b>缺省行为</b>:
 * <ul>
 *   <li>JSON node 缺字段 + 对应 parameter primitive → 抛 {@code IllegalArgumentException}
 *       (因为 primitive 不能为 null;LangShu 用原始类型标记必传)</li>
 *   <li>JSON node 缺字段 + 对应 parameter boxed ({@code Integer} 等) → 传 {@code null}</li>
 *   <li>JSON node 缺字段 + 对应 parameter 有 default value (无法用反射读 default;JLS spec 不可达) →
 *       同 boxed 行为</li>
 * </ul>
 *
 * <p><b>不依赖 jackson-module-jsonSchema</b> —— 走简化路径,只覆盖 primitive + String,
 * 复杂类型返回 {@code IllegalArgumentException}(dsh §6.5 (3) L4930-4946 「简化:读参数类型 +
 * @ToolParam 描述」显式说明;OQ-Future 留给 jackson-module-jsonSchema 重型方案)。
 *
 * <p><b>JDK 8 兼容</b> — 静态方法 + Jackson 已锁依赖,无 {@code var} / {@code List.of}。
 *
 * @since 1.0.0
 */
public final class JsonArgsConverter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonArgsConverter() {
        // utility class
    }

    /**
     * JSON input → Method args 数组。
     *
     * <p>顺序与 {@code params} 一一对应;返回数组长度 = {@code params.length}。
     *
     * @param input  JSON args 节点(可能为 null,这时按缺省处理:primitive 抛异常 / boxed 传 null)
     * @param params  method 的 parameter 数组(顺序与 invoke 时对应)
     * @return        严格长度 == {@code params.length} 的 args 数组
     * @throws IllegalArgumentException 当 primitive 必传但 JSON 缺字段 / JSON 值类型与 parameter 类型不兼容
     *                                  / parameter 类型不在 supported 列表(复杂类型)
     */
    public static Object[] convert(JsonNode input, Parameter[] params) {
        if (params == null || params.length == 0) {
            return new Object[0];
        }
        JsonNode node = input != null ? input : JsonNodeFactory.instance.objectNode();
        Object[] out = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            Parameter p = params[i];
            String paramName = p.getName();
            JsonNode value = paramName != null && !paramName.isEmpty() ? node.get(paramName) : null;
            out[i] = coerce(value, p);
        }
        return out;
    }

    /**
     * 单值类型强制转换 —— 优先按 boxed / primitive 走 supported types,其余抛 {@link IllegalArgumentException}。
     */
    private static Object coerce(JsonNode value, Parameter p) {
        Class<?> type = p.getType();
        boolean isPrimitive = type.isPrimitive();

        // 缺字段语义:primitive 报错,boxed / 对象 传 null
        if (value == null || value.isNull()) {
            if (isPrimitive) {
                throw new IllegalArgumentException(
                    "Required parameter '" + p.getName() + "' (" + type.getName() + ") is missing");
            }
            return null;
        }

        if (type == String.class) {
            return value.asText();
        }
        if (type == int.class || type == Integer.class) {
            if (!value.canConvertToInt()) {
                throw new IllegalArgumentException(
                    "Parameter '" + p.getName() + "' expected int, got " + value.getNodeType());
            }
            return value.asInt();
        }
        if (type == long.class || type == Long.class) {
            if (!value.canConvertToLong()) {
                throw new IllegalArgumentException(
                    "Parameter '" + p.getName() + "' expected long, got " + value.getNodeType());
            }
            return value.asLong();
        }
        if (type == boolean.class || type == Boolean.class) {
            if (!value.isBoolean()) {
                throw new IllegalArgumentException(
                    "Parameter '" + p.getName() + "' expected boolean, got " + value.getNodeType());
            }
            return value.asBoolean();
        }
        if (type == double.class || type == Double.class
                || type == float.class || type == Float.class) {
            if (!value.isNumber()) {
                throw new IllegalArgumentException(
                    "Parameter '" + p.getName() + "' expected number, got " + value.getNodeType());
            }
            double d = value.asDouble();
            if (type == float.class || type == Float.class) {
                return (float) d;
            }
            return d;
        }

        // 复杂类型(Object / Map / List / 自定义 POJO)— 当前未支持,抛 IllegalArgumentException
        // 留 OQ-Future 给 jackson-module-jsonSchema 重型方案(Story 进入 prompt cache 阶段再补)
        throw new IllegalArgumentException(
            "Unsupported parameter type: " + type.getName()
                + " for parameter '" + p.getName()
                + "' — only String / int / long / boolean / double / float + boxed variants are supported in Story #022");
    }

    /** 测试用 fixture —— 暴露共享 {@link ObjectMapper} 以便构造 JsonNode 用例。 */
    static ObjectMapper sharedMapper() {
        return MAPPER;
    }

    /**
     * 测试用 fixture —— 走 convertible 路径(typesafe 一次性 dispatch)。
     */
    @SuppressWarnings("unused")
    private static void assertJsonShapeSanity() {
        // sanity guard — ensure Jackson classpath is present at runtime
        JsonNodeFactory.instance.objectNode();
    }

    /**
     * 测试用 fixture —— 让 caller 校验给定 {@link JsonNode} 含所有 params name(用于 caller 选 inform caller 错).
     *
     * <p>不抛异常,只返回 boolean —— caller 据此决定要不要再 trigger {@link #convert}。
     */
    public static boolean hasAllRequiredFields(JsonNode input, Parameter[] params) {
        if (input == null) return false;
        for (Parameter p : params) {
            if (!p.getType().isPrimitive()) continue;
            String n = p.getName();
            if (n == null || n.isEmpty()) continue;
            if (input.get(n) == null) return false;
        }
        return true;
    }

    // ────────────────────────────────────────────────────────────────────
    //  Internal helpers (testing seam — package-private)
    // ────────────────────────────────────────────────────────────────────

    /**
     * 内部 helper —— JSON object 字段遍历用于单元测试。
     */
    static Iterator<Map.Entry<String, JsonNode>> fieldIterator(JsonNode node) {
        return node != null ? node.fields() : null;
    }
}
