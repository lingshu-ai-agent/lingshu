# Contract: `GET /.well-known/agent.json` HTTP API

**Contract ID**: `lingshu.contract.agent-card-http-api.v1`
**Feature**: Story #009 a2a-agent-card
**Created**: 2026-09-21
**Status**: Stable

---

## 1. 契约方

| 角色 | 类/方法 | 文件 |
|---|---|---|
| **Producer**(服务端)| `A2aServer.handleAgentCard(HttpExchange)` | `lingshu-a2a-server/src/main/java/ai/lingshu/a2a/server/A2aServer.java` |
| **Consumer**(客户端)| 远端 A2A client(其他 LingShu 实例 / Google A2A SDK / `curl`)| — |

---

## 2. HTTP 端点契约

### 2.1 必填要求

| 项 | 要求 |
|---|---|
| **HTTP 方法** | `GET`(强制;`POST` / `PUT` / `DELETE` / `PATCH` → 405) |
| **URL 路径** | `/.well-known/agent.json`(A2A v1.0 spec §2.1 固定路径) |
| **Query 参数** | **不**支持(忽略,不带) |
| **Request headers** | **不**要求任何 header |
| **Request body** | **不**接受 |
| **Response Content-Type** | `application/json; charset=utf-8` |
| **Response Cache-Control** | `public, max-age=60`(1 分钟,§5.6.4 默认 cardTtl = 5min,本 Story 简化)|
| **CORS** | **不**实现(US3 不要求)|

### 2.2 响应状态码

| Status | 触发条件 | Response body |
|---|---|---|
| **200 OK** | 成功 | `application/json` AgentCard |
| **404 Not Found** | URL 路径 ≠ `/.well-known/agent.json`(由 catch-all handler 处理)| `{"error":"not found","path":"<actual-path>"}` |
| **405 Method Not Allowed** | HTTP 方法 ≠ GET(GET 端点上 POST / PUT 等)| `{"error":"method not allowed","method":"<actual-method>"}` + `Allow: GET` header |
| **500 Internal Server Error** | `LocalAgentCardGenerator.generate()` 抛非预期异常(本 Story 应该不发生,服务端兜底)| `{"error":"internal server error","errorCode":"<code>"}` |

### 2.3 AgentCard JSON Schema

**最小必填字段**(AC-10 验证):

```json
{
  "name": "my-coding-agent",
  "description": "AI 编码助手",
  "version": "0.1.0",
  "skills": [],
  "capabilities": {
    "streaming": false,
    "pushNotifications": false,
    "stateTransitionHistory": false
  },
  "defaultInputModes": ["text"],
  "defaultOutputModes": ["text"]
}
```

**字段对照表**:

| JSON 字段 | Java 字段 | 来源 | 可空 | 默认值(本 Story)|
|---|---|---|---|---|
| `name` | `AgentCard.name` | `cfg.getIdentity().getName()` | ❌(必填,空抛 `LINGS-T02`)| `"lingShu-agent"` |
| `description` | `AgentCard.description` | `cfg.getIdentity().getRole()` | ✅ | `null` |
| `version` | `AgentCard.version` | 硬编码 `"0.1.0"` | ❌ | `"0.1.0"` |
| `skills` | `AgentCard.skills` | 硬编码空 list | ✅ | `[]` |
| `capabilities` | `AgentCard.capabilities` | `AgentCapabilities.empty()` | ❌ | `streaming=false / pushNotifications=false / stateTransitionHistory=false` |
| `defaultInputModes` | `AgentCard.defaultInputModes` | 硬编码 | ❌ | `["text"]` |
| `defaultOutputModes` | `AgentCard.defaultOutputModes` | 硬编码 | ❌ | `["text"]` |
| `securitySchemes` | `AgentCard.securitySchemes` | (本 Story 不涉及)| ✅ | `null` |
| `security` | `AgentCard.security` | (本 Story 不涉及)| ✅ | `null` |
| `provider` | `AgentCard.provider` | (本 Story 不涉及)| ✅ | `null` |
| `documentationUrl` | `AgentCard.documentationUrl` | (本 Story 不涉及)| ✅ | `null` |
| `iconUrl` | `AgentCard.iconUrl` | (本 Story 不涉及)| ✅ | `null` |

### 2.4 字段序列化规则

- **null 字段**:`@JsonInclude(ALWAYS)` → 输出 `null` 字面量(FR-002 + US1-AS2)
  ```json
  "description": null
  ```
- **空 list**:输出 `[]`(不是 `null`)
  ```json
  "skills": []
  ```
- **空字符串 name**:**不**应到达 HTTP 端点(`LocalAgentCardGenerator` 启动期校验抛 `LINGS-T02`,Spring 上下文启动失败);若绕过校验到达(理论不可能),返 500 + `LINGS-T02`

---

## 3. 性能契约

| 指标 | 阈值 | 来源 |
|---|---|---|
| 响应时间 P50 | ≤ 10ms(本地内存 + Jackson 序列化)| NFR-001 |
| 响应时间 P99 | ≤ 100ms | NFR-001 |
| 吞吐量 | ≥ 1000 req/s(单核,4KB JSON)| 实施期压测验证(本 Story 不卡 K6)|
| HTTP server 启动时间 | ≤ 5s(`HttpServer.start()` 同步阻塞)| NFR-002 |

---

## 4. 错误契约

### 4.1 `LINGS-T02 A2A_CARD_INVALID_CONFIG`

**触发**:`LocalAgentCardGenerator.generate()` 检测 `Identity.name` 为 `null` / 空 / 纯空白
**抛出位置**:启动期(`A2aServer.@PostConstruct start()` → `LocalAgentCardGenerator.generate()`)
**结果**:Spring 上下文启动失败,`lingshu serve --a2a` 进程退出非 0
**HTTP 端点**:此错误**不**通过 HTTP 返回(因为 server 启动失败前不会监听端口)

**修复路径**:
```yaml
agent:
  identity:
    name: my-coding-agent  # 不能为空
```

### 4.2 `LINGS-S06 A2A_SERVER_START_FAILED`

**触发**:`A2aServer.start()` 抛 `BindException` / `IllegalArgumentException` / `UnknownHostException`
**抛出位置**:启动期
**结果**:Spring 上下文启动失败
**HTTP 端点**:此错误**不**通过 HTTP 返回

**修复路径**:
```yaml
agent:
  a2a:
    port: 9090  # 改端口绕开占用
```

---

## 5. 兼容性

### 5.1 向后兼容

- **JSON 字段集**:**不**移除已有字段(向 #009b 演进时只**新增**字段,**不**重命名 / 移除)
- **状态码**:**不**改 200 → 其他状态;**不**改 JSON 结构
- **HTTP 路径**:`/.well-known/agent.json` 是 A2A v1.0 spec 固定路径,**不**改

### 5.2 演进路径

- **Story #009b**:AgentCard 新增 `provider` / `documentationUrl` / `iconUrl` 字段(从 yml `a2a.server.provider.*` 派生)
- **Story #009b**:`securitySchemes` 字段启用(Bearer auth)
- **Story #009c**:`skills` 字段非空(扫本地 Tool 注册表生成)
- **v1.5+**:`streaming` / `pushNotifications` 字段启用(需要 SSE / webhook 支持)

---

## 6. 测试覆盖

| 测试 ID | 文件 | 验证点 |
|---|---|---|
| TC-API-1 | `LocalAgentCardGeneratorTest#generate_withIdentityName_returnsAgentCardWithName` | US1-AS1 主路径 |
| TC-API-2 | `LocalAgentCardGeneratorTest#generate_defaultIdentity_returnsLingShuAgent` | US1-AS2 零配置 |
| TC-API-3 | `LocalAgentCardGeneratorTest#generate_blankIdentityName_throwsLingsT02` | US1-AS3 + Edge Case EC-1 |
| TC-API-4 | `AgentCardJsonTest#serialize_minimalCard_returnsAllRequiredFields` | FR-006 Jackson 序列化 |
| TC-API-5 | `AgentCardJsonTest#serialize_nullDescription_returnsNullLiteral` | FR-002 null 字段 |
| TC-API-6 | `A2aServerLifecycleTest#getAgentJson_returns200WithValidCard` | L2 Slice 端到端 |
| TC-API-7 | `A2aServerIT#curlBlackBox` | L5 E2E AC-10 |

---

## 7. SemVer 影响

- **MAJOR**:无变化(接口契约稳定)
- **MINOR**:Story #009 新增端点 + 数据类型(MINOR bump 由 release manager 在 v0.2 → v0.3 时统一)
- **PATCH**:无变化

**契约版本**:`v1`(稳定)。后续 #009b 增量变更在 `v1.1` / `v2`。
