# Story #037 `permission-policy-multi-provider-alignment` — Spec

> **Status**: Draft 2026-10-02
> **Source**: dsh v1.5.52 §5.5 多 Provider 模式 + §5.6.4 SPI 总表 Slot 4 + §4.7 PermissionPolicy Provider 注册约定 + **实测发现**(2026-10-02 用户对照 `AllowAllPermissionPolicyProvider` 类发现 `@Component` 模式 vs `StrictPermissionPolicyProvider` / `AskUserPermissionPolicyProvider` 显式 `@Bean` 模式不一致)
> **前置依赖**:`#001` PermissionPolicy 默认 AllowAll 落地 + `#029` Strict 真接通(2026-09-30 已合)+ `#030` AskUser 真接通(2026-10-02 已合)+ `#031` Pattern matching(2026-10-01 已合)
> **同 Story 拆解**:无。本 Story 是 v1.5.28 §5.5 多 Provider 模式的**对齐补漏**,让 3 个 Provider 注册路径风格一致。

---

## 状态

[ ] Draft  [ ] Specified  [ ] Planned  [ ] Tasks Ready  [ ] In Progress  [ ] Validated  [ ] Merged

---

## 来源

- **设计文档**:`dsh_agent_design.md` v1.5.52 §5.5 多 Provider 模式 + §5.6.4 SPI 总表 Slot 4 + §4.7 PermissionPolicy + Story #031 changelog「`@Component` on value-object policy class 会让 Spring reflection instantiate 无参 ctor,本 Story 改用纯 POJO 模式,由 `StrictPermissionPolicyProvider.create()` 显式 new」+ Story #030 changelog「`AskUserPermissionPolicyProvider` SPI 由 `@Component` 自动注册」
- **实测发现**(2026-10-02 对比 3 个 Provider 类级别注解):
  - `AllowAllPermissionPolicyProvider.java` L6 `import org.springframework.stereotype.Component` + L12 `@Component`(Story #001 当年的模式)
  - `StrictPermissionPolicyProvider.java` **无** `@Component`(Story #031 主动删除,Javadoc 解释)
  - `AskUserPermissionPolicyProvider.java` **无** `@Component`(Story #030 跟 strict 对齐)
  - **`PermissionPolicyAutoConfiguration`** 显式 `@Bean(name="permissionPolicyProvider_strict-1.0.0")` + `@Bean(name="permissionPolicyProvider_ask-1.0.0")`,**不**注册 default(因 @Component 自动注册)
- **业务后果**:
  - **功能上正常** —— `PermissionPolicyRouter` 按 `name()` method 路由(name 互不冲突:"default" / "strict" / "ask"),3 个 Bean 名风格不同但无 `BeanDefinitionOverrideException`
  - **风格不一致 2/3 vs 1/3** —— AllowAll 残留 v1.5.27 之前的 `@Component` 风格,与 v1.5.28 §5.5 多 Provider 模式不符
  - **未来 plugin 贡献者易复制错误模式** —— 看到 AllowAll 残留,可能照抄 `@Component` 而非 `@Bean(name="permissionPolicyProvider_<name>-<version>")`
  - **IDE 静态分析 noise** —— `AllowAllPermissionPolicyProvider` 报 IDE warning「Autowired members must be defined in valid Spring bean」?—— 实际无 `@Autowired`,但 `#030` 实施期间遇到类似 IDE warning(Spring plugin 看到 `@Autowired` 注解但类无 `@Component` 报错),删 `@Component` 是根治
- **对应风险**:无 R-XX 直接相关,纯 tech debt 清理(防止 R-19 类似 #009e 那种 wiring 漂移问题)

---

## 范围(WHAT)

### 改动清单(≤ 5 核心文件)

1. **modify** `lingshu-core/src/main/java/ai/lingshu/core/impl/permission/AllowAllPermissionPolicyProvider.java`
   - 删 L6 `import org.springframework.stereotype.Component`
   - 删 L12 `@Component` 类级别注解
   - Javadoc 改写:对齐 `StrictPermissionPolicyProvider` / `AskUserPermissionPolicyProvider` 风格,说明「Not @Component, registration is exclusively via `PermissionPolicyAutoConfiguration#defaultPermissionPolicyProvider()`」

2. **modify** `lingshu-core/src/main/java/ai/lingshu/core/impl/permission/PermissionPolicyAutoConfiguration.java`
   - 加 `@Bean(name = "permissionPolicyProvider_default-1.0.0") public PermissionPolicyProvider defaultPermissionPolicyProvider() { return new AllowAllPermissionPolicyProvider(); }`
   - 平行 strict / ask 两个 `@Bean`(命名风格 `permissionPolicyProvider_<name>-<version>` 一致)
   - Javadoc 同步补「🆕 v1.5.53 Story #037 — AllowAll 也走显式 @Bean 模式,3 个 Provider 注册路径风格对齐」

### 测试清单

3. **new** `lingshu-core/src/test/java/ai/lingshu/core/impl/permission/AllowAllPermissionPolicyProviderTest.java`(L1)
   - US1-AS1:`name()` = `"default"`
   - US1-AS2:`priority()` = `0`
   - US1-AS3:`version()` = `"1.0.0"`
   - US1-AS4:`create(AgentConfig)` 返回 `AllowAllPermissionPolicy` 实例

4. **new** `lingshu-core/src/test/java/ai/lingshu/core/impl/permission/PermissionPolicyRouterMultiProviderIT.java`(L2)
   - US2-AS1:Spring 上下文启动后,`PermissionPolicyRouter` 解析 `"default"` 返回 `AllowAllPermissionPolicy`(走 `defaultPermissionPolicyProvider` `@Bean`)
   - US2-AS2:`PermissionPolicyRouter` 解析 `"strict"` 返回 `StrictPermissionPolicy`(走 `strictPermissionPolicyProvider` `@Bean`)
   - US2-AS3:`PermissionPolicyRouter` 解析 `"ask"` 返回 `AskUserPermissionPolicy`(走 `askUserPermissionPolicyProvider` `@Bean`)
   - US2-AS4:三个 Bean 都注册到 Spring 容器(grep `Map<String, Provider>` in `PermissionPolicyRouter` size=3)
   - US2-AS5:`AllowAllPermissionPolicyProvider` 类不再 `import org.springframework.stereotype.Component`(Reflection verify `getDeclaredAnnotation(Component.class) == null`)

### 文档同步清单

5. **modify** `specs/ROADMAP.md`(实施前 + 实施后)
   - 段二 🟡 待补段加 #037 行(实施前,**先 PR 这一行 + spec/plan/tasks 文件**,review 通过再实施代码)
   - 段一 ✅ 已完成段加 #037 行(实施后)

6. **modify** `README.md` 「Story 路线图」段
   - 追加 #037 retrospective(2 改动文件 / 2 new test / R-13 0 binary delta 第 23 次 PASS)

7. **modify** `CLAUDE.md`(项目-真理侧)
   - v1.3.47 → v1.3.48 同步 Story #037 文档同步条目 + dsh 版本号 v1.5.52 → v1.5.53

8. **modify** `dsh_agent_design.md`
   - §13 changelog 加 v1.5.53 行(Story #037 完成)
   - §5.5 末补「🆕 v1.5.53 Story #037 — AllowAll 也走显式 `@Bean` 模式,3 个 Provider 风格 100% 对齐」

---

## AC(验收条件)

- **AC-37-1**:`AllowAllPermissionPolicyProvider.java` grep `import org.springframework.stereotype.Component` 0 hit(`grep -n "import org.springframework.stereotype.Component" AllowAllPermissionPolicyProvider.java` 空输出)
- **AC-37-2**:`AllowAllPermissionPolicyProvider.java` grep `@Component` 0 hit(类级别无 Spring stereotype 注解)
- **AC-37-3**:`PermissionPolicyAutoConfiguration.java` 3 个 `@Bean` 显式注册(default-1.0.0 / strict-1.0.0 / ask-1.0.0),grep `permissionPolicyProvider_(default|strict|ask)-1.0.0` 各 1 hit
- **AC-37-4**:`mvn -pl lingshu-core test` 全部通过,包含新 `AllowAllPermissionPolicyProviderTest` 4 case
- **AC-37-5**:`PermissionPolicyRouterMultiProviderIT` 5 case 通过
- **AC-37-6**:`mvn -pl lingshu-core dependency:tree` pre/post diff 仅时间戳差异 = **0 binary delta**(R-13 mitigation (d) 第 23 次 PASS)
- **AC-37-7**:demo-product 启动 `permission-policy: default` yml 正常(`Agent.runBlocking` 不抛 `BeanDefinitionOverrideException`)
- **AC-37-8**:demo-product 启动 `permission-policy: strict` yml 正常(back-compat 守住,#029 / #031 0 回归)
- **AC-37-9**:demo-product 启动 `permission-policy: ask` yml 正常(back-compat 守住,#030 0 回归)
- **AC-37-10**:`PermissionPolicyRouterMultiProviderIT` 启动 Spring 上下文时无 `BeanDefinitionOverrideException`(3 个 Bean 名互不冲突)
- **AC-37-11**:`PermissionPolicyAutoConfiguration` 类 Javadoc 含 `🆕 v1.5.53 Story #037` 引用
- **AC-37-12**:`AllowAllPermissionPolicyProvider` 类 Javadoc 解释「Not @Component」设计意图(strict / ask 模式对齐)

## 反向 AC(失败条件)

- **AC-37-X-1**:**不允许**新增 Maven 依赖(0 binary delta)
- **AC-37-X-2**:**不允许**新增 ErrorCode(0 new ErrorCode)
- **AC-37-X-3**:**不允许**改 `PermissionPolicy` SPI(公开方法签名 `check(Tool, ToolExecutionContext) → Decision` 不变)
- **AC-37-X-4**:**不允许**改 `Decision` 3 子类(Allow / Deny / AskUser)
- **AC-37-X-5**:**不允许**改 `PermissionPolicyRouter` 行为(仍按 `name()` resolve,`Map<String, Provider>` 不变)
- **AC-37-X-6**:**不允许**改 `AgentConfig` 字段(0 字段新增;本 Story 是注册路径对齐,不是配置扩展)
- **AC-37-X-7**:**不允许**改 `LinearTurnEngine` / `DefaultToolExecutor` / `ToolExecutor.dispatch()` 5 步流水线(§4.10.1 硬规则 2 守住)
- **AC-37-X-8**:**不允许**改 `PermissionPolicyAutoConfiguration` 的 `@Configuration` → `@AutoConfiguration`(跨 Story 范畴,等 sandbox 系列 / MCP 系列统一)
- **AC-37-X-9**:**不允许**加 `@ConditionalOnMissingBean`(v1.5.28 起明确禁止,阉割多 Provider 模式)
- **AC-37-X-10**:**不允许**改 `AllowAllPermissionPolicy` 实现类(本 Story 只动 Provider 包装层)

---

## 业务价值

- **风格 100% 对齐**:3 个 Provider 全部走 `@Bean(name="permissionPolicyProvider_<name>-<version>")` 显式注册,跟 v1.5.28 §5.5 多 Provider 模式完全对齐
- **未来 plugin 贡献者参考样本**:3 个 Provider 都是同一个样板,无历史遗留特例
- **IDE 静态分析 noise 减少**:3 个 Provider 类级别不再有 `@Component`,@Autowired constructor 警告不再触发
- **0 行为变化**:Router 按 `name()` 路由逻辑不变,3 个 yml 配置(`default` / `strict` / `ask`)完全 back-compat
- **R-13 守住**:0 binary delta,跟 #001—#036 累计 22 个 Story 一致(R-13 mitigation (d) 第 23 次 PASS)

---

## 修复者

Claude Code(根据用户 2026-10-02 会话反馈,用户在 IDE 打开 `AskUserPermissionPolicyProvider` 时发现 `@Autowired` IDE 警告,对照 3 个 Provider 发现 `AllowAllPermissionPolicyProvider` 仍用 v1.5.27 之前的 `@Component` 模式,确认 tech debt → 触发本 Story 实施)
