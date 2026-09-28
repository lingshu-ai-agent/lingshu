package ai.lingshu.examples.demoproduct;

import ai.lingshu.core.tool.AgentTool;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Demo-product {@code @AgentTool} methods (Story #025) — 4 reflection-discovered
 * tools wrapped by {@link ai.lingshu.core.tool.SpringAiToolAdapter}:
 * {@code time}, {@code calc}, {@code random}, {@code uuid}.
 *
 * <p>Pattern copied verbatim from {@code demo-delegate/AgentToolFixture.java}.
 * The {@link ai.lingshu.core.tool.AgentToolScanner} picks these up on
 * {@code ContextRefreshedEvent} and registers them in the shared
 * {@link ai.lingshu.core.slot.ToolRegistry}.
 *
 * <p>For the demo we keep the implementations intentionally simple — production
 * tools would route through external services.
 */
@Component
public class ProductAgentTools {

    @AgentTool(name = "time", description = "Return the current time as an ISO-8601 string.")
    public String time() {
        return Instant.now().toString();
    }

    /**
     * Arithmetic expression evaluator — handles +,-,*,/,% with integer literals.
     * Single-pass shunting-yard, no precedence tree (precedence via operator weights).
     */
    @AgentTool(name = "calc",
               description = "Evaluate an arithmetic expression. Supports +,-,*,/,%, parens, integers.")
    public String calc(String expr) {
        try {
            return String.valueOf(evaluate(expr.replace(" ", "")));
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    @AgentTool(name = "random",
               description = "Return a random integer in the inclusive range [min, max].")
    public int random(int min, int max) {
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    @AgentTool(name = "uuid", description = "Generate a random UUID (RFC 4122).")
    public String uuid() {
        return UUID.randomUUID().toString();
    }

    // ── Tiny shunting-yard expression evaluator (no precedence tree) ───────

    private static long evaluate(String expr) {
        java.util.Deque<Long> nums = new java.util.ArrayDeque<>();
        java.util.Deque<Character> ops = new java.util.ArrayDeque<>();
        int i = 0;
        while (i < expr.length()) {
            char c = expr.charAt(i);
            if (Character.isDigit(c)) {
                long n = 0;
                while (i < expr.length() && Character.isDigit(expr.charAt(i))) {
                    n = n * 10 + (expr.charAt(i) - '0');
                    i++;
                }
                nums.push(n);
                continue;
            } else if (c == '(') {
                ops.push(c);
            } else if (c == ')') {
                while (ops.peek() != null && ops.peek() != '(') {
                    apply(nums, ops.pop());
                }
                ops.pop(); // pop '('
            } else if (c == '+' || c == '-' || c == '*' || c == '/' || c == '%') {
                while (ops.peek() != null && ops.peek() != '('
                       && precedence(ops.peek()) >= precedence(c)) {
                    apply(nums, ops.pop());
                }
                ops.push(c);
            }
            i++;
        }
        while (ops.peek() != null) {
            apply(nums, ops.pop());
        }
        return nums.pop();
    }

    private static int precedence(char op) {
        if (op == '+' || op == '-') return 1;
        return 2;
    }

    private static void apply(java.util.Deque<Long> nums, char op) {
        long b = nums.pop(), a = nums.pop();
        switch (op) {
            case '+': nums.push(a + b); break;
            case '-': nums.push(a - b); break;
            case '*': nums.push(a * b); break;
            case '/': nums.push(a / b); break;
            case '%': nums.push(a % b); break;
            default: throw new IllegalStateException("unknown op: " + op);
        }
    }
}