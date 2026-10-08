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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #019 — L1 unit tests for the four built-in local Tools' {@link Tool#inputSchema()}
 * JSON Schema contracts.
 *
 * <p>Each schema declares {@code "type": "object"} + a {@code "properties"} object with
 * the documented fields, and a {@code "required"} array listing mandatory fields. The
 * Tool itself does NOT validate input (it parses defensively and returns
 * {@code ToolResult.error("missing required field: X")}); the schema is for the LLM.
 */
class LocalToolSchemasTest {

    @Test
    @DisplayName("AC-019-11: readTool_schema_hasFilePathRequired")
    void readTool_schema_hasFilePathRequired() {
        ReadTool t = new ReadTool(new LocalToolProps(200_000, 1_000_000));

        JsonNode schema = t.inputSchema();
        assertThat(schema.get("type").asText()).isEqualTo("object");
        assertThat(schema.get("properties").get("file_path").get("type").asText())
            .isEqualTo("string");

        JsonNode required = schema.get("required");
        assertThat(isArrayWithExactly(required, 1)).isTrue();
        assertThat(required.get(0).asText()).isEqualTo("file_path");
    }

    @Test
    @DisplayName("AC-019-12: writeTool_schema_hasFilePathAndContentRequired")
    void writeTool_schema_hasFilePathAndContentRequired() {
        WriteTool t = new WriteTool(new LocalToolProps(200_000, 1_000_000));

        JsonNode schema = t.inputSchema();
        assertThat(schema.get("type").asText()).isEqualTo("object");
        assertThat(schema.get("properties").get("file_path").get("type").asText())
            .isEqualTo("string");
        assertThat(schema.get("properties").get("content").get("type").asText())
            .isEqualTo("string");

        JsonNode required = schema.get("required");
        assertThat(isArrayWithExactly(required, 2)).isTrue();
    }

    @Test
    @DisplayName("AC-019-12: editTool_schema_hasAllThreeFieldsRequired")
    void editTool_schema_hasAllThreeFieldsRequired() {
        EditTool t = new EditTool();

        JsonNode schema = t.inputSchema();
        assertThat(schema.get("type").asText()).isEqualTo("object");
        assertThat(schema.get("properties").get("file_path").get("type").asText())
            .isEqualTo("string");
        assertThat(schema.get("properties").get("old_string").get("type").asText())
            .isEqualTo("string");
        assertThat(schema.get("properties").get("new_string").get("type").asText())
            .isEqualTo("string");

        JsonNode required = schema.get("required");
        assertThat(isArrayWithExactly(required, 3)).isTrue();
    }

    @Test
    @DisplayName("AC-019-12: bashTool_schema_hasCommandRequired_descriptionOptional")
    void bashTool_schema_hasCommandRequired_descriptionOptional() {
        BashTool t = new BashTool();

        JsonNode schema = t.inputSchema();
        assertThat(schema.get("type").asText()).isEqualTo("object");
        assertThat(schema.get("properties").get("command").get("type").asText())
            .isEqualTo("string");
        assertThat(schema.get("properties").get("description").get("type").asText())
            .isEqualTo("string");

        JsonNode required = schema.get("required");
        assertThat(isArrayWithExactly(required, 1)).isTrue();
        assertThat(required.get(0).asText()).isEqualTo("command");
        // description is NOT in required — it's optional.
        for (JsonNode n : required) {
            assertThat(n.asText()).isNotEqualTo("description");
        }
    }

    /** True iff {@code arr} is a JSON array of exactly {@code size} elements. */
    private static boolean isArrayWithExactly(JsonNode arr, int size) {
        if (arr == null || !arr.isArray()) {
            return false;
        }
        return arr.size() == size;
    }

    /** Helper to create an empty properties object — kept for future schema tests. */
    @SuppressWarnings("unused")
    private static ObjectNode emptyProps() {
        return new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
    }
}