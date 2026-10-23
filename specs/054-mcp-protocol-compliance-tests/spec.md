# Feature Specification: MCP Protocol Compliance Tests

**Feature Branch**: `054-mcp-protocol-compliance-tests`
**Created**: 2026-10-22
**Status**: Draft
**Input**: User description: 收口 PR #90(已 merge,commit a417464,外部贡献者 xiaobaibaiaaa)引入的两处评审缺陷。(a) demo-product `application.yml` 的 stdio args 硬编码了贡献者的 Windows 绝对路径 `d:\lingshu\...`,换机器 / CI 直接跑不起来;改回 Story #025 原有的相对路径 `src/main/resources/mcp-servers/echo-stdio.py`。(b) ~821 行 MCP SSE / Streamable HTTP 单端点协议重写 0 个 AC 测试 —— 旧 fixture `TestMcpHttpServer` 的 5 端点方案 + `SseMcpServerConnectionHeartbeatTest` 的 `GET /health` 心跳语义已被重写淘汰却没同步更新,fixture 照着旧的非规范协议写,测试一直绿而真实 MCP server 根本连不上,违反 constitution「不省略 AC 黑盒验证」。

## Context / 背景 *(mandatory —— 本 Story 的存在理由)*

PR #90 把 2 个 HTTP 类 MCP transport 重写成 MCP 规范的单端点语义,7 文件 +821 行,**0 个测试、0 处文档**:

- **SSE transport** —— 规范定义**恰好 2 个端点**:`GET` SSE URL 拿长连接,服务端下发的**第一个**事件是 `event: endpoint`(data 是 POST URI);客户端所有消息 POST 到该 URI,POST 返回 202 ack,**答案不从 POST body 回**,而是以 `event: message` 从 SSE 流推回,**按 JSON-RPC id 关联**。心跳是 JSON-RPC `ping`(不是厂商 `GET /health`)。
- **Streamable HTTP transport** —— **单一** MCP endpoint,所有 JSON-RPC 消息(initialize / notifications/initialized / tools/list / tools/call)一律 POST 到 `cfg.getUrl()` 本身,url **永不**当目录前缀用;服务端在 initialize 响应可下发 `Mcp-Session-Id` 头,后续请求回传;响应可能是 `application/json` **或** `text/event-stream`,客户端两种都解析;**HTTP 404 且本地有活跃 sessionId** ⇒ session 过期 ⇒ 清 sessionId + protocolVersion ⇒ 重 `initialize` ⇒ 同一 envelope / 同一 id **重试恰好 1 次**。心跳同为 JSON-RPC `ping`。

**两处缺陷**:

| 缺陷 | 现象 | 根因 |
|---|---|---|
| (a) 硬编码 Windows 绝对路径 | demo-product stdio MCP server 在任何非该贡献者的机器上启动失败 | `application.yml` 的 `args:` 写死 `d:\lingshu\...`;相对路径本来可用,因为 `StdioMcpServerConnection.spawn()` 用裸 `new ProcessBuilder(cmd)` **没有** `.directory(...)`,子进程继承 JVM 工作目录,而 `mvn -pl <module> spring-boot:run` fork 时 CWD = 模块 basedir |
| (b) 821 行协议重写零 AC 测试 | 单测全绿,但真实 MCP server 根本连不上 | 2 个 fixture 照着**旧的非规范协议**写:`TestMcpHttpServer` 5 端点(`/initialize` / `/notifications/initialized` / `/tools/list` / `/tools/call` / `/health`)已被单端点语义淘汰;`TestMcpSseServer` 的 `/sse` handler **从不**下发 `event: endpoint`(新 `doConnect()` 恰恰阻塞在这一步),且 5 处 handler 全部把 JSON-RPC id 写死成 `1L`(新实现按 id 关联,压根不看这个);`SseMcpServerConnectionHeartbeatTest` 用 `dontReplyHealth` / `delayMs` 系统属性驱动探测,而这些属性对**所有** handler 生效 —— 探测打到不存在的 `GET /` 拿到 404 也算「秒回」,所以断言只验状态机(CONNECTED / RECONNECTING / `lastHeartbeatAt` 非空),**永远发现不了协议不对** |

**本 Story 交付 0 新 Maven 依赖 / 0 新 ErrorCode**(复用 `LINGS-M03` / `[LINGS-S01]`)。

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Fresh-clone demo-product starts its stdio MCP server (Priority: P1)

一名拿到仓库的新贡献者 clone 下来,在**自己**(可能是 Windows / Linux / macOS,绝不会是原贡献者的 `d:\lingshu\...`)机器上按 README 执行 `mvn -pl lingshu-examples/demo-product spring-boot:run`。demo-product 启动后配置里声明的 stdio MCP server(`echo-stdio`)**正常连上**并列出它的工具,启动日志里没有 `not connected` / 重连退避刷屏。

**Why this priority**:这是**零代码改动**的一行配置修复,但它是外部贡献者体验的第一道门 —— 现在 clone 下来第一步就炸,任何评审 / 试跑都被卡住。而且它是整条 MCP 支链唯一「不依赖任何 fixture」的验收,可独立跑通、独立交付价值。

**Independent Test**:干净 checkout + `mvn -pl lingshu-examples/demo-product spring-boot:run` ⇒ 启动日志出现该 stdio server 的连接成功 / 工具列表记录。无需跑任何单测、无需启任何 MCP HTTP server。也可以脱离运行时验证:yml 里 stdio server 的脚本参数是**仓库相对路径**而非任何绝对路径。

**Acceptance Scenarios**:

1. **Given** 一台全新 clone 的机器(不是原贡献者机器,CWD 不含 `d:\lingshu`),**When** 启动 demo-product,**Then** `echo-stdio` server 达到已连接状态并能列出工具,无需手工改 yml。
2. **Given** 任意机器,**When** 检查 demo-product 配置里 stdio server 的脚本参数,**Then** 该参数是仓库相对路径(指向 `mcp-servers` 目录下的 `echo-stdio.py`),不含任何盘符 / 用户目录 / 机器专属前缀。
3. **Given** 上述相对路径,**When** 用户改从别的目录(例如仓库根)直接 `java -jar` 启动,**Then** 相对路径解析不到脚本时**失败信息清晰**(点名脚本路径),而不是伪装成「连上了但没工具」。

---

### User Story 2 - SSE transport completes a spec-compliant handshake against a real-shaped server (Priority: P1)

一名集成者在自己的 MCP server 实现了规范的 HTTP+SSE 端点(`GET /sse` 下发 `event: endpoint`,`POST` 到该 URI 返回 202,答案 `event: message` 推回)。他把该 server 配进 `agent.mcp.servers`。LingShu 的 SSE transport 必须**完成握手**:GET 长连接 → 收到 `event: endpoint` → 记住 POST 端点 → POST `initialize` → 从流上按 id 拿到 initialize 的答案 → POST `notifications/initialized` → POST `tools/list` → 按 id 拿到工具列表 → 进入已连接。之后工具可被模型调用,心跳用 JSON-RPC `ping`。

**Why this priority**:这是 PR #90 改写的**核心**,也是缺陷 (b) 的正面战场 —— 现在 fixture 不发 `event: endpoint`,所以这条路径**从头到尾 0 覆盖**。User Story 1 是配置一行改完即得,本 Story 是「测试第一次真的能证伪」。

**Independent Test**:拉起一个只实现规范必需行为的 fixture server(不下发 `tools/list_changed` 之类的额外推送),构造 SSE transport 并 `start()`,断言它到达已连接状态、工具列表非空、`callTool` 拿到结果。fixture 一旦不发 `event: endpoint`,连接必须失败 —— 也就是说这个测试**有证伪能力**,不是永远绿。

**Acceptance Scenarios**:

1. **Given** 一个规范 SSE server:GET 连接后**首个**事件是 `event: endpoint`(data 为 POST URI),**When** transport 建立连接,**Then** 握手成功、工具列表被解析并缓存、状态转为已连接。
2. **Given** 服务端下发的 POST 端点是相对 URI,**When** transport 解析它,**Then** 相对 URI 相对于 SSE URL 正确解析(而不是相对于进程工作目录或原始配置的 host 根)。
3. **Given** POST 端点返回 202 且**空 body**(答案从 SSE 流回),**When** transport 发出 `initialize` / `tools/list` 请求,**Then** 它在流上按 **JSON-RPC id** 取回属于各自请求的答案(并发 / 乱序下发也能正确配对)。
4. **Given** 服务端下发 `event: endpoint` 后 transport 完成 initialize,**When** 已连接状态运行,**Then** 心跳是对已公告 POST 端点发的 **JSON-RPC `ping`**,且**不再**依赖任何厂商 `GET /health` 端点。
5. **Given** 服务端**永不**下发 `event: endpoint`,**When** transport 在心跳超时窗口内等待,**Then** 连接失败并进入重连退避,状态**不是**已连接(有证伪能力)。

---

### User Story 3 - Streamable HTTP recovers from an expired session exactly once (Priority: P2)

集成者的 Streamable HTTP MCP server 会在 initialize 响应下发 `Mcp-Session-Id`,并对每个后续请求校验该头。会话在服务端过期后,服务端对带**过期 / 缺失** session 的请求回 **HTTP 404**。LingShu 必须在这种情况下自愈:丢掉本地 session、重新握手、再把原请求原样重试 —— **恰好 1 次**,不多不少。

**Why this priority**:这是 PR #90 里最容易写错的一格(重试 2 次 = 无限循环风险,重试 0 次 = 长连接直接死)。但它属于「连接已建立后才会遇到」的恢复路径,排在握手路径之后。

**Independent Test**:fixture 先正常完成握手并下发 session id,然后被配置成「对过期 session 回 404」。构造 transport、走一次工具调用,断言:会话被清空并重新握手、**同一请求 id** 拿到了答案、客户端总共只多发一次该请求。

**Acceptance Scenarios**:

1. **Given** 握手时服务端下发了 session id,**When** 后续某请求收到 HTTP 404,**Then** transport 清掉本地会话信息、重新握手,并用**同一个请求 id** 重试该请求。
2. **Given** 上面那次重试**又**收到 404,**When** transport 处理它,**Then** 重试不会无限递归 —— 404 正常向上传播为传输失败(连接进入重连退避),而不是继续重试。
3. **Given** 服务端是无状态的(initialize 不下发 session id),**When** 某个请求收到 HTTP 404,**Then** transport **不**尝试重新握手(无会话可清),404 直接按传输失败处理。
4. **Given** 服务端以 `text/event-stream` 而非 `application/json` 应答,**When** transport 解析响应,**Then** 两种内容类型都能正确解析出 JSON-RPC 答案,调用方无感知。
5. **Given** 任何出站请求,**When** 目标域名不在白名单,**Then** 请求**不发起**,直接得到沙箱拒绝(`[LINGS-S01]`)—— 与 PR #90 之前的行为一致,协议重写不得绕过沙箱。

---

### Edge Cases

- **`event: endpoint` 超时未到** —— SSE 流连上了但服务端迟迟不下发公告端点,在心跳超时窗口内必须失败并退避重连,不能挂死等。
- **服务端下发 `event: endpoint` 后流立刻断开** —— 端点已公告但流已死:握手必须失败,而不是拿一个用不了的端点继续。
- **公告的 POST URI 是绝对路径 / 带 query** —— 两者都要能原样使用。
- **SSE 流上出现非 JSON 的 `data:` 帧** —— 跳过并继续读流,不能整条流报废。
- **SSE 流结束但始终没有匹配到请求 id 的答案** —— 明确失败(超时 / 流终止),不能无限等待。
- **服务端下发 `event: endpoint` 多次 / `notifications/initialized` 之前就抢跑工具调用** —— 握手顺序必须先完成后调用。
- **404 连续发生但服务端从未下发过 session id** —— 走「无会话可清」路径。
- **重连后沿用旧的 session id / 旧的协议版本号** —— 重连必须重新协商,不复用旧值。
- **域名白名单未命中** —— 出站请求必须**不发起**,`[LINGS-S01]` 拒绝,协议重写不得绕过沙箱守卫。
- **JDK 8 编译目标** —— 只能用手头有的 HTTP 能力,不能用更高版本才有的 API;不得使用 `var` / 记录类型 / 不可变集合简写等更高版本语法。
- **0 新依赖 / 0 新错误码** —— 复用既有错误码,不为本 Story 扩充依赖或错误码空间。

---

## Requirements *(mandatory)*

### Functional Requirements

**A. demo-product stdio 路径可移植**

- **FR-001**: demo-product 配置中 stdio MCP server 的脚本参数 MUST 是仓库相对路径,指向仓库自带的 `mcp-servers` 目录下的 echo 脚本。
- **FR-002**: 配置中 MUST NOT 出现任何机器专属的绝对路径(盘符、用户主目录、开发者本机目录)。
- **FR-003**: 该相对路径的可用性 MUST NOT 依赖在 stdio 连接实现里额外设置子进程工作目录;即沿用「子进程继承运行进程工作目录」这一既有行为。
- **FR-004**: 当相对路径在运行目录下解析不到脚本时,失败 MUST 以明确方式暴露(能看出是脚本路径问题),MUST NOT 静默变成「连上了但没有工具」。

**B. 测试替身(fixture)必须说规范的话**

- **FR-005**: 测试用 HTTP 替身 MUST 以规范端点形状对外提供能力:
  - SSE: `GET` 流连接后首个事件为 `event: endpoint`(data 为客户端 POST 的 URI);`POST` 到该 URI 返回接收确认且**不从响应体返回答案**;答案以 `event: message` 从流推回。
  - Streamable HTTP: **单一**端点接收全部 JSON-RPC 消息;initialize 响应可下发 `Mcp-Session-Id` 头,后续请求需回传;应答内容类型可为 JSON 或事件流。
- **FR-006**: 测试替身 MUST 把每个 JSON-RPC 响应的 `id` 回显为对应请求的 `id`,MUST NOT 使用固定常量 id。
- **FR-007**: 测试替身 MUST NOT 依赖任何非规范的专用端点(例如厂商健康检查端点)来表达存活探测。
- **FR-008**: 测试替身 MUST 支持「会话过期回 404」与「不发生过期」两种可切换行为,以驱动恢复路径与正常路径。
- **FR-009**: 测试替身 MUST 能在**不**下发端点公告的情况下运行,以便验证「公告缺失 ⇒ 连接失败」这一反向断言。
- **FR-010**: 若新旧两套替身存在重复实现,应合并为单一替身以消除「测试替身互相抄错的旧协议」这一漂移来源。

**C. SSE 握手与消息关联(P1)**

- **FR-011**: 系统 MUST 在收到服务端下发的端点公告之前不尝试发起任何客户端请求;公告缺失 MUST 导致连接失败(在超时窗口内)。
- **FR-012**: 系统 MUST 支持服务端公告的 POST 地址为相对 URI,并相对 SSE 地址正确解析。
- **FR-013**: 系统 MUST 按 **JSON-RPC id** 关联「请求」与「答案」;当服务端在流上乱序 / 并发下发答案时,每个请求 MUST 拿到属于自己 id 的答案。
- **FR-014**: 系统 MUST 把「初始握手」与「已初始化通知」区分为「带 id 的请求」与「不带 id 的通知」。
- **FR-015**: 存活探测 MUST 使用规范定义的 JSON-RPC `ping`,MUST NOT 依赖厂商专用健康检查端点。
- **FR-016**: 存活探测 MUST 在端点地址缺失时被判定为断连(而非静默成功)。

**D. Streamable HTTP 单端点与会话恢复(P2)**

- **FR-017**: 全部 JSON-RPC 消息 MUST POST 到配置的单一端点本身;该端点 MUST NOT 被当作目录前缀再拼接子路径。
- **FR-018**: 系统 MUST 记录服务端在初始握手时下发的会话标识,并在后续每次请求回传。
- **FR-019**: 系统 MUST 记录协商出的协议版本并在后续请求回传;初始握手响应未给出协议版本时 MUST 有可用兜底值。
- **FR-020**: 当收到 HTTP 404 **且**本地持有活跃会话标识时,系统 MUST 清空会话标识与协议版本、重新握手、并用**同一请求 id** 重试该请求**恰好一次**。
- **FR-021**: 重试本身 MUST NOT 再次触发重试(第二次 404 必须正常传播为传输失败)。
- **FR-022**: 当收到 HTTP 404 **但**本地无会话标识时,系统 MUST NOT 重新握手,404 MUST 直接按传输失败处理。
- **FR-023**: 系统 MUST 同时支持 `application/json` 与 `text/event-stream` 两种应答内容类型。
- **FR-024**: 重连 MUST 重新协商(清空并重建会话标识与协议版本),MUST NOT 复用旧值。

**E. 回归与工程约束**

- **FR-025**: 现有针对 stdio、工厂分派、工具适配器、以及两个 HTTP transport 的既有用例 MUST 保持通过;因替身协议形状变化而需要同步更新的用例 MUST 一起更新,MUST NOT 通过删除断言的方式「修复」。
- **FR-026**: 本 Story MUST NOT 引入新的 Maven 依赖。
- **FR-027**: 本 Story MUST NOT 引入新的错误码;既有错误码复用。
- **FR-028**: 所有新增 / 修改代码 MUST 满足 JDK 8 编译目标,不得使用更高版本才具备的语言特性或 API。
- **FR-029**: 两个 HTTP transport 在重连成功后 MUST 重新拉取工具列表,MUST NOT 复用重连前的旧缓存。
- **FR-030**: 沙箱域名白名单守卫 MUST 在每次出站 HTTP 之前生效(沿用既有实现),协议重写 MUST NOT 绕过它。

### Key Entities

- **MCP 连接配置** —— 一个远端 MCP server 的接入描述:名称、传输方式(三种之一)、端点地址(URL 或命令+参数)、心跳间隔 / 超时 / 重连上限、域名白名单。stdio 用命令+参数,两个 HTTP 传输用 URL。
- **连接状态** —— 六态枚举:空闲 / 连接中 / 已连接 / 已断开 / 重连中 / 失败。对外可订阅状态变更通知。
- **会话标识** —— 服务端在初始握手时可选下发的会话凭据;客户端持有并在后续每次请求回传;404 意味着该会话在服务端已失效。
- **待决请求** —— 已发出但答案尚未到达的请求槽位,以 JSON-RPC id 为键;SSE 侧答案从流上按 id 配对填入,超时后清理。
- **端点公告** —— 仅 SSE 传输存在:服务端在事件流首事件中下发的「客户端 POST 目标地址」。
- **测试替身** —— 测试专用的一次性 HTTP 服务进程,按规范端点形状对客户端说协议,支持多种可切换行为(会话过期 / 不发公告 / 延迟等)。

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 在**非**原贡献者机器的干净 checkout 上,按 README 启动 demo-product,其 stdio MCP server 达到已连接状态并列出工具,零手工配置改动。
- **SC-002**: demo-product 配置文件中,机器专属绝对路径的出现次数为 **0**;脚本参数为仓库相对路径的比例为 **100%**。
- **SC-003**: SSE 握手的 4 个关键环节 —— 端点公告、公告地址解析、按 id 关联答案、`ping` 存活探测 —— 各自至少有 **1 个**独立自动化验收用例,且每个用例在其对应缺陷被重新引入时都会**失败**(具备证伪能力,非恒绿)。
- **SC-004**: Streamable HTTP 的会话恢复 —— 「404 后清会话 + 重新握手 + 同 id 重试 1 次」与「第二次 404 不再重试」各有至少 **1 个**自动化验收用例。
- **SC-005**: 本 Story 新增 / 修改的自动化用例全绿,且**修改后**的既有用例失败数为 **0**(无回归)。
- **SC-006**: 替身从「多套互相重复、各说各话」收敛为**单一**规范形状实现(重复实现的套数为 0);若因客观原因保留多套,则每套都必须能被证明说同一套规范协议。
- **SC-007**: 新增 Maven 依赖数为 **0**,新增错误码数为 **0**。
- **SC-008**: 依赖树前后比对确认二进制依赖零增量(沿用既有基线镜像流程)。
- **SC-009**: 新增代码 100% 满足 JDK 8 编译目标。

---

## Assumptions

- `StdioMcpServerConnection` 保持「裸 `ProcessBuilder`、不设子进程工作目录、子进程继承运行进程工作目录」的既有行为;本 Story 不改该实现,只把配置改回相对路径。
- 标准运行方式为 `mvn -pl lingshu-examples/demo-product spring-boot:run`,fork 出的进程工作目录为**模块**根目录,因此仓库内相对路径 `<module>/src/main/resources/mcp-servers/echo-stdio.py` 可解析。
- 相对路径方案不覆盖「从仓库根用 `java -jar` 直接启动」的部署形态;该形态下用户需自行指定工作目录或改用绝对路径(SC / FR-004 只要求失败信息清晰,不要求该形态开箱即用)。
- 外部真实 MCP server(例如官方参考实现)可用与否**不**作为本 Story 的通过判据 —— CI 不引入外部网络依赖;验收通过本地规范形状替身完成。
- 两个 HTTP transport 的协议层形态在本 Story 视为**已定稿**(PR #90 已合入 main),本 Story 不改其公开契约,只补可证伪的验收测试 + 修正随协议重写而失效的配置。
- 域名白名单守卫、错误码(`LINGS-M03` / `[LINGS-S01]`)、指数退避(1s→2s→…→60s 封顶)在 PR #90 中未变,本 Story 沿用不改。
- 心跳间隔 / 超时 / 重连上限沿用配置值;验收用例优先用既有的「可注入时间参数」构造入口来缩小等待窗口,而不是靠替身端点是否响应来间接推断 —— 这是旧用例失效的根因,新用例不再沿用该间接推断方式。
- 依赖树基线镜像沿用既有流程与既有结论格式,无需重新定义。