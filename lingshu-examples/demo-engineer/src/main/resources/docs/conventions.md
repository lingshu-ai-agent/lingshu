# lingshu-engineer 代码约定(被 project-tree MemorySource 加载)

## 1. 命名约定

- **类**:`UpperCamelCase`,包名小写,如 `DefaultPromptBuilder`
- **方法**:`lowerCamelCase`,如 `buildTurnContext`
- **常量**:`SCREAMING_SNAKE_CASE`,如 `MEMORY_SEPARATOR`
- **错误码**:`LINGS-<域><编号>`,如 `LINGS-S01`

## 2. 不可变性优先

```java
// ✅ 用 @Value 不可变 + @Builder
@Value
@Builder
public class AgentConfig { ... }

// ❌ 避免 @Data(自带 setter 破坏不变性)
@Data
public class AgentConfig { ... }
```

## 3. MemorySource 列表顺序 vs priority

`application.yml` 的 `agent.prompt.memory-sources` 列表顺序 = 实际拼装顺序;
**不**按 `priority()` 自动排序(Story #002 US2 设计契约)。

```yaml
agent:
  prompt:
    memory-sources:        # 按这个顺序拼装,不是 priority desc
      - project-tree        #   priority 40 ← 后置 priority,先装配
      - identity           #   priority 30
      - project-claude-md  #   priority 10
```

## 4. 缺失文件静默

```java
// ProjectClaudeMdSource.load(ctx) — 缺失 / IOException / disabled → null
// 不抛异常,不记 ERROR 日志(只 DEBUG)
return Files.exists(path) ? Files.readString(path) : null;
```