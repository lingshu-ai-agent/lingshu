# agent

Invoke a remote A2A agent's skill explicitly (Style B — bypassing the LLM's
auto-discovery).

When the user invokes `/agent <agentName>.<skill> <args...>`, construct and
call the `remote_agent` tool with input `{"agentName": "<X>", "skill": "<Y>",
"input": {<args>}}`. The remote A2A server (configured under
`agent.a2a.remote-agents[]` in `application.yml`) handles the dispatch and
returns the result.

## Syntax

```
/agent translator.translate hello → es
/agent translator.translate {"text": "thank you", "targetLang": "ja"}
/agent translator.translate text=hello targetLang=es
```

## Configured remote agents (Story #025b demo)

- `translator` — exposes the `translate` skill (sibling module
  `demo-product-a2a-server`, port 9090). Args: `text` (string) +
  `targetLang` (ISO 639-1: en | es | zh | ja | fr | de). Default target: en.

## When to use this skill

Use `/agent` when:
- The user wants to bypass the LLM's tool-call decision loop and force a
  specific skill on a specific remote agent.
- The user is debugging which skill maps to which agent.
- The LLM's auto-discovery isn't picking up the right skill (e.g. the
  remote agent's AgentCard changed and the descriptionSkillLimit truncated
  the list).

For everything else, let the LLM discover skills automatically via
`remote_agent` (Style A) — it will pick the right one based on the user's
prompt.

## How to respond

Parse the slash command:
1. Strip `/agent ` prefix.
2. Split the rest on the first whitespace. The first token is
   `<agentName>.<skill>`. Split on `.` to get the two halves.
3. The remaining text is the args. Try parsing as JSON first; if that
   fails, fall back to `key=value` pairs separated by whitespace; if
   *that* fails, treat the whole remaining text as `input.text`.
4. Call `remote_agent` with the assembled input.
5. Surface the `resultJson` field of the response verbatim to the user,
   prefixed with `[translator.translate] ` so it's obvious which agent
   handled the call.
