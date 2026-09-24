# Plan: Story #022 `spring-ai-annotation-tool`

> **Spec anchors**: specs/022-spring-ai-annotation-tool/spec.md
> **Design anchors**: dsh v1.5.40 §6.5 (3) L4873-4980 + §4.6 / §4.10.1 硬规则 2 / §6.4 Skill 边界 / §15.4 ErrorCode 域 + constitution v1.0
> **Stratum**: A-Story(`specification` → `plan` → `tasks` → `implementation` 单 Story 闭环)

---

## 约束(从 constitution + spec 继承)

- **JDK 8 only** — 不许 `var` / `List.of` / `Map.of` / `record` / `sealed` / `var`(constitution §1 第 1 项 + §6 兼容性矩阵)
- **Lombok `@Value` 不可变优先** — 注解是 JDK 元注解,不需要 `@Value`;`SpringAiToolAdapter` 用经典 final field + 显式 ctor,避免 `record`
- **新 ErrorCode 走 `LINGS-<域><编号>` 命名** — LINGS-T08(constitution §4 + spec §1 修正)
- **性能预算 §14.15.1 不退化** — `setApplicationContext()` 在启动期同步跑,反射开销 < 100ms / 100 个 `@AgentTool` 注解方法(实测基线)
- **`ToolExecutor.dispatch()` 5 步流水线不变** — `SpringAiToolAdapter.execute()` 在第 5 步被调,不绕过任何一步(§4.10.1 硬规则 2)
- **0 新 Maven 依赖** — `spring-ai-bom` 已锁,`JsonArgsConverter` 用 Jackson 已锁 `ObjectMapper`,`Schema` 生成用纯 Jackson `ObjectNode`(constitution §2 + R-13 mitigation (d) 强制)
- **测试用裸 `AnnotationConfigApplicationContext`,不引 `@SpringBootTest`**(规避 Mockito 5.x + JDK 23 inline mockmaker 兼容 issue,沿用 `#007` 经验)

---

## 1. 涉及接口(新增 / 修改)

### 新增

| 接口 / 注解 / 类 | 路径 | 角色 |
|---|---|---|
| `@AgentTool` 注解 | `lingshu-core/src/main/java/ai/lingshu/core/tool/annotation/AgentTool.java` | `@Target(METHOD) @Retention(RUNTIME)` 注解,3 字段:`name()` / `description()` / `capabilities()`(dsh §6.5 (3) L4883-4889 字面落地) |
| `SpringAiToolAdapter implements Tool` | `lingshu-core/src/main/java/ai/lingshu/core/tool/adapter/SpringAiToolAdapter.java` | 包装 `@AgentTool` Method 的反射调用 + JSON Schema 生成(dsh §6.5 (3) L4896-4956) |
| `AgentToolScanner implements ApplicationContextAware` | `lingshu-core/src/main/java/ai/lingshu/core/tool/scanner/AgentToolScanner.java` | 启动期扫 `ApplicationContext` 内所有 `@Component` Bean 的 `@AgentTool` 方法 + 注册到 `ToolRegistry`(dsh §6.5 (3) L4961-4979) |
| `JsonArgsConverter` 静态工具类 | `lingshu-core/src/main/java/ai/lingshu/core/tool/converter/JsonArgsConverter.java` | `convert(JsonNode input, Parameter[] params) → Object[]` —— Jackson 反序列化为方法参数(本 Story 新增,dsh §6.5 (3) L4918 字面引用) |
| `AgentToolAutoConfiguration` | `lingshu-core/src/main/java/ai/lingshu/core/tool/AgentToolAutoConfiguration.java` | `@AutoConfiguration` + `@Bean` 提供 `AgentToolScanner`(复用 §5.5 默认 AutoConfiguration 样板) |
| `ToolErrorCodes` 静态常量类 | `lingshu-core/src/main/java/ai/lingshu/core/tool/ToolErrorCodes.java` | `LINGS_T08 = "LINGS-T08"` 常量集中(对齐 `#021b` `McpErrorCodes` 模式) |

### 修改

| 接口 / 类 | 修改 |
|---|---|
| 无 | **`#022` 不修改任何已有接口** —— `Tool` / `ToolRegistry` / `ToolExecutor` 全部 0 改动(spec §5 反向 AC 明确) |

**新增接口严格遵循 dsh 字面落地**,不引入新接口契约,与 `#021b` `McpTransport` / `McpToolAdapter` 模式对齐(同属 Tool SPI 第 3 个 Scheme 来源,但走反射而非 MCP 协议)

---

## 2. 文件清单

| 文件 | 状态 | 行数预估 |
|---|---|---|
| `lingshu-core/src/main/java/ai/lingshu/core/tool/annotation/AgentTool.java` | 新增 | ~25 |
| `lingshu-core/src/main/java/ai/lingshu/core/tool/adapter/SpringAiToolAdapter.java` | 新增 | ~140 |
| `lingshu-core/src/main/java/ai/lingshu/core/tool/scanner/AgentToolScanner.java` | 新增 | ~75 |
| `lingshu-core/src/main/java/ai/lingshu/core/tool/converter/JsonArgsConverter.java` | 新增 | ~110 |
| `lingshu-core/src/main/java/ai/lingshu/core/tool/AgentToolAutoConfiguration.java` | 新增 | ~50 |
| `lingshu-core/src/main/java/ai/lingshu/core/tool/ToolErrorCodes.java` | 新增 | ~20 |
| `lingshu-core/src/test/java/ai/lingshu/core/tool/adapter/SpringAiToolAdapterTest.java` | 新增 | ~250(7 case 覆盖 AC-NN-2 + AC-NN-3 + AC-NN-5 + AC-NN-6) |
| `lingshu-core/src/test/java/ai/lingshu/core/tool/scanner/AgentToolScannerTest.java` | 新增 | ~200(5 case 覆盖 AC-NN-1 + scanner null guard + duplicate name + no @Component bean 空跑) |
| `lingshu-core/src/test/java/ai/lingshu/core/tool/converter/JsonArgsConverterTest.java` | 新增 | ~190(7 case 覆盖 AC-NN-5 + 6 primitive 类型 + unknown type + null input) |
| `lingshu-core/src/test/java/ai/lingshu/core/tool/AgentToolAutoConfigurationTest.java` | 新增 | ~80(2 case 覆盖 AC-NN-1 wiring 路径 + ScannerBean 存在性) |
| `lingshu-core/src/test/java/ai/lingshu/core/tool/integration/AgentToolIntegrationTest.java` | 新增 | ~150(L2 slice 端到端覆盖 LLM 视角下 @AgentTool 可见 + 可调) |
| `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | 新增行 | `ai.lingshu.core.tool.AgentToolAutoConfiguration`(对齐 `#020a` SkillAutoConfiguration + `#021b` McpTransportAutoConfiguration 模式) |

**5 核心新实现 + 1 ErrorCode 常量 + 5 测试 + 1 SPI 装载**,**0 修改**,与 ROADMAP 表第 7 行「5 文件」对齐(注解 + 适配器 + Scanner + JsonArgsConverter + AutoConfiguration)+ 1 个 ErrorCode 常量

---

## 3. 实现顺序

> **原则**:依赖方向 core 内部:`@AgentTool` 注解 → `JsonArgsConverter`(纯函数无依赖)→ `SpringAiToolAdapter`(依赖注解 + Tool interface + JsonNode)→ `AgentToolScanner`(依赖注解 + ApplicationContextAware + ToolRegistry)→ `AgentToolAutoConfiguration`(依赖 Scanner)→ `META-INF/spring/...` 装载 → 测试

| 序 | 任务 | 依赖 | 输出 |
|---|---|---|---|
| 1 | `@AgentTool` 注解 + `ToolErrorCodes` 常量 | 无 | 2 文件可编译 |
| 2 | `JsonArgsConverter.convert(JsonNode, Parameter[])` 纯函数 | Jackson `ObjectMapper` | 1 文件 + L1 Unit 测试 |
| 3 | `SpringAiToolAdapter` + `JsonArgsConverter` 集成 | `@AgentTool` + `JsonArgsConverter` + `Tool` interface | 1 文件 + L1 测试 |
| 4 | `AgentToolScanner implements ApplicationContextAware` | `@AgentTool` + `ToolRegistry` | 1 文件 + L2 测试 |
| 5 | `AgentToolAutoConfiguration` + `META-INF/spring/...imports` | `AgentToolScanner` | 2 文件 + SPI 注册测试 |
| 6 | L3 slice / 端到端 | 全部 | `AgentToolIntegrationTest` LLM → ToolExecutor → @AgentTool 走通 |

**每步独立 commit**(`feat(agent): T-NN <动作>` 格式;首 commit 是 stub,后续补实现 — 沿用 `#009d` 风格)
**绝对禁止一次性 commit 5 文件**(`#021b` 反面教材,`#022` 严格离散)

---

## 4. 测试策略(§14.15.7 + dsh §14.15.7 7 层金字塔)

| 层 | 用例数 | 覆盖 | 文件 |
|---|---|---|---|
| **L1 Unit** | 14 | `JsonArgsConverter` 6 primitive + 1 unknown type + 1 null input;`SpringAiToolAdapter` 3 件套(name/description/inputSchema);`SpringAiToolAdapter.execute()` happy + `InvocationTargetException` + `IllegalArgumentException` 3 case;`@AgentTool` 注解反射验证 1 case | `SpringAiToolAdapterTest` / `JsonArgsConverterTest` |
| **L2 Slice** | 8 | `AgentToolScanner` 5 case(单 bean 单方法 / 单 bean 多方法 / null ctx / dup name / no `@Component` 注入)+ `AgentToolAutoConfiguration` 2 case(Scanner bean 存在 / yml 内 disabled OQ-Future)+ `AgentToolIntegrationTest` LLM → ToolExecutor 全链路 1 case | `AgentToolScannerTest` / `AgentToolAutoConfigurationTest` / `AgentToolIntegrationTest` |
| **L3 Component** | —(并入 L2)| 无需独立 L3,L2 已覆盖「多 Bean 协作」 | — |
| **L4 Contract** | 0(无接口契约变更)| `#022` 不改 `Tool` / `ToolRegistry` interface,**无 L4** | — |
| **L5 E2E / Smoke** | 含入 AC 验证 | `mvn -pl lingshu-core test` 全过,**AC-NN-1—NN-6 + AC-NN-deps-1—NN-2** 全跑通 | CI |
| **L6 Performance** | 不跑(Story 体量不达 NFR 阈值)| `setApplicationContext()` 反射开销 < 100ms / 100 个 `@AgentTool` 方法 **L6 不强制**(给 OQ-Future 留口) | — |
| **L7 兼容** | CI matrix 跑 | `mvn -pl lingshu-core verify` 在 JDK 8/17/21 全过 | CI |

**New Case 计数**:**22 test cases** 跨 5 文件(L1 14 + L2 8 = 22)
**ROADMAP 估算**:表第 7 行「5 文件」+ `tool 领域 ≤ 80%` 覆盖率门槛(spec §4 AC-NN + plan §4)→ 22 cases 与 `#021b` 同 Story 量级

---

## 5. 风险与回滚

| 风险 | 概率×影响 | 缓解 | 回滚 |
|---|---|---|---|
| **R-A** `ApplicationContextAware` 启动期 ctx 注入顺序不确定性(`#021b` 已类似处理)| 2×2=4 | `AgentToolScanner` 走 `ApplicationContextAware.setApplicationContext(...)` —— Spring 文档保证 ctx 刷新前 Bean 已实例化,**handler 被回调时**所有 `@Component` Bean 已 ready,**不需**监听器 / `SmartLifecycle`(比 `#021b` 简单一档) | revert PR;旧 `ToolRegistry` 仍只含 `#019` 4 个 built-in tool,功能完整 |
| **R-B** `@AgentTool(name="dup")` 重复名冲突(`#019` 已处理同场景)| 2×1=2 | `DefaultToolRegistry.register` 已 first-wins + log warn(`#020a` 落地),扫描器**不**做启动期 fail-fast — 与 `#019` `LocalToolsAutoConfiguration` 行为对齐 | 关掉冲突 Bean 上的注解即可 |
| **R-C** 反射 method.invoke 抛异常时 `ToolResult.error` ErrorCode 编码遗漏 | 3×2=6 | L2 slice 测试 AC-NN-3 严格断言 `result.errorCode() == "LINGS-T08"`,**显式断言** + L1 测试覆盖 `Exception` catch-all 子句 | PR review 阶段 + L2 测试卡 CI |
| **R-D** `JsonArgsConverter` 复杂类型(int[] / List<Obj> / Map<String, Obj>)不匹配 LLM JSON | 2×2=4 | LLM 通常只调简单类型(inputSchema 限定 string/integer/number/boolean),`#022` 走简化路径(string + primitive + boxed)返回 `object` placeholder —— L1 测试明确覆盖复杂类型**失败**转 `IllegalArgumentException` → SpringAiToolAdapter catch-all → LINGS-T08 | 复杂类型用户**不写注解**(写一个手写 Tool)或自行 `#019` 模式 |
| **R-13(已有)** `spring-ai-bom` 误用 / binary 膨胀 | 2×3=6(R-13 mitigation (d))| **R-13 mitigation (d) 强制项** — AC-NN-deps-1 + AC-NN-deps-2 + tasks.md T-dep-tree-1—T-dep-tree-4 + PR body `### R-13 dependency:tree 自查` 节(`#009d` 已验证 0 binary delta,`#022` 第 7 次验证) | revert PR;旧 `ToolRegistry` 仍可工作 |

**等级**:R-A / R-B / R-D ≤ 4 监控即可;**R-C ≥ 4 必缓解**(L2 测试断言强制);**R-13 ≥ 6 必缓解**(mitigation (d) 7 次验证 + enforcer build fail)

---

## 6. 文档同步

- [ ] `README.md` 顶部加 `@AgentTool` 示例代码(1 段 + 1 yaml snippet;`#019` built-in-tools 同款行文)
- [ ] `specs/022-spring-ai-annotation-tool/quickstart.md`(本 PR 内;给 Alice 30min 跑通 hello world,模板对齐 `#009d`)
- [ ] `specs/022-spring-ai-annotation-tool/data-model.md`(`@AgentTool` annotation source / `SpringAiToolAdapter` 字段对照表;对齐 `#009d`)
- [ ] `dsh_agent_design.md` §13 changelog 加 `v1.5.40` 行(若本 PR 先合则 v1.5.41)
- [ ] `constitution.md` §10 R-13 风险登记:`Story #022` 标记「已缓解」+ 描述本次 0 binary delta 验证结果
- [ ] `ROADMAP.md` 段一 ✅ 已完成表加 `#022` 行
- [ ] `ROADMAP.md` §15.4 ErrorCode 域表同步 `LINGS-T08`(`constitution §4` + dsh §15.4)
- [ ] `lingshu-docs` 仓 `docs/concepts/tools-spring-ai.md`(Story 推 master 后开)
- [ ] `CLAUDE.md` 对应设计文档版本号引用同步(`v1.5.40` 维持)

---

## 7. 关键不变项(冻结)

1. `Tool` interface 5 方法 + `ToolRegistry` interface 8 方法(7+#021b 的 unregister)+ `ToolExecutor.dispatch()` 5 步流水线(**第 4.10.1 硬规则 2**)全部 0 改动
2. `#019` 落地的 `Read / Write / Edit / Bash` 4 个 hand-written Tool 实现 + `LocalToolsAutoConfiguration.afterPropertiesSet()` 注册路径 — `0 改动`
3. `#021b` 落地的 `McpTransport` / `McpToolAdapter` / `ToolRegistry.unregister` SPI / `LINGS-M02` — `0 改动`
4. dsh §6.5 (3) L4873-4980 代码块(注解 + adapter + scanner + JsonArgsConverter 字面落地,无新接口引入)
5. constitution v1.0 §1—§9 全部不变,只 §10 R-13 风险状态更新
6. dsh §15.4 域字母 T 编号表:新增 `LINGS-T08 TOOL_REFLECTION_FAILED`,**T01—T07 编号不动**
7. **0 新 Maven 依赖**(R-13 mitigation (d) 第 7 次验证)
8. **0 modify**(纯新增;无 SPI 签名变化;接口契约向后兼容)

---

**Plan writer**: Claude Code
**Plan date**: 2026-09-24
**Plan version**: v0.1 Draft
