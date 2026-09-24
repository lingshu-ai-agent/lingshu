package ai.lingshu.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #022 — L1 Unit tests for {@link JsonArgsConverter}.
 *
 * <p>覆盖 Story #022 spec §4 AC-NN-5 (primitive 类型转换表) + 反向 AC 复杂类型 / null 行为。
 *
 * <p><b>JDK 8 兼容</b> — 测试用 plain JUnit 5 + AssertJ,无 {@code var} / {@code List.of}。
 */
class JsonArgsConverterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Method METHOD_TWO_INT;
    private static Method METHOD_STRING_INT_BOOL;
    private static Method METHOD_ALL_PRIMITIVES;
    private static Method METHOD_BOXED;
    private static Method METHOD_OBJECT_PARAM;  // 反向 AC:复杂类型 → IAE

    @BeforeAll
    static void setUp() throws Exception {
        METHOD_TWO_INT = SampleBean.class.getMethod("add", int.class, int.class);
        METHOD_STRING_INT_BOOL = SampleBean.class.getMethod("format", String.class, int.class, boolean.class);
        METHOD_ALL_PRIMITIVES = SampleBean.class.getMethod("kitchenSink",
            String.class, int.class, long.class, boolean.class, double.class);
        METHOD_BOXED = SampleBean.class.getMethod("boxedOne", Integer.class);
        METHOD_OBJECT_PARAM = SampleBean.class.getMethod("complexType", Object.class);
    }

    // ──────────────────────────────────────────────────────────────────────
    //  AC-NN-5 happy-path: primitive 类型转换
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-022-1: convert_twoInts_returnsNativeIntArgs")
    void convert_twoInts_returnsNativeIntArgs() throws Exception {
        ObjectNode in = MAPPER.createObjectNode();
        in.put("a", 2);
        in.put("b", 3);

        Object[] args = JsonArgsConverter.convert(in, METHOD_TWO_INT.getParameters());

        assertThat(args).hasSize(2);
        assertThat(args[0]).isEqualTo(2);
        assertThat(args[0].getClass()).isEqualTo(Integer.class);  // valueOf returns Integer
        assertThat(args[1]).isEqualTo(3);
    }

    @Test
    @DisplayName("AC-022-2: convert_mixedStringIntBool_returnsAllSet")
    void convert_mixedStringIntBool_returnsAllSet() throws Exception {
        ObjectNode in = MAPPER.createObjectNode();
        in.put("prefix", "hi");
        in.put("count", 7);
        in.put("upper", true);

        Object[] args = JsonArgsConverter.convert(in, METHOD_STRING_INT_BOOL.getParameters());

        assertThat(args).hasSize(3);
        assertThat(args[0]).isEqualTo("hi");
        assertThat(args[1]).isEqualTo(7);
        assertThat(args[2]).isEqualTo(true);
    }

    @Test
    @DisplayName("AC-022-3: convert_allFivePrimitives_returnsBoxedNative")
    void convert_allFivePrimitives_returnsBoxedNative() throws Exception {
        ObjectNode in = MAPPER.createObjectNode();
        in.put("prefix", "x");
        in.put("count", 1);
        in.put("big", 9_999_999_999L);
        in.put("flag", false);
        in.put("ratio", 3.14);

        Object[] args = JsonArgsConverter.convert(in, METHOD_ALL_PRIMITIVES.getParameters());

        assertThat(args).hasSize(5);
        assertThat(args[0]).isEqualTo("x");
        assertThat(args[1]).isEqualTo(1);
        assertThat(args[2]).isEqualTo(9_999_999_999L);
        assertThat(args[3]).isEqualTo(false);
        assertThat(args[4]).isEqualTo(3.14);
    }

    @Test
    @DisplayName("AC-022-4: convert_longCanOverflow_whenValueIsTooBig")
    void convert_longCanOverflow_whenValueIsTooBig() throws Exception {
        ObjectNode in = MAPPER.createObjectNode();
        in.put("prefix", "x");
        in.put("count", 1);
        // Integer.MAX_VALUE + 1 → auto升级 Long
        in.put("big", (long) Integer.MAX_VALUE + 1L);
        in.put("flag", false);
        in.put("ratio", 3.14);

        Object[] args = JsonArgsConverter.convert(in, METHOD_ALL_PRIMITIVES.getParameters());
        assertThat(args[2]).isEqualTo((long) Integer.MAX_VALUE + 1L);
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Failure paths: 类型不兼容 / 缺字段 primitive / 复杂类型
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-022-5: convert_primitiveMissing_throwsIllegalArgumentException")
    void convert_primitiveMissing_throwsIllegalArgumentException() throws Exception {
        // METHOD_TWO_INT 的 a / b 都是 primitive,缺 a 应该 IAE
        ObjectNode in = MAPPER.createObjectNode();
        in.put("b", 3);
        // a 缺
        Parameter[] params = METHOD_TWO_INT.getParameters();
        assertThatThrownBy(() -> JsonArgsConverter.convert(in, params))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Required parameter 'a'");
    }

    @Test
    @DisplayName("AC-022-6: convert_typeMismatch_throwsIllegalArgumentException")
    void convert_typeMismatch_throwsIllegalArgumentException() throws Exception {
        // a 是 int 但 JSON 给了 string
        ObjectNode in = MAPPER.createObjectNode();
        in.put("a", "not_a_number");
        in.put("b", 3);
        Parameter[] params = METHOD_TWO_INT.getParameters();
        assertThatThrownBy(() -> JsonArgsConverter.convert(in, params))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("expected int");
    }

    @Test
    @DisplayName("AC-022-7: convert_boxedMissing_returnsNull")
    void convert_boxedMissing_returnsNull() throws Exception {
        // Integer boxed 允许 null(JSON 缺字段 → null)
        ObjectNode in = MAPPER.createObjectNode();
        // value 缺
        Object[] args = JsonArgsConverter.convert(in, METHOD_BOXED.getParameters());
        assertThat(args).hasSize(1);
        assertThat(args[0]).isNull();
    }

    @Test
    @DisplayName("AC-022-8: convert_complexObjectType_throwsIllegalArgumentException")
    void convert_complexObjectType_throwsIllegalArgumentException() throws Exception {
        // 复杂类型(Object)当前未支持 → IAE
        ObjectNode in = MAPPER.createObjectNode();
        in.put("any", "anything");
        Parameter[] params = METHOD_OBJECT_PARAM.getParameters();
        assertThatThrownBy(() -> JsonArgsConverter.convert(in, params))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported parameter type");
    }

    // ──────────────────────────────────────────────────────────────────────
    //  边界场景
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-022-9: convert_nullInput_primitiveFails_boxedNull")
    void convert_nullInput_primitiveFails_boxedNull() throws Exception {
        // null input — primitive 必传字段抛 IAE,boxed 传 null
        assertThatThrownBy(() -> JsonArgsConverter.convert(null, METHOD_TWO_INT.getParameters()))
            .isInstanceOf(IllegalArgumentException.class);
        Object[] boxedArgs = JsonArgsConverter.convert(null, METHOD_BOXED.getParameters());
        assertThat(boxedArgs[0]).isNull();
    }

    @Test
    @DisplayName("AC-022-10: convert_emptyParams_returnsEmptyArray")
    void convert_emptyParams_returnsEmptyArray() throws Exception {
        Object[] args = JsonArgsConverter.convert(MAPPER.createObjectNode(), new Parameter[0]);
        assertThat(args).isEmpty();
    }

    @Test
    @DisplayName("AC-022-11: convert_jsonNullValue_forPrimitiveFails")
    void convert_jsonNullValue_forPrimitiveFails() throws Exception {
        // {"a": null, "b": 3} — a 是 primitive,JSON null 视为缺字段 → IAE
        ObjectNode in = MAPPER.createObjectNode();
        in.putNull("a");
        in.put("b", 3);
        Parameter[] params = METHOD_TWO_INT.getParameters();
        assertThatThrownBy(() -> JsonArgsConverter.convert(in, params))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("AC-022-12: convert_floatAndDouble_bothAsDoubleThenCastForFloat")
    void convert_floatAndDouble_bothAsDoubleThenCastForFloat() throws Exception {
        // float / Float 走 asDouble 然后窄化
        Method mFloat = SampleBean.class.getMethod("floatOne", float.class);
        ObjectNode in = MAPPER.createObjectNode();
        in.put("value", 1.5);
        Object[] args = JsonArgsConverter.convert(in, mFloat.getParameters());
        assertThat(args[0]).isEqualTo(1.5f);
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Test fixture
    // ──────────────────────────────────────────────────────────────────────

    /** Sample 测试 bean —— 给 reflection 拿 method 用。 */
    @SuppressWarnings("unused")
    public static class SampleBean {
        public int add(int a, int b) { return a + b; }
        public String format(String prefix, int count, boolean upper) {
            return prefix + count + (upper ? "!" : "");
        }
        public String kitchenSink(String prefix, int count, long big, boolean flag, double ratio) {
            return prefix + count + big + flag + ratio;
        }
        public Integer boxedOne(Integer value) { return value; }
        public Object complexType(Object any) { return any; }
        public float floatOne(float value) { return value; }
    }

    /** Sanity: 防御 `assertJsonShapeSanity` 触发错误。 */
    @Test
    @DisplayName("AC-022-99: sanityCheck_objectNodeFactoryAvailable")
    void sanityCheck_objectNodeFactoryAvailable() {
        JsonNode n = JsonNodeFactory.instance.objectNode();
        assertThat(n).isNotNull();
        assertThat(n.isObject()).isTrue();
    }
}
