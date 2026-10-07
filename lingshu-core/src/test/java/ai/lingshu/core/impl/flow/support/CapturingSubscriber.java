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
package ai.lingshu.core.impl.flow.support;

import ai.lingshu.core.event.AgentEvent;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Story #004 test fixture — simple {@link Subscriber} that captures every {@link AgentEvent}
 * emitted by the engine into a thread-safe list. Use as a sink in engine tests where we
 * want to assert on the event sequence (e.g. {@code ToolCompleted} arrival order, count).
 *
 * <p>Test-only class.
 */
public class CapturingSubscriber implements Subscriber<AgentEvent> {

    private final List<AgentEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void onSubscribe(Subscription s) {
        s.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(AgentEvent event) {
        events.add(event);
    }

    @Override
    public void onError(Throwable t) {
        events.add(new AgentEvent.ErrorEvent(t));
    }

    @Override
    public void onComplete() {
        // no-op — engine does not call onComplete; it ends via TurnCompleted
    }

    /** Unmodifiable snapshot of all captured events. */
    public List<AgentEvent> events() {
        return Collections.unmodifiableList(events);
    }
}