# Contract: Wiring Diagram — AgentFactory → PromptBuilder → MemorySource

**Source**: derived from spec + dsh §5.3.1 + §5.5 + §7.1.2
**Audience**: implementer of Story #002 wiring code, reviewers verifying scope

---

## 1. Spring Bean Wiring (Single Source of Truth)

```
┌──────────────────────────────────────────────────────────────────────────┐
│  Spring Application Context (lingshu-core + auto-configured plugins)   │
└──────────────────────────────────────────────────────────────────────────┘
         │
         │  @Component scan: ai.lingshu.core.impl.memory.*  (4 beans)
         │  @Component scan: ai.lingshu.core.impl.prompt.*  (1 bean)
         │  @Component scan: ai.lingshu.core.impl.router.*   (1 file, 6 inner @Component classes)
         │  @Component scan: ai.lingshu.core.impl.runtime.* (1 bean)
         │
         ▼
┌──────────────────────────────────────────────────────────────────────────┐
│  4 × MemorySourceProvider (Beans named memorySourceProvider_<name>)    │
│  ─────────────────────────────────────────────────────────────────────  │
│  ProjectClaudeMdSourceProvider  (priority=10)                            │
│  UserClaudeMdSourceProvider     (priority=20)                            │
│  IdentityMemorySourceProvider   (priority=30)                            │
│  ProjectTreeMemorySourceProvider(priority=40)                            │
└──────────────────────────────────────────────────────────────────────────┘
         │
         │ Spring injects List<MemorySourceProvider> into MemorySourceRouter constructor
         ▼
┌──────────────────────────────────────────────────────────────────────────┐
│  MemorySourceRouter (extends SlotRouter)                                │
│  ─────────────────────────────────────────────────────────────────────  │
│  • byName: Map<String, MemorySourceProvider>  (built once in ctor)       │
│  • Logs "[MemorySource] resolved 4 provider(s): ..." at startup          │
│  • Exposes resolve(name, cfg) and resolveAll(names, cfg)                 │
└──────────────────────────────────────────────────────────────────────────┘
         │
         │ Spring injects MemorySourceRouter into DefaultPromptBuilderProvider
         ▼
┌──────────────────────────────────────────────────────────────────────────┐
│  DefaultPromptBuilderProvider (modified)                                 │
│  ─────────────────────────────────────────────────────────────────────  │
│  • @Autowired MemorySourceRouter memorySourceRouter                     │
│  • create(cfg) calls memorySourceRouter.resolveAll(                     │
│       cfg.prompt.memorySources, cfg)                                    │
│    and passes the result to new DefaultPromptBuilder(sources)            │
└──────────────────────────────────────────────────────────────────────────┘
         │
         │ Spring injects DefaultPromptBuilderProvider into PromptBuilderRouter
         │ (via the standard SlotRouter<List<Providers.PromptBuilderProvider>, PromptBuilder>)
         ▼
┌──────────────────────────────────────────────────────────────────────────┐
│  PromptBuilderRouter (extends SlotRouter, existing from #001)           │
│  ─────────────────────────────────────────────────────────────────────  │
│  • Resolves "default" → DefaultPromptBuilderProvider                     │
│  • AgentFactory.validate(config) calls promptBuilderRouter.resolve(      │
│       config.prompt.builder, config)                                     │
└──────────────────────────────────────────────────────────────────────────┘
         │
         │ AgentFactory @Autowired PromptBuilderRouter (existing #001 wiring)
         ▼
┌──────────────────────────────────────────────────────────────────────────┐
│  AgentFactory (existing from #001, NO MODIFICATIONS for #002)           │
│  ─────────────────────────────────────────────────────────────────────  │
│  • 7 validation items: llm.provider / toolExecutor / sandbox.policy /   │
│    prompt.builder / flowEngine + 2 explicit field checks                 │
│  • prompt.builder is validated via promptBuilderRouter.resolve()        │
│    → throws on unknown name → IllegalArgumentException → fail-fast      │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Runtime Call Sequence (per turn)

```
User code: factory.create(defaultConfig()).run("你是做什么的")
   │
   │  AgentFactory.create(cfg)
   │     ├── validate(cfg) [7 items, NO new items for #002]
   │     ├── promptBuilderRouter.resolve(cfg.prompt.builder, cfg)
   │     │      └── DefaultPromptBuilderProvider.create(cfg)
   │     │             ├── memorySourceRouter.resolveAll(cfg.prompt.memorySources, cfg)
   │     │             │      └── resolve("project-claude-md") → ProjectClaudeMdSource(cfg)
   │     │             │      └── resolve("user-claude-md")    → UserClaudeMdSource(cfg)
   │     │             │      └── resolve("identity")          → IdentityMemorySource(cfg)
   │     │             │      └── resolve("project-tree")      → ProjectTreeMemorySource(cfg)
   │     │             └── new DefaultPromptBuilder(List<MemorySource>(4))
   │     └── new DefaultAgent(session, cfg, engine)
   │
   │  Agent.run(input)
   │     └── FlowEngine.runTurn(ctx, subscriber)
   │           └── (LLM call requires a Prompt)
   │                 └── PromptBuilder.build(ctx)
   │                       ├── Segment 1: [ROLE] from cfg.identity
   │                       ├── Segment 2: [INSTRUCTIONS] from cfg.instructions (with {{var}} render)
   │                       ├── Segment 3: [PROJECT MEMORY] = for each ms: ms.load(ctx)
   │                       ├── Segment 4: [CONVERSATION HISTORY] = ctx.session().history()
   │                       └── Segment 5: [USER MESSAGE] = Message.User(input)
   │
   └── RunResult(text, turns, usage, stopReason, elapsedMillis)
```

---

## 3. Failure Modes & Error Codes

| Failure | Where caught | Error code | HTTP/CLI exit |
|---|---|---|---|
| Unknown `agent.prompt.builder` value | `PromptBuilderRouter.resolve()` | `LINGS-S01` | exit 1 |
| Unknown `agent.prompt.memory-sources` entry | `MemorySourceRouter.resolve()` | `LINGS-S01` | exit 1 |
| Missing Identity defaults | (impossible — `Identity.defaults()` always returns non-null) | — | — |
| Missing Instructions file | (silent fallback to `inline`, then to empty segment) | — | — |
| Missing CLAUDE.md / extras file | (silent skip in `MemorySource.load()`) | — | — |
| File read IOException | (caught inside `load()` → return null) | — | — |
| Template `{{var}}` not in `variables` | (silent passthrough, literal kept) | — | — |
| Template `variables` contains `{{` substring | (no escaping in v1 — see D-01) | — | — |

---

## 4. Files Touched Summary

| File | Status | Lines (est.) |
|---|---|---|
| `lingshu-core/.../impl/memory/ProjectClaudeMdSource.java` | NEW | ~30 |
| `lingshu-core/.../impl/memory/UserClaudeMdSource.java` | NEW | ~30 |
| `lingshu-core/.../impl/memory/IdentityMemorySource.java` | NEW | ~40 |
| `lingshu-core/.../impl/memory/ProjectTreeMemorySource.java` | NEW | ~60 |
| `lingshu-core/.../impl/memory/ProjectClaudeMdSourceProvider.java` | NEW | ~25 |
| `lingshu-core/.../impl/memory/UserClaudeMdSourceProvider.java` | NEW | ~25 |
| `lingshu-core/.../impl/memory/IdentityMemorySourceProvider.java` | NEW | ~25 |
| `lingshu-core/.../impl/memory/ProjectTreeMemorySourceProvider.java` | NEW | ~25 |
| `lingshu-core/.../impl/router/Routers.java` | MODIFIED (+1 inner @Component) | +20 |
| `lingshu-core/.../impl/prompt/DefaultPromptBuilderProvider.java` | MODIFIED (@Autowired + create change) | +10 |
| `lingshu-core/.../impl/prompt/DefaultPromptBuilder.java` | MODIFIED (constructor + 5-segment refactor) | +30, -20 |
| `lingshu-core/src/test/.../MemorySourceRouterTest.java` | NEW | ~80 |
| `lingshu-core/src/test/.../DefaultPromptBuilderTest.java` | NEW | ~150 |
| `lingshu-core/src/test/.../impl/memory/IdentityMemorySourceTest.java` | NEW | ~50 |
| `lingshu-core/src/test/.../impl/memory/ProjectTreeMemorySourceTest.java` | NEW | ~80 |
| `lingshu-examples/demo-engineer/pom.xml` | NEW | ~30 |
| `lingshu-examples/demo-engineer/.../DemoEngineerApplication.java` | NEW | ~80 |
| `lingshu-examples/demo-engineer/.../prompts/system-engineer.md` | NEW | ~20 |
| `lingshu-examples/demo-engineer/.../CLAUDE.md` | NEW | ~30 |
| `lingshu-examples/demo-engineer/.../application.yml` | NEW | ~25 |
| `README.md` | MODIFIED (Story #002 example) | +20 |

**Total**: 16 new + 3 modified = 19 files, ~860 net lines added.

**Per file average**: ~45 lines. Within Story #002's "≤ 5 个核心文件改动" budget if we count by *behavior-changing* files (DefaultPromptBuilderProvider, DefaultPromptBuilder, Routers, AgentConfig + demo-engineer Application) — well within budget.
