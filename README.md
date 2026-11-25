# kestra-plugin-amis-apps

独立 AMIS Apps 扩展。一个 Flow 承载 JSON 页面声明、页面 API 与执行逻辑。
应用变更无需编译应用专属前端；扩展固定运行时按本仓独立发布。

当前状态：独立仓基础 CI 已通过并由主控下载审计；当前触发器模型改动尚未通过独立 CI，
没有 HTTP/授权/执行运行时或生产组合验收，不是 P0 交付。不得据此目录部署生产。
总体需求基线为部署仓 `docs/amis-apps-requirements.md`，版本
`23d837333003ee2f4918784d001c5ffa191dd799`；构建不得读取部署仓文件。

本仓负责业务声明、页面/API、schema、编辑器和自身浏览器门；宿主提供公开契约，
RBAC 提供统一权限策略，部署仓负责模式 A/B 的正式 Compose 验收。

### 已审计的基础 CI（历史范围，不包含当前模型改动）

主控已核对基础提交 `692fbcd7d28f5a5ca6f0f29d6bdcbd9c8b0f0791` 的成功 CI
`45644295878`，实际下载 artifact `15037706392`：82 项 XML 测试零失败、零错误、零跳过，
4 项构建负例通过；基础 jar SHA-256 为
`4db149c55a0c440c76b9e2f6f62e09694fff4fe0ec0787ee7690084f7678e49d`。
这只证明当时的纯领域基础、正式输入与薄 jar 门，不证明本轮触发器模型、新门规则、
HTTP、真实授权、Flow execution、页面或生产组合已经通过或发布。

## 当前模型范围

基础标识：`AMIS-FOUNDATION-20261125`。现已增加真实 `PageTrigger`、`ApiTrigger` 声明模型，
可以经宿主 PluginRegistry、YamlParser、schema 生成与 ModelValidator 使用。
这不是完整 P0，也没有可提供页面或触发执行的运行时。
没有 Controller、业务 Micronaut bean、授权实现、存储访问或执行入口。
两类直接继承既有正式 `AbstractTrigger`，不继承 `AbstractWebhookTrigger`，
不消费任何未发布新宿主 SPI；独立纯领域类型仍不依赖宿主类型。
不复制旧 dsh 实现，不读取 RBAC 私有模型，不提供匿名或其他授权旁路。

### Flow 声明模型（尚未发布）

```yaml
id: example.app
namespace: company._team
tasks:
  - id: log
    type: io.kestra.plugin.core.log.Log
    message: model only
triggers:
  - id: page-home
    type: io.kestra.plugin.amis.apps.PageTrigger
    pageId: home
    schema: nsfile:///pages/home.json
  - id: api-home
    type: io.kestra.plugin.amis.apps.ApiTrigger
    apiId: home
  - id: api-save
    type: io.kestra.plugin.amis.apps.ApiTrigger
    apiId: save
    mode: SYNC
    syncTimeout: PT10S
    resultOutput: response
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
  P0 目标只激活各一个页面和 API，多声明必须由后续 handler 拒绝，不能静默任选；当前没有 handler，
  不激活任何页面或 API。目标设计见 `docs/runtime-contract.md`，该文档尚未发布，不代表运行时已交付。
- 原生继承字段继续由宿主解析和校验，未知字段由真实严格 YamlParser 拒绝。
  页面/API 路由集合冲突仍由纯领域 `FlowDeclarations` 校验；当前没有保存拦截器或全局路由注册器，
  不宣称单个模型约束已替代整个 Flow 的路由集合检查。
- `META-INF/services/io.kestra.core.models.Plugin` 精确登记两类模型。
  仅有 class 或合法 introspection 但没有 Plugin 服务声明，不能成为已登记插件类型。
  模型带真实生成的 `@Introspected` 元数据，不注册为 singleton，不生成业务 bean。

### 领域约定（尚未发布）

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
  此阶段只生成不可变候选列表，不读文件、不执行回退。后续解析器必须仅在文件不存在
  时尝试下一候选；当前文件非法或读取异常不得静默改用父级文件。
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
  不由每个 API 声明独立总池。两者均只校验配置，不实际调度资源。
  这些数值是当前库的候选配置边界，不是已发布运行契约。
  尚未实现等待、并发许可、取消、执行创建、轮询、到期响应或资源释放；不以 SSE 为契约。

## 独立构建与测试

前置：JDK 25（含 `java`、`javac`、`javap`）、Bash、Python 3.11+、Docker CLI/daemon、
`curl`、`unzip`、`shasum`，以及访问 Maven Central、Gradle 发布站点和私有 GHCR 的权限。
使用现有 `docker login ghcr.io` 配置；不得把凭据写入脚本、参数日志或 Git。
跨私有仓读取包需要实际包访问授权，仅声明 `packages: read` 不保证可读。
本仓 Actions 使用 `github.token` 和 `packages: read`。维护者已配置宿主 GHCR package
的 Manage Actions access，授予本仓 Read；无需新增 PAT 或 repository secret。
包访问必须以本仓 workflow 的实际下载日志核对；下载成功不能替代后续构建与测试通过。

在本仓独立目录执行，不能依赖相邻源码：

```bash
bash scripts/prepare-host.sh
bash scripts/gradle.sh clean check
python3 scripts/test-gradle-launcher.py
python3 scripts/test-build-gates.py
```

`scripts/gradle.sh` 固定 Gradle 9.8.0，下载时校验正式分发 ZIP 的 SHA-256。
可以设置 `AMIS_GRADLE_HOME` 指向已安装的同版本 Gradle；脚本核对版本，不下载替代版本。
runner 或 shell 预设的通用 `GRADLE_HOME` 不参与版本选择，避免继承其他项目的工具版本。
Java 版本与 Gradle 版本不是同一概念：本项目使用 Java 25、Gradle 9.8.0。
依赖版本由 `gradle.lockfile` 固定；维护者调整依赖时才执行 `--write-locks`。
注解处理器固定为 `io.micronaut:micronaut-inject-java:4.10.33`，版本与正式宿主内
micronaut-core、micronaut-inject、micronaut-core-processor 的 pom 一致，构建前检查。
处理器关闭传递依赖，除该正式编译工具外，其处理依赖仍从同一正式宿主 jar 取得；
只启用 `TypeElementVisitorProcessor` 生成 introspection，不引入另一套运行依赖。
`gradle/verification-metadata.xml` 由标准 Gradle SHA-256 校验元数据命令生成，固定实际解析的
处理器与既有测试依赖；宿主文件依赖仍由正式 digest/provenance 门核对。

并行调查时可以将全部 Gradle 输出切到独占验证树，不改变 CI 默认 `build/` 路径：

```bash
export AMIS_BUILD_DIR=.build
bash scripts/gradle.sh clean check
python3 scripts/test-build-gates.py
```

设置该变量时，jar、JUnit 报告、插件 classpath 场景和负向门日志均写入 `.build/`。
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
  当前正式 `91d6726d` 的 revision/source/title/version/created 标签来自上游 kestra-base，
  `72d6031...` 不是本报告证明的 overlay commit。这不否定正式 digest 或提取产物的身份。
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
基础提交的成功独立 CI 范围见前述记录；本轮模型尚无对应成功 CI、正式发布或根仓组合报告。
不得把基础 CI 或本地模型测试当成本轮生产能力通过，不代表可部署的 P0 插件已发布。
完整阶段由主控核对独立仓发布与根仓真实组合证据后裁决。
