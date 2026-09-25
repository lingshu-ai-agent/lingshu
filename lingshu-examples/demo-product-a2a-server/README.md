# demo-product-a2a-server — companion A2A server for demo-product

A minimal A2A agent that exposes a single `translate` skill, paired with
`demo-product` (the chat UI on port 8080) to demonstrate **cross-JVM tool
calls** via A2A's `remote_agent` tool.

## What you get

```
┌──────────────────────┐         ┌────────────────────────────┐
│   demo-product       │  A2A    │  demo-product-a2a-server   │
│   (port 8080)        │ ──────▶ │  (port 9090)               │
│                      │  HTTP   │                            │
│  chat UI + agent     │  JSON   │  AgentCard:                │
│  + remote_agent tool │  RPC    │    skills: [translate]     │
│                      │ ◀────── │                            │
└──────────────────────┘  result │  translate(text, lang) →   │
                                │    {translated, targetLang}│
                                └────────────────────────────┘
```

When the user types *"translate 'hello' to Spanish"* in the chat UI, the
demo-product agent invokes `remote_agent` (its own Tool, registered via
`RemoteAgentToolAutoConfiguration`) which makes a JSON-RPC `message/send`
call to this server. This server looks `translate` up in its local
`ToolRegistry`, executes it (the `TranslateTools.translate` `@AgentTool`
method), and returns the translation. The original agent then sees the
translated text and forwards it to the user.

## Why a custom A2A server (instead of `lingshu.a2a.server.A2aServer`)

`lingshu.a2a.server.A2aServer.RpcDispatcherHandler` handles `message/send`
by storing the input JSON in a map and returning a synthetic echo — see
`A2aServer.java:397-424`. It does **not** dispatch to a local
`ToolRegistry`. Fixing this in core requires resolving `ToolExecutionContext`
ownership across the JSON-RPC boundary (the context normally flows from the
engine via `TurnContext`); out of scope for a demo.

This demo therefore:
1. Excludes `A2aServerAutoConfiguration` in the `@SpringBootApplication`.
2. Builds its own `com.sun.net.httpserver.HttpServer` on port 9090.
3. Implements a JSON-RPC 2.0 dispatcher that looks up the requested skill
   in the local `ToolRegistry` and invokes `Tool.execute` with a no-op
   stub context.
4. Builds the `AgentCard.skills[]` list at startup by scanning
   `ToolRegistry.modelVisibleSpecs()` (same pattern as
   `RemoteAgentSchemaBuilder`, Story #009d).

The on-wire format matches `HttpJsonRpcA2aTransport`'s contract, so
demo-product's client doesn't know the difference.

## Run

```bash
# Terminal 1 — this server (port 9090)
mvn -pl lingshu-examples/demo-product-a2a-server -am spring-boot:run

# Terminal 2 — chat UI (port 8080)
mvn -pl lingshu-examples/demo-product -am spring-boot:run

# Open browser
open http://localhost:8080

# Or curl directly
curl http://localhost:9090/.well-known/agent.json | jq .
```

## Smoke test

### 1. Verify the AgentCard advertises the translate skill

```bash
curl -s http://localhost:9090/.well-known/agent.json | jq '.skills'
```

Expected:
```json
[
  {
    "id": "translate",
    "name": "translate",
    "description": "Translate short text into the target language. ...",
    "tags": [],
    "examples": [],
    "inputModes": ["text"],
    "outputModes": ["text"]
  }
]
```

### 2. Call translate directly (skip demo-product)

```bash
curl -s -X POST http://localhost:9090/rpc \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": "1",
    "method": "message/send",
    "params": {
      "agentName": "translator",
      "skill": "translate",
      "inputJson": "{\"text\": \"hello\", \"targetLang\": \"es\"}"
    }
  }' | jq .
```

Expected:
```json
{
  "jsonrpc": "2.0",
  "id": "1",
  "result": {
    "status": "COMPLETED",
    "taskId": "<uuid>",
    "resultJson": "{\"sourceText\":\"hello\",\"targetLang\":\"es\",\"translated\":\"hola\"}"
  }
}
```

### 3. End-to-end via demo-product chat UI

1. Open `http://localhost:8080` in a browser.
2. Type: *"Translate 'thank you' to Japanese using the translator agent"*
3. Watch the event panel: you should see `tool.start` (remote_agent) →
   `tool.done` (with the JSON-RPC result) → text streaming the answer.

The LLM in demo-product sees the `translate` skill in its tool list
(because demo-product's `application.yml` lists `translator` in
`agent.a2a.remote-agents`), and decides to call it automatically.

### 4. Translate via the explicit `/agent` skill (Style B)

In demo-product, type: `/agent translator.translate hello → es`

The `/agent` SKILL.md in demo-product routes to the `remote_agent` tool
explicitly without relying on the LLM's auto-discovery.

## Implementation notes

- **JDK `com.sun.net.httpserver.HttpServer`** — same as the stock A2aServer,
  zero new Maven dependencies. Lives in the JDK since 1.6.
- **`@PostConstruct` from `javax.annotation`** — matches the rest of the
  project (`YamlWatcher.java:10`, `ToolExecutorConfig.java:6`).
- **`maven.compiler.parameters=true`** in pom.xml so
  `SpringAiToolAdapter` reads the Java parameter names (`text`,
  `targetLang`) instead of JDK's erased `arg0`/`arg1`.
- **Stub `ToolExecutionContext`** — `DemoA2aServer.StubToolExecutionContext`
  is a no-op implementation. `TranslateTools.translate` doesn't touch any
  context field, so this is fine for the demo. If you wire a tool here that
  needs file system / HTTP / approval, replace the stub with a real one
  sourced from a `TurnContext`.
- **Cross-agent guard** — `DemoA2aServer.handleMessageSend` rejects any
  `params.agentName` that doesn't match `cfg.identity.name` ("translator").
  Prevents one demo-product-a2a-server from impersonating another when
  several are running side by side.

## Files

| File | Lines | Purpose |
|---|---|---|
| `DemoProductA2aServerApplication.java` | ~75 | `@SpringBootApplication(exclude = A2aServerAutoConfiguration.class)` |
| `TranslateTools.java` | ~140 | `@Component` + `@AgentTool translate({text, targetLang})` |
| `DemoA2aServer.java` | ~430 | custom HttpServer + AgentCard builder + JSON-RPC dispatcher |
| `application.yml` | ~35 | port 9090, agent identity, disable built-in local tools |

## Verification

```bash
# Compile
mvn -pl lingshu-examples/demo-product-a2a-server -am -DskipTests compile

# Boot
mvn -pl lingshu-examples/demo-product-a2a-server spring-boot:run

# In another terminal
curl -s http://localhost:9090/.well-known/agent.json | jq .name        # → "translator"
curl -s http://localhost:9090/.well-known/agent.json | jq '.skills[].id'  # → "translate"
```
