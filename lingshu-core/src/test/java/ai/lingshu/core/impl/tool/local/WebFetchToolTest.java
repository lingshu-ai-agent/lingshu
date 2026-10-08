/*
 * Copyright 2026 The LingShu Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.lingshu.core.impl.tool.local;

import ai.lingshu.core.impl.sandbox.WhitelistedHttpClient;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.slot.AccessDeniedException;
import ai.lingshu.core.slot.ToolCallConfig;
import ai.lingshu.core.slot.ToolExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Story #032 — L1 unit tests for {@link WebFetchTool}.
 *
 * <p>8 cases cover the metadata contract (name / description / inputSchema /
 * sourceCategory) plus four {@code execute()} paths:
 * <ul>
 *   <li>AC-NN-7 (reversed) — empty domain-whitelist {@link WhitelistedHttpClient}
 *       triggers {@link AccessDeniedException} which is translated to
 *       {@code ToolResult.error("[LINGS-S01] Domain not whitelisted: ...")}</li>
 *   <li>AC-NN-10 — default 1 MB truncation marker appended when body exceeds
 *       {@link WebFetchTool#DEFAULT_MAX_BYTES}</li>
 *   <li>AC-NN-11 — {@code max_bytes} input field overrides the default cap</li>
 *   <li>AC-NN-12 — missing {@code url} argument returns {@code ToolResult.error}</li>
 * </ul>
 *
 * <p>L2 HTTP-stack coverage (real connection to a mock server, HTTPS User-Agent
 * verification, real whitelist hit, real HTTP 404) lives in
 * {@link WebFetchToolHttpServerIT} so the heavy {@code com.sun.net.httpserver}
 * fixture stays out of the L1 unit boundary.
 */
class WebFetchToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Metadata contract ────────────────────────────────────────────────

    @Test
    @DisplayName("AC-NN-1: webFetchTool_nameIsWebFetch")
    void nameIsWebFetch() {
        WebFetchTool t = new WebFetchTool();
        assertThat(t.name()).isEqualTo("web_fetch");
    }

    @Test
    @DisplayName("AC-NN-2: webFetchTool_descriptionMentionsDomainWhitelistAndGetOnly")
    void descriptionMentionsDomainWhitelistAndGetOnly() {
        WebFetchTool t = new WebFetchTool();
        assertThat(t.description()).contains("domain whitelist");
        assertThat(t.description()).containsIgnoringCase("POST/PUT/DELETE traffic is NOT supported");
    }

    @Test
    @DisplayName("AC-NN-3: webFetchTool_inputSchemaIsUrlStringRequiredAndMaxBytesInteger")
    void inputSchemaIsUrlStringRequiredAndMaxBytesInteger() {
        WebFetchTool t = new WebFetchTool();
        JsonNode schema = t.inputSchema();

        assertThat(schema.get("type").asText()).isEqualTo("object");
        JsonNode properties = schema.get("properties");
        assertThat(properties).isNotNull();
        assertThat(properties.get("url").get("type").asText()).isEqualTo("string");
        assertThat(properties.get("max_bytes").get("type").asText()).isEqualTo("integer");

        JsonNode required = schema.get("required");
        assertThat(required).isNotNull();
        assertThat(required.isArray()).isTrue();
        assertThat(required).hasSize(1);
        assertThat(required.get(0).asText()).isEqualTo("url");
    }

    @Test
    @DisplayName("AC-NN-4: webFetchTool_sourceCategoryIsLocal")
    void sourceCategoryIsLocal() {
        WebFetchTool t = new WebFetchTool();
        assertThat(t.sourceCategory()).isEqualTo("local");
    }

    // ── execute() contract ───────────────────────────────────────────────

    @Test
    @DisplayName("AC-NN-7 reverse: emptyWhitelist_throwsAccessDenied_LINGS_S01_PropagatedAsError")
    void emptyWhitelist_throwsAccessDenied_LINGS_S01_PropagatedAsError() {
        WebFetchTool t = new WebFetchTool();

        // Real WhitelistedHttpClient with empty whitelist — any host is denied.
        // This is the strongest sandbox-defense proof at L1: the Tool cannot
        // accidentally bypass WhitelistedHttpClient.check() because the test
        // exercises the real client.
        WhitelistedHttpClient client = new WhitelistedHttpClient(Collections.<String>emptyList());
        ToolExecutionContext ctx = ctxWith(client);

        ToolResult r = t.execute(call("c1", "http://anywhere.example/path"), ctx);

        assertThat(r.getStatus()).isEqualTo(ToolResult.Status.ERROR);
        assertThat(r.isError()).isTrue();
        assertThat(r.getContent()).startsWith("[LINGS-S01]");
        assertThat(r.getContent()).contains("Domain not whitelisted");
        assertThat(r.getContent()).contains("anywhere.example");
    }

    @Test
    @DisplayName("AC-NN-10: default1MB_truncationMarkerAppended")
    void default1MB_truncationMarkerAppended() {
        WebFetchTool t = new WebFetchTool();

        // Mock NetworkClient to return a 2 MB body — every host passes the
        // sandbox check (we never call real http()) so the only constraint
        // is the Tool's truncation logic.
        String big = new String(new char[2_097_152]).replace('\0', 'a'); // 2 MB
        ToolExecutionContext ctx = ctxWithMockReturning(big);

        ToolResult r = t.execute(call("c1", "http://whitelisted.example/data"), ctx);

        assertThat(r.getStatus()).isEqualTo(ToolResult.Status.SUCCESS);
        assertThat(r.isError()).isFalse();
        // First 1_048_576 chars preserved + marker appended.
        assertThat(r.getContent().length()).isEqualTo(1_048_576 + formattedMarkerLen(2_097_152));
        assertThat(r.getContent()).endsWith("...[truncated, original 2097152 bytes]");
    }

    @Test
    @DisplayName("AC-NN-11: maxBytes100_overridesDefault_TruncatesAt100")
    void maxBytes100_overridesDefault_TruncatesAt100() {
        WebFetchTool t = new WebFetchTool();

        // 1 KB body — small enough that without override it would not truncate.
        String small = new String(new char[1024]).replace('\0', 'b');
        ToolExecutionContext ctx = ctxWithMockReturning(small);

        ObjectNode input = MAPPER.createObjectNode();
        input.put("url", "http://whitelisted.example/data");
        input.put("max_bytes", 100);
        ToolResult r = t.execute(new ToolCall("c1", "web_fetch", input), ctx);

        assertThat(r.getStatus()).isEqualTo(ToolResult.Status.SUCCESS);
        assertThat(r.getContent().length()).isEqualTo(100 + formattedMarkerLen(1024));
        assertThat(r.getContent()).endsWith("...[truncated, original 1024 bytes]");
    }

    @Test
    @DisplayName("AC-NN-12: missingUrl_returnsError")
    void missingUrl_returnsError() {
        WebFetchTool t = new WebFetchTool();
        // WhitelistedHttpClient is irrelevant here — the Tool should reject
        // before even touching ctx.http().
        ToolExecutionContext ctx = ctxWithMockReturning("never called");

        ObjectNode input = MAPPER.createObjectNode();
        // url field absent
        ToolResult r = t.execute(new ToolCall("c1", "web_fetch", input), ctx);

        assertThat(r.getStatus()).isEqualTo(ToolResult.Status.ERROR);
        assertThat(r.isError()).isTrue();
        assertThat(r.getContent()).isEqualTo("url is required");
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static int formattedMarkerLen(int bodyLen) {
        // Marker template is "\n...[truncated, original %d bytes]" (length 34).
        // The `%d` placeholder is exactly 2 chars; after formatting with
        // `bodyLen` it becomes `String.valueOf(bodyLen)` whose length depends
        // on the digit count. Net length = templateLen - 2 + digitCount.
        return "\n...[truncated, original %d bytes]".length()
            - 2 + String.valueOf(bodyLen).length();
    }

    private static ToolCall call(String id, String url) {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("url", url);
        return new ToolCall(id, "web_fetch", input);
    }

    private static ToolExecutionContext ctxWith(WhitelistedHttpClient realClient) {
        ToolExecutionContext c = mock(ToolExecutionContext.class);
        when(c.http()).thenReturn(realClient);
        when(c.callConfig()).thenReturn(new ToolCallConfig(30, 0, 0));
        return c;
    }

    /**
     * Build a mocked {@link ToolExecutionContext} whose {@code http().get(...)}
     * returns a fixed string. Used when the test wants to isolate the Tool's
     * own truncation/error logic from the sandbox layer.
     */
    private static ToolExecutionContext ctxWithMockReturning(final String body) {
        ToolExecutionContext c = mock(ToolExecutionContext.class);
        ToolExecutionContext.NetworkClient nc = mock(ToolExecutionContext.NetworkClient.class);
        try {
            when(nc.get(anyString())).thenReturn(body);
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
        when(c.http()).thenReturn(nc);
        when(c.callConfig()).thenReturn(new ToolCallConfig(30, 0, 0));
        return c;
    }
}
