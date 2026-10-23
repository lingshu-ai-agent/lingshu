# Implementation Plan: Story #054 MCP 协议合规测试收口

**Branch**: `054-mcp-protocol-compliance-tests` | **Date**: 2026-10-22 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/054-mcp-protocol-compliance-tests/spec.md`

## Summary

PR #90(外部贡献者 `xiaobaibaiaaa`,commit `a417464`,合入 `a214fd9`)把 SSE / Streamable HTTP 两个 MCP
transport 从「5 端点自创协议 + `GET /health` 心跳」改写成「MCP 规范单端点 + `event: endpoint` 端点公告
+ JSON-RPC `ping` 心跳」,**7 文件 +821 行,0 行测试、0 行文档**。留下两处评审缺陷:

- **缺陷 A**:`lingshu-examples/demo-product/src/main/resources/application.yml` 的 stdio `args` 写死
  机器相关绝对路径,换机器 / CI 必挂。
- **缺陷 B**:新协议形态零 AC 测试;旧 fixture `TestMcpHttpServer` 的 5 端点方案与
  `SseMcpServerConnectionHeartbeatTest` 的 `GET /health` 心跳语义**已被重写淘汰却没同步更新**,
  测试仍在测一条生产代码已经不走的路径。

技术方案:把 MCP HTTP 测试基建从「两套 fork fixture + 系统属性开关 + 秒级 sleep」升级为
**单份规范形状 fixture**(同时服务 SSE + Streamable HTTP)+ **可注入时间参数构造器**,
然后按 spec FR-001—FR-030 更新 ~20 个语义失效的既有测试(就地改,不许删断言)、补齐
4 条 SSE 握手链路 + 2 条 404 会话恢复链路的 AC 黑盒用例,最后合流成一个规范形状实现。

## Technical Context

**Language/Version**: Java 8 编译目标(`<source>1.8</source>`),JDK 17+ 运行时
(Spring Boot 3.2.5 / R-06)。测试代码同样守 JDK 8:无 `var` / `List.of` / `record` / `sealed` /
text block。

**Primary Dependencies**: Spring Boot 3.2.5、Jackson、Lombok 1.18.30、JUnit 5、Awaitility、
JDK 内置 `com.sun.net.httpserver`(1.6+,测试 fixture 用)。
**本 Story 新增依赖数 = 0**(constitution §2 技术栈锁定 13 项内,全部已锁)。

**Storage**: N/A(本 Story 不新增任何持久化;`FileSessionStore` 无关)。

**Testing**: JUnit 5(`org.junit.jupiter`),Awaitility 异步等待,`com.sun.net.httpserver`
起真 HTTP 端点,subprocess fixture 打印 `PORT=<n>` 由父进程从 stdout 读端口。
**注意**:本仓 `pom.xml` 无 surefire/failsafe `<includes>`,`*IT` 后缀**不带构建语义**,
全量 `mvn -pl lingshu-core test` 一把跑。

**Target Platform**: Linux x86_64/arm64、macOS x86_64/arm64(开发机)、Windows WSL2
(constitution §6 兼容矩阵)。缺陷 A 的根因正是「开发机绝对路径」跨平台不可移植。

**Project Type**: 既有 Maven 多模块库(`lingshu-core`)+ 教学示例(`lingshu-examples/demo-product`)。
本 Story **不新增模块**,只改既有模块内的文件。

**Performance Goals**: 测试自身预算 —— 每个用例 ≤ 2 s wall clock。
通过可注入时间构造器把现有 `Thread.sleep(6_000L)` / `Thread.sleep(8_000L)` 级等待
压到 50–200 ms 量级,单文件测试从分钟级降到秒级。

**Constraints**:
- **0 新 Maven 依赖**(FR-026;constitution §2 + R-13 `banned-dependencies` enforcer build 阶段 fail)
- **0 新 ErrorCode**(FR-027;复用 `LINGS-M03` / `[LINGS-S01]`)
- **既有测试必须保持通过、只能就地更新**(FR-025;**不得以删除断言的方式修复**)
- R-13 mitigation (d):`dependency:tree` pre/post baseline 镜像,须 **0 binary delta**
- 外部真实 MCP server 连通性**不作为通过判据**(spec Assumptions 第 3 条),避免引入 CI 网络依赖

**Scale/Scope**: 生产代码 **0 行改动**(缺陷 A 改 1 行 yml + fixture 属测试源码);
测试改动覆盖 `lingshu-core` MCP 包 78 个 `@Test`(27 文件)中的约 20 个语义失效用例 + 新增用例。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 门禁 | 依据 | 本 Story 状态 |
|---|---|---|
| **不省略 AC 黑盒验证** | constitution §5 尾注:*"每个 Story 必须自带 validate:跑完所有 AC-NN 才算完成,否则 PR 不合(SOP §4.3)"* | ✅ **本 Story 的全部目的就是补上 PR #90 缺失的 AC 验证**。缺陷 B 本身违反此门禁,本 Story 正是修复它 |
| **JDK 8 兼容** | constitution §1 原则 1 + §6(编译目标 1.8) | ✅ 新增/改写的 fixture 与测试代码只用 JDK 8 已有 API(`HttpServer` / `AtomicLong` / `CountDownLatch` / `Collections`);0 `var` / `List.of` / `record` / `sealed` |
| **技术栈锁定,0 新依赖** | constitution §2(13 项已锁) | ✅ 全部用已锁项;`banned-dependencies` enforcer Rule 0 须 passed |
| **错误码约定** | constitution §4 `LINGS-<域><编号>` | ✅ 0 新错误码,复用既有 `LINGS-M03` / `[LINGS-S01]` |
| **测试策略 7 层金字塔** | constitution §5 | ✅ 新用例落在 **L1 Unit / L2 Slice / L3 Component**:握手编排是组件级行为(transport ↔ fixture 双向),归 L3;`postJsonRpcSpec` 纯编解码归 L1。不新增 L5/L6(无真实 MCP server / 无性能压测) |
| **兼容性矩阵** | constitution §6 | ✅ fixture 全部绑 `127.0.0.1`(IPv4 loopback),三平台一致;**不用** IPv6 `localhost` 字面量(Windows 解析顺序差异会导致 flake) |
| **不跨 Story 改宪章** | CLAUDE.md §11.3 | ✅ 0 次 constitution 改动 |
| **Story 边界** | CLAUDE.md §11.4(≤5 核心文件 / ≤3 ErrorCode) | ⚠️ 见 Complexity Tracking 第 1 行(测试文件数量天然超 5,但 0 生产 SPI 改动) |

**门禁判定:通过。** 无 unjustified violation。

## Project Structure

### Documentation (this feature)

```text
specs/054-mcp-protocol-compliance-tests/
├── plan.md              # 本文件
├── spec.md              # /speckit-specify 产出(182 行,已定稿,不改)
├── research.md          # Phase 0:三个 plan 决策点 + 78 用例 break 判定
├── data-model.md        # Phase 1:fixture 开关模型 + 请求记录器模型
├── quickstart.md        # Phase 1:验证运行指南
├── checklists/
│   └── requirements.md  # 26/26 通过
├── contracts/
│   └── fixture-protocol.md  # fixture 线上契约(两种 transport 的帧/头形状)
└── tasks.md             # /speckit-tasks 产出(本命令不创建)
```

### Source Code (repository root)

```text
lingshu-examples/demo-product/src/main/resources/
└── application.yml                       # [MODIFY] L215 1 行:绝对路径 → 仓内相对路径
└── mcp-servers/echo-stdio.py             # [不变] stdio fixture,line-JSON framing 是既有有意偏离,本 Story 不动

lingshu-core/src/test/java/ai/lingshu/core/mcp/
├── fixture/
│   ├── TestMcpHttpServer.java            # [REWRITE] 单份规范形状 fixture,同时服务 SSE + Streamable HTTP
│   ├── TestMcpSseServer.java             # [RETIRE] fork,合并进 TestMcpHttpServer(SC-006)
│   └── TestMcpServer.java                # [不变] stdio fixture,id 回显参考实现
├── McpHttpTestSupport.java               # [MODIFY] 新增开关参数透传
├── McpHttpSupportTest.java               # [MODIFY] 保留 4 个 postJsonRpc 用例,新增 postJsonRpcSpec 覆盖
├── McpHttpSupportPostJsonRpcSpecTest.java# [NEW] postJsonRpcSpec 双向编解码 + 两种 content-type
├── SseMcpServerConnectionStartTest.java  # [MODIFY] 握手四段断言(公告→POST→event: message→id 关联)
├── SseMcpServerConnectionHeartbeatTest.java # [MODIFY] /health 语义 → JSON-RPC ping 语义
├── SseMcpServerConnectionReconnectTest.java# [MODIFY] 端点缺失 ⇒ 断连(FR-016),修 stub 端口选择器
├── SseMcpServerConnectionListenerTest.java # [MODIFY] 状态迁移序列对齐新握手
├── SseMcpServerConnectionCloseAndCallTest.java # [MODIFY] 陈旧 URL + 断连后 callTool 返回 error
├── StreamableHttpMcpServerConnectionStartTest.java   # [MODIFY] 陈旧 /initialize URL → cfg.getUrl()
├── StreamableHttpMcpServerConnectionHeartbeatTest.java # [MODIFY] ping 心跳 + 会话 id 记录/回放
├── StreamableHttpMcpServerConnectionReconnectTest.java # [MODIFY] 降 sleep + 重连重新协商
├── StreamableHttpMcpServerConnectionSessionRecoveryTest.java # [NEW] 404 → 清会话 → 重握手 → 同 id 重试一次
├── StreamableHttpMcpServerConnectionCloseAndCallTest.java  # [MODIFY] 陈旧 URL
├── McpHttpDomainGuardIT.java             # [MODIFY] 补「白名单放行 ⇒ 真实握手连通」正向断言
└── (其余 14 个既有测试文件)              # [不变] 见 research.md break 判定表

lingshu-core/src/main/java/ai/lingshu/core/mcp/                  # [全部不变]
├── SseMcpServerConnection.java           # 4 参可注入时间构造器 L146-149(本 Story 首次启用)
├── StreamableHttpMcpServerConnection.java# 同款构造器 L110-113(本 Story 首次启用)
├── McpHttpSupport.java                   # postJsonRpcSpec L419(本 Story 首次被测)
└── StdioMcpServerConnection.java         # spawn() 不设 cwd —— 缺陷 A 相对路径可行的根因,不动
```

**Structure Decision**: 沿用既有 `lingshu-core` 单模块测试布局,**不新建 Maven 模块**。
新增文件只有 2 个(`McpHttpSupportPostJsonRpcSpecTest` +
`StreamableHttpMcpServerConnectionSessionRecoveryTest`),其余全部落在既有路径就地改。
`contracts/fixture-protocol.md` 描述 fixture 的线上帧形状,是本 Story 唯一的「接口契约」——
fixture 对 transport 而言扮演「一个规范 MCP server」。

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

| Violation | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| 测试改动跨 ~22 个文件(> CLAUDE.md §11.4 的「≤5 核心文件」) | 门禁 §11.4 限制的是**核心生产文件**,而本 Story 的 0 生产 SPI 改动。PR #90 改了 7 个生产文件却 0 测试,导致 **78 个既有 `@Test` 中约 20 个语义失效** —— 这 20 个分布在 11 个测试文件里,必须逐个就地更新 | 「只加新测试不管旧测试」会留下 ~20 个假绿/假红用例,缺陷 B 只修一半;「删掉旧测试」被 FR-025 明确禁止 |
| 引入 1 个新 fixture 抽象层(请求记录器) | SC-003 要求「缺陷被重新引入时用例**必须失败**」。断言需要能观察「transport 实际发了什么 JSON-RPC 包」,现有 fixture 只回包不记账 | 每次用例各自手写 `AtomicReference<String> lastBody` 会散落 20+ 处且无法复用;不记账就无法写 id 关联断言(FR-013) |

## Phase 0 → Phase 1 输出索引

| 产物 | 内容要点 |
|---|---|
| `research.md` | 三个 plan 决策点拍板(FR-010 合并 fixture / 采纳可注入时间构造器 / `postJsonRpc` 死代码处置)、78 用例 break 判定全表、可注入时间的两个下限约束、`application.yml` 现值与用户描述不符的记录 |
| `data-model.md` | fixture 开关模型(sysprop → 规范行为)、请求记录器模型、断言可见性矩阵 |
| `contracts/fixture-protocol.md` | fixture 的 SSE / Streamable HTTP 帧形状与响应头契约 |
| `quickstart.md` | 跑单个用例、跑整个 MCP 包、跑全量 + R-13 baseline 镜像的验证指南 |