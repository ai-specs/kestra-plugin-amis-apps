# P0 固定运行时：AMIS-UI-20261125

本目录独立构建 AMIS 6.13.0 运行时。只实现 P0 的页面、文本表单、提交和结果等待，
不实现 P1 复杂控件、P3 编辑器、Java 入口或生产认证。测试 transport 不证明生产身份、
RBAC、CSRF 服务端校验、Flow execution、队列或存储已经通过。不得把本地 UI 门当作 P0 整阶段交付。

## 正式输入与独立构建

使用 Node.js 24 和 npm 11。所有直接依赖精确锁定，完整依赖树由 package-lock.json 固定。
React 16.14.0 与 AMIS、react-json-view 的 peer 相容；react-cropper 固定 2.1.8，
避免其宽范围自动选择要求 React 17 的新版本。不得使用 legacy-peer-deps 或 force。

本仓 `.integration/host-webserver.jar` 必须预先存在。SDK 提取脚本从本仓
`host-contract.properties` 读取唯一 overlay.image，与 provenance 的镜像 digest 比较，
并核对实际 jar SHA-256 与 provenance。UI 不维护第二份镜像或 exe hash pin；主控更新
正式宿主输入后无需改 UI 代码。实际镜像、jar hash、host-contract hash 和每个提取文件的
SHA-256 记录在 `.host-sdk/provenance.json`，并进入 `reports/inputs.json`。
脚本只从此产物提取 `META-INF/kestra/ui-sdk/v1` 的公开 locale/主题契约，不运行 Docker，
不读取相邻宿主源码，不导入 Vue、私有实例或宿主 SPA，不修改根 prepare-host 脚本。
镜像内继承的 revision label 不能证明 overlay 源码提交。SDK 提取也不代替宿主 CI/发布链审计。

在本目录执行：

```sh
npm ci
npm ls --all
npm run build
npm test
npm run test:translations
npx playwright install chromium
npm run test:chromium
npm run test:preferences:red
node scripts/check-report.mjs
npm audit --registry=https://registry.npmjs.org --json > reports/npm-audit.json
```

Playwright 使用真实 Chromium 的 `channel: chromium`，不使用 Happy DOM。
`test:preferences:red` 现在是正式偏好回归门。旧 SDK 缺失 descriptor 时的两项红例保留在
历史目录；正式 descriptor 接入后，当前门必须通过，不使用 skip/xfail。
`check-report.mjs` 核对两份报告、截图和输入 hash；存在偏好失败时最终验收为红，退出码为 1。
`preview` 仅服务构建产物；测试文件显式截获 HTTP transport，生产构建没有测试 transport。
生产 jar 由本仓 Gradle 和独立 CI 打包与发布；Compose 生产组合验收归部署仓。

## HTML 装载与接口

`dist/index.html` 是模板，不是可直接部署的已填充页面。服务端必须：

1. 把 `__AMIS_BASE__` 安全替换为同源 `/{namespace}/{flowId}/`，保留末尾斜杠。
   Vite 输出引用 `./assets/<hash>`；不得替换成 assets/ 前缀而形成双 assets 路径。
2. 把 `__AMIS_CSRF__` 替换为安全转义的当前会话 CSRF meta 值。
3. 把 `__AMIS_BOOTSTRAP__` 替换为 runtime-contract.md 的 V1 JSON，必须转义 `<`，
   防止 `</script>` 注入。不得插入 JWT、cookie 或其他凭据。
4. 通过受保护的 `/{namespace}/{flowId}/assets/` 提供 dist/assets 内完整资源，包括字体和动态分块。
   精确白名单位于 `dist/runtime-manifest.json`，不生成或发布 `.vite/manifest.json`。
   完整资产 SHA-256 同时记录于 `reports/inputs.json`。
5. 页面、schema、POST、轮询继续执行服务端认证、CSRF 和两门授权；前端 URL 检查不是授权机制。

bootstrap 严格验证 version、namespace、flowId、pageId、schemaUrl、apiBase、assetBase。
limits 全为正整数，上限为 requestTimeoutMs=30000、pollIntervalMs=10000、pollTimeoutMs=300000。
页面 JSON 从 schemaUrl 成功加载后才交给真实 AMIS renderer，不生成替代表单。
P0 注册 page、panel、form、input-text、control、tpl、action；其他控件不属于本次范围。
主题通过 AMIS 官方 cxd/dark API 注册、renderer 的 theme 属性和对应官方 CSS 切换。
官方皮肤用 Vite inline CSS 处理本地资源引用，页面只启用一份官方皮肤；不自造深色调色板，
不以 data-theme 属性代替实际原生 dark-* DOM 和计算颜色。自有 CSS 只桥接页面容器、
偏好选择器、错误区和焦点，颜色消费官方 AMIS CSS 变量。
amis-ui 公共组件按导出路径引用，不复制厂商源码，避免 barrel 导入把编辑器资产带入运行时。
AMIS 原始 CSS 含旧 IE 无效 media query；构建使用 LightningCSS errorRecovery 去除无效规则。

Flow ID 使用上游 `[A-Za-z0-9][A-Za-z0-9._-]*`，最长 100；page/api ID 独立使用
`[A-Za-z0-9_][A-Za-z0-9_-]{0,99}`，允许下划线开头，不接受点，不共用 Flow 标识规则。
fetcher 只允许同 Flow 的 `POST /apis/{apiId}`。共享 schema 可以声明 `post:greet`：
AMIS 将方法和 URL 拆开后，fetcher 把合法 bare API ID 拼到 bootstrap.apiBase 下，
再执行同源、同 Flow 和规范 API ID 校验。`../`、原生 API、跨 Flow、外源和编码路径仍拒绝。
请求体必须是 JSON 对象，写入发送
`X-CSRF-TOKEN`，使用 same-origin cookie、no-store 和 redirect:error；不采用 schema 传来的
任意 headers、凭据或重定向策略。不访问 native 管理 API，不向外源发送 cookie 写入。
POST 返回 202 时，只接受首次响应的同源、同 namespace/Flow/API/execution 的 executionUrl。
轮询 GET 不创建新 execution，不跟随后续响应提供的 URL，即使后续 URL 指向外源或原生 API，
也继续只使用首次验证的 URL。每个响应（包括首个 SYNC 200）验证非空安全 executionId、
整数 status、公开受支持 executionState、布尔 resultReady 和对象 data；轮询还必须匹配首次 ID。
客户端只能核对 ID 的安全形状和前后一致性，不能据此证明服务端确实创建了 execution。
支持的 17 个状态及终态分类由正式 jar 的 State.Type 公开枚举和终态方法核对。
只有受支持终态、HTTP 200、resultReady=true、status=0 才能成功；FAILED/KILLED/CANCELLED
即使错误地携带 status=0 也拒绝。未知状态、非终态声称就绪、200 未就绪和 202 已就绪都拒绝。
resultReady=false 不产生成功结果；
即使 executionState=SUCCESS，也必须等到结果可读。缺结果、执行失败和 HTTP 失败显示错误。
轮询总等待及单请求均有上限，支持 AbortSignal、AMIS cancelExecutor、取消按钮和卸载取消。
离开页面只取消客户端等待，不声称取消后端 execution。错误区域保留 `/oidc/logout` 退出入口，
路径由正式 jar 的 OidcLoginController 公开 Controller/Get 注解核对，不猜测根级 `/logout`。
本地测试只验证该入口可点击，不证明真实会话已清除，生产退出/换号须由主控组合验收。

## 偏好和界面范围

使用正式 SDK 的 13 个 locale、alias 和 en fallback，以及 light/dark/dark-2 主题值。
浏览器语言经过 SDK resolveLocale；默认主题跟随 prefers-color-scheme，支持实时系统切换。
页面提供语言和主题选择；选择仅保留在本页内存，不读写 token/localStorage，也不启动宿主 SPA。
公开 SDK 没有独立文档读取宿主私有偏好存储的接口，因此不宣称跨宿主页面同步或刷新后保留手动选择。
中文/英文提供独立运行时文案和 AMIS locale。按 SDK messageFormat 提供每 locale 包装的 JSON，
运行正式 jar 提取的 check-translations.mjs，核对 13 个 locale 的完整键、非空和插值参数。
其他 SDK locale 明确回退英文，不冒称已翻译或已运行各语言业务浏览器矩阵。
dark-2 在 P0 映射 AMIS 官方 dark 皮肤，不宣称它与宿主 dark-2 视觉完全等同。
正式 SDK 已发布并由 `extract-sdk.mjs` 提取 `contract.preferences`。运行时只消费该正式字段，
不读取私有 bundle、不猜测存储键、不消费 candidate。正式偏好回归已通过；这只证明当前
正式 descriptor 下的 UI 刷新、locale/theme 和本地事件语义，不替代根仓生产组合验收。

### 偏好适配准备：目标草案，不是已发布兼容性

`src/preferences.mjs` 提供纯 `resolvePreferences`、descriptor 验证和可注入存储/事件/media 的
生命周期适配器。目标形状只在 `tests/fixtures/preferences-target-draft.json` 定义，明确标为
TARGET DRAFT。字段布局是本次准备设计，不代表未来正式 SDK 必须采用相同字段布局。
单测不读取 candidate jar，也不把草案写入 `.host-sdk/contract.json`。

目标语义由本次用户指令明确：localStorage 的 theme/lang；theme 缺失或非法时默认
syncWithSystem，显式 light/dark/dark-2 不受系统事件改写；系统模式按 media 解析明暗。
lang 缺失/空为 en，支持值与别名使用已提取正式 SDK 的 locales 解析函数。
写端立即通知自己的视图，不等待本 document 不会收到的 storage 事件；其他 document 的
storage 事件只接受对应 storageArea 和 theme/lang/clear，再读存储而不信事件 payload。
系统事件、显式 refresh 和新适配器启动均重新解析；AbortSignal/dispose 清理事件监听。
存储失败不冒称写入成功，事件异常交给显式 onError，未持久化 token 或 CSRF。

生产入口尚未导入该准备模块。未来唯一生产入口是
`createPublishedPreferenceAdapter(extractedContract, environment)`，只读取提取产物的
`contract.preferences`；缺失或不支持的形状必须抛错，不允许传草案当正式契约。
正式 digest 更新后必须重新核对实际 descriptor、接入页面、重跑完整 Chromium、刷新和跨页面
偏好门。这些适配单测证明目标语义；当前正式浏览器门已使用实际提取 descriptor。

运行准备单测：`node --test tests/preferences-target.test.mjs`。
旧偏好红例和输入源码 hash 保存在 `reports/preferences-preparation-before/` 及
`reports/old-formal-sdk-browser-before-vite-fix/`；当前正式偏好回归报告为通过。
AMIS 文本控件的可见 label 没有绑定原生 input 的可访问名称；测试核对真实 DOM 的唯一 textbox
及可见 label，不伪造 getByRole 的名称。本次验证键盘焦点，不宣称完整无障碍认证。

## 证据与限制

具名浏览器矩阵：中文/英文 × 浅色/深色/跟随系统/390px 窄屏焦点，共 8 项。
每项真实输入、点击提交，核对完整 JSON body 与 CSRF header，等待真实 AMIS 渲染返回数据。
检查系统主题切换、稳定计算颜色、文字对比度和焦点。另有加载、schema/API 的 401/403/404/503、
202 轮询、结果延迟、执行失败、外源轮询拒绝、显式取消、卸载、超时和缺 CSRF 用例。
保留每项截图、具名 JSON、失败 trace 和输入 hash。浏览器错误不得整体忽略。

- `reports/inputs.json`：源文件、lock、构建资产及正式 SDK 输入 hash。
- `reports/chromium.json`、`reports/chromium-html/`：实际浏览器结果。
- `reports/preferences-red.json`、`reports/preferences-red-html/`：正式偏好刷新具名回归；旧文件名保留，当前必须通过。
- `reports/translations.json`：正式 SDK 工具的 13 locale 键检查原始报告。
- `test-results/`：每项截图，失败时附错误上下文与 trace。
- `test-results-preferences-red/`：独立偏好红例截图、错误上下文和 trace。
- `reports/summary.json`：具名矩阵、准确计数、报告/截图 hash 和输入绑定。
- `reports/attempt-*/`：本次修复前的实际失败证据，不计入最终通过数。
- `reports/transport-review-red/`：第二轮 transport 修复前的单元/Chromium 红例、截图、trace
  和修复前源码/hash。它们仅证明 transport 层缺口，不是生产身份或持久化证据。
- `tests/runtime-protocol-fixture.test.mjs`：只读本仓 `contracts/runtime-v1-cases.json`，
  以 binding 构造同 Flow 路径，通过真实 createTransport 消费全部 13 个具名响应案例。
  pending 使用 POST 202 和受控等待的一次合法 GET 200，结果释放前断言 Promise 未完成、
  状态仍等待；failed 与非法 ready/state/data 必须 Promise 拒绝并产生可见错误通知。
  此共享 producer/consumer 协议测试不是生产验收，也不运行 Java producer。
- `reports/runtime-protocol-first-run/`：13 项首次运行日志及共享文件、测试与 transport hash。
  首次即 13 项通过，没有发现新红例，因此不制造失败或修改共享文件来迎合代码。
- `reports/npm-audit.json`：官方 registry 审计原始结果。非零退出码表示风险仍存在。

AMIS 6.13.0 的完整 npm 依赖包含未在本 P0 注册的编辑器和办公组件。运行时不导入编辑器，
dist 不包含 Monaco/TinyMCE/Froala/CodeMirror/amis-editor 的独立 JS 资产；厂商 cxd.css 仍包含
额外组件样式，不把它冒称为已支持的控件。不得把未装载解释为全部依赖安全。
2026-11-25 官方 npm audit 实际发现 18 项风险（8 moderate、9 high、1 critical），包括 tar、
xlsx、markdown-it/linkify-it 和未装载编辑器相关依赖。不运行 audit fix --force，不擅自改变
指定 AMIS 版本。风险需要主控另行裁决；本地构建及浏览器通过不等于安全风险已消除。
构建仍报告大 chunk 和 react-intersection-observer 的 use client 指令警告，不隐藏这些警告。

### PageDocument classpath manifest V1

`reports/page-document-manifest.json` 是给后端 PageDocument 消费者的实际格式示例，不是授权清单。
后端将 `ui/index.html` 作为模板对应的 `dist/index.html`，只替换三个占位符：
`__AMIS_BASE__`、`__AMIS_CSRF__`、`__AMIS_BOOTSTRAP__`。模板必须保留相对 `./assets/` 引用。
manifest 的 `assets.allowlist` 是构建后每个 `assets/` 文件的精确相对路径、字节数和 SHA-256；
后端只允许 `/{namespace}/{flowId}/assets/{assetPath}` 命中该 allowlist，拒绝未列出、query/fragment、
编码、反斜杠和路径穿越。manifest 同时包含 `template.replacementRules`、`artifact.indexSha256`、
`artifact.assetCount` 和非授权用途说明。后端仍必须独立执行认证、CSRF、两门授权和 no-store 规则；
该文件不得被用作权限决定。

正式输入生成的 manifest 位于 `reports/page-document-manifest.json`，供 jar 打包与报告审计使用；
它不修改 `build.gradle`、Java runtime 或根仓文件。
