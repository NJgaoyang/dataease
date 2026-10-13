# DataEase V3 第一阶段增强开发交接文档

> **交接日期：2026-10-13**  
> **接手对象：本地 AI / 开发人员**  
> **目标仓库：** https://github.com/NJgaoyang/dataease  
> **唯一开发目标分支：** `dev20261012`（基于 `dev-v3`）  
> **本文件编写时的已实现代码 HEAD：** `ef6618f0a718f7e97c2044efb3160ca7b48de491`  
> **状态：第一阶段进行中，尚未达到生产交付/验收标准。**

## 0. 给接手 AI 的执行说明（先读）

你是一名资深 Java/Spring Boot + Vue 3/TypeScript 全栈工程师，同时负责产品交互、安全设计与定向测试。请以仓库当前真实代码、`AGENTS.md`、现有路由及 API DTO 为依据，**直接在 `dev20261012` 完成第一阶段遗留开发，不要只输出方案，也不要每完成一点就停下询问**。用户已授权在该开发分支修改、测试、提交；**未授权对线上/现有运行服务发布或进行破坏性数据库操作**。

硬性要求：

1. **绝不复制、移植或反编译 de-xpack/XPack 专有实现**；本项目是基于开源 DataEase V3 的独立权限增强模块，遵守仓库 LICENSE 及相关授权边界。不要绕过许可证校验。
2. 维护 **可选启用、默认关闭** 的原则：`dataease.enhance.enabled=false` 时社区版原有行为不变；启用时权限验证必须后端执行且失败即拒绝。
3. 增量改动，尽可能限定在 `addons/dataease-enhance/`，核心模块只保留必要桥接/拦截点；不要重构无关代码或引入反向依赖。
4. **先核对问题是否已修复**，防止重复做旧版本任务；修改前检查真实 API、路由和当前 Git 状态。
5. 优先只验证改动模块；但是在权限体系正式验收前必须有完整 Maven 编译、实际 HTTP/数据库/SQL 集成测试，以及最小必要的前端页面测试。区分“隔离测试通过”和“运行版验收通过”。
6. 所有成功修改**提交到 `dev20261012`**。不要向 `dev-v3`、其他项目或生产实例推送；不要使用 `git reset --hard` 清除有未提交改动的工作目录。
7. Java Long/Snowflake ID 给前端要确保字符串传输，不得损失整数精度；输入/输出不能泄漏密码散列、JWT、密钥、SQL 敏感数据。
8. **不要因交接文档写“实现”就放开非管理员图表/数据集/导出接口**；必须先完成整个取数链条的行、列、资源鉴权与测试。

建议先执行 `git fetch origin dev20261012 && git checkout dev20261012 && git pull --ff-only`，前提是这是**干净的正常工作克隆**。若已有本地改动，先保存并审查，不执行破坏性切换。最安全的方式是在新目录从远端克隆开发分支。

## 1. 项目目标与第一阶段范围

- 产品：DataEase V3 **3.2.0** 的独立企业权限增强；不是复刻付费 XPack。
- 第一阶段：**用户身份认证、单一组织树、组织成员、系统/组织角色、菜单访问控制、资源归属/访问控制、数据集行列权限、必要审计和已有管理页面可用性**。
- 期望：管理员可登录、配置用户/组织/角色及数据集可见范围；普通用户只看到授权菜单、只访问授权数据；越权请求被服务器拒绝；在 30+ 内部用户规模下可稳定使用。
- 保持开源 DataEase 原数据连接、数据集、图表、仪表板工作流，不额外导入示例数据。
- 架构：`addons/dataease-enhance` 是独立 Spring Boot 条件模块；`core-backend` 用少量适配器接入原生身份和查询；前端复用现有 DataEase V3 管理页面/交互，不另做臃肿页面。
- 范围边界：授权链接分享、外部嵌入、定时报表、工作流/代理、异步导出等需单独梳理权限。**未实现/未校验的入口必须保持禁止或明确不支持**，不可默许全量数据可见。

## 2. Git 事实、历史提交与本地工作目录陷阱

### 2.1 已提交到远端的历史（从旧到新）

| 序号 | 提交（前 7 位） | 实现内容 |
|---|---|---|
| 1 | `06fc89b` | 条件启用的身份、组织、ACL、JWT、初始化表与模块基础 |
| 2 | `29bc3b9` | 动态菜单、作用域角色与迁移版本追踪 |
| 3 | `5c1ea1b` | 组织/角色 ID 串行化及查询兼容修复 |
| 4 | `4ae1029` | scoped 组织角色、登录锁定/Token 失效等加固及测试 |
| 5 | `8b18b06` | 兼容既有组织接口、删除安全检查 |
| 6 | `f6ed44a` | V3 用户管理接口、独立初始密码、V002 用户资料与逻辑删除 |
| 7 | `178d90f` | V003 资源归属表、跨组织授权阻断 |
| 8 | `3f2b204` | 原生 CorePermissionManage 适配和组织成员管理 |
| 9 | `1deddae` | WebSocket 认证 Bean 隔离及 Token 撤销测试 |
| 10 | `610b667` | JWT 身份绑定原生 `V3UserUtil`，MVC 守卫、请求结束恢复 |
| 11 | `b758d62` | V004 数据集列白名单、原生字段投影接入 |
| 12 | `ef6618f` | V005 等值行过滤、元数据字段核对与原生 SQL 树接入 |

远端比较：https://github.com/NJgaoyang/dataease/compare/dev-v3...dev20261012

**截至交接前，代码分支比 `dev-v3` 多 12 次提交，0 次落后；交接文档将作为独立新提交，不算上述 12 个业务提交。** 以接手时 `git log` 为最新事实来源。

### 2.2 危险点：开发机“本地 HEAD 旧、工作目录内容新”

原开发机路径：`/opt/dataease-v3-dev20261012`。截至 2026-10-13：

- 该目录执行 `git rev-parse HEAD` 仍显示旧基线 `d4c51b8894fe6af823cecfde30c408fd7d3c8d35`，**并非**远端最新提交；它是稀疏/未完全同步的开发副本。
- 目录的 `core/core-backend`、`sdk/common` 等文件有大量 `M`，`addons/` 为 `??`；其中绝大多数修改**已通过 GitHub API 单独提交至远端**。不要把它们当成“全部未提交待提交”，也不要基于旧 HEAD 直接 `git push`。
- **明确发现唯一独立的本地待审核文件：** `addons/dataease-enhance/src/main/java/io/dataease/enhance/EnhancePermissionReadServer.java`，用于 `GET /enhance/permissions/roles/{rid}/config` 和 `GET /enhance/permissions/resources`。它写入了旧工作目录，但**未提交到 `dev20261012`、未做完整测试**。接手时应从该文件读取设计意图，审计权限/返回值后移植到干净分支；不能假定其已经在远端。
- 本地存在临时测试辅助目录 `/tmp/enhance-sandbox/`，含编译用 SDK DTO/V3UserUtil **桩实现**、自定义 JUnit 反射运行器；**不是实际 SDK 或完整项目**，不提交这些临时桩到仓库。
- 最安全操作：**另起干净克隆**，将远端 `dev20261012` 作为唯一代码真相；只选择性提取上述未提交且有价值的文件，别对旧稀疏目录执行强推、重置或全量 add。

建议：

```bash
git clone --branch dev20261012 --single-branch \
  https://github.com/NJgaoyang/dataease.git dataease-phase1
cd dataease-phase1
git status --short
git log -1 --oneline
git diff dev-v3...dev20261012 --stat  # 如本地没有 dev-v3 引用，先 fetch
```

**提醒：** 本次文档本身加入分支后，`git log -1` 应当是文档提交，倒数第二个业务提交仍是 `ef6618f`；具体以最新历史为准。

## 3. 已实现能力（代码存在，不代表已端到端验收）

### 3.1 身份认证、会话、组织、角色

主要类（相对仓库根目录）：

- `addons/dataease-enhance/src/main/java/io/dataease/enhance/EnhanceConfiguration.java`、`EnhanceSettings.java`：Spring Boot 条件启用，BCrypt、ID 生成；开关默认 false。
- `EnhanceIdentityService.java`、`EnhanceLoginServer.java`、`EnhanceAccessController.java`：bcrypt 登录、JWT 签发/校验、Token 版本撤销、退出/更换密码及失败次数锁定。
- `EnhanceSchema.java`：`de_ext_*` 元数据表初始化及增量版本；首次启用需要管理员 bootstrap 密码，重复启动不重置已设密码。
- `EnhanceSession.java`：增强模块请求内身份 ThreadLocal；每次请求 finally 清理。
- `EnhanceTokenFilter.java`：HTTP Token 验证；普通用户路由采用受限白名单，其余 BI 主数据接口目前拒绝。注意：`/enhance/` 前缀通过过滤器，但**每个控制器自身必须严谨鉴权**。
- `EnhanceNativeIdentity.java`（模块）与 `core/core-backend/.../enhance/EnhanceNativeIdentityAdapter.java`（核心）：通过已验证的身份进入/退出 `V3UserUtil.getUid()/setUid()` 范围。
- `core/core-backend/.../enhance/EnhanceNativeIdentityGuard.java` + `core/core-backend/.../config/DeMvcConfig.java`：检查原生上下文 UID 未被其他 MVC 拦截器覆盖；异常拒绝请求。
- `EnhanceWebSocketConfiguration.java` + `core/core-backend/.../websocket/config/WsConfig.java`：条件启用增强 WS 认证，避免社区默认 Bean 同时创建；测试了 Token 撤销后的 Socket 认证拒绝。
- `EnhanceOrgServer.java`、`EnhanceOrgCompatServer.java`、`EnhanceOrgGovernanceController.java`、`EnhanceOrgMemberServer.java`：组织/成员的兼容 API、组织角色创建/分配/移除、最后一位有效组织管理员保护。
- `EnhanceUserServer.java`、`EnhanceAdminController.java`、`EnhanceRoleServer.java`：用户/角色 CRUD 及既有 V3 URL 兼容，单用户初始随机密码、软删保留历史引用、账号不重用、禁用/删除撤销会话。
- `EnhanceMenuFilter.java`、`core/.../menu/server/MenuServer.java`：动态菜单筛选。菜单显示不代表后端鉴权，必须与资源权限相配合。

### 3.2 资源归属、菜单 ACL、数据集行/列权限

- `EnhanceAcl.java`：角色与菜单/资源操作的授权检验，资源必须登记属于同一组织才能赋权，未知资源禁止。
- `EnhanceCoreAuthorization.java` 与 `core/.../system/manage/CorePermissionManage.java`：模块开启时为核心资源权限检查提供后端代理；关闭时保留社区行为。初步映射：`DATASET→dataset`、`PANEL→dashboard`、`SCREEN→screen`、`DATASOURCE→datasource`；读、管理、导出映射已有基础规则。
- `EnhanceDatasetColumnPolicy.java`、`EnhanceDatasetColumnController.java`：角色-数据集-字段 ID 白名单，未配置返回空列；不受信任的前端不能自行扩大允许字段。
- `EnhanceDatasetRowPolicy.java`、`EnhanceDatasetRowController.java`：只接受预配置的**普通字段等值规则**（`fieldId + value`），不接受任意 SQL；跨组织、未知数据集/字段、空行规则拒绝。多个值形成原生查询树中的多个条件，复用已有 `WhereTree2Str`。
- `EnhanceDatasetFieldLookup.java` + `core/.../enhance/EnhanceDatasetFieldLookupAdapter.java`：从 DataEase 实际字段元数据校验行过滤所引用的字段归属与类型；失败时拒绝。
- `core/.../dataset/manage/PermissionManage.java`：对普通增强用户返回受限行过滤树，并对字段投影应用允许列白名单；数据仍需要在**实际 SQL 执行链**测试证明规则生效。
- `core/.../chart/manage/ChartDataManage.java` 与 `core/.../engine/trans/WhereTree2Str.java` 为后续端到端检查的关键原生调用链；上次开发未宣布整个链路验收通过。

**关键限制：** 尽管已经构建行/列规则，`EnhanceTokenFilter` 仍对普通用户未列入白名单的 `/chartData/*`、`/datasetData/*`、导出接口等拒绝。**这是有意的安全措施，绝对不要为了看见图表直接改成全部放行。**

### 3.3 用户管理和页面

在 `core/core-frontend/src/views/menu/system/` 复用现有页面：

- `sysuser/UserForm.vue`、`member/UserForm.vue`：已经提交过初始密码“一次展示”兼容修改，对应后端 `POST /user/create` 返回 `DE_ENHANCE_ONETIME:<password>`，响应 `Cache-Control: no-store`。
- `org/DeptEditor.vue`、`org/AddMemberDialog.vue`、`org/api.ts` 仍需逐个按真实请求/响应联调（其中成员 API 由 `EnhanceOrgMemberServer` 实现）。
- `member/RoleManage.vue`、`member/RoleForm.vue`、`member/OrgUser.vue` 复用已有 UI 框架；其调用 `src/api/user.ts` 的 URL/DTO 与当前增强接口**不保证全部对齐**。
- `auth/ResourceConfig.vue`、`auth/UserConfig.vue` 等属于原有复杂权限管理页，后端未提供全部等价接口；不能因静态页面存在就宣称权限 UI 已完成。
- 本地文件 `EnhancePermissionReadServer.java`（未提交）可作为“按角色读取菜单/资源/行列策略配置”的后端只读接口草稿，但应先核查泄露和组织权限，再纳入正式提交。

## 4. 已完成的增强数据库迁移

迁移文件都在 `addons/dataease-enhance/src/main/resources/db/enhance/`，由 `EnhanceSchema` 按版本执行：

| 版本 | 文件 | 内容 |
|---|---|---|
| V001 | `V001__identity_org_acl.sql` | `de_ext_user`、组织、成员关系、角色、用户角色、菜单/资源 ACL、审计及对应基础表 |
| V002 | `V002__user_profile_and_soft_delete.sql` | email、phone_prefix、phone、deleted；Java 实现逐列检查，支持 ALTER 中断后重试 |
| V003 | `V003__resource_ownership.sql` | `de_ext_resource_registry`，记录资源类型、ID、唯一所属组织 |
| V004 | `V004__dataset_column_allow.sql` | `de_ext_dataset_column_allow`：角色数据集字段允许清单 |
| V005 | `V005__dataset_row_equal.sql` | `de_ext_dataset_row_eq`：角色数据集字段等值行过滤规则 |

版本追踪表：`de_ext_schema_version`。增量结构设计为不覆盖原社区版业务表；**未做 MySQL 正式升级试运行**。H2 + MySQL 模式通过不代表 MySQL、StarRocks 方言都验证。正式启用前：备份 → 独立库迁移 → 重复启动 → 部分失败恢复 → 权限/数据验收。不得直接改写已用过的 V001–V005；新的数据结构走 V006 及以后。

首次激活示例**仅供隔离环境参考（不包含真实密钥）**：

```yaml
dataease:
  enhance:
    enabled: false  # 当前推荐：保持关闭，直到验收完成
    token-secret: ${DE_ENHANCE_TOKEN_SECRET}
    bootstrap-password: ${DE_ENHANCE_BOOTSTRAP_PASSWORD}
    session-minutes: 60
    max-login-failures: 5
    lock-minutes: 15
    node-id: 1
```

真实秘钥通过环境变量/密钥管理配置，严禁提交。bootstrap 密码仅用于空增强存储首次初始化，不要将随机演示值当生产凭据。

## 5. 核心接口契约与需要核对的兼容性

| 类型 | 路径/逻辑 | 当前状态与注意事项 |
|---|---|---|
| 登录 | `/login/localLogin` | 条件替补登录；需与前端登录请求和实际 token response 联调 |
| 当前用户 | `GET /user/info`、`/user/personInfo` | 已实现增强 DTO；检查权限 Store 是否兼容 |
| 用户管理 | `POST /user/create`、`/user/edit`、`/user/enable`、`/user/delete/{id}`、`/user/pager/{page}/{size}` | 已实现主体，初始密码仅创建成功时显示一次；检查错误和数据兼容 |
| 组织成员 | `POST /sys/org/member/page`、`/candidates`、`/roleOptions`、`/add`、`/switchRole`、`/remove/{uid}/{oid}` | 已有增强接口，需前后端实际 HTTP 验证 |
| 角色/组织 | `/role/*`、`/org/*`、`/enhance/org/*` | 兼容部分原有 URL；需校验全部真实组件调用 |
| 权限注册 | `POST /enhance/admin/resources/register` | 仅系统管理员；尚未绑定原生资源归属变更事件 |
| 资源 ACL | `PUT /enhance/admin/roles/{id}/resources` 或 `/enhance/org/roles/{id}/resources` | 已有角色/组织校验，须与列表读取/前端保存联调 |
| 列策略 | `PUT /enhance/permissions/roles/{roleId}/datasets/{datasetId}/columns` | 保存 `List<Long>`，空列表意味着全部字段不可见 |
| 行策略 | `PUT /enhance/permissions/roles/{roleId}/datasets/{datasetId}/rows` | 保存非空 `[{fieldId, value}]`，字段 ID 对应真实数据集 |
| 原生权限 | `CorePermissionManage.checkAuth(BusiPerCheckDTO)` | 已加桥接，但不是每个入口都调用；不能据此推断完全安全 |
| 图表数据 | `POST /chartData/getData` → `ChartDataManage.calcData` | 普通用户尚未开放，必须全链路授权 |
| 数据预览 | `DatasetDataServer.previewData` → `DatasetDataManage.previewDataWithLimit(..., checkPermission=false, ...)` | **高危核查点**：部分原路径显式跳过权限；先修后考虑放开 |
| 导出/下载 | `chartData/innerExport* `、`ExportCenter*` | 尚未端到端验收，普通用户保持拒绝 |
| 分享/嵌入 | 原生 `DeLinkPermit`、`ShareAuthorizationManage`、分享/访客入口 | 需单独核对签名、身份和缓存，不能直接继承普通用户权限 |

**特别提醒：** URI 和方法以运行时代码、SDK 注解以及前端 `src/api/` 为最终依据。本表是接手导航，不是声称所有 API 现已可运行。

## 6. 实际做过的测试、未做的测试和构建问题

### 6.1 已做：模块级定向验证

之前在开发机 `/tmp/enhance-sandbox/` 使用真实缓存依赖 + 临时 SDK/V3UserUtil DTO 桩，对关键类进行独立 `javac`、JUnit 反射运行：

- `EnhanceFoundationTest`：11/11（登录、失败锁定、Token 撤销、跨组织权限、菜单 ACL、未授权查询过滤、组织删除等）。
- 额外 H2 单测（各 1）：`EnhanceUserFlowTest`、`EnhanceResourceOwnershipTest`、`EnhanceOrgMemberServerTest`、`EnhanceCoreAuthorizationTest`、`EnhanceWebSocketAuthTest`、`EnhanceDatasetColumnPolicyTest`、`EnhanceDatasetRowPolicyTest`，各自独立运行通过。**累计 18 项模块 H2 定向测试**（11 + 7）。
- `core/core-backend/src/test/java/io/dataease/enhance/`：`EnhanceNativeIdentityAdapterTest`（2）、`EnhanceNativeIdentityGuardTest`（1）、`EnhanceRequestIdentityTest`（1），**4 项隔离身份测试通过**。
- 曾检查 39 个 Java 源文件的语法解析无错，`git diff --check` 通过；这不是全部 Maven 模块的字节码编译。
- `EnhanceDatasetRowPolicyTest` 检查行规则对象、组织隔离和记录撤权，但**没有真正使用 MySQL/StarRocks 执行 SQL**，也没有验证所有图表/缓存/导出路径。

以前通过的测试不保证接手时仍通过；接手后应在正确依赖下运行，记录新的具体结果。

### 6.2 未做/不能冒称通过的验证

- **尚无完整 Maven reactor 编译成功记录；尚无完整前后端打包/启动/HTTP 页面验收。**
- 原稀疏目录缺 `core/core-frontend`，执行 `mvn -f core/pom.xml` 会报子模块不存在；直接 `mvn -f core/core-backend/pom.xml -o ...` 曾缺 `com.mysema.maven:apt-maven-plugin:1.1.3`。
- 尝试在线后端编译仍缺 `com.dm:DmDialect:6`、`io.dataease:api-base/api-permissions/api-sync/dataease-enhance:3.2.0` 等依赖；还要核对上游自定义 Calcite/许可证 SDK 在正确仓库和合法凭据下能否解析。禁止用反编译或复制专有 JAR/源码绕过授权。
- 开发机另有尝试下载完整 GitHub 分支的 `/tmp/dataease-phase1-source/source.zip`；该文件曾存在，但**未确认已成功完整解压/构建**。交接后可在新的完整工作克隆上正规构建，不要把下载开始当成完成。
- 仓库后端 Surefire 可能显式设置 `<skip>true</skip>`；`mvn test` 返回 BUILD SUCCESS 不等于实际执行单测，必须核验报告。

在新**隔离环境**且依赖就绪后，优先：

```bash
# 在完整克隆的仓库根目录，核对 pom profile 与依赖后执行
mvn -f core/core-backend/pom.xml compile -Pstandalone
# 对目标测试配置 Surefire/JUnit，确认输出实际 TESTS / surefire-reports
# 前端：到 core/core-frontend，安装/使用锁文件对应依赖
npm run ts:check
# 接口与数据库集成：新建隔离数据库、不要复用正式环境
```

不要为验证而直接 `mvn clean`、启动生产服务，或操作任何用户实际业务库。

## 7. 剩余开发任务：按优先级连续完成

以下为**第一阶段必须闭环的事项**，不是“可选优化”。每项先核对现有代码和是否已完成，改动只测受影响范围，最终需进行一次最小端到端权限验收。

### P0-1 完整源码与构建环境恢复

- [ ] 在干净的 `dev20261012` 克隆上核对 `AGENTS.md`、`git status`、当前依赖和 POM 模块拓扑。
- [ ] 让根 SDK → 增强模块 → core-backend 构建链实际成立：缺失 SDK/DmDialect/自定义依赖从**合法**依赖仓库获取，不引入 XPack 实现。
- [ ] 修复本阶段新增 Java 的真正编译错误，包括接口返回 DTO、Bean 同名/自动注入、多 Profile 条件、`EnhanceDatasetFieldLookupAdapter` 所依赖的实际字段方法。
- [ ] 能实际启动**隔离环境**的 backend + frontend；构建时要核对 standalone/distributed/desktop 条件组件冲突和应用上下文 Bean 创建。
- [ ] 正式建立可重复 JUnit / Spring MockMvc / H2+MySQL 集成测试入口；不要长期依赖临时反射运行器。

**验收：** 完整核心模块 build 可重现、应用上下文启动、模块禁用/启用两种模式都能启动；无真实凭据进入源码。

### P0-2 权限安全审计与真实 SQL 取数闭环

- [ ] 逐个核对 `ChartDataApi`、`ChartDataServer`、`ChartDataManage.calcData`、图表导出、字段枚举、数据集预览、DatasetSQLBot、数据源直连查询、Dashboard/Screen、复制/导入/分享/缓存路径。
- [ ] 每个取数入口服务端同时核实：已登录且有效的用户 → 资源所属组织 → dataset/dashboard/chart 所属资源关系 → 允许操作 → 列投影 → 行过滤 → 脱敏 → 查询/导出 → 缓存隔离。请求参数中的用户 ID、数据集 ID、view ID 不能信任。
- [ ] **重点审查** `DatasetDataServer.previewData` 调用 `previewDataWithLimit(..., false, ...)`，以及其他 `checkPermission=false`、原生后台任务和定时报表入口；不能只给图表主路径补一处检查。
- [ ] 验证 V005 等值规则最终变成 SQL WHERE，字段引用来自可信元数据（不仅 DTO 对象），值经过原生方言安全转义/参数化。行规则“同角色不同值 OR、多角色不同条件 OR/AND、无规则拒绝、恶意注入、时间/数字类型、计算字段、跨数据源/联邦查询”的业务语义需定稿并测试。
- [ ] 校验列字段过滤不会通过 `allFields`、API 预览、元数据、枚举、grouping/排序/钻取/计算字段侧信道泄漏；严格过滤最终 SQL 投影。
- [ ] 校验原生身份 `V3UserUtil` 在**真正可运行**的过滤器/拦截器顺序及请求结束时无串号；异步/导出任务必须显式传递并验证用户上下文。
- [ ] 将显式放行策略替代目前 `EnhanceTokenFilter.limitedNonAdminEndpoint` 的临时 allowlist；一次仅开放**已逐个测试过**的资源/API，未支持分享/嵌入/导出继续拒绝。
- [ ] 验证 `CorePermissionManage` 权限 Hook 在所有目标入口都被调用，且操作类型 read/manage/export 的效果正确。

**验收：** 至少 2 组织、2 普通用户、不同角色和两城市数据；无授权禁止，授权后只返回期望城市/字段，直接改请求 ID、猜 URL、缓存命中、导出等不能扩大访问；同时不破坏 super-admin 的正常体验。

### P0-3 资源登记与权限管理闭环

- [ ] 资源实际归属要同 `core_dataset_group`、仪表板/大屏、数据源原生元数据核对；当前 `de_ext_resource_registry` 是**人工登记**，没有自动同步移动/复制/删除/恢复，因此不能单独作为最终真实归属证据。
- [ ] 建立可重试的资源生命周期同步/清理；组织删改检查原生 BI 资产（不仅 `de_ext_*`），避免孤立资源及残余授权。
- [ ] 菜单授权、角色授权、资源授权、行列规则保存和实际界面展示统一；确认系统角色与组织角色行为，禁止普通组织管理员提升到 SYS_ADMIN。
- [ ] 完成 `/enhance/permissions/roles/{rid}/config` 等**只读查询接口**，对照上面提到的本地草稿，核查每一个响应字段的组织作用域和敏感数据可见性。

**验收：** 管理员配置权限 → 页面读回一致 → 普通用户权限即时变化 → 删除/撤权旧 Token 与缓存不可继续访问。

### P0-4 前端管理页面实际可用

- [ ] 检查并修复 `views/menu/system/sysuser/UserForm.vue`、`member/UserForm.vue`、`member/RoleManage.vue`、`member/RoleForm.vue`、`org/DeptEditor.vue`、`org/AddMemberDialog.vue` 与后端的 URL、HTTP 方法、payload、`PageResult` 封装、string IDs。
- [ ] 检查 `src/api/user.ts` 与 `src/views/menu/system/org/api.ts`：同一“角色选择成员”前端有 `/user/role/*` 与后端 `/role/user/*` 等路径差异风险；不能只改一个接口就宣布完成。
- [ ] 实现简洁可用的角色权限设置：至少可绑定菜单、资源、数据集列白名单和行过滤条件（本阶段 V005 仅等值）。复用 Element Plus 和项目既有样式；页面文案简洁中文，有必要的成功/失败提示。
- [ ] 修复用户创建“只显示一次初始密码”、启停、重置密码、组织切换、最后组织管理员保护的 UX；不通过浏览器永久存储初始凭据。
- [ ] 检查动态路由/菜单/Pinia 的组织切换缓存与授权即时刷新，避免只隐藏菜单、但权限 Store 与 HTTP 状态不一致。
- [ ] 对可能属于 XPack 的功能入口做合理降级/禁用；不要显示无法工作的假按钮。
- [ ] 用本地前后端实际环境核验管理员和普通用户 CRUD/异常路径，执行至少定向 TypeScript、Vue SFC 编译检查。

**验收：** 管理员从浏览器完成用户、组织、角色、菜单、行列规则的创建、编辑、查看、撤销，不依赖 Postman/直接写库。

### P1-5 分享/嵌入、导出、异步和审计

- [ ] 决定本阶段实际支持边界；不支持时做 UI/API 清晰拒绝，不允许旧社区逻辑在增强模式下匿名绕过。
- [ ] 涉及分享、嵌入、定时报表、导出/下载和异步任务时，分别验证 Token 作用域、后台执行身份、资源归属、权限收回和缓存键。
- [ ] 审计日志补充角色/行列规则/资源授权/成员变更的操作者、所属组织、对象和时间，查询必须安全分页和权限隔离；不得写入密码/Token。
- [ ] 整理 SQL 兼容性和事务/并发；迁移版本表以及新增关系表在 MySQL 真实 DDL 中重复执行和恢复时保持正确。

**验收：** 本阶段明确承诺的每一条外部入口均能正确限权，不支持的入口不会悄然返回敏感数据。

### P0-6 最终最小验收与交付

- [ ] 完整打包并在隔离环境真实启动增强开关关/开两种模式。
- [ ] 做矩阵测试：系统管理员、组织管理员、普通授权用户、普通未授权用户；读、编辑、导出；同组织和跨组织；首次登录、过期/锁定/删除/撤权；无行规则、无列规则、恶意字段/SQL；空数据、分页、切换组织、重启后恢复。
- [ ] 同数据集的单表、多表关联、计算字段和 StarRocks/MySQL 方言至少覆盖业务将用到的组合。
- [ ] 确认增量数据库迁移、默认关闭、升级回滚方式、配置与部署文档；出具已完成/未完成清单和测试记录。只在全部 P0 验收成功且用户明确授权部署时才考虑启用生产实例。
- [ ] 按修改点分批提交到 `dev20261012`，避免因单个阻塞导致功能散落在本地。

## 8. 需要尽早修复/证明的具体安全隐患

1. **非管理员图表取数仍处于阻断状态。** 权限 DTO 有了不等于数据已安全可用；放行前必须给全部取数/导出/预览路径加检查。
2. **原生社区方法可能返回默认 true。** 确保条件增强代理实际注册在核心业务的 Bean 注入链；不能只看单元测试 stub。
3. **人工资源归属有漂移风险。** `de_ext_resource_registry` 当前仅由管理员写入，未核对/同步原生资源拥有者，不能无条件信任。
4. **V005 是受限等值规则。** 多角色组合与原生 `WhereTree2Str` 的 OR/AND、导出条件语义要实测；不存在规则时是拒绝而不是无条件查询。
5. **SQL/字段绕过与元数据泄漏。** 部分预览路径不检查权限；不能只在 `filterColumnPermissions` 截断 UI 字段列表。
6. **请求身份可能跨线程或被覆盖。** Native UID 仅在同步请求范围处理，导出、WebSocket、定时任务需各自验证。
7. **前端同名 API 与后端有兼容差异。** 特别是 `/user/role/option`、`/user/role/selected` vs `/role/user/option`、`/role/user/selected`，需要逐一复查。
8. **权限配置读回接口本地未提交。** 不要误以为远端已有完整权限管理闭环。
9. **数据源的原生行列字段没有跑 MySQL/StarRocks 真实 SQL。** 隔离 H2 仅验证策略存取与 DTO/流程，不等价于防泄漏集成测试。
10. **授权开关保持关闭。** 不能把“模块已存在、单测绿灯”包装为生产可用并部署公网。

## 9. 建议接手顺序与最终交付格式

推荐按以下顺序持续执行，不必每批再向用户确认：

1. **新干净分支克隆、编译与应用上下文**：先找最早的实际编译/Bean 问题；不可借缺失依赖跳过真实构建。
2. **处理本地未提交草稿 + 前后端 API 契约**：审核 `EnhancePermissionReadServer`，整理权限 read/write API，先让管理员配置可见。
3. **单数据集真实 SQL 行列权限**：构造最小示例，在独立 MySQL/StarRocks 跑已授权/未授权行列查询，验证 SQL WHERE 和投影；逐步支持多表、计算字段、图表。
4. **逐一开放并验收业务 URL**：图表查询 → 数据集预览/枚举 → 仪表板/大屏 → 导出/分享（如果阶段范围需要），每开放一条就加入正常与越权用例。
5. **管理前端全流程联调**：现有 Vue 管理页面修复、操作反馈、刷新、中文精简风格。
6. **完整回归范围仅覆盖涉及的权限/查询/页面、双模式构建和最终验收**，提交分批 Git commit 和最终完成清单。

接手 AI 最终应交付：

- 代码提交链接、模块文件/接口修改清单；
- 真正运行过的编译、单测、MockMvc、SQL、前端 UI/手动验收报告；
- 可以启用的隔离部署说明、配套迁移/配置范例、回滚方案；
- 第一阶段逐项核对的验收矩阵，以及仍不支持的功能明确声明。

**在上述验收条件全部满足之前：第一阶段状态必须始终标记为“开发中/未生产验收”，`dataease.enhance.enabled` 保持 false。**

---

> **交接附注：** 当前用户要求的是“整理交接并提交文档”，不是继续提交未经验证的功能。本文件应与业务源码分开形成独立 Git commit；本次不把本地待审核的 `EnhancePermissionReadServer.java` 一并提交，也不更改线上部署。