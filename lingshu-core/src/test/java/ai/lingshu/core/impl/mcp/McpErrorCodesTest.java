package ai.lingshu.core.impl.mcp;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link McpErrorCodes} (Story #021b, T-08).
 *
 * <p>Covers three invariants:
 * <ol>
 *   <li>Both constants hold the canonical {@code LINGS-Mxx} string values.</li>
 *   <li>The class is non-instantiable (utility-class idiom).</li>
 *   <li>The private constructor is final-class-shape and throws {@link AssertionError}.</li>
 * </ol>
 */
class McpErrorCodesTest {

    @Test
    void assertM01Value() {
        assertEquals("LINGS-M01", McpErrorCodes.LINGS_M01,
            "LINGS_M01 must hold the MCP_CONNECT_FAILED code introduced in Story #021a");
    }

    @Test
    void assertM02Value() {
        assertEquals("LINGS-M02", McpErrorCodes.LINGS_M02,
            "LINGS_M02 must hold the MCP_TOOL_CALL_FAILED code introduced in Story #021b");
    }

    @Test
    void assertClassIsFinal() throws NoSuchMethodException {
        // Utility class — must be final so subclasses cannot break the constants contract.
        int mods = McpErrorCodes.class.getModifiers();
        assertTrue(Modifier.isFinal(mods),
            "McpErrorCodes must be final (utility-class idiom)");
    }

    @Test
    void assertPrivateConstructorRejected() throws NoSuchMethodException {
        Constructor<McpErrorCodes> ctor = McpErrorCodes.class.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(ctor.getModifiers()),
            "Constructor must be private");
        ctor.setAccessible(true);
        // The ctor body throws AssertionError; reflection wraps it in InvocationTargetException.
        InvocationTargetException ex = assertThrows(InvocationTargetException.class, ctor::newInstance);
        assertEquals(AssertionError.class, ex.getTargetException().getClass(),
            "Private ctor must throw AssertionError");
    }
}