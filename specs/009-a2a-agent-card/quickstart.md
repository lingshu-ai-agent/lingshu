# Quickstart: Story #009 a2a-agent-card

**Feature**: Story #009 a2a-agent-card — AC-10 黑盒验证
**Created**: 2026-09-21

---

## 1. 30 秒验证(US1-AS1 主路径 / AC-10)

```bash
cd /Users/lineng/Documents/AIFullStack/MyDSHAgentDesign/remote_repos/lingshu
git checkout story-009-a2a-agent-card

# Step 1: R-13 baseline 捕获
mvn -pl lingshu-a2a-server dependency:tree -Dverbose=true > /tmp/deps-009-pre.txt
cat /tmp/deps-009-pre.txt | head -30

# Step 2: 编译 + 测试
mvn -pl lingshu-a2a-server test

# Step 3: R-13 post diff(期望 0 行差异)
mvn -pl lingshu-a2a-server dependency:tree -Dverbose=true > /tmp/deps-009-post.txt
diff /tmp/deps-009-pre.txt /tmp/deps-009-post.txt

# Step 4: AC-10 黑盒验证(L5 E2E,真实 `curl` 进程)
mvn -pl lingshu-a2a-server verify -Dtest=A2aServerIT
```

**期望输出**:
```
[INFO] Tests run: 15, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
```

---

## 2. AC-10 端到端验证(US1-AS1)

### 2.1 启动 server

```bash
cd /Users/lineng/Documents/AIFullStack/MyDSHAgentDesign/remote_repos/lingshu
mvn -pl lingshu-a2a-server,lingshu-core -am spring-boot:run \
  -Dspring-boot.run.mainClass=ai.lingshu.cli.Main \
  -Dspring-boot.run.arguments="serve --a2a"
```

**期望日志**(节选):
```
... [A2aServer] listening on http://0.0.0.0:8080
... Started Application in 5.234 seconds
```

### 2.2 curl 黑盒验证

```bash
curl -i http://localhost:8080/.well-known/agent.json
```

**期望响应**:
```http
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8
Cache-Control: public, max-age=60

{
  "name": "lingShu-agent",
  "description": null,
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

### 2.3 自定义 yml 验证(US1-AS1 自定义 Identity)

写 `application.yml`:
```yaml
agent:
  identity:
    name: alice-coding
    role: AI 编码助手
```

重启 server,再 curl:
```bash
curl http://localhost:8080/.well-known/agent.json
```

**期望响应**:
```json
{
  "name": "alice-coding",
  "description": "AI 编码助手",
  "version": "0.1.0",
  ...
}
```

---

## 3. POST /rpc 501 占位验证(US3-AS1)

```bash
curl -i -X POST http://localhost:8080/rpc \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":"1","method":"message/send","params":{}}'
```

**期望响应**:
```http
HTTP/1.1 501 Not Implemented
Content-Type: application/json; charset=utf-8

{"error":"not implemented","method":"message/send"}
```

---

## 4. 未知路径 404 验证(US3-AS2)

```bash
curl -i http://localhost:8080/foo
```

**期望响应**:
```http
HTTP/1.1 404 Not Found
Content-Type: application/json; charset=utf-8

{"error":"not found","path":"/foo"}
```

---

## 5. 端口冲突验证(US1-AS4 / Edge Case EC-4)

```bash
# Terminal 1:启动 server 监听 8080
mvn -pl lingshu-a2a-server spring-boot:run ...

# Terminal 2:启动 server 监听同一 8080
mvn -pl lingshu-a2a-server spring-boot:run ...

# Terminal 2 期望:
# [LINGS-S06 A2A_SERVER_START_FAILED] Failed to start on port 8080: BindException: Address already in use
# hint: change 'a2a.server.port' in application.yml or stop the conflicting process
# BUILD FAILURE
```

---

## 6. 空 Identity.name 校验验证(US1-AS3 / Edge Case EC-1)

写 `application.yml`:
```yaml
agent:
  identity:
    name: ""  # 空字符串
```

启动 server:

**期望日志**:
```
[LINGS-T02 A2A_CARD_INVALID_CONFIG] AgentConfig.identity.name must not be blank
hint: set agent.identity.name in application.yml or use Identity.defaults()
BUILD FAILURE
```

---

## 7. 自定义端口验证(US2-AS1)

写 `application.yml`:
```yaml
agent:
  a2a:
    port: 9090
```

启动 server + curl:
```bash
curl http://localhost:9090/.well-known/agent.json
```

**期望**:HTTP 200 + AgentCard JSON(与默认 8080 一致)

---

## 8. 优雅停止验证(US2-AS3)

```bash
# Terminal 1:启动 server
mvn -pl lingshu-a2a-server spring-boot:run ...

# Terminal 2:发 SIGTERM
kill -TERM <PID>
```

**Terminal 1 期望日志**:
```
... [A2aServer] stopped on http://0.0.0.0:8080
... Stopping service [Tomcat]  (or just Spring context close)
```

---

## 9. R-13 dep-tree 自查报告(PR body 必含)

```bash
echo "=== baseline ==="
mvn -pl lingshu-a2a-server dependency:tree -Dverbose=true > /tmp/deps-009-pre.txt
wc -l /tmp/deps-009-pre.txt

echo "=== after Story #009 ==="
mvn -pl lingshu-a2a-server dependency:tree -Dverbose=true > /tmp/deps-009-post.txt
wc -l /tmp/deps-009-post.txt

echo "=== diff ==="
diff /tmp/deps-009-pre.txt /tmp/deps-009-post.txt && echo "OK: 0 行差异"
```

**期望输出**:`OK: 0 行差异`(`jdk.httpserver` 是 JDK 模块,Maven dependency:tree 不显示)

---

## 10. 测试矩阵总览

| 测试 | 文件 | L 层 | 数量 | 验证目标 |
|---|---|---|---|---|
| 配置派生 | `LocalAgentCardGeneratorTest` | L1 | 5 | US1-AS1/AS2/AS3 + EC-1 |
| JSON 序列化 | `AgentCardJsonTest` | L1 | 3 | FR-002 / FR-006 |
| Server Lifecycle | `A2aServerLifecycleTest` | L1 + L2 | 6 | US2-AS1/AS2/AS3/AS4 + EC-4/5 |
| Black-box E2E | `A2aServerIT` | L5 | 1 | AC-10(curl 真实进程)|
| **合计** | **4 文件** | — | **15 case** | **AC-10 完整覆盖** |
