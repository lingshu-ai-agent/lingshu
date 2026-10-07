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
package ai.lingshu.core.exception;

/**
 * Configuration-domain exception (dsh §15 error code {@code LINGS-C02}).
 *
 * <p>Thrown at startup (or on first invalid use) when an {@link ai.lingshu.core.runtime.AgentConfig}
 * sub-section fails validation. The {@code code} field carries the canonical
 * machine-readable identifier; the {@code message} carries the human-readable
 * explanation with the exact field paths that failed.
 *
 * <p>Story #006 — also raised by {@link ai.lingshu.core.runtime.AgentConfig.TenantsConfig#validate()}
 * when tenant configurations are missing required fields, exceed the {@code 1000}-entry
 * limit, or contain mismatched tenantId keys. Reusing {@code C02} (rather than
 * inventing a new Tenant-domain code) keeps the error catalog small — tenant config
 * validation is still fundamentally "config validation", just with a richer schema.
 */
public class LingsConfigException extends RuntimeException {

    /** Canonical error code; always set so callers can branch on it without parsing the message. */
    private final String code;

    /**
     * @param code    machine-readable code (e.g. {@code "C02"}, {@code "C03"}); should
     *                align with the dsh §15 catalog
     * @param message human-readable explanation, ideally with the failing field path
     */
    public LingsConfigException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** @return the canonical error code (e.g. {@code "C02"}) */
    public String getCode() {
        return code;
    }
}