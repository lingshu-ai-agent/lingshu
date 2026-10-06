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
package ai.lingshu.core.impl.permission;

import ai.lingshu.core.decision.Decision;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.slot.PermissionPolicy;
import ai.lingshu.core.slot.ToolExecutionContext;

/**
 * Story #001 default {@link PermissionPolicy} — allow-all.
 *
 * <p>Story #008 (react-max-steps) + Story #014 (session-store) replace this with a
 * {@code StrictPermissionPolicy} that consults a configurable whitelist.
 */
public class AllowAllPermissionPolicy implements PermissionPolicy {

    @Override
    public Decision check(ToolCall call, ToolExecutionContext ctx) {
        return new Decision.Allow("default policy: allow all");
    }
}