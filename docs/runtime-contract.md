# AMIS P0 运行时目标契约

状态：P0 运行时契约。取用必须核对固定输入对应的成功检查和独立发布产物，不能仅凭
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
