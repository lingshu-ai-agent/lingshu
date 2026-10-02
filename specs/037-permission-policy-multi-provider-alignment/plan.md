# Story #037 `permission-policy-multi-provider-alignment` — Plan

> **对应 spec**: [`spec.md`](./spec.md)
> **对应 tasks**: [`tasks.md`](./tasks.md)
> **依赖 Story**: #001 + #029 + #030 + #031(全部已合)
> **Status**: Draft 2026-10-02

---

## 接口 / 文件改动总览

| 文件 | 改动类型 | 行数估算 | 说明 |
|---|---|---|---|
| `lingshu-core/.../permission/AllowAllPermissionPolicyProvider.java` | modify | -3 行(删 import + 注解)+ Javadoc 改写 ~10 行 | 类风格对齐 strict / ask |
| `lingshu-core/.../permission/PermissionPolicyAutoConfiguration.java` | modify | +8 行(`@Bean` + Javadoc)| 新增 defaultPermissionPolicyProvider @Bean |
| `lingshu-core/src/test/.../permission/AllowAllPermissionPolicyProviderTest.java` | new | ~80 行 | L1 单测 4 case |
| `lingshu-core/src/test/.../permission/PermissionPolicyRouterMultiProviderIT.java` | new | ~120 行 | L2 IT 5 case |

**合计:2 modify + 2 new = 4 核心文件**(≤ 5 Story 边界 ✅)
**文档同步:4 文件**(specs/ROADMAP.md + README.md + CLAUDE.md + dsh_agent_design.md)

---

## 接口设计细节

### AllowAllPermissionPolicyProvider(改后)

```java
package ai.lingshu.core.impl.permission;

import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.PermissionPolicy;
import ai.lingshu.core.spi.Providers;

/**
 * Default Provider for Slot 4 PermissionPolicy — name {@code "default"},
 * priority 0, allow-all behavior.
 *
 * <p>Sibling of {@link StrictPermissionPolicyProvider} (name {@code "strict"}, priority 10)
 * and {@link AskUserPermissionPolicyProvider} (name {@code "ask"}, priority 10).
 *
 * <p>Aligned with the v1.5.28 §5.5 multi-Provider pattern — each {@code XxxProvider}
 * registers as a separately-named Spring Bean (see
 * {@link PermissionPolicyAutoConfiguration#defaultPermissionPolicyProvider()})
 * so {@code PermissionPolicyRouter.resolve("default", cfg)} matches
 * {@code @Bean(name="permissionPolicyProvider_default-1.0.0")} via the
 * {@code SlotRouter} parent class's name-keyed map.
 *
 * <p><b>Not {@code @Component}</b>: registration is exclusively via
 * {@link PermissionPolicyAutoConfiguration#defaultPermissionPolicyProvider()}
 * which produces the uniquely-named Bean {@code "permissionPolicyProvider_default-1.0.0"}.
 * Removing {@code @Component} prevents Spring from auto-registering a second
 * conflicting Bean named {@code "allowAllPermissionPolicyProvider"} (the default
 * camelCase from class name).
 *
 * <p>🆕 v1.5.53 Story #037 — migration from Story #001 {@code @Component} style to
 * v1.5.28 multi-Provider {@code @Bean(name="...")} style, aligning with siblings
 * {@link StrictPermissionPolicyProvider} and {@link AskUserPermissionPolicyProvider}.
 */
public class AllowAllPermissionPolicyProvider implements Providers.PermissionPolicyProvider {

    @Override public String name() { return "default"; }

    @Override public int priority() { return 0; }

    @Override public String version() { return "1.0.0"; }

    @Override
    public PermissionPolicy create(AgentConfig config) {
        return new AllowAllPermissionPolicy();
    }
}
```

### PermissionPolicyAutoConfiguration 新增 @Bean

```java
/**
 * 🆕 v1.5.53 Story #037 — registers the {@link AllowAllPermissionPolicyProvider}
 * under the {@code "permissionPolicyProvider_default-1.0.0"} Bean name. User
 * selects via {@code agent.permission-policy: default} (or absent — falls back
 * to allow-all by default, see Story #001 back-compat).
 *
 * <p>Before Story #037 this Provider was registered via {@code @Component} (Story #001),
 * which produced the camelCase Bean name {@code "allowAllPermissionPolicyProvider"}
 * — inconsistent with the v1.5.28 §5.5 multi-Provider
 * {@code @Bean(name = "<slot>Provider_<name>-<version>")} naming convention used by
 * siblings {@link StrictPermissionPolicyProvider} and {@link AskUserPermissionPolicyProvider}.
 *
 * <p>Behavior is unchanged: {@code PermissionPolicyRouter} resolves by {@code name()}
 * ("default"), which still maps to {@link AllowAllPermissionPolicy}. The migration
 * is a registration-path alignment, not a behavior change.
 */
@Bean(name = "permissionPolicyProvider_default-1.0.0")
public PermissionPolicyProvider defaultPermissionPolicyProvider() {
    return new AllowAllPermissionPolicyProvider();
}
```

---

## 测试策略

### L1 单元测试(`AllowAllPermissionPolicyProviderTest`)

| Case | 描述 | 期望 |
|---|---|---|
| US1-AS1 | `name()` 返回值 | `"default"` |
| US1-AS2 | `priority()` 返回值 | `0` |
| US1-AS3 | `version()` 返回值 | `"1.0.0"` |
| US1-AS4 | `create(AgentConfig)` 返回 `AllowAllPermissionPolicy` 实例 + `check` 永远返 `Allow` | 反射验证实现类 |

### L2 集成测试(`PermissionPolicyRouterMultiProviderIT`)

| Case | 描述 | 期望 |
|---|---|---|
| US2-AS1 | Spring 启动后 `permissionPolicyRouter.resolve("default", cfg)` 返回 `AllowAllPermissionPolicy` | policy instanceof AllowAllPermissionPolicy |
| US2-AS2 | 同上 `"strict"` | policy instanceof StrictPermissionPolicy |
| US2-AS3 | 同上 `"ask"` | policy instanceof AskUserPermissionPolicy |
| US2-AS4 | 3 个 Bean 全部注册(反射拿 `PermissionPolicyRouter` 内部 `Map<String, P>` size = 3) | map.size() == 3 |
| US2-AS5 | `AllowAllPermissionPolicyProvider` 类不再有 `@Component` 注解 | `getDeclaredAnnotation(Component.class) == null` |

**测试基础设施复用**:
- L1 用 Mockito / AssertJ / 静态 `new AllowAllPermissionPolicyProvider()`(无 Spring 依赖)
- L2 用 `@SpringBootTest(classes = PermissionPolicyAutoConfiguration.class)` 启动最小 Spring 上下文(避 Mockito 5.x + JDK 23 inline mockmaker 兼容问题,沿用 Story #007 / Story #009e 模式)

---

## 实施顺序

1. **T-37-1**:删 `AllowAllPermissionPolicyProvider` 类级别 `@Component` + `import`
2. **T-37-2**:`AllowAllPermissionPolicyProvider` Javadoc 改写对齐 sibling 风格
3. **T-37-3**:`PermissionPolicyAutoConfiguration` 加 `defaultPermissionPolicyProvider()` `@Bean` + Javadoc
4. **T-37-4**:`mvn -pl lingshu-core compile` 通过(无 Spring 装配错误)
5. **T-37-5**:新建 `AllowAllPermissionPolicyProviderTest` 4 case
6. **T-37-6**:`mvn -pl lingshu-core test` 单测通过
7. **T-37-7**:新建 `PermissionPolicyRouterMultiProviderIT` 5 case
8. **T-37-8**:`mvn -pl lingshu-core verify` IT 通过
9. **T-37-9**:R-13 mitigation (d) baseline 镜像 diff(`mvn -pl lingshu-core dependency:tree` pre/post)
10. **T-37-10**:启动 demo-product 跑 `permission-policy: default` yml(back-compat 守住)
11. **T-37-11**:启动 demo-product 跑 `permission-policy: strict` yml(back-compat 守住,#029 / #031 0 回归)
12. **T-37-12**:启动 demo-product 跑 `permission-policy: ask` yml(back-compat 守住,#030 0 回归)
13. **T-37-13**:文档同步 — `specs/ROADMAP.md`(段二 + 段一)/ `README.md` / `CLAUDE.md` / `dsh_agent_design.md` §13
14. **T-37-14**:`git add ...` + `git commit -m "..."` + push + `gh pr create`

---

## 关键不变项(回退保护)

- `PermissionPolicy` SPI 不变(公开方法签名 `check(Tool, ToolExecutionContext) → Decision` 不变)
- `Decision` 3 子类(Allow / Deny / AskUser)不变
- `PermissionPolicyRouter` 行为不变(按 `name()` resolve,`Map<String, Provider>` size 不变)
- `AgentConfig` 不可变契约不变(0 字段新增)
- `LinearTurnEngine` / `DefaultToolExecutor` 不变(业务层 0 改动)
- `ToolExecutor.dispatch()` 5 步流水线不变(§4.10.1 硬规则 2 守住)
- `PermissionPolicy` 三实现类(`AllowAllPermissionPolicy` / `StrictPermissionPolicy` / `AskUserPermissionPolicy`)内部逻辑 0 改动
- 9 Slot 体系不变
- JDK 8 兼容(`@Component` / `@Bean` / `@Configuration` / `@Autowired` 已锁,无新 binary)
- 0 新 Maven 依赖
- 0 新 ErrorCode
- R-13 mitigation (d) baseline 镜像 **第 23 次 PASS 0 binary delta**

---

## 反模式检查

- ❌ **不改 `@Configuration` 为 `@AutoConfiguration`** —— 当前 strict / ask / default 都在 `@Configuration` 内,跨 Story 范畴(等 sandbox / MCP 系列统一)
- ❌ **不加 `@ConditionalOnMissingBean`** —— v1.5.28 起明确禁止(阉割多 Provider 模式)
- ❌ **不改 `PermissionPolicyRouter` 行为** —— Router 内部 `Map<String, P>` + resolve 逻辑不动
- ❌ **不改 `AllowAllPermissionPolicy` 实现类** —— 本 Story 只动 Provider 包装层,不动 Policy 实现
- ❌ **不引入 4-arg ctor + 5-arg ctor back-compat 模式** —— AllowAll 不需要注入依赖,无需 ctor 复杂度
- ❌ **不改 `AgentConfig` schema** —— 0 字段新增,本 Story 是注册路径对齐,不是配置扩展

---

## Story 边界自检

- ✅ 核心文件改动 2 modify + 2 new = **4 ≤ 5**
- ✅ ErrorCode 引入 **0 ≤ 3**
- ✅ 不跨 Story 改 constitution(`PermissionPolicy` SPI 不变)
- ✅ AC 黑盒验证 12 条(L1 4 + L2 5 + yml back-compat 3)
- ✅ R-13 0 binary delta(纯 Spring `@Component` / `@Bean` / `@Configuration` / `@Autowired` 注解清理)
- ✅ ReAct Loop 不动(`LinearTurnEngine` 0 改动)
- ✅ Spring AI 不引入(`ChatClient.tools().call()` 仍禁止使用,本 Story 0 Spring AI 接触)
