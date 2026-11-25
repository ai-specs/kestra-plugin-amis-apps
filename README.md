# kestra-plugin-amis-apps

独立 AMIS Apps 扩展。一个 Flow 承载 JSON 页面声明、页面 API 与执行逻辑。
应用变更无需编译应用专属前端；扩展固定运行时按本仓独立发布。

当前状态：P0 开发中，尚未通过独立 CI、发布或部署组合验收。不得据此目录部署生产。
总体需求基线为部署仓 `docs/amis-apps-requirements.md`，版本
`23d837333003ee2f4918784d001c5ffa191dd799`；构建不得读取部署仓文件。

本仓负责业务声明、页面/API、schema、编辑器和自身浏览器门；宿主提供公开契约，
RBAC 提供统一权限策略，部署仓负责模式 A/B 的正式 Compose 验收。

## 本次基础范围

标识：`AMIS-FOUNDATION-20261125`。这是 Java 25 纯领域库和独立构建骨架，
不是完整 P0，也不是可装载运行的 PageTrigger/ApiTrigger 插件。
没有 Controller、Micronaut bean、Flow 插件类型注册、授权实现、存储访问或执行入口。
没有借用未发布的新宿主 SPI；纯领域类型不依赖宿主类型。
不复制旧 dsh 实现，不读取 RBAC 私有模型，不提供匿名或其他授权旁路。

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
本次未运行远端 CI，因此此权限配置的成功下载仍须以本仓首次 workflow 日志核对。

在本仓独立目录执行，不能依赖相邻源码：

```bash
bash scripts/prepare-host.sh
bash scripts/gradle.sh clean check
python3 scripts/test-build-gates.py
```

`scripts/gradle.sh` 固定 Gradle 9.8.0，下载时校验正式分发 ZIP 的 SHA-256。
可以设置 `GRADLE_HOME` 指向已安装的同版本 Gradle；脚本核对版本，不下载替代版本。
Java 版本与 Gradle 版本不是同一概念：本项目使用 Java 25、Gradle 9.8.0。
依赖版本由 `gradle.lockfile` 固定；维护者调整依赖时才执行 `--write-locks`。

`prepare-host.sh` 只读取本仓 `host-contract.properties` 的固定正式镜像 digest。
本次初始 digest 来自 RBAC 的公开构建配置调查；脚本和 Gradle 不读取 RBAC、overlay、
部署仓或其他相邻目录，也不触发其编译或镜像构建。
脚本执行 `docker pull`，创建一个带 `owner=amis-foundation-20261125` label 的容器，
不 start；仅复制 `/app/kestra`，核对镜像 digest、提取公开类型签名并删除该容器。
不会创建 PG、网络或用户部署数据。脚本失败时也尝试删除本次容器。

正式输入证据保留在被忽略的 `.integration/`：

- `host-webserver.jar`：正式镜像原始产物，Gradle 仅作为 `compileOnly` 输入。
- `host-provenance.json`：镜像 digest、镜像 ID、平台、镜像 revision、产物 SHA-256。
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
`gradle.sh clean check` 和负向构建门。随后再次核对恢复后的输入与 jar。
CI 不 checkout 相邻仓、不编译宿主、不运行容器或数据库。
报告上传显式包含隐藏目录，失败时也保留已执行的报告；正式接收仍需实际下载 artifact，
核对提交、测试零失败/跳过、镜像 digest 与 jar hash，不能仅看上传步骤成功。

`check` 运行 JUnit 正负测试和 `verifyThinJar`。薄 jar 门拒绝本库领域包以外的 class、
嵌套 jar 和 Micronaut/service 注册。此阶段无 bean，索引必须为空，不能假造插件索引。
第三方依赖（包括 Jackson）不打入 jar；此库 jar 不应直接部署为运行时插件。
后续接入插件类型时必须另行生成并验证真实 bean/插件索引和运行依赖兼容性。

输出：

- `build/libs/kestra-plugin-amis-apps-0.0.1-SNAPSHOT.jar`
- `build/reports/tests/test/index.html` 与 `build/test-results/test/TEST-*.xml`
- `build/reports/thin-jar.txt`（jar SHA-256 和薄 jar 门结果）
- `.work/gate-tests/result.json` 和各项日志：主动污染输入后验证宿主 hash、第三方 class、
  嵌套 jar、非预期 bean 索引均被门禁拒绝；脚本在退出前恢复原始输入。

## 验证限制

本地领域测试不验证生产认证、原生路由优先、CSRF、两道授权、撤权、namespace file
存储、Flow 保存/执行、队列副作用、AMIS 渲染、浏览器、编辑器或 Compose。
尚无成功独立 CI 报告、发布产物或根仓组合报告；不得把本地测试视作这些检查通过。
基础库的提交与 CI 只证明上述有限范围，不代表可部署的 P0 插件已发布。
完整阶段由主控核对独立仓发布与根仓真实组合证据后裁决。
