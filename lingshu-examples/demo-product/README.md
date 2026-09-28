# demo-product — LingShu HTTP SSE chat product

A comprehensive demo combining **9 features** of LingShu into one
HTTP-streaming web app. Open the browser at `http://localhost:8080`,
type a message, watch text stream in real time and see the event
panel fill up with `tool.start` / `tool.done` / `compacted` / etc.

## Features

| # | Feature | Where to see it |
|---|---|---|
| 1 | HTTP SSE chat endpoint | `POST /api/chat/{sessionId}` |
| 2 | Multi-turn session | `POST /api/sessions` + `/api/sessions/{id}/history` |
| 3 | Custom Tools (`@Component`) | `read_file`, `write_file`, `list_dir`, `bash_safe` |
| 4 | `@AgentTool` methods | `time`, `calc`, `random`, `uuid` |
| 5 | Skills (`/help` `/clear` `/compact`) | Type `/help` in the chat |
| 6 | Delegate sub-agents | `Task` tool → explore / engineer / reviewer |
| 7 | Compactor (auto + manual) | `compactor` event in panel |
| 8 | Identity / Instructions / Memory | Pre-loaded on every turn |
| 9 | Sandbox (Slot 3) | Bounds the 4 fs-touching Tools — see `application.yml` `agent.sandbox:` |

## Run

```bash
# 1. Set API key + base-url (from Claude Code settings or shell)
export ANTHROPIC_API_KEY=sk-ant-...
export ANTHROPIC_BASE_URL=https://api.minimax.cn/anthropic/
# or override via Spring system property:
# -Dspring.ai.anthropic.base-url=https://api.minimax.cn/anthropic/

# 2. Boot the demo
mvn -pl lingshu-examples/demo-product -am spring-boot:run

# 3. Open browser
open http://localhost:8080
```

> Without these env vars the demo boots fine but every chat call returns
> `IllegalStateException: ANTHROPIC_AUTH_TOKEN not set` — this is expected
> and proves the SSE event-stream error path works.

## API

| Endpoint | Method | Body / Headers |
|---|---|---|
| `/` | GET | serves `index.html` |
| `/api/sessions` | POST | `{}` → `{sessionId}` |
| `/api/sessions` | GET | `{sessionId → ageMs}` |
| `/api/sessions/{id}/history` | GET | `[{role, content}, ...]` |
| `/api/chat/{id}` | POST | `{"prompt": "..."}` + `Accept: text/event-stream` |
| `/api/sessions/{id}` | DELETE | `{}` |

### SSE event types

```
event: text              data: {"delta": "..."}
event: tool.start        data: {"toolCallId", "name"}
event: tool.progress     data: {"toolCallId", "partial"}
event: tool.done         data: {"result": {content, isError}}
event: reasoning.start   data: {"step", "maxSteps"}
event: observation       data: {"step", "toolResultCount"}
event: turn.completed    data: {"reason", "usage": {inputTokens, outputTokens}}
event: compacted         data: {"beforeSize", "afterSize", "approxTokensFreed"}
event: max_steps         data: {"maxSteps", "totalUsage"}
event: approval          data: {"ask": {...}}
event: error             data: {"class", "message"}
event: message           data: {"message": {...}}
```

## Smoke test (curl)

```bash
SESSION=$(curl -s -X POST http://localhost:8080/api/sessions | jq -r .sessionId)

curl -N -X POST http://localhost:8080/api/chat/$SESSION \
  -H "Accept: text/event-stream" \
  -H "Content-Type: application/json" \
  -d '{"prompt":"读一下 README.md 第一行"}'
```

## Implementation notes

- **First reactive consumer** in the codebase. Every other demo uses
  `Agent.runBlocking(...)`; this one subscribes to `Agent.run(String)`'s
  `Publisher<AgentEvent>` and forwards each event to SSE.
- **No new dependencies** — `spring-boot-starter-web` precedent in
  `demo-empty/pom.xml:25`. Tomcat, Jackson, etc. are all transitive.
- **`SessionRegistry` is new** — a tiny `ConcurrentHashMap<sessionId, Agent>`
  with TTL eviction (30 min). The framework has no equivalent because
  every prior demo was `CommandLineRunner`-scoped.
- **`Compacted` workaround** — `AgentEvent.Compacted` has no payload, so
  `AgentEventMapper` tracks the last-known history size and computes
  `approxTokensFreed` as a delta. UI labels it "≈".