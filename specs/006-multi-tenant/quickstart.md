# Quickstart: Story #006 multi-tenant

**Branch**: `story-006-multi-tenant` | **Date**: 2026-09-21
**Purpose**: Runnable validation scenarios for AC-05 multi-tenant isolation

---

## Validation 1: AC-05 黑盒主路径(4 维隔离同时验证)

### Prerequisites
- JDK 17+ 安装
- Maven 3.6.3+
- 当前 Story #006 分支 `story-006-multi-tenant` checked out
- Story #001—#005 全部 merged(`mvn -pl lingshu-core test` 19 测试用例 green)

### Setup
```bash
cd lingshu
git checkout story-006-multi-tenant
mvn -pl lingshu-core -am compile  # 编译应通过
```

### Run
```bash
mvn -pl lingshu-core test -Dtest=TenantIsolationIT
```

### Expected Outcome
```
[INFO] Running ai.lingshu.core.tenant.TenantIsolationIT
[INFO]   ✓ aliceAndBobIsolated_across4Dims(872ms)
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### What it validates
- Spring Boot 启动 + yml 配 `agent.tenants.{alice,bob}` → AgentFactory.create() 通过 `validateTenants`
- Alice turn: `TenantContext.runAs("alice", () -> agent.runBlocking(...))`:
  - Memory path: alice `sandbox.workingDirectory/CLAUDE.md` 读到 alice-secret
  - Cost: alice `CostTracker.usedMicros == 5_000_000`(alice budget USD 10 消耗 USD 5)
  - Sandbox: alice `commandWhitelist=[ls,cat]` 拒 `git`(PermissionDenied)
  - Session key: alice session key = `alice:sess-123`
- Bob turn: `TenantContext.runAs("bob", () -> agent.runBlocking(...))`:
  - Memory path: bob `sandbox.workingDirectory/CLAUDE.md` 读到 bob-secret,**不**读到 alice-secret
  - Cost: bob `CostTracker.usedMicros == 3_000_000`(bob budget USD 100 消耗 USD 3,**不**受 alice 影响)
  - Sandbox: bob `commandWhitelist=[ls,cat,git]` 允许 `git`
  - Session key: bob session key = `bob:sess-123`
- **4 维同时** 验证通过 — AC-05 ✅

---

## Validation 2: 单租户 fallback(Story #006 兼容性验证)

### Setup
```bash
# 在 lingshu-examples/ 下创建空 yml
cat > /tmp/empty-application.yml << 'EOF'
agent:
  flow-engine: linear
  llm:
    provider: mock
    model: mock-model
  tool-executor: default
  sandbox:
    policy: allow-all
    runtime: noop
    working-directory: ./
  prompt:
    builder: default
  # ⚠️ 不配 agent.tenants → 单租户模式
EOF
```

### Run
```bash
mvn -pl lingshu-core test -Dtest=TenantConfigValidationTest#emptyTenants_singleTenantMode_succeeds
```

### Expected Outcome
```
[INFO] Running ai.lingshu.core.tenant.TenantConfigValidationTest
[INFO]   ✓ emptyTenants_singleTenantMode_succeeds(243ms)
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### What it validates
- yml 不配 `agent.tenants` → AgentConfig.tenants == null → AgentFactory.create() 跳过 tenant 校验
- 启动成功,日志输出 `INFO multi-tenant disabled, using global config`(NFR-009)
- 业务代码可调 `TenantConfigProvider.resolve("alice")` 返 `Optional.empty()`(无 tenant)
- 所有 turn 走 yml 全局配置(与 Story #001—#005 完全兼容)

---

## Validation 3: TenantContext 嵌套 + 异常清理

### Run
```bash
mvn -pl lingshu-core test -Dtest=TenantContextTest
```

### Expected Outcome
```
[INFO] Running ai.lingshu.core.tenant.TenantContextTest
[INFO]   ✓ current_initialState_isNull(2ms)
[INFO]   ✓ set_thenCurrent_returnsSetValue(1ms)
[INFO]   ✓ runAs_basicBlock_clearsAfterCallback(3ms)
[INFO]   ✓ runAs_nested_innerDoesNotPolluteOuter(5ms)
[INFO]   ✓ runAs_cleanupOnException(8ms)
[INFO]   ✓ runWithSnapshot_crossThreadRestore(12ms)
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### What it validates
- L1-001—L1-006 全过 → FR-001 / FR-005 / FR-006 正确性
- 嵌套 runAs 栈式保存(D-01)
- 异常清理(R-02 (a))
- 跨线程 snapshot+runWithSnapshot(D-02 + R-02 (c))

---

## Validation 4: 启动期校验失败(fail-fast)

### Setup
```bash
# 缺字段 yml
cat > /tmp/invalid-tenant-yml.txt << 'EOF'
agent:
  flow-engine: linear
  llm:
    provider: mock
    model: mock
  tool-executor: default
  sandbox:
    policy: allow-all
  prompt:
    builder: default
  tenants:
    alice:
      # ⚠️ 缺 memory.dir
      sandbox:
        command-whitelist: [ls]
      cost:
        session-budget-micros: 10000000
EOF
```

### Run
```bash
mvn -pl lingshu-core test -Dtest=TenantConfigValidationTest#missingMemoryDir_failsFast
```

### Expected Outcome
```
[INFO] Running ai.lingshu.core.tenant.TenantConfigValidationTest
[INFO]   ✓ missingMemoryDir_failsFast(45ms)
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### What it validates
- yml 缺 `tenants.alice.memory.dir` → AgentFactory.create() 抛 `LingsConfigException("C02", ...)` + message 列出 `"tenants.alice.memory.dir is required"`(FR-010 + US6 S1)

---

## Validation 5: R-13 依赖自查(0 新增)

### Run
```bash
mvn -pl lingshu-core dependency:tree -Dverbose=true > /tmp/deps-006.txt
diff /tmp/deps-005-baseline.txt /tmp/deps-006.txt
```

### Expected Outcome
```
# diff 应为空(0 行新增)
$ diff /tmp/deps-005-baseline.txt /tmp/deps-006.txt
$ echo $?
0
```

### What it validates
- NFR-003 — Story #006 仅用 JDK 8 内置 API(ThreadLocal / Deque / Supplier),**0 新增依赖**
- R-13 mitigation (d) 合规

---

## Validation 6: 回归测试(Story #001—#005 仍 green)

### Run
```bash
mvn -pl lingshu-core test
```

### Expected Outcome
```
[INFO] Results:
[INFO]   L1 Unit × N + L2 Slice × M + L5 E2E × K = 19 (Story #001—#005) + 19 (Story #006) = 38 测试用例
[INFO] Tests run: 38, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### What it validates
- NFR-009 — 多租户是 additive,不影响 single-tenant 路径
- Story #001—#005 全部 19 测试用例仍 green(SC-008)

---

## Summary Checklist

| Validation | What it proves | Status |
|---|---|---|
| V1 | AC-05 4 维隔离黑盒 | ✅ Required |
| V2 | 单租户 fallback 兼容性 | ✅ Required |
| V3 | TenantContext 基础 + 嵌套 + 异常 + 跨线程 | ✅ Required |
| V4 | 启动期 fail-fast | ✅ Required |
| V5 | R-13 0 新增依赖 | ✅ Required |
| V6 | 回归测试无破坏 | ✅ Required |

**所有 6 个 Validation 通过** → Story #006 ✅ 完成,可提 PR。
