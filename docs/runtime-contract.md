# AMIS 运行时契约

范围：P0 运行时与 P1 交互、执行生命周期契约。取用必须核对固定输入对应的成功检查和独立发布产物，不能仅凭
本文件判定某个镜像或 jar 已通过发布门。总体目标以部署仓确认的 AMIS Apps 需求为准。
本文件只维护本扩展自己的字段、HTTP 与前端交互约定；根仓生产组合仍须独立验收。

## 声明与范围

- `PageTrigger`：`pageId`、`schema`；schema 使用已定义的安全 `nsfile:///` 引用。
- `ApiTrigger`：`apiId`、`mode`（默认 `ASYNC`）、`syncTimeout`（仅显式 `SYNC`），
  `resultOutput`（默认 `response`，可改为实际 Flow output ID，不是任务 ID 或任务命名规则）。
- trigger 自身 `id` 沿用宿主规则，与 pageId/apiId 分开。同 Flow 的页面和 API 可以同名，
  同一类型的重复规范路径必须拒绝。P0 最多激活一个页面和一个 API，多声明不能静默任选。
- 两类均直接继承 `AbstractTrigger`，不继承匿名 Webhook trigger。
- `kestra.amis-apps.enabled` 控制扩展业务 bean 注册；未启用或 jar 缺席不提供这些入口。
- 入口能力 ID 为 `amis-apps`；模式 B 使用 `EXTENSION_ROUTE / EXECUTE` 及 namespace
  范围，resource ID 为该能力 ID。模式 A 不装 RBAC jar，仍保留生产认证和 CSRF。

## HTTP 路径

| 路径 | 方法 | 结果 |
|---|---|---|
| `/{namespace}/{flowId}/pages/{pageId}` | GET | 独立 HTML 文档、同源登录返回目标 |
| `/{namespace}/{flowId}/schemas/{pageId}` | GET | 完整 AMIS schema JSON，不加 API 数据信封 |
| `/{namespace}/{flowId}/assets/{assetPath}` | GET/HEAD | 本扩展构建清单内的固定 hash 资产 |
| `/{namespace}/{flowId}/apis/{apiId}` | POST | 创建承载 Flow 的 execution，输入为 JSON 对象 |
| `/{namespace}/{flowId}/apis/{apiId}/executions/{executionId}` | GET | 当前 API 的执行状态和获准结果 |

原生路径始终优先。路由身份只来自规范路径，不从 body 或 query 改写。API 不支持匿名，
也不以 GET 创建 execution。页面、schema、资产、API 和轮询均不能绕过适用的两门链。
HTML 文档以外的 API/schema/资产不得因为 `Accept: text/html` 成为登录书签。

页面 schema 可以使用 `post:greet` 这类裸 API ID；fetcher 按当前 bootstrap.apiBase
解析后再核对同 Flow 边界，便于复用父级 schema。该便利规则不允许 `../`、原生管理
API 或跨源地址。Flow ID 与 page/API ID 分别遵守各自已声明的标识规则。

页面/schema 最小操作计划为 FLOW READ 与 EXECUTE。POST 另须执行链的 EXECUTION
CREATE、实际输入/检查的完整依赖计划；需要结果时另检查 EXECUTION READ 与
ACCESS_OUTPUTS。轮询每次重新通过入口和具体 Flow 门，先检查结果读取权限，再查询
execution，并核对 namespace、Flow、API 和执行来源。不得直接调用宿主 Controller。

请求体若使用保留的路由绑定字段 `apiId` 与实际路径冲突，拒绝请求；如 Flow 声明同名
输入，其值由服务端匹配结果提供。可信 execution trigger 元数据也记录实际 apiId 与
调用主体，不把任意 body 内容当作可信触发来源。不渲染客户端提供的模板表达式。

`resultOutput` 从已声明的 Flow outputs 中选取当前 API 的 JSON 对象结果。定义缺失、
类型错误和缺少结果不得当作空成功；P2 再验证多 API 来源隔离，P0 不提前开放多声明。

## 数据响应与轮询

schema 读取只在文件不存在时回溯父级。已读取文件的 JSON 或 AMIS 根结构不合法时返回
`400`，不得回落父级或将其伪装为暂时不可用。宿主读取、身份及授权错误保留其原有失败
语义；未知依赖故障仍失败关闭，不将所有读取异常改成输入错误。

API 数据使用 AMIS 信封：`status`（0 成功，非零失败）、`msg`、对象 `data`；执行响应
另有顶层 `executionId`、`executionState`、`resultReady` 与同源 `executionUrl`。

- 默认 ASYNC POST 返回 202 和真实执行标识，`resultReady=false`，不提前声称表单成功。
- 轮询等待中返回 202；即使 execution 已终态，只要输出尚未可读，仍明确
  `resultReady=false`，不能以 `{}` 冒充成功结果。
- 结果可用返回 200、`resultReady=true`；成功为 `status=0`，终态失败为非零且保留
  真实 executionState。不得回显内部 SQL、凭据或未经审查的异常文本。
- SYNC 和轮询响应均校验 executionId、受支持的 executionState 及 readiness；未知
  状态或仍在 RUNNING 却宣称结果成功必须拒绝，不能显示为正常提交成功。
- SYNC 显式等待受维护参数约束；超时只停止 HTTP 等待，不声称 execution 已取消。
- 前端只轮询初次响应的 executionUrl，检查同源、同 namespace/Flow/API、同 execution
  ID、无凭据及无非法路径编码；不猜测任意 native API URL，不接受重定向到外部源。
- 轮询有间隔、总等待上限、单请求上限和 AbortSignal；卸载取消等待和事件监听。

401、403、404、409、413、429、503 等 HTTP 失败必须明确显示，不转换为空成功。
请求体读取有独立的 `kestra.amis-apps.request-body-timeout`，默认 `PT30S`，维护者可设为
大于零且不超过 30 秒；超时返回 408，关闭 byteBody/输入流，不提交 execution。此边界防止
慢速上传在有界字节数之内仍无限占用授权资源，不能仅依赖浏览器取消或连接空闲超时。
无权的浏览器文档复用宿主拒绝/退出流程；不存在目标仍正常 404。安全响应 no-store；
hash 资产可以 private cache，但缓存的资产不提供 schema、API 或结果权限。

## HTML 与前端资产边界

固定运行时使用 AMIS 6.13.0；具体完整依赖由独立 lockfile 与构建测试锁定。P0 不包含
编辑器依赖，不把 React/AMIS 源码或宿主 UI 私有组件复制进扩展。

HTML 模板包含 `__AMIS_BASE__` 占位的同源 base URL、`amis-bootstrap` JSON 数据节点
及 `csrf-token` meta。后端必须安全转义 JSON/HTML，防止 script 闭合注入。bootstrap V1：
`version`、`namespace`、`flowId`、`pageId`、`schemaUrl`、`apiBase`、`assetBase`，以及
有界 `limits`（`requestTimeoutMs`、`pollIntervalMs`、`pollTimeoutMs`）。
前端读取并验证这些字段，不读 JWT、不持久化 CSRF/token、不从 URL 获得凭据。

资产由 jar 自身提供，不依赖外部 CDN。构建采用相对资产引用，后端使用安全 base URL
使文件在对应 Flow 前缀下装载。schema 数据加载后才调用真实 AMIS renderer。
AMIS fetcher 仅调用同 Flow API 与经过验证的结果 URL；不向外部源或原生管理 API
发送 cookie 写请求。主题、locale 优先复用所消费宿主的公开 SDK 与偏好规则；不得
启动宿主业务 SPA 来读取配置。表单真实提交、等待、终态、失败、退出换号及刷新必须
通过所属仓 Chromium 和根仓生产组合分别验收，测试 transport 必须显式标识。


## P1 交互与执行生命周期

阶段成功必须核对所选正式产物的 sourceCi、独立报告及根仓组合，文档本身不构成通过证据。保持单 Flow、单页面、单 API；多个测试 Flow
分别覆盖场景，不提前开放 P2 多声明。固定消费正式 host digest
`182e03ce88803dc6718bc0d32729e1002af0321fe1dc18d41925342aad17b988`，executable
SHA-256 `18b390e1a2ba1452ce9729b1f149e0d6457735f762395c393b13b1ee9f628fb7`。

### 最小实施与现有机制复用

1. 注册 AMIS 6.13.0 的数字、选择、日期、开关、数组、Service、表格及 Dialog
   等实际 renderer。使用 JSON schema 实现必填、条件显示、条件请求、页面数据加载、
   列表分页/空态和对话框表单；不建立应用专属前端包。
2. 保留同 Flow POST API 边界。页面数据加载与查询显式使用 POST，查询参数放在
   JSON 对象 body；不为组件便利开放任意 GET、原生 API 或跨源调用。
3. 复用现有执行提交、输出读取及 SYNC semaphore。区分提交、等待、暂停、终态、
   输出待持久化、HTTP 等待到期、浏览器等待到期和取消等待。
4. 已获得 executionId 后，恢复只 GET 原 executionUrl；刷新/离开不自动重放 POST。
   保存的恢复信息只含规范路径和 executionId，不含输入、结果、token 或权限快照。
   恢复查询重新执行生产身份、入口、Flow 及输出权限检查；退出换号后仍遵守这些检查。
5. 同一页面相同 API/输入正在提交或等待时合并请求，防止双击产生重复 execution；
   完成后明确的新提交可以创建新 execution。POST 确认丢失时不得自动重试 POST，
   必须显示结果不确定。此规则不宣称跨客户端、网络重放的服务端 exactly-once。

### 生命周期公开约定与缺口

- 默认 ASYNC 初次响应为 202；SYNC 仅显式启用，等待范围为大于零且不超过 30 秒。
  SYNC 等待到期返回原 execution 的 202 pending；execution 继续，后续只 GET。
- PAUSED/BREAKPOINT 是等待状态，显示暂停提示，继续有界查询；不得显示业务成功。
- 浏览器超时/取消只终止等待，保留可恢复执行标识；不得声称执行已取消。
- 当前已有公开宿主机制可完成执行创建与结果查询，尚未发现需要新增宿主 SPI 的证据。
- 前端以版本化 sessionStorage 记录执行路径和 ID，提供在途提交合并和显式恢复入口。
  未收到初次响应时不自动重试；跨请求去重缺少已核实的宿主原子提交机制，不得用进程内 Map
  冒充持久化幂等。若主控要求此更强保证，先提出通用机制提案。

### 验收矩阵与证据边界

保留原 Java 375、14 项构建负例、UI unit 189、Chromium 35 和偏好负例 2 的
具名用例；新增用例独立列名，不用新增数量抵消原门丢失。
新增真实 Chromium 检查全部复杂控件、校验、条件交互、页面加载、列表分页与空态、
对话框保存、状态/错误提示，以及明暗主题、中文/英文、窄屏、焦点。
独立测试 transport 仅证明组件和协议，不证明生产执行。

生产 execution 门必须使用正式宿主与本仓 Flow/schema 测试数据，经生产登录和真实
AMIS 页面提交：默认 ASYNC 创建/运行/成功/失败/暂停/超时；显式 SYNC 成功/失败/
等待到期后查询；双击、刷新、离开重入不增加原 execution。每项核对请求轨迹、
executionId、承载 Flow、可信 trigger 来源、持久化 inputs/outputs、执行数量与结果。
拒绝 POST 比较数据库、队列、execution、存储及延迟副作用。根仓另执行模式 A/B。

本地先执行无 PG 门；Docker/PG 由主控协调唯一实例后才能运行。生产门未执行时必须
报告未验证，不得以 transport 或先前 P0 报告替代。失败报告保留已运行用例、失败、
跳过与未运行项，最终报告绑定提交、host digest、executable/jar hash。


### P1 报告契约 V1

精确具名 ID 由 `contracts/p1-cases.json` 唯一维护：`transport` 20 项、`production`
16 项。修改清单必须同步实现、checker 和接收方，不得从实际输出自动推导预期。

- `ui/reports/p1-transport.json`：独立 Chromium 控件/协议报告；`scope` 固定为
  `test-transport`，不得宣称生产身份或执行持久化。原生 Playwright 报告为
  `ui/reports/p1-chromium.json`，截图/trace 作为附件，分别记录主题、语言、宽度。
- `ui/reports/p1-production.json`：生产登录、真实宿主、真实 Flow execution 报告；
  `scope` 固定为 `production-execution`。不得使用 route interception 或测试 transport。
- 两份归一报告均包含 `schemaVersion:1`、`manifestSha256`、`sourceCommit`、
  `sourceSha256`、`hostImage`、`hostExecutableSha256`、`jarSha256`、`cases`、`stats`、
  `passed`。`cases` 每项包含 `id`、`status`（`passed/failed/skipped/not-run`）、
  `evidence`（相对报告目录的文件路径和 SHA-256），失败项另含 `error`。
  `stats` 包含 `expected/passed/failed/skipped/notRun`；必需 ID 必须精确相等且只出现一次。
  未完成的矩阵也保存报告，只有全部必需用例 passed 且无跳过/未运行才能 `passed:true`。
- transport 报告的 `jarSha256` 在只验证 UI 时可以为 null；正式 publisher 必须在
  打包前绑定实际薄 jar hash。production 报告该字段必须为实际运行 jar 的 hash。
- production 每项 evidence 必须包含请求方法/规范路径、executionId 集合、承载 Flow、
  可信 trigger 来源、持久化输入/输出、执行数量与状态观察；拒绝项另有操作前后及
  延迟副作用比较。不得写入密码、cookie、CSRF 或 token。
- publisher 必须打包上述报告、精确用例清单及源码 snapshot；provenance.files 绑定
  全部报告与附件。正式发布必须拒绝缺报告、ID 不符、hash 不符或任一未通过。
  根仓只消费该正式报告做输入审计，并另行执行代表性模式 A/B 组合增量。


### P1 有界浏览器等待维护参数

`kestra.amis-apps.poll-timeout` 是 ISO-8601 Duration，默认 `PT5M`（300000 ms），
允许范围 `PT0.001S` 至 `PT5M`，小于 1 ms、零、负值、超限和无法解析的配置必须
拒绝启动。转换到 bootstrap.limits.pollTimeoutMs 时取完整毫秒；不扩大既有最大值。
本仓独立生产门显式设为 `PT5S`，使用真实 8 秒 Sleep execution 验证超时后查询原
execution。此参数只停止浏览器等待，不取消 execution，不修改 SYNC 的服务端等待上限。


P1 生产拒绝写证据必须对业务表完整行内容计算稳定摘要，覆盖
`namespace_file_metadata`、`kv_metadata`、`execution_outputs`、全部队列表及其 offset，
并核对整个测试存储的文件 hash。先等待先前正控 execution 和派发稳定，再执行拒绝写，
连续至少 5 秒检查副作用；相同行数的数据修改及队列 offset 替换必须被识别。
已确认 pending execution 后的 GET 401/403 必须保留恢复记录，重新认证后只查询原 ID。
原始浏览器 trace 与容器日志可能含凭据，仅留本地；发布证据只包含脱敏请求轨迹、
execution 业务数据、截图与内容摘要，不发布私密配置或完整网络 trace。

生产逐例 evidence 保留顶层 `scope/caseId/caller/executionIds/consoleErrors`、
`requests[]/responses[]/executions[]` 与完整 inventory。`flows[]` 使用固定用例计划，
不得从实际请求推导预期 Flow。每项含 `namespace/expectedFlowId/apiId/caller`、
`requests/responses/executions/executionIds/beforeExecutionIds/afterExecutionIds/expectedNewExecutions`。
顶层 executionIds 必须等于各 Flow 并集，Flow 之间不得交叉；零执行项也保留空集合和
完整前后 inventory。条件加载用例固定 `p1-init` 与 `p1-conditional` 两组，查询为单独
`p1-query` 用例；这与具名矩阵保持对应，不将查询计入条件加载用例。


队列观察沿正式宿主既有协调/业务边界：仅排除 `scheduler_event` 中精确的四种
`VNODES_ASSIGNMENT_REQUEST/REPLY/RELEASE/REJECTED`，且该行不得出现 namespace、
flowId、executionId、tenantId 中任一字段。未知类型、携带任一身份字段的协调消息和
所有业务队列记录都保留完整内容与 offset。该例外用于排除宿主持续协调活动，不得称为
“全队列不变”。单元负例验证带身份记录、未知类型及同数量 offset 替换不会被排除。
业务数据库摘要覆盖 flows、executions、execution_queued、namespace_file_metadata、
kv_metadata、execution_outputs 的完整内容。侧效证据记录实际 coverage。

### 示例与既有 RBAC 输出依赖解析

模式 B 的既有安全输出解析只接受固定任务输出路径，例如
`{{ outputs.responseValue.value }}`。公开 P1 示例将复杂表达式放在普通公开
`io.kestra.plugin.core.debug.Return` 任务的 `format` 中，再由 `type: JSON` 的 Flow
output 引用该任务的 JSON 字符串。宿主按 JSON 类型解析为对象，保留数字、布尔值和数组。
这复用现有执行授权与任务边界，不为输入表达式、函数、过滤器或动态输出路径放宽依赖门。
