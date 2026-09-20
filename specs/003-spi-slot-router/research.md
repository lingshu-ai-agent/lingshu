# Research: Story #003 spi-slot-router

**Branch**: `story-003-spi-slot-router` | **Date**: 2026-09-20 | **Spec**: [spec.md](./spec.md)

> Phase 0 — Resolve all `NEEDS CLARIFICATION` from spec.md + capture the key technical decisions already locked in during spec drafting (and their rationale + rejected alternatives).

## Summary

Story #003 spec has **zero** `NEEDS CLARIFICATION` markers — every ambiguous question was either resolved during the spec drafting turn or deferred to a documented Assumption (10 items). This research.md therefore serves as the **decision log** for those resolutions, so plan / tasks / implementers can trace "why this approach" without re-reading the spec.

---

## D-01 — semver 简化版:严格 3 段 MAJOR.MINOR.PATCH(无预发布 / 无 build metadata)

**Decision**: `Version.parse(String)` 只接受 `MAJOR.MINOR.PATCH`(3 段非负整数,用 `.` 分隔);**不**支持 semver 2.0.0 完整语法(无 `-RC1` 预发布后缀 / 无 `+sha.abc` build metadata / 无前缀 `v` / 无 4 段以上 / 无字母)。

**Rationale**:
1. **R-13 mitigation (d) 硬约束**(constitution §10 + dsh §17):新依赖需 RFC + `dependency:tree` CI gate + banned-dependencies enforcer。`com.github.zafarkhaja:jsemver` (~15KB jar) 是为 30 行功能引 1 个 transitive 风险,**不**划算。
2. v1 Provider 演进是**线性**的(Major 跨版本才破坏,Minor/Patch 都是向后兼容);无需预发布机制 —— 用户 plugin 用了 Slot 未声明的方法就是 bug,启动期 fail-fast 比"假装 RC1 能跑"安全得多。
3. JDK 8 `String.split("\\.")` + `Integer.parseInt` 在 ~10 行内可完成解析;异常路径用 `IllegalArgumentException` 包错误码 `LINGS-S05` 即可。
4. 完整 semver 2.0.0 留 v2,届时再升级 `Version` 工具类(`parse` 签名不变,只放宽正则)。

**Alternatives considered**:
| Option | Why rejected |
|---|---|
| `com.github.zafarkhaja:jsemver` | 新依赖(R-13),完整 semver 解析对 v1 过度 |
| `org.semver:java-semver` | 同上,且 v0.9.x 维护频率低 |
| 手写完整 semver 2.0.0 正则 | v1 不需要预发布,代码量翻倍且调试难 |
| `String.split` + try-catch Integer.parseInt | **CHOSEN** —— ~10 行覆盖 strict 3 段,异常路径清晰 |
| 用 Maven version comparator | 这是 jar manifest 解析方向,**不**是源码契约 |

**Implementation note**: `Version.parse("1.0.0") → [1, 0, 0]`;`Version.parse("v1")` → 抛 `IllegalArgumentException("version 'v1' must be MAJOR.MINOR.PATCH (3 dot-separated non-negative integers, no 'v' prefix)")`,错误码 `LINGS-S05`。

---

## D-02 — 契约版本声明位置:9 Slot 接口内的 `public static final String CONTRACT_VERSION = "1.0.0"` 常量

**Decision**: 每个 Slot 接口(`LlmProvider` / `ToolExecutor` / `PermissionPolicy` / `SessionStore` / `Compactor` / `PromptBuilder` / `MemorySource` / `FlowEngine` / `A2aTransport` / `Tool` / `RuntimeSandbox` / `Skill` / `SkillSource`)各自声明 `public static final String CONTRACT_VERSION = "1.0.0"`;**不**用 `@since` 注解 / `package-info.java` / 单独的 `ContractVersion.java` 集中表。

**Rationale**:
1. **single-source-of-truth**:每个接口的契约版本**与该接口的 API 表面共生** —— 当 Slot 接口加新方法时,IDE 自动提示"修改 CONTRACT_VERSION"(因为常量紧贴方法定义);分文件会导致"改了方法忘了改版本"的 drift。
2. **`@since` 不靠谱**:JDK 8 `@since` 是 javadoc-only,运行时反射麻烦(`AnnotatedElement.getAnnotation(Since.class)`);且 javadoc tag 在编译后丢失语义,**不能**作为契约版本源。
3. **jar manifest 的 `Implementation-Version` 也不靠谱**:依赖打包配置(`maven-jar-plugin` 写入 `Implementation-Version`);用户运行 `java -cp ...` 不带 manifest 时,**不**生效;跨 IDE / 跨打包工具行为不一致。
4. **`ContractVersion.java` 集中表**:违反"每个 Slot 自治"原则 —— Slot 1 演进要改 2 个文件;且 Slot 1—9 9 个表项 vs 12+ 个接口 12+ 个常量,**集中表不省事**。
5. **`package-info.java`**:不支持 Java 8 字段常量(只能放 package-level annotation),且每个包多 1 个文件冗余。

**Alternatives considered**:
| Option | Why rejected |
|---|---|
| `@since` Javadoc tag + 反射 | 反射复杂 + javadoc-only 不可靠 |
| jar `Implementation-Version` manifest | 跨打包工具不一致,**不**可移植 |
| 单独 `ContractVersion.java` 集中表 | 违反"Slot 自治",改 1 个 Slot 要改 2 个文件 |
| `package-info.java` | 不支持字段,只支持 annotation |
| 每个接口内 `public static final String CONTRACT_VERSION` | **CHOSEN** —— 与 API 表面共生,改方法时 IDE 提示改版本,跨打包工具一致 |

**Implementation note**: `SlotRouter<P, T>` 父类构造期通过反射读取 `P` 对应 Slot 接口的 `CONTRACT_VERSION` —— 由于 `SlotProvider<T>` 泛型 `T` 在编译期擦除,实际做法是 **`SlotRouter` 子类构造时显式传 `slotContractVersion` 参数**(避免反射),或 `SlotRouter` 子类在 super 调用前通过 `T.class.getField("CONTRACT_VERSION")` 反射读 —— v1 选**反射**方案(Slot 接口数量 ≤ 13,反射开销启动期一次,可接受)。

---

## D-03 — 兼容策略 = backward-compat within major(同 major + Provider minor ≤ Slot minor)

**Decision**: `Version.isCompatible(String providerVer, String slotContractVer) → boolean` 规则:
1. **major 不同** → 不兼容(返回 false)
2. **major 相同**:
   - Provider minor < Slot minor → 兼容(允许滞后)
   - Provider minor == Slot minor:
     - Provider patch < Slot patch → 兼容
     - Provider patch == Slot patch → 兼容(精确匹配,最高优先级)
     - Provider patch > Slot patch → 不兼容
   - Provider minor > Slot minor → 不兼容(Provider 可能用了 Slot 未声明的方法)

**Rationale**:
1. 与 Java 自身 SPI 演进习惯一致(JDK module `@since` + module-info 兼容矩阵)
2. "Provider minor 滞后"是合法场景(用户 plugin 还在用老 API,Slot 升级了 minor 但保留老 API)
3. "Provider minor 超前"是危险信号(Provider 用了 Slot 还没声明的方法,运行时 NoSuchMethodError),启动 fail-fast 拦下
4. "major 不同"是大破坏性变更,必须显式 fail(避免用户 plugin 升级到 v2 但 lingshu-core 还在 v1 时静默跑挂)

**Alternatives considered**:
| Option | Why rejected |
|---|---|
| 严格相等才兼容(providerVer == slotContractVer) | 太严格,用户 plugin 滞后 minor 就启动 fail,**不**友好 |
| 完全不校验(只校验非 null + 合法 semver) | spec US2 Scenario 2/3 显式要求不兼容时 fail-fast,放宽就丢核心价值 |
| Provider minor > Slot minor 也兼容 | 危险(可能用了未声明的方法),违背 §17 R-04 缓解 |
| Provider 完全自由(只校验格式不校验数字) | 同上,变成"格式校验"而非"兼容校验" |

**Implementation note**: `isCompatible("1.5.3", "2.0.0")` → false(major 不同);`isCompatible("1.5.3", "1.10.0")` → true(Provider minor 5 ≤ Slot minor 10);`isCompatible("1.10.0", "1.5.0")` → false(Provider minor 10 > Slot minor 5);`isCompatible("1.0.0", "1.0.0")` → true(精确匹配)。

---

## D-04 — 校验时机:启动期(AgentFactory.create() 内),**不**每个 turn 校验

**Decision**: `SlotRouter` 父类**构造期**(被 Spring 实例化时)校验所有 Provider 的 `version()`;`resolve(name, cfg)` 二次校验被选中的 Provider(防 yml 改了 `agent.<slot>.name` 指向 classpath 不存在的 Provider);**不**在每个 turn 跑。

**Rationale**:
1. Spec FR-003 + Assumption A-05 显式约束"启动期校验,版本不变 = 启动一次 OK"
2. **运行时校验开销不可接受**:每个 turn 校验 9 Router × N Provider × semver 比较 ≈ 100+ 次整数比较(虽然快),但没收益 —— 版本是常量,启动期检过就不变
3. **二次校验覆盖 yml hot-reload 场景**:Story #007 yaml-hot-reload 会动态改 `cfg`,但 `cfg` 改变 → 走新的 `create(cfg)` 路径 → Router 已是单例(`byName` map 不变),`resolve` 时再查一次版本**避免** yml 指向未收集的 Provider
4. **turn-time 校验的失败模式**:如果允许 turn-time 校验失败,业务跑到一半挂掉,影响窗口是 turn 内,**比启动 fail 严重**;启动 fail 用户连第一个 token 都拿不到,影响窗口 = 0

**Alternatives considered**:
| Option | Why rejected |
|---|---|
| turn-time 校验 | 运行时开销 + 失败模式恶劣 |
| 完全不二次校验(只构造期校验) | yml hot-reload 后改 `agent.<slot>.name` 指向未注册 Provider 会静默失败(其实 `resolve` 第一步就会 NPE,**不**静默,但错误信息不友好) |
| 校验 + 缓存(每个 Provider 校验一次,缓存结果) | 校验结果本身就是 O(1) 整数比较,缓存无收益 |
| 启动期 + resolve() 二次校验 | **CHOSEN** —— 启动期挡 99% 失败,resolve() 兜底 hot-reload 边界 |

**Implementation note**: `SlotRouter.resolve(name, cfg)` 在 `byName.get(name)` 返回非 null 后,再做一次 `Version.isCompatible(p.version(), T.class.getField("CONTRACT_VERSION"))` —— 这一步**很轻**(整数比较),不显著影响 resolve 性能。

---

## D-05 — 错误码 `LINGS-S05 PROVIDER_INIT_FAILED`(本 Story 新增 1 个)

**Decision**: 新增 `LINGS-S05 PROVIDER_INIT_FAILED` 错误码(在 constitution §4 错误码表"Slot 域 S"新增一行),由 `ProviderInitException extends IllegalStateException` 抛出,带 `errorCode="LINGS-S05"` 字段 + cause chain + 可选 `hint` 字段。

**Rationale**:
1. 符合 constitution §11 #4 "≤ 3 个 ErrorCode" 约束(本 Story 引入 1 个)
2. 与 Story #001 的 `LINGS-S01 SLOT_NOT_FOUND` 区分:`S01` 是"name 不在 Router",`S05` 是"Provider 格式 / 版本校验失败" —— 两类不同的失败模式,用户错误排查体验更清晰
3. `IllegalStateException` 而非 `IllegalArgumentException` 是因为失败发生在**启动期**(`@Component` 构造器),不是用户传错参数 —— `IllegalStateException` 语义更准确
4. cause chain 长度 ≥ 2:`ProviderInitException(cause: IllegalArgumentException("version 'v1' must be..."))`,保留底层错误信息

**Alternatives considered**:
| Option | Why rejected |
|---|---|
| 复用 `LINGS-S01 SLOT_NOT_FOUND` | 语义不准确(S01 是"name 不在 byName",S05 是"version 不兼容"),用户排查体验差 |
| 用 Spring `BeanCreationException` | 把 SPI 错误和 Spring 容器错误混在一起,偏离 §15 域字母约定 |
| 自定义 `RuntimeException` 而非 `IllegalStateException` | 语义偏差 —— IllegalStateException 准确表达"对象初始化失败" |
| 引入 2 个新 ErrorCode(S05 + S06) | 超出 §11 #4 约束,本 Story 用 1 个足够 |

**Implementation note**: `ProviderInitException.errorCode()` 返回 `"LINGS-S05"`;`ProviderInitException.hint()` 返回人话建议(如 `"hint: implement version() returning '1.0.0' on your @Component class"`);AgentFactory 启动期 catch 住后,stderr 输出 ERROR 级日志后 JVM 退出 1(Spring 默认行为,`@Component` 构造失败 → Spring `ApplicationContext` 初始化失败 → JVM 退出)。

---

## D-06 — `Version` 工具类位置:`ai.lingshu.core.spi.Version`(与 SlotProvider / SlotRouter 同包)

**Decision**: 新增 `ai.lingshu.core.spi.Version` 工具类(与 `SlotProvider` / `SlotRouter` 同包),提供静态方法 `parse(String) → int[3]` / `isCompatible(String providerVer, String slotContractVer) → boolean` / `format(int[3]) → String`。

**Rationale**:
1. **同包原则**:Version 是 SlotProvider / SlotRouter 的**支撑 utility**(被 SlotRouter 构造期 + resolve() 调用),与 SPI 抽象同包,**不**单独开 `ai.lingshu.core.util.version`
2. **静态方法 + 不可变**:Version 是纯函数(无状态),用静态方法 + 返回新 `int[3]` 数组(避免 caller 篡改);**不**做 `Version` class 实例化(避免 Lombok `@Value` 30 行 vs 静态工具 30 行的对比,Lombok 反而复杂)
3. **不可变 `int[3]`**:Java 数组本身可变,但 Story #003 只在构造期 / resolve() 用,生命周期短;**不**过度封装(`List<Integer>` / `ImmutableIntList`)增加复杂度

**Alternatives considered**:
| Option | Why rejected |
|---|---|
| `ai.lingshu.core.util.Version`(单独 util 包) | 与 SPI 抽象跨包,import 噪音 |
| `ai.lingshu.core.spi.Version` + 静态方法 | **CHOSEN** —— 同包,纯函数 |
| `Version` Lombok `@Value` class(可实例化) | 静态方法更直接,Lombok 增加 import 噪音 |
| 放进 `SlotRouter.java` 内部 | `Version` 是独立 utility,被 9 Router 共用,**不**属于某一个 Router |
| 用 `javax.lang.model.SourceVersion`(JDK 自带) | API 是给 annotation processor 用的,不适合运行时版本比较 |

**Implementation note**: `Version.java` 约 50 行 —— `parse`(~15 行)/ `isCompatible`(~10 行)/ `format`(~5 行)/ `validateSegment`(~5 行)+ class-level Javadoc(~10 行)。**不引** Guava / Apache Commons。

---

## D-07 — 9 个默认 Provider stub 升级:从"throws UnsupportedOperationException"到"返回真实实例 + version()=1.0.0"

**Decision**: Story #001 落地的 9 个默认 Provider stub(部分仍 throw `UnsupportedOperationException`)全部升级:
1. 加 `@Override public String version() { return "1.0.0"; }`
2. 改 `create(AgentConfig)` body:`DefaultPromptBuilderProvider` 已实装(Story #002),保持;其余 8 个 Provider(`AnthropicLlmProviderFactory` / `DefaultToolExecutorProvider` / `AllowAllPermissionPolicyProvider` / `FileSessionStoreProvider` / `TruncatingCompactorProvider` / `LinearTurnEngineProvider` / 4 个 MemorySource Provider / Slot 9 stub)全部把 `throw new UnsupportedOperationException("TODO: Story #NNN")` 改为 `return new XxxImpl(config)`(实例化已有 concrete impl)
3. 不改**优先级 / name / create 签名**

**Rationale**:
1. Spec FR-007 + Assumption A-09 显式要求 9 默认 Provider 全部带 `version()="1.0.0"` + 真实 `create()` body
2. 现有 concrete impl 已经写好(`DefaultPromptBuilder` / `LinearTurnEngine` / `DefaultToolExecutor` / `AllowAllPermissionPolicy` / 4 个 MemorySource),Provider 只是少一个 `new` 调用 —— 改 ~10 行 × 9 Provider = 90 行净增,**不**是大量新代码
3. Story #003 不实现具体业务逻辑(那是 #004 Tool 业务 / #014 SessionStore 落地的活),**只**补 `version()` + 改 `create()` body 为 `new XxxImpl(config)` —— 这是"接通 stub 到 concrete"的最小改动
4. 9 Provider 改完后,`factory.defaultConfig()` 真正能跑通 ReAct(否则 `create()` 抛 `UnsupportedOperationException`)

**Alternatives considered**:
| Option | Why rejected |
|---|---|
| 保留 `UnsupportedOperationException` | `version()` 校验就 fail-fast,Story #003 的兼容校验无法跑通 |
| 改 1—2 个 Provider,其余留给后续 Story | spec FR-007 显式要求"全部 9 个",部分改会让启动 fail |
| 删 Provider stub,只保留 concrete impl | 破坏 §5.5 多 Provider 模式(用户无法 `@Component implements XxxProvider` 加新实现) |
| 把 `version()` 抽到 concrete impl | Provider 是工厂,concrete impl 是产品;版本属于工厂(生产者契约) |

**Implementation note**: 9 Provider 改动**逐个 commit**便于 review,但**1 个 PR**合入(避免 9 个 commit 拉长 PR 时间线);具体 commit message:`feat(agent): Story #003 spi-slot-router — Provider.version() + SlotRouter 兼容性校验 + 9 默认 Provider 升级`。

---

## D-08 — `describe()` 方法位置:`SlotRouter` 父类暴露 `List<String> describe()` 同步方法

**Decision**: `SlotRouter<P, T>` 父类新增 `public List<String> describe()` 同步方法,返回 N 行字符串(`"  <name> v<version> (priority=<n>)"`),供 `factory.create(cfg).description()` 调 AgentFactory 聚合 9 Router 输出。

**Rationale**:
1. Spec US3 Scenario 1 + FR-009 显式要求 `factory.create(cfg).description()` 含 9 行 Slot 自描述
2. **`SlotRouter` 父类暴露**而非每个子类各自实现:9 Router 行为统一(打印 name + version + priority),父类抽出公共方法;子类**不**需 override
3. **同步 + 只读**:`describe()` **不**抛异常(版本在构造期已校验),**不**读文件,**不**调 LLM;可在 log 抓取 / health endpoint / debug 命令多次调用,**无副作用**
4. **`AgentFactory.description()`** 聚合 9 Router + 头部(`AgentFactory v<X.Y.Z> for JVM <java.version>`)+ 末尾(`Turn=0 Session=<sessionId>`)

**Alternatives considered**:
| Option | Why rejected |
|---|---|
| 每个 Router 子类各自实现 `describe()` | 9 份重复代码,违反 DRY |
| `describe()` 走 `LOG.info` 重新打日志 | 重复打印,污染 stdout |
| `describe()` 异步 + Future | 不需要,纯读 map 操作 O(N) 同步开销可忽略 |
| 放进 `SlotProvider` 接口(每个 Provider 各自 describe) | 同名冲突 + 版本归属 Router 层级,放 Router 更准确 |

**Implementation note**: `describe()` 返回 `List<String>` 而非 `String`(避免 caller 拼接时出错);AgentFactory 聚合时 `String.join("\n", allLines)`。

---

## D-09 — 不创建独立 `SlotResolver` 抽象(SlotRouter 父类已足,Story #002 D-07 已 defer)

**Decision**: Story #003 **不**新建 `SlotResolver` 抽象;沿用 Story #002 research.md D-07 的 defer 决策(理由:`SlotRouter<P, T>` 父类已涵盖 9 Slot 公共逻辑;AgentFactory 直接 `@Autowired` 9 Router,无需中间层)。

**Rationale**:
1. **复用 > 新增**:Story #001 已落地 7 Router + Story #002 加 1 Router(MemorySourceRouter)= 8 Router,父类抽象足够
2. **避免 scope creep**:Story #003 焦点是"版本校验",不是"Slot 抽象重构"
3. **Story #007 yaml-hot-reload 才需要 `SlotResolver`**:hot-reload 要求"统一遍历 9 Router 重新 resolve",那时再抽 `SlotResolver` 不迟(可参考 Dubbo `ExtensionLoader` 的演进路径)
4. **AgentFactory 7 项校验 + Router 直接调用已跑通**:Story #001 AC-01-1 + Story #002 AC-09 都基于"AgentFactory + 7 Router"模式,**不**改

**Alternatives considered**:
| Option | Why rejected |
|---|---|
| 新建 `SlotResolver` 抽象 | scope creep;Story #003 焦点是版本校验 |
| 复用 Story #001 / #002 的 Router + AgentFactory 模式 | **CHOSEN** —— 已跑通,Story #003 只加 version() 字段 + 校验逻辑 |
| 抽 `SlotProviderRegistry` 注册中心 | 与 Spring `@Component` 重复,无收益 |

**Implementation note**: 9 Router 各自独立,`SlotRouter` 父类加 `version()` 校验 + `describe()` 方法,**不**改 AgentFactory 7 项校验清单(`validate(config)` 不变)。

---

## D-10 — 零新依赖(R-13 mitigation (d) 硬约束)

**Decision**: Story #003 引入**零**新 Maven 依赖。`mvn dependency:tree` before/after Story #003 必须 diff = 0。

**Rationale**:
1. constitution §10 R-13 mitigation (d):"Story #001 / #003 / #009 实施者必须先 `mvn dependency:tree` 自查 + 贴关键子树到 PR body"。**Story #003 是 R-13 显式列出的 3 个高风险 Story 之一**,自查流程是硬约束
2. `Version` 工具类 ~50 行手写,无 `com.github.zafarkhaja:jsemver` 需求
3. `ProviderInitException extends IllegalStateException`,无 Guava `Preconditions` 需求
4. `SlotRouter` 父类构造期反射读 `T.class.getField("CONTRACT_VERSION")`,JDK 8 自带
5. Story #003 PR body **必须**包含 `### R-13 dependency:tree 自查` 节

**Verification command**(PR 前必跑):
```bash
mvn -pl lingshu-core dependency:tree -Dverbose=true > /tmp/deps-003.txt
diff /tmp/deps-002-baseline.txt /tmp/deps-003.txt  # expect zero delta
```

**Alternatives considered**:无 —— R-13 硬约束无替代方案。

---

## Phase 0 Done When

- [x] Zero `NEEDS CLARIFICATION` markers in spec.md(verified at line scan)
- [x] 10 key decisions documented with rationale + alternatives considered
- [x] All decisions consistent with constitution §1-§10
- [x] R-13 self-check command documented for PR body

**Next Phase**: Phase 1 — write `data-model.md` + `contracts/` + `quickstart.md` + fill `plan.md`。
