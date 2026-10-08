# Story #037 `permission-policy-multi-provider-alignment` — Tasks

> **对应 spec**: [`spec.md`](./spec.md)
> **对应 plan**: [`plan.md`](./plan.md)
> **Status**: Draft 2026-10-02

---

## 任务列表(dependency-ordered)

| ID | 描述 | 关联 AC | 状态 |
|---|---|---|---|
| T-37-1 | 删 `AllowAllPermissionPolicyProvider.java` L6 `import org.springframework.stereotype.Component` | AC-37-1, AC-37-2 | ⬜ |
| T-37-2 | 删 `AllowAllPermissionPolicyProvider.java` L12 类级别 `@Component` 注解 | AC-37-2 | ⬜ |
| T-37-3 | `AllowAllPermissionPolicyProvider.java` Javadoc 改写对齐 `StrictPermissionPolicyProvider` / `AskUserPermissionPolicyProvider` 风格(说明 Not @Component + 引用 PermissionPolicyAutoConfiguration 注册路径)| AC-37-12 | ⬜ |
| T-37-4 | `PermissionPolicyAutoConfiguration.java` 加 `@Bean(name="permissionPolicyProvider_default-1.0.0") public PermissionPolicyProvider defaultPermissionPolicyProvider() { return new AllowAllPermissionPolicyProvider(); }` | AC-37-3, AC-37-11 | ⬜ |
| T-37-5 | `PermissionPolicyAutoConfiguration.java` Javadoc 加 `🆕 v1.5.53 Story #037` 引用,解释迁移历史 | AC-37-11 | ⬜ |
| T-37-6 | `mvn -pl lingshu-core compile` 通过(无 Spring `@Component` vs `@Bean` 冲突)| AC-37-4 隐式 | ⬜ |
| T-37-7 | 新建 `lingshu-core/src/test/java/ai/lingshu/core/impl/permission/AllowAllPermissionPolicyProviderTest.java`(L1,4 case: `name()` / `priority()` / `version()` / `create()`)| AC-37-4 | ⬜ |
| T-37-8 | `mvn -pl lingshu-core test -Dtest=AllowAllPermissionPolicyProviderTest` 通过 | AC-37-4 | ⬜ |
| T-37-9 | 新建 `lingshu-core/src/test/java/ai/lingshu/core/impl/permission/PermissionPolicyRouterMultiProviderIT.java`(L2,5 case: 3 Provider resolve + map size + Component annotation reflection verify)| AC-37-5, AC-37-10 | ⬜ |
| T-37-10 | `mvn -pl lingshu-core verify` IT 通过 | AC-37-4, AC-37-5, AC-37-10 | ⬜ |
| T-37-11 | R-13 mitigation (d) baseline 镜像 diff(`mvn -pl lingshu-core dependency:tree` pre/post,只时间戳差异 = 0 binary delta)| AC-37-6 | ⬜ |
| T-37-12 | 启动 demo-product + yml `permission-policy: default` 跑通(`Agent.runBlocking` 不抛异常)| AC-37-7 | ⬜ |
| T-37-13 | 启动 demo-product + yml `permission-policy: strict` 跑通(back-compat #029 / #031 0 回归)| AC-37-8 | ⬜ |
| T-37-14 | 启动 demo-product + yml `permission-policy: ask` 跑通(back-compat #030 0 回归)| AC-37-9 | ⬜ |
| T-37-15 | `specs/ROADMAP.md` 段一 ✅ 已完成加 #037 行(2026-10-02,R-13 0 binary delta 第 23 次 / 0 新 ErrorCode / +9 new case / +2 改动文件)| 文档 | ⬜ |
| T-37-16 | `README.md` 「Story 路线图」段追加 #037 retrospective(2 modify + 2 new test + R-13 0 binary delta 第 23 次 + 0 新 ErrorCode)| 文档 | ⬜ |
| T-37-17 | `CLAUDE.md`(项目-真理侧)v1.3.47 → v1.3.48 同步 Story #037 文档同步条目 + dsh 版本号 v1.5.52 → v1.5.53 + 累计计数 39 → 40 个 Story | 文档 | ⬜ |
| T-37-18 | `dsh_agent_design.md` §13 changelog 加 v1.5.53 行(Story #037 完成)+ §5.5 末补「🆕 v1.5.53 Story #037」blockquote | 文档 | ⬜ |
| T-37-19 | `specs/ROADMAP.md` 段二 🟡 待补段 #037 行划掉(已在 T-37-15 加到段一)| 文档 | ⬜ |
| T-37-20 | `git add lingshu-core/.../AllowAllPermissionPolicyProvider.java lingshu-core/.../PermissionPolicyAutoConfiguration.java lingshu-core/src/test/.../permission/AllowAllPermissionPolicyProviderTest.java lingshu-core/src/test/.../permission/PermissionPolicyRouterMultiProviderIT.java specs/ROADMAP.md README.md CLAUDE.md dsh_agent_design.md` | 提交 | ⬜ |
| T-37-21 | `git commit -m "feat(permission): Story #037 permission-policy-multi-provider-alignment — 3 Provider 注册路径 100% 对齐 v1.5.28 §5.5 多 Provider 模式(删 AllowAll @Component + 加 @Bean + 9 new case)"` | 提交 | ⬜ |
| T-37-22 | `git push origin docs/oq-8-rag-decision` 前的 `git checkout -b feat/037-permission-policy-multi-provider-alignment`(基于最新 main)| 提交 | ⬜ |
| T-37-23 | `git push -u origin feat/037-permission-policy-multi-provider-alignment` | 提交 | ⬜ |
| T-37-24 | `gh pr create --base main --head feat/037-permission-policy-multi-provider-alignment --title "feat(permission): Story #037 permission-policy-multi-provider-alignment — ..." --body "..."` | PR | ⬜ |

---

## 任务依赖关系

```
T-37-1 ──▶ T-37-2 ──▶ T-37-3 ──┐
                                  ├──▶ T-37-6 ──▶ T-37-7 ──▶ T-37-8 ──▶ T-37-9 ──▶ T-37-10
T-37-4 ──▶ T-37-5 ──────────────┘                                                  │
                                                                                    ▼
                                                                              T-37-11(R-13)
                                                                                    │
                                                                                    ▼
                                                                              T-37-12~14(yml 跑通)
                                                                                    │
                                                                                    ▼
                                                                              T-37-15~19(文档)
                                                                                    │
                                                                                    ▼
                                                                              T-37-20~24(commit + PR)
```

---

## 反向 AC 自检(失败兜底)

- ❌ T-37-1~5 不允许新增 / 删除其他 Spring 注解(只能动 `@Component` 和 `@Bean` 路径)
- ❌ T-37-7~10 不允许修改 `PermissionPolicy` / `Decision` / `PermissionPolicyRouter` 行为(测试只验证现有契约)
- ❌ T-37-12~14 不允许改 `application.yml` 的 `permission-policy:` 值(只跑不通就报错,不改 yml 凑合)
- ❌ T-37-15~18 不允许改 dsh §4 / §10.1 / §15(章节锁定表,跨 Story 改动需 RFC)

---

## 时间分配参考(不要给真实估计,只看阶段)

| 阶段 | 任务 | 关注点 |
|---|---|---|
| 实施 | T-37-1~5 | 类级别注解删除 + 显式 `@Bean` 加 |
| 验证 | T-37-6~10 | 编译 + L1 单测 + L2 IT |
| 守门 | T-37-11 | R-13 0 binary delta(最容易翻车)|
| 黑盒 | T-37-12~14 | yml 3 模式 back-compat(必跑)|
| 文档 | T-37-15~19 | 5 文件同步(必走)|
| 提交 | T-37-20~24 | commit + push + PR(网络可能抖,跟 PR #64 一样备 HTTP/1.1 fallback)|

---

## 验收里程碑(每个 T-NN 完成打勾)

- [ ] **代码完成**(T-37-1~5):2 modify
- [ ] **编译通过**(T-37-6):`mvn -pl lingshu-core compile` 0 error
- [ ] **L1 通过**(T-37-7~8):4 case 0 fail
- [ ] **L2 通过**(T-37-9~10):5 case 0 fail
- [ ] **R-13 通过**(T-37-11):baseline 镜像 0 binary delta
- [ ] **黑盒通过**(T-37-12~14):3 yml back-compat 0 回归
- [ ] **文档完成**(T-37-15~19):4 文档 0 drift
- [ ] **PR ready**(T-37-20~24):commit + push + `gh pr create` 成功
