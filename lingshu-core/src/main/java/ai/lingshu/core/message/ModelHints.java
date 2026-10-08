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
package ai.lingshu.core.message;

import lombok.Value;

/**
 * Model-level parameters: which model, sampling temperature, output cap.
 *
 * <p>Integer/Double boxes are used (not primitives) so any field can be null,
 * meaning "leave it to the provider default". Primitive defaults would silently
 * override the provider's own tuning.
 */
@Value
public class ModelHints {
    /** Model id string, e.g. {@code "claude-3-5-sonnet-latest"}, {@code "gpt-4o"}, {@code "deepseek-chat"}. */
    String model;
    /** Sampling temperature; {@code null} → provider default. */
    Double temperature;
    /** Max output tokens; {@code null} → provider default. */
    Integer maxTokens;
}