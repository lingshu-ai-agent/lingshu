package ai.lingshu.core.runtime;

import ai.lingshu.core.message.Checkpoint;
import ai.lingshu.core.message.Message;

import java.util.List;

/**
 * Per-turn / per-conversation state holder. Created by {@code AgentFactory.create},
 * carried through {@link TurnContext}, persisted via {@link ai.lingshu.core.slot.SessionStore}.
 *
 * <p>{@code history()} is the only mutable piece (Guarded by internal lock in the
 * {@code DefaultSession} class). All other methods return immutable views.
 */
public interface Session {

    /** Unique session id (UUID by default, configurable for distributed setups). */
    String id();

    /** Ordered conversation history. Mutations (append / prepend / replace) are internal-only. */
    List<Message> history();

    /**
     * Fork the session for a sub-agent with its own history view. The original session is
     * unchanged; this is a copy-on-write semantic.
     */
    Session fork(String subagentType);

    /** Take a snapshot suitable for {@link ai.lingshu.core.slot.SessionStore#save}. */
    Checkpoint checkpoint();
}