package ai.lingshu.examples.demoproducta2aserver;

import ai.lingshu.core.tool.AgentTool;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Story #025b — single {@code @AgentTool} backed skill that this demo exposes
 * over A2A as {@code "translate"}.
 *
 * <p>When {@link DemoA2aServer} receives a {@code message/send} RPC whose
 * {@code params.skill == "translate"}, it looks the name up in
 * {@code ToolRegistry} (which {@code AgentToolScanner} populated during
 * {@code ContextRefreshedEvent} — Story #022) and dispatches via
 * {@code Tool.execute}.
 *
 * <p><b>Why a dictionary stub</b> — this demo proves end-to-end wire
 * (tool-call across JVM via A2A, schema generation, result propagation back
 * to the calling agent). A real translation backend would call an LLM or a
 * translation API; for the demo, returning canned phrases is enough to show
 * the chain works and lets the demo run without an external API key.
 *
 * <p><b>Why camelCase parameter names</b> — {@link TranslateTools}'s
 * module pom enables {@code -parameters} so {@code SpringAiToolAdapter}
 * (Story #022) reads {@code "text"} and {@code "targetLang"} instead of
 * {@code arg0} / {@code arg1}. The LLM prompt builder surfaces the schema
 * to the model with these exact field names, and the model uses them in
 * its tool call.
 *
 * <p><b>Schema generation contract</b>:
 * <pre>{@code
 * {
 *   "type": "object",
 *   "properties": {
 *     "text":       {"type": "string"},
 *     "targetLang": {"type": "string"}
 *   },
 *   "required": []
 * }
 * }</pre>
 * Both fields are boxed {@code String} — not primitive — so neither ends up
 * in {@code required[]}. The {@code translate} method tolerates either field
 * being missing (uses {@code null} / blank defaults), so the LLM can call
 * with just {@code {"text": "..."}} to translate to the default language.
 */
@Component
public class TranslateTools {

    /**
     * Default target language when the caller omits {@code targetLang}.
     * Picking English as the default rather than auto-detect keeps the
     * stub deterministic for curl smoke tests.
     */
    private static final String DEFAULT_TARGET_LANG = "en";

    /**
     * The single {@code @AgentTool} exposed by this agent. The skill id
     * surfaced in {@code AgentCard.skills[].id} MUST match
     * {@link AgentTool#name()} — {@link DemoA2aServer.AgentCardBuilder}
     * enforces that contract by keying the skill map on {@code tool.name()}.
     *
     * @param text       the text to translate (free-form; non-null recommended)
     * @param targetLang ISO 639-1 code (e.g. {@code "en"}, {@code "es"},
     *                   {@code "zh"}); falls back to {@link #DEFAULT_TARGET_LANG}
     *                   when null / blank
     * @return the translated text plus a metadata block (so the caller
     *         sees both the answer and what the agent inferred as the target)
     */
    @AgentTool(
        name = "translate",
        description = "Translate short text into the target language. "
            + "Args: {\"text\": \"...\", \"targetLang\": \"<iso-639-1>\"}. "
            + "Supported: en, es, zh, ja, fr, de. Default target: en.")
    public TranslateResult translate(String text, String targetLang) {
        String effectiveTarget = (targetLang == null || targetLang.trim().isEmpty())
            ? DEFAULT_TARGET_LANG
            : targetLang.trim().toLowerCase(Locale.ROOT);

        if (text == null) {
            text = "";
        }

        String translated = doTranslate(text, effectiveTarget);

        // Return a structured object rather than a bare string so the
        // calling LLM sees the resolved target alongside the answer.
        // SpringAiToolAdapter.serialize() (Story #022) calls Objects.toString
        // on the return value — we toString ourselves here.
        TranslateResult out = new TranslateResult();
        out.sourceText = text;
        out.targetLang = effectiveTarget;
        out.translated = translated;
        return out;
    }

    // ── Tiny canned-phrase dictionary stub ──────────────────────────────
    //
    // Each map key = source text (case-insensitive); value = translated form.
    // Mismatches fall through to a "no-op echo with [LANG] prefix" so the
    // demo still shows *something* happening end-to-end.

    private static final Map<String, Map<String, String>> DICT = buildDict();

    private static Map<String, Map<String, String>> buildDict() {
        Map<String, Map<String, String>> d = new HashMap<String, Map<String, String>>();
        d.put("hello", map(
            "es", "hola",
            "zh", "你好",
            "ja", "こんにちは",
            "fr", "bonjour",
            "de", "hallo"));
        d.put("thank you", map(
            "es", "gracias",
            "zh", "谢谢",
            "ja", "ありがとう",
            "fr", "merci",
            "de", "danke"));
        d.put("goodbye", map(
            "es", "adiós",
            "zh", "再见",
            "ja", "さようなら",
            "fr", "au revoir",
            "de", "auf wiedersehen"));
        d.put("yes", map(
            "es", "sí",
            "zh", "是",
            "ja", "はい",
            "fr", "oui",
            "de", "ja"));
        d.put("no", map(
            "es", "no",
            "zh", "不",
            "ja", "いいえ",
            "fr", "non",
            "de", "nein"));
        return d;
    }

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new HashMap<String, String>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String doTranslate(String text, String targetLang) {
        if (text.isEmpty()) {
            return "";
        }
        String key = text.trim().toLowerCase(Locale.ROOT);
        Map<String, String> row = DICT.get(key);
        if (row != null) {
            String hit = row.get(targetLang);
            if (hit != null) {
                return hit;
            }
        }
        // Fallback — clearly mark so the caller sees "I had no entry for this".
        return "[" + targetLang + "] " + text;
    }

    /**
     * Public so {@code Objects.toString(result)} renders both fields
     * via Jackson's bean discovery. {@code SpringAiToolAdapter} serializes
     * the return value with {@code Objects.toString(result, "")}, which
     * calls this method.
     */
    public static class TranslateResult {
        public String sourceText;
        public String targetLang;
        public String translated;

        @Override
        public String toString() {
            return "{\"sourceText\":\"" + escape(sourceText)
                + "\",\"targetLang\":\"" + escape(targetLang)
                + "\",\"translated\":\"" + escape(translated) + "\"}";
        }

        private static String escape(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
