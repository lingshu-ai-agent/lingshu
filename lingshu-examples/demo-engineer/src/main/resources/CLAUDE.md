# lingshu-engineer 项目说明(注入到 [PROJECT MEMORY])

> 这是 `project-claude-md` MemorySource 在 demo-engineer 启动时加载的项目级记忆。
> 由 `application.yml` 的 `agent.memory.claude-md.project` 字段指定路径。

## 1. 项目一句话

灵枢 LingShu Agent Engine — JDK 8+ 的企业内 AI 编码助手,9 个可插拔 Slot + Spring Boot SPI + ReAct Loop。

## 2. 仓库结构

```
lingshu/                                  ← 主仓根
├── pom.xml                               ← 父 POM(Spring Boot 3.2.5 父继承)
├── lingshu-core/                         ← 核心:9 Slot 接口 + AgentConfig + 默认实现
├── lingshu-a2a-client/                   ← A2A 客户端(§5.6,可独立打包)
├── lingshu-a2a-server/                   ← A2A 服务端 + AgentCard 生成
├── lingshu-examples/                     ← 教学示例(≤ 10 个,各 ≤ 100 行)
│   ├── demo-empty/                       ← Story #001 AC-01-1:零配置启动
│   └── demo-engineer/                    ← Story #002 AC-09:业务三件套 + 5 段装配(本例)
└── lingshu-cli/                          ← CLI 入口
```

## 3. 关键技术约束(JDK 8 only)

- ❌ `record` / `sealed` / `var` / `List.of(...)` / Pattern matching for switch / Text blocks
- ✅ 用 Lombok `@Value` / `@Builder` / `@NonNull` 替代 records
- ✅ 用 `org.reactivestreams:reactive-streams:1.0.4` 而非 JDK 9+ `Flow`

## 4. Prompt 装配 5 段契约

`DefaultPromptBuilder.build(ctx)` 严格按以下顺序拼装 system message:

1. `[ROLE]` — `identity.name` + `role` + `language` + `traits` + `tone`
2. `[INSTRUCTIONS]` — `instructions.file` 读取内容(可选 mustache 渲染)
3. `[PROJECT MEMORY]` — 4 个 MemorySource 依 yml `memory-sources` 顺序,
   非 null 块以 `\n\n── separator ──\n\n` 拼接
4. `[CONVERSATION HISTORY]` — session 历史的只读视图
5. `[USER MESSAGE]` — 当前 turn 的用户输入

缺失段静默跳过 — 不抛异常、不输出 ERROR 日志(Story #002 US3 反向 AC)。

## 5. 验证步骤(AC-09 黑盒)

```bash
cd lingshu-examples/demo-engineer
time java -jar target/demo-engineer-0.1.0-SNAPSHOT.jar "你是做什么的"
```

期望:
- 启动日志含 `[MemorySource] resolved 4 provider(s):`
- 首个 token ≤ 30s
- LLM 回复中含 "Java 后端工程师"(来自 identity.role 字段)

## 6. 反模式(看到立刻停)

- ❌ Story 间不读前序 PR → 接口冲突
- ❌ 跨 Story 改 constitution → 必须走 RFC
- ❌ 把整个 dsh 7250 行贴 prompt → Context 爆炸
- ❌ 跳过 docs 同步 → 下一位工程师抓瞎