# kestra-plugin-amis-apps

独立 AMIS Apps 扩展。一个 Flow 承载 JSON 页面声明、页面 API 与执行逻辑。
应用变更无需编译应用专属前端；扩展固定运行时按本仓独立发布。

当前实现覆盖 P0 的单页面、单 API、固定 AMIS 运行时及其认证、授权和有界执行接线。
P1 复杂控件、P2 多页面/API 激活和 P3 编辑器不在此版本范围。
取用时必须核对所选提交的成功检查、独立发布 workflow 与 `release-provenance.json`；
根仓生产组合验收是另一道必需门，不能把模型阶段的 jar 或组件测试当作 P0 阶段交付。
总体需求基线为部署仓 `docs/amis-apps-requirements.md`，版本
`23d837333003ee2f4918784d001c5ffa191dd799`；构建不得读取部署仓文件。

本仓负责业务声明、页面/API、schema、编辑器和自身浏览器门；宿主提供公开契约，
RBAC 提供统一权限策略，部署仓负责模式 A/B 的正式 Compose 验收。

### 已审计的模型 CI（历史范围，不包含当前运行时改动）

主控已核对模型提交 `fde169e83d821b193103bf6c8cfa5a79825f94c3` 的成功 CI
`45664780692`，实际下载 artifact `15047905420`：156 项 XML 测试零失败、零错误、零跳过，
10 项构建负例通过；模型 jar SHA-256 为
`06414141302a4626d1938ff31bb2f621eb990e4ed3029f0f5ed28eddc45b26dc`。
这只证明当时的触发器模型、正式输入与薄 jar 门，不证明本轮运行时、新门规则、
HTTP、真实授权、Flow execution、页面或生产组合已经通过或发布。

## 当前模型范围

基础标识：`AMIS-FOUNDATION-20261125`。现已增加真实 `PageTrigger`、`ApiTrigger` 声明模型，
可以经宿主 PluginRegistry、YamlParser、schema 生成与 ModelValidator 使用。
P0 使用一个 `HttpExtensionRouteProviderV1` bean，通过宿主正式公开类型访问 Flow、
执行和 namespace files。字段、请求顺序与有界行为见
[运行时契约](docs/runtime-contract.md)。源码和定向测试存在不表示所选产物已经通过发布门。
两类直接继承既有正式 `AbstractTrigger`，不继承 `AbstractWebhookTrigger`，
不消费任何未发布新宿主 SPI；独立纯领域类型仍不依赖宿主类型。
不复制旧 dsh 实现，不读取 RBAC 私有模型，不提供匿名或其他授权旁路。

### Flow 声明模型

```yaml
id: example.app
namespace: company._team
inputs:
  - id: name
    type: STRING
    required: true
tasks:
  - id: echo
    type: io.kestra.plugin.core.debug.Return
    format: "{{ inputs.name }}"
outputs:
  - id: response
    type: JSON
    value:
      message: "{{ outputs.echo.value }}"
triggers:
  - id: page-home
    type: io.kestra.plugin.amis.apps.PageTrigger
    pageId: home
    schema: nsfile:///pages/home.json
  - id: api-greet
    type: io.kestra.plugin.amis.apps.ApiTrigger
    apiId: greet
```

- `id` 是宿主 trigger 标识，遵守上游校验与 Flow 内唯一规则，页面和 API 也不能共享此 id。
  `pageId`、`apiId` 是业务路径标识，可以同名，因为页面和 API 使用不同路径。
  `namespace`、Flow id 仍由承载 Flow 提供；没有 `appName`、独立 namespace 或路由事实库。
- `PageTrigger.pageId` 与 `schema` 必填。`schema` 是字符串形式的规范 `nsfile:///` 引用，
  由现有 `Identifiers`、`SchemaReference` 校验；只接受下节明确的 ASCII/JSON 子集，
  不宣称支持所有合法 namespace file 路径。模型不读取 schema 文件，也不解析其 JSON 内容。
- `ApiTrigger.apiId` 必填。省略 `mode` 时为 `ASYNC`；显式 `mode: null` 或未知枚举拒绝。
  `SYNC` 必须显式声明，`syncTimeout` 使用 ISO-8601 Duration，范围大于零且不超过 `PT30S`。
  ASYNC 不接受非空 `syncTimeout`。配置被校验不表示等待或执行已发生。
- `ApiTrigger.resultOutput` 默认 `response`，可显式指定 Flow output ID，不是任务 ID 或任务命名规则。
  使用正式宿主 Output.id 的模式 `^[a-zA-Z0-9][.a-zA-Z0-9_-]*`，非空且拒绝路径、编码及空白。
  字段只做标识语法校验；对应 Flow output 是否存在、JSON 对象类型与执行结果来源由后续业务服务检查。
- 模型可以表达多个页面/API，不永久限制声明数量。同名 pageId/apiId 仍允许，trigger id 仍须唯一。
  P0 只激活各一个页面和 API；`RouteDeclarations` 对多声明、重复或禁用状态明确拒绝，
  不静默任选。具体请求行为见 `docs/runtime-contract.md`。
- 原生继承字段继续由宿主解析和校验，未知字段由真实严格 YamlParser 拒绝。
  页面/API 路由集合冲突仍由纯领域 `FlowDeclarations` 校验；当前没有保存拦截器或全局路由注册器，
  不宣称单个模型约束已替代整个 Flow 的路由集合检查。
- `META-INF/services/io.kestra.core.models.Plugin` 精确登记两类模型。
  仅有 class 或合法 introspection 但没有 Plugin 服务声明，不能成为已登记插件类型。
  模型带真实生成的 `@Introspected` 元数据，不注册为 singleton，不生成业务 bean。

### 领域约定

- URL 原始路径为 `/{namespace}/{flowId}/pages/{pageId}` 或
  `/{namespace}/{flowId}/apis/{apiId}`。仅解析原始路径，不接受完整 URL、query、fragment、
  尾斜杠、额外路径段、百分号编码、双重编码、反斜杠或非 ASCII 标识。
  不执行 URL 解码。调用方必须保留原始路径，不能先解码再调用。
  这不是路由分发器，不能据此证明原生路由优先、身份认证或两道授权门已经实现。
- namespace 与 Flow ID 按固定正式宿主 `AbstractFlow` 字段的 `@Pattern`、`@Size` 对齐。
  namespace 长度 1 至 150，模式 `^[a-z0-9][a-z0-9_-]*(\.[a-z0-9_-]+)*`：
  必须小写，首段不能以下划线或短横线开头，后续段可以，例如 `company._team`。
  Flow ID 长度 1 至 100，模式 `^[a-zA-Z0-9][a-zA-Z0-9._-]*`，允许点，例如 `Flow.v1`。
  page/api 自定义标识匹配 `[A-Za-z0-9_][A-Za-z0-9_-]{0,99}`，不接受点。
  测试从实际宿主类读取注解，对照正负输入，不把插件自定义标识规则用于 Flow ID。
- schema 引用只接受规范 `nsfile:///path/to/page.json`，无 authority、query、fragment。
  path 最长 1024 字符，每段最长 128 字符，匹配 `[A-Za-z0-9_][A-Za-z0-9_.-]*`，
  只接受小写 `.json` 后缀。空段、点段、隐藏文件段、编码及穿越全部拒绝。
  路径本身不携带 namespace；例如路径中的 `other` 只是当前 namespace 的目录，
  不能指定兄弟 namespace。候选由 `company.team.app`、`company.team`、`company`
  按顺序组成，路径不变，没有空 namespace、兄弟或无关 namespace 候选。
  `AsyncSchemaResolver` 只在文件不存在时尝试下一候选；当前文件非法或读取异常不得静默
  改用父级文件。生产接线通过宿主公开 `FlowNamespaceFileReaderV1` 读取单个文件，
  每次读取保留当前请求身份、承载 Flow 及 READ/EXECUTE 授权，不另加文件管理权限。
  读取、父级回溯和取消沿同一异步链传播；原生文件管理 API 仍独立检查其管理权限。
- JSON 必须为严格 UTF-8，大小为 1 至 1,048,576 字节，最大容器嵌套深度 64
  （根对象计一层）。根必须是对象且 `type` 等于字符串 `page`。
  重复 JSON key、尾随 JSON、注释、非法编码和超限输入拒绝。
  这是 JSON 与 AMIS 根验证，不是完整 AMIS 控件、表达式、URL 或脚本安全验证。
  读取传输层仍必须另行限制字节，不能先无限读取后才调用本库。
- 同一 Flow 内按规范路径判定冲突，重复页面路由、重复 API 路由拒绝。
  页面与 API 可以同名，因为 `/pages/home` 和 `/apis/home` 是不同路径。
  不同 Flow 可以重用标识。声明列表不可变，不创建全局路由索引或事实存储。
- API 未声明模式时为 `ASYNC`。`SYNC` 必须显式指定，并同时提供大于零且不超过
  30 秒的 timeout。ASYNC 不接受 SYNC timeout 参数。
  `RuntimeLimits` 单独表示部署维护的 SYNC 总并发边界，范围为 1 至 32；
  不由每个 API 声明独立总池。HTTP 运行时使用全局 semaphore 执行此并发边界，
  `kestra.amis-apps.max-sync-concurrency` 默认 4，可配置 1 至 32；不按账号身份豁免。
  运行时通过宿主公开执行链提交，SYNC 使用有界等待及并发许可；超时返回真实 execution
  的 pending 信封供后续轮询，取消只停止 HTTP 等待，不冒称已取消 Flow execution。不以 SSE 为契约。

### P0 页面与权限准备

在上述 Flow 的 `company._team` namespace file 中保存 `/pages/home.json`：

```json
{
  "type": "page",
  "title": "Greeting",
  "body": {
    "type": "form",
    "api": "post:greet",
    "body": [
      {"type": "input-text", "name": "name", "label": "Name", "required": true},
      {"type": "tpl", "tpl": "Result: ${message}"}
    ],
    "actions": [{"type": "submit", "label": "Submit"}]
  }
}
```

开启 `kestra.amis-apps.enabled: true`，用生产登录访问
`/company._team/example.app/pages/home`。Flow 的 `response` output 必须已声明；
缺少声明返回 409，不创建 execution。已声明结果尚未可读时继续 pending，不能伪装为空成功。
具体部署、正式镜像/jar 选择和容器更新由部署仓 README 负责，不在本仓重新构建宿主。

模式 A 不装载 RBAC jar，但仍要求登录和 CSRF。模式 B 在已有 `kestra-ui` 应用的运行时
权限中显式维护 `EXTENSION_ROUTE / EXECUTE`、namespace 表达式和精确 ID `amis-apps`，
关联当前用户已获授的既有 scope。入口能力不自动补授给首装账号，也不新增应用成员或 tab。
它不替代具体 Flow READ/EXECUTE、执行创建及结果 READ/ACCESS_OUTPUTS。授权放行后，运行
schema 可以读取当前或父级文件，不另加文件级授权；编辑管理不继承此运行读取许可。

### 独立产物与报告发布

push CI 上传 GitHub Actions artifact。独立发布 workflow 只在该源 CI 已经 completed/success
后下载其原始 artifact，不重新编译，再发布仅包含 jar、报告和 source snapshot 的
`ghcr.io/ai-specs/kestra-plugin-amis-apps-artifact:<commit>` 包。它不是可以启动的应用服务镜像。
部署消费必须固定实际 digest，核对 `release/release-provenance.json` 中 commit、jar SHA-256、
host 输入、已完成的 `sourceCi` 及报告，不能只信任可重用 tag。使用 `docker create` 时指定一个占位 command，
只 `docker cp` 读取 `/release` 并删除 created 状态容器；不得启动此产物包。
该私有包的跨仓 Actions Read 必须单独授予部署仓，不能用个人 token 绕过权限配置。

## 独立构建与测试

前置：JDK 25（含 `java`、`javac`、`javap`）、Node.js 24、npm 11、Bash、Python 3.11+、Docker CLI/daemon、
`curl`、`unzip`、`shasum`，以及访问 Maven Central、Gradle 发布站点和私有 GHCR 的权限。
使用现有 `docker login ghcr.io` 配置；不得把凭据写入脚本、参数日志或 Git。
跨私有仓读取包需要实际包访问授权，仅声明 `packages: read` 不保证可读。
检查 workflow 使用 `github.token` 和 `packages: read` 读取宿主；独立发布 workflow 以
`packages: write` 发布本仓自己的只含产物和报告的包。
宿主 GHCR package
的 Manage Actions access，授予本仓 Read；无需新增 PAT 或 repository secret。
包访问必须以本仓 workflow 的实际下载日志核对；下载成功不能替代后续构建与测试通过。

在本仓独立目录执行，不能依赖相邻源码：

```bash
bash scripts/prepare-host.sh
bash scripts/gradle.sh clean check
python3 scripts/test-gradle-launcher.py
python3 scripts/test-build-gates.py
python3 scripts/test-runtime-assets.py
python3 scripts/test-package-release.py
cd ui
npx playwright install chromium
npm run test:chromium
npm run test:preferences:red
node scripts/check-report.mjs
```

`scripts/gradle.sh` 固定 Gradle 9.8.0，下载时校验正式分发 ZIP 的 SHA-256。
可以设置 `AMIS_GRADLE_HOME` 指向已安装的同版本 Gradle；脚本核对版本，不下载替代版本。
runner 或 shell 预设的通用 `GRADLE_HOME` 不参与版本选择，避免继承其他项目的工具版本。
Java 版本与 Gradle 版本不是同一概念：本项目使用 Java 25、Gradle 9.8.0。
依赖版本由 `gradle.lockfile` 固定；维护者调整依赖时才执行 `--write-locks`。
注解处理器固定为 `io.micronaut:micronaut-inject-java:4.10.33`，版本与正式宿主内
micronaut-core、micronaut-inject、micronaut-core-processor 的 pom 一致，构建前检查。
处理器关闭传递依赖，除该正式编译工具外，其处理依赖仍从同一正式宿主 jar 取得；
启用 `TypeElementVisitorProcessor` 和 `BeanDefinitionInjectProcessor`，分别生成触发器
introspection 与 HTTP provider bean 索引，不引入另一套运行依赖。
`gradle/verification-metadata.xml` 由标准 Gradle SHA-256 校验元数据命令生成，固定实际解析的
处理器与既有测试依赖；宿主文件依赖仍由正式 digest/provenance 门核对。

并行调查时可以将全部 Gradle 输出切到独占验证树，不改变 CI 默认 `build/` 路径：

```bash
export AMIS_BUILD_DIR=.work/independent-check
bash scripts/gradle.sh clean check
python3 scripts/test-build-gates.py
```

设置该变量时，jar、JUnit 报告、插件 classpath 场景和负向门日志均写入 `.work/independent-check/`。
负向宿主 hash 测试使用独立测试配置，不再临时改写 `.integration/` 的正式 provenance。

`prepare-host.sh` 只读取本仓 `host-contract.properties` 的固定正式镜像 digest。
本次初始 digest 来自 RBAC 的公开构建配置调查；脚本和 Gradle 不读取 RBAC、overlay、
部署仓或其他相邻目录，也不触发其编译或镜像构建。
脚本执行 `docker pull`，创建一个带 `owner=amis-foundation-20261125` label 的容器，
不 start；仅复制 `/app/kestra`，核对镜像 digest、提取公开类型签名并删除该容器。
不会创建 PG、网络或用户部署数据。脚本失败时也尝试删除本次容器。

正式输入证据保留在被忽略的 `.integration/`：

- `host-webserver.jar`：正式镜像原始产物，Gradle 仅作为 `compileOnly` 输入。
- `host-provenance.json`：镜像 digest、镜像 ID、平台、产物 SHA-256，以及明确标为
  `imageLabelRevision`、`imageLabelSource`、`imageLabelTitle`、`imageLabelVersion`、
  `imageLabelCreated` 的原始镜像配置标签；`imageLabels` 保留原始标签值。
  这些标签可能继承自基础镜像，不能当作 overlay 源码提交证明。
  基础镜像标签可能指向上游 kestra-base；它们不是本报告证明的 overlay commit，
  也不能替代正式 digest 或提取产物的身份。
  实际 overlay 提交必须由成功 CI 的 GitHub head 与绑定该 digest 的推广/attestation 链证明；
  本脚本的 `overlaySourceProvenance.status` 明确为 `not-verified-by-this-script`。
  标签字段回归可以本地运行 `python3 scripts/test-host-evidence.py`；这项专项回归不冒充已运行的 CI 检查。
- `host-public-types.txt`：从实际产物执行 `javap -public` 得到的 Flow、Execution、
  TypedAuthorization、TrustedIdentityV2 签名；provenance 同时绑定此文件的 SHA-256。
- `host-flow-validation.txt`：对实际 `AbstractFlow` 执行 `javap -p -v` 的字段注解证据，
  provenance 绑定此文件 hash，并记录实际宿主 Jackson 三个组件的版本。

编译前运行 `verifyHost`，检查固定镜像输入和上述文件 hash；缺失或不匹配则失败。
当前正式宿主包含 Jackson databind/core `2.22.3`、annotations `2.22`。
主源码通过 `compileOnly` 使用宿主 jar 的 Jackson 类，测试通过 `testImplementation`
使用同一正式 jar；不另拉 Maven Jackson，不混用旧版本。测试核对实际类来源及版本。
这些公开类型的存在不能证明新的 AMIS 通用入口契约已发布或两门链可以工作。
本脚本记录产物来源，不验证签名或重新取得宿主成功 CI 证明。
没有宿主 CI、正式发布与组合报告时，不得宣布完整交付。

独立 CI 为 `.github/workflows/foundation.yml`，与本地执行相同的 `prepare-host.sh`、
`gradle.sh clean check`、启动器回归和负向构建门。随后再次核对恢复后的输入与 jar。
CI 不 checkout 相邻仓、不编译宿主、不运行容器或数据库。
报告上传显式包含隐藏目录，失败时也保留已执行的报告；正式接收仍需实际下载 artifact，
核对提交、测试零失败/跳过、镜像 digest 与 jar hash，不能仅看上传步骤成功。

`check` 运行领域测试、真实宿主模型测试、三个独立 JVM 的薄 jar classpath 场景和 `verifyThinJar`。
独立场景分别使用无 jar、带真实 introspection 但去掉 Plugin 服务声明的 jar、完整薄 jar。
它们只读取正式宿主和被测 jar，不把源码输出目录混入被测插件 classpath。
验证实际 Registry、完整 Flow/plugin schema、YamlParser、真实 Hibernate ModelValidator，
并检查模型和 introspection 的实际类来源为被测 jar。

薄 jar 门只接受本插件包内 class、精确两项 Plugin 服务声明、两项实际生成的合法
BeanIntrospectionReference 索引及对应 class；禁止业务 BeanDefinitionReference、
其他服务登记、宿主/第三方 class 和嵌套 jar。门内用独立 JVM 实际装载 introspection，
不能以文件名存在冒充合法元数据。Jackson、宿主和处理器依赖都不打入插件 jar。
负向门保留原有四项，并增加错误服务、额外资源、缺服务、额外 provider、缺 introspection 和损坏 introspection 检查。

模型测试的隔离上下文只装入正式宿主校验链及最小依赖，禁用外部配置和环境推断，
不启动 HTTP、repository、队列或数据库。延迟存储 Provider 的测试配置不构成生产访问许可。
这里的 Flow 校验不是持久化保存或读回验收。

输出：

- `build/libs/kestra-plugin-amis-apps-0.0.1-SNAPSHOT.jar`
- `build/reports/tests/test/index.html` 与 `build/test-results/test/TEST-*.xml`
- `build/reports/thin-jar.txt`（jar SHA-256 和薄 jar 门结果）
- `build/reports/plugin-contract/`（独立 JVM 场景日志、逐场景结果和真实生成的 schema）
- `build/reports/thin-jar-metadata/`、`build/reports/thin-jar-metadata.log`（门内实际登记与 introspection 装载）
- `.work/gate-tests/result.json` 和各项日志：主动污染输入后验证宿主 hash、第三方 class、
  嵌套 jar、非预期 bean 索引以及新增服务/introspection 负例均被门禁拒绝；脚本恢复原始 jar。
  `AMIS_BUILD_DIR` 存在时，负向门报告位于该输出目录的 `reports/gate-tests/`。

## 验证限制

本地领域测试不验证生产认证、原生路由优先、CSRF、两道授权、撤权、namespace file
存储、Flow 保存/执行、队列副作用、AMIS 渲染、浏览器、编辑器或 Compose。
模型提交的成功独立 CI 范围见前述记录，不能作为本轮运行时的检查或部署证据。
所选运行时必须对应成功检查、已完成的独立发布和根仓组合报告；缺任一必需门不得宣布阶段交付。
完整阶段由主控核对独立仓发布与根仓真实组合证据后裁决。
